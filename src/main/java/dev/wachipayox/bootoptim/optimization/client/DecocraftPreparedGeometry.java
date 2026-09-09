package dev.wachipayox.bootoptim.optimization.client;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import net.minecraft.client.renderer.FaceInfo;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Experimental Decocraft 3.0.11 prepare/commit split.
 *
 * <p>The prepared representation is owned by the reload-local BBGeometry instance. It contains
 * only Blockbench geometry snapshots: element/group rotations, face order, raw UVs and shade.
 * Material resolution, sprite lookup/interpolation and ModelState are intentionally left for the
 * stock bake call site. A structural fingerprint is re-read at commit; mutation falls back to the
 * original Decocraft path.</p>
 */
public final class DecocraftPreparedGeometry {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/DecocraftPreparedGeometry");
    private static final boolean ENABLED = Boolean.getBoolean("boot_optim.experimentalDecocraftPreparedGeometry");
    private static final boolean PROFILE = Boolean.getBoolean("boot_optim.profileDecocraftPreparedGeometry");
    private static final String SUPPORTED_VERSION = "3.0.11";
    private static final ThreadLocal<Prepared> HANDOFF = new ThreadLocal<>();
    private static final LongAdder PREPARE_CALLS = new LongAdder();
    private static final LongAdder PREPARE_NANOS = new LongAdder();
    private static final LongAdder PREPARE_FAILURES = new LongAdder();
    private static final LongAdder COMMIT_CALLS = new LongAdder();
    private static final LongAdder COMMIT_NANOS = new LongAdder();
    private static final LongAdder COMMIT_FALLBACKS = new LongAdder();
    private static final LongAdder COMMIT_QUADS = new LongAdder();
    private static volatile String prepareThread = "none";
    private static volatile String commitThread = "none";
    private static volatile Bindings bindings;
    private static volatile boolean bindingFailed;
    private static volatile boolean warned;

    private DecocraftPreparedGeometry() {}

    public static Object prepare(Object geometry) {
        if (!ENABLED) return null;
        long started = System.nanoTime();
        try {
            Bindings b = bindings();
            if (b == null) return null;
            Object model = b.bbGeometryModel.get(geometry);
            if (model == null) return null;
            Prepared prepared = b.prepare(model);
            PREPARE_CALLS.increment();
            if (PROFILE) prepareThread = Thread.currentThread().getName();
            return prepared;
        } catch (Throwable t) {
            PREPARE_FAILURES.increment();
            warnOnce("prepare", t);
            return null;
        } finally {
            PREPARE_NANOS.add(System.nanoTime() - started);
        }
    }

    public static void offer(Object prepared) {
        if (!ENABLED || !(prepared instanceof Prepared value)) {
            HANDOFF.remove();
            return;
        }
        HANDOFF.set(value);
    }

    public static void clearHandoff() {
        HANDOFF.remove();
    }

    /** Returns null to request the untouched Decocraft buildQuads implementation. */
    public static List<BakedQuad> commit(
            Object blockbenchModel,
            Function<ResourceLocation, TextureAtlasSprite> spriteGetter) {
        if (!ENABLED) return null;
        Prepared prepared = HANDOFF.get();
        HANDOFF.remove();
        if (prepared == null) return null;

        long started = System.nanoTime();
        try {
            Bindings b = bindings();
            if (b == null || !b.matches(blockbenchModel, prepared)) {
                COMMIT_FALLBACKS.increment();
                return null;
            }
            List<BakedQuad> result = b.commit(blockbenchModel, spriteGetter, prepared);
            COMMIT_CALLS.increment();
            COMMIT_QUADS.add(result.size());
            if (PROFILE) commitThread = Thread.currentThread().getName();
            return result;
        } catch (Throwable t) {
            COMMIT_FALLBACKS.increment();
            warnOnce("commit", t);
            return null;
        } finally {
            COMMIT_NANOS.add(System.nanoTime() - started);
        }
    }

    public static void finishModelBake() {
        HANDOFF.remove();
        if (!PROFILE) return;
        long prepareCalls = PREPARE_CALLS.sumThenReset();
        long prepareNanos = PREPARE_NANOS.sumThenReset();
        long prepareFailures = PREPARE_FAILURES.sumThenReset();
        long commitCalls = COMMIT_CALLS.sumThenReset();
        long commitNanos = COMMIT_NANOS.sumThenReset();
        long commitFallbacks = COMMIT_FALLBACKS.sumThenReset();
        long quads = COMMIT_QUADS.sumThenReset();
        if (prepareCalls == 0L && commitCalls == 0L && prepareFailures == 0L && commitFallbacks == 0L) return;
        LOGGER.info(
                "BOOTOPTIM_DECOCRAFT_PREPARED kind=summary prepare_calls={} prepare_ms={} prepare_failures={} commit_calls={} commit_ms={} commit_fallbacks={} quads={} prepare_thread={} commit_thread={}",
                prepareCalls, ms(prepareNanos), prepareFailures, commitCalls, ms(commitNanos), commitFallbacks,
                quads, sanitize(prepareThread), sanitize(commitThread));
    }

    private static Bindings bindings() {
        Bindings local = bindings;
        if (local != null) return local;
        if (bindingFailed) return null;
        synchronized (DecocraftPreparedGeometry.class) {
            local = bindings;
            if (local != null) return local;
            if (bindingFailed) return null;
            try {
                if (!SUPPORTED_VERSION.equals(decocraftVersion())) {
                    bindingFailed = true;
                    return null;
                }
                local = new Bindings();
                bindings = local;
                return local;
            } catch (Throwable t) {
                bindingFailed = true;
                warnOnce("bind", t);
                return null;
            }
        }
    }

    private static String decocraftVersion() throws Exception {
        Class<?> modListClass = Class.forName("net.neoforged.fml.ModList");
        Object modList = modListClass.getMethod("get").invoke(null);
        Object optional = modListClass.getMethod("getModContainerById", String.class).invoke(modList, "decocraft");
        if (!(optional instanceof Optional<?> value) || value.isEmpty()) return "missing";
        Object container = value.get();
        Object modInfo = container.getClass().getMethod("getModInfo").invoke(container);
        Object version = modInfo.getClass().getMethod("getVersion").invoke(modInfo);
        return String.valueOf(version);
    }

    private static void warnOnce(String stage, Throwable t) {
        if (warned) return;
        synchronized (DecocraftPreparedGeometry.class) {
            if (warned) return;
            warned = true;
            LOGGER.warn("Decocraft prepared geometry disabled/falling back at {}: {}", stage, t.toString());
        }
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    private static String sanitize(String value) {
        return value == null ? "none" : value.replace(' ', '_').replace('\n', '_').replace('\r', '_');
    }

    private static final class Prepared {
        final Object elementsRef;
        final Object resolutionRef;
        final long fingerprint;
        final boolean hasRootOffset;
        final float rootTx;
        final float rootTy;
        final float rootTz;
        final List<PreparedFace> faces;

        Prepared(Object elementsRef, Object resolutionRef, long fingerprint, boolean hasRootOffset,
                float rootTx, float rootTy, float rootTz, List<PreparedFace> faces) {
            this.elementsRef = elementsRef;
            this.resolutionRef = resolutionRef;
            this.fingerprint = fingerprint;
            this.hasRootOffset = hasRootOffset;
            this.rootTx = rootTx;
            this.rootTy = rootTy;
            this.rootTz = rootTz;
            this.faces = faces;
        }
    }

    private static final class PreparedFace {
        final Direction sourceDirection;
        final boolean shade;
        final float u0;
        final float v0;
        final float u1;
        final float v1;
        final float[] positions;

        PreparedFace(Direction sourceDirection, boolean shade, float u0, float v0, float u1, float v1,
                float[] positions) {
            this.sourceDirection = sourceDirection;
            this.shade = shade;
            this.u0 = u0;
            this.v0 = v0;
            this.u1 = u1;
            this.v1 = v1;
            this.positions = positions;
        }

        boolean emit() {
            return positions != null;
        }
    }

    private static final class Bindings {
        final Class<?> elementClass;
        final Class<?> locatorClass;
        final Class<?> vertexDataClass;
        final Field bbGeometryModel;
        final Field bbModelElements;
        final Field bbModelResolution;
        final Field elementName;
        final Field elementFrom;
        final Field elementTo;
        final Field elementInflate;
        final Field elementFaces;
        final Field elementOrigin;
        final Field elementRotation;
        final Field elementParent;
        final Field elementShade;
        final Field locatorPosition;
        final Field groupOrigin;
        final Field groupRotation;
        final Field groupParent;
        final Field vectorX;
        final Field vectorY;
        final Field vectorZ;
        final Field faceUv;
        final Field faceTexture;
        final Field uvU0;
        final Field uvV0;
        final Field uvU1;
        final Field uvV1;
        final Field resolutionWidth;
        final Field resolutionHeight;
        final Field settingScale;
        final Field settingFlipV;
        final Field settingMaterial;
        final Field modelElements;
        final Field modelResolution;
        final Field modelSettings;
        final Field modelBakeSettings;
        final Field modelFaceQuads;
        final Field modelBakery;
        final Field vertexX;
        final Field vertexY;
        final Field vertexZ;
        final Constructor<?> bakeryConstructor;
        final Constructor<?> vertexConstructor;
        final Method selectPosition;
        final Method applyElementRotation;
        final Method calculateFacing;

        Bindings() throws Exception {
            Class<?> geometryClass = Class.forName("com.razz.decocraft.models.bbmodel.BBModelGeometryLoader$BBGeometry");
            Class<?> bbModelClass = Class.forName("com.razz.decocraft.models.bbmodel.BBModel");
            elementClass = Class.forName("com.razz.decocraft.models.bbmodel.BBModelParts$Element");
            Class<?> elementBaseClass = Class.forName("com.razz.decocraft.models.bbmodel.BBModelParts$ElementBase");
            locatorClass = Class.forName("com.razz.decocraft.models.bbmodel.BBModelParts$Locator");
            Class<?> groupClass = Class.forName("com.razz.decocraft.models.bbmodel.BBModelParts$OutlinerGroup");
            Class<?> vectorClass = Class.forName("com.razz.decocraft.models.libgdx.Vector3");
            Class<?> faceClass = Class.forName("com.razz.decocraft.models.bbmodel.BBModelParts$Face");
            Class<?> uvClass = Class.forName("com.razz.decocraft.models.bbmodel.BBModelParts$UVCoordinate");
            Class<?> resolutionClass = Class.forName("com.razz.decocraft.models.bbmodel.BBModelParts$Resolution");
            Class<?> settingClass = Class.forName("com.razz.decocraft.models.bbmodel.BlockbenchLoader$BlockbenchSetting");
            Class<?> modelClass = Class.forName("com.razz.decocraft.models.bbmodel.BlockbenchModel");
            Class<?> bakeryClass = Class.forName("com.razz.decocraft.models.bbmodel.BlockbenchBakery");
            vertexDataClass = Class.forName("com.razz.decocraft.models.bbmodel.BlockbenchBakery$VertexData");

            bbGeometryModel = field(geometryClass, "bbModel");
            bbModelElements = field(bbModelClass, "elements");
            bbModelResolution = field(bbModelClass, "resolution");
            elementName = field(elementBaseClass, "name");
            elementFrom = field(elementClass, "from");
            elementTo = field(elementClass, "to");
            elementInflate = field(elementClass, "inflate");
            elementFaces = field(elementClass, "faces");
            elementOrigin = field(elementBaseClass, "origin");
            elementRotation = field(elementBaseClass, "rotation");
            elementParent = field(elementBaseClass, "parent");
            elementShade = field(elementBaseClass, "shade");
            locatorPosition = field(locatorClass, "position");
            groupOrigin = field(groupClass, "origin");
            groupRotation = field(groupClass, "rotation");
            groupParent = field(groupClass, "parent");
            vectorX = field(vectorClass, "x");
            vectorY = field(vectorClass, "y");
            vectorZ = field(vectorClass, "z");
            faceUv = field(faceClass, "uv");
            faceTexture = field(faceClass, "texture");
            uvU0 = field(uvClass, "u0");
            uvV0 = field(uvClass, "v0");
            uvU1 = field(uvClass, "u1");
            uvV1 = field(uvClass, "v1");
            resolutionWidth = field(resolutionClass, "width");
            resolutionHeight = field(resolutionClass, "height");
            settingScale = field(settingClass, "scale");
            settingFlipV = field(settingClass, "flipV");
            settingMaterial = field(settingClass, "material");
            modelElements = field(modelClass, "elements");
            modelResolution = field(modelClass, "resolution");
            modelSettings = field(modelClass, "settings");
            modelBakeSettings = field(modelClass, "bakeSettings");
            modelFaceQuads = field(modelClass, "faceQuads");
            modelBakery = field(modelClass, "BAKERY");
            vertexX = field(vertexDataClass, "x");
            vertexY = field(vertexDataClass, "y");
            vertexZ = field(vertexDataClass, "z");

            bakeryConstructor = bakeryClass.getDeclaredConstructor();
            bakeryConstructor.setAccessible(true);
            vertexConstructor = vertexDataClass.getDeclaredConstructor();
            vertexConstructor.setAccessible(true);
            selectPosition = method(bakeryClass, "selectPosition");
            applyElementRotation = method(bakeryClass, "applyElementRotation");
            calculateFacing = method(bakeryClass, "calculateFacing");
        }

        Prepared prepare(Object bbModel) throws Exception {
            Object elementsObject = bbModelElements.get(bbModel);
            Object resolution = bbModelResolution.get(bbModel);
            if (!(elementsObject instanceof List<?> elements) || resolution == null) {
                throw new IllegalStateException("missing elements/resolution");
            }
            long fingerprint = fingerprint(elements, resolution);
            Object root = findRoot(elements);
            boolean hasRootOffset = root != null && locatorPosition.get(root) != null;
            float rootTx = 0.0F;
            float rootTy = 0.0F;
            float rootTz = 0.0F;
            if (hasRootOffset) {
                Object pos = locatorPosition.get(root);
                rootTx = -vectorX.getFloat(pos) / 16.0F;
                rootTy = -vectorY.getFloat(pos) / 16.0F;
                rootTz = -vectorZ.getFloat(pos) / 16.0F;
            }

            Object bakery = bakeryConstructor.newInstance();
            List<PreparedFace> preparedFaces = new ArrayList<>();
            for (Object base : elements) {
                if (!elementClass.isInstance(base)) continue;
                Object facesObject = elementFaces.get(base);
                if (!(facesObject instanceof Map<?, ?> faces)) continue;
                Object from = elementFrom.get(base);
                Object to = elementTo.get(base);
                float inflate = elementInflate.getFloat(base);
                float halfInflate = inflate / 2.0F;
                float fromX = (vectorX.getFloat(from) - halfInflate) / 16.0F;
                float fromY = (vectorY.getFloat(from) - halfInflate) / 16.0F;
                float fromZ = (vectorZ.getFloat(from) - halfInflate) / 16.0F;
                float toX = (vectorX.getFloat(to) + halfInflate) / 16.0F;
                float toY = (vectorY.getFloat(to) + halfInflate) / 16.0F;
                float toZ = (vectorZ.getFloat(to) + halfInflate) / 16.0F;
                boolean shade = elementShade.getBoolean(base);

                for (Object key : faces.keySet()) {
                    Direction direction = (Direction) key;
                    Object face = faces.get(key);
                    if (faceTexture.getInt(face) < 0) continue;
                    Object uv = faceUv.get(face);
                    if (uv == null) continue;
                    float u0 = uvU0.getFloat(uv);
                    float v0 = uvV0.getFloat(uv);
                    float u1 = uvU1.getFloat(uv);
                    float v1 = uvV1.getFloat(uv);
                    boolean emit = Math.abs(u1 - u0) * Math.abs(v1 - v0) >= 0.1F;
                    float[] positions = emit ? new float[12] : null;
                    if (emit) {
                        FaceInfo faceInfo = FaceInfo.fromFacing(direction);
                        for (int i = 0; i < 4; i++) {
                            Object pos = selectPosition.invoke(
                                    null, faceInfo.getVertexInfo(i), fromX, fromY, fromZ, toX, toY, toZ);
                            applyElementRotation.invoke(bakery, pos, base);
                            int offset = i * 3;
                            positions[offset] = vectorX.getFloat(pos);
                            positions[offset + 1] = vectorY.getFloat(pos);
                            positions[offset + 2] = vectorZ.getFloat(pos);
                        }
                    }
                    preparedFaces.add(new PreparedFace(direction, shade, u0, v0, u1, v1, positions));
                }
            }
            return new Prepared(elementsObject, resolution, fingerprint, hasRootOffset, rootTx, rootTy, rootTz,
                    List.copyOf(preparedFaces));
        }

        boolean matches(Object model, Prepared prepared) throws Exception {
            Object elements = modelElements.get(model);
            Object resolution = modelResolution.get(model);
            if (elements != prepared.elementsRef || resolution != prepared.resolutionRef) return false;
            return elements instanceof List<?> list && fingerprint(list, resolution) == prepared.fingerprint;
        }

        @SuppressWarnings("unchecked")
        List<BakedQuad> commit(Object model, Function<ResourceLocation, TextureAtlasSprite> spriteGetter,
                Prepared prepared) throws Exception {
            Map<Direction, List<BakedQuad>> faceQuads =
                    (Map<Direction, List<BakedQuad>>) modelFaceQuads.get(model);
            for (Direction direction : Direction.values()) {
                faceQuads.put(direction, new ArrayList<>());
            }

            Object settings = modelSettings.get(model);
            float scale = settingScale.getFloat(settings);
            boolean flipV = settingFlipV.getBoolean(settings);
            String material = (String) settingMaterial.get(settings);
            TextureAtlasSprite texture = spriteGetter.apply(ResourceLocation.parse(material));
            List<BakedQuad> quads = new ArrayList<>();
            if (texture == null) return quads;

            Object resolution = modelResolution.get(model);
            int width = resolutionWidth.getInt(resolution);
            int height = resolutionHeight.getInt(resolution);
            ModelState modelState = (ModelState) modelBakeSettings.get(model);
            Object bakery = modelBakery.get(model);

            for (PreparedFace face : prepared.faces) {
                // Stock evaluates ModelState before bakeQuad performs its tiny-UV early return.
                Matrix4f modelTransform = modelState != null
                        ? modelState.getRotation().getMatrix()
                        : new Matrix4f();
                if (!face.emit()) continue;

                Object vertexData = Array.newInstance(vertexDataClass, 4);
                float[] xyz = new float[12];
                float[] us = new float[4];
                float[] vs = new float[4];
                for (int i = 0; i < 4; i++) {
                    int p = i * 3;
                    float x = face.positions[p];
                    float y = face.positions[p + 1];
                    float z = face.positions[p + 2];

                    float sx = scale * x + 0.0F * y + 0.0F * z + 0.0F;
                    float sy = 0.0F * x + scale * y + 0.0F * z + 0.0F;
                    float sz = 0.0F * x + 0.0F * y + scale * z + 0.0F;
                    float tx = 1.0F * sx + 0.0F * sy + 0.0F * sz + 0.5F;
                    float ty = 0.0F * sx + 1.0F * sy + 0.0F * sz + 0.0F;
                    float tz = 0.0F * sx + 0.0F * sy + 1.0F * sz + 0.5F;
                    if (prepared.hasRootOffset) {
                        float rx = 1.0F * tx + 0.0F * ty + 0.0F * tz + prepared.rootTx;
                        float ry = 0.0F * tx + 1.0F * ty + 0.0F * tz + prepared.rootTy;
                        float rz = 0.0F * tx + 0.0F * ty + 1.0F * tz + prepared.rootTz;
                        tx = rx;
                        ty = ry;
                        tz = rz;
                    }
                    if (!modelTransform.equals(new Matrix4f())) {
                        Vector4f delta = new Vector4f(tx - 0.5F, ty - 0.5F, tz - 0.5F, 1.0F);
                        delta.mul(modelTransform);
                        tx = delta.x + 0.5F;
                        ty = delta.y + 0.5F;
                        tz = delta.z + 0.5F;
                    }
                    xyz[p] = tx;
                    xyz[p + 1] = ty;
                    xyz[p + 2] = tz;

                    float u;
                    float v;
                    if (!flipV) {
                        u = (i < 2) ? face.u0 : face.u1;
                        v = (i == 0 || i == 3) ? face.v0 : face.v1;
                    } else {
                        u = (i < 2) ? face.u0 : face.u1;
                        v = (i == 0 || i == 3) ? face.v1 : face.v0;
                    }
                    us[i] = texture.getU(u / (float) width);
                    vs[i] = texture.getV(v / (float) height);

                    Object vertex = vertexConstructor.newInstance();
                    vertexX.setFloat(vertex, tx);
                    vertexY.setFloat(vertex, ty);
                    vertexZ.setFloat(vertex, tz);
                    Array.set(vertexData, i, vertex);
                }

                Direction facing = (Direction) calculateFacing.invoke(bakery, vertexData);
                int[] vertices = new int[32];
                int normal = packNormal(facing.getStepX(), facing.getStepY(), facing.getStepZ());
                for (int i = 0; i < 4; i++) {
                    int dst = i * 8;
                    int p = i * 3;
                    vertices[dst] = Float.floatToRawIntBits(xyz[p]);
                    vertices[dst + 1] = Float.floatToRawIntBits(xyz[p + 1]);
                    vertices[dst + 2] = Float.floatToRawIntBits(xyz[p + 2]);
                    vertices[dst + 3] = -1;
                    vertices[dst + 4] = Float.floatToRawIntBits(us[i]);
                    vertices[dst + 5] = Float.floatToRawIntBits(vs[i]);
                    vertices[dst + 6] = 0;
                    vertices[dst + 7] = normal;
                }
                quads.add(new BakedQuad(vertices, -1, facing, texture, face.shade));
            }
            return quads;
        }

        private Object findRoot(List<?> elements) throws Exception {
            Object root = null;
            for (Object element : elements) {
                if (!locatorClass.isInstance(element)) continue;
                String name = (String) elementName.get(element);
                if (name.toLowerCase().equals("root_node")) root = element;
            }
            return root;
        }

        private long fingerprint(List<?> elements, Object resolution) throws Exception {
            long hash = 0xcbf29ce484222325L;
            hash = mix(hash, elements.size());
            hash = mix(hash, resolutionWidth.getInt(resolution));
            hash = mix(hash, resolutionHeight.getInt(resolution));
            for (Object base : elements) {
                hash = mix(hash, base.getClass().getName().hashCode());
                Object name = elementName.get(base);
                hash = mix(hash, name == null ? 0 : name.hashCode());
                if (locatorClass.isInstance(base)) {
                    hash = mixVector(hash, locatorPosition.get(base));
                }
                if (!elementClass.isInstance(base)) continue;
                hash = mixVector(hash, elementFrom.get(base));
                hash = mixVector(hash, elementTo.get(base));
                hash = mix(hash, Float.floatToRawIntBits(elementInflate.getFloat(base)));
                hash = mixVector(hash, elementOrigin.get(base));
                hash = mixVector(hash, elementRotation.get(base));
                hash = mix(hash, elementShade.getBoolean(base) ? 1 : 0);
                Object parent = elementParent.get(base);
                int depth = 0;
                while (parent != null) {
                    if (++depth > 256) throw new IllegalStateException("Decocraft parent chain too deep");
                    hash = mixVector(hash, groupOrigin.get(parent));
                    hash = mixVector(hash, groupRotation.get(parent));
                    parent = groupParent.get(parent);
                }
                hash = mix(hash, depth);
                Object facesObject = elementFaces.get(base);
                if (!(facesObject instanceof Map<?, ?> faces)) {
                    hash = mix(hash, -1);
                    continue;
                }
                hash = mix(hash, faces.size());
                for (Object key : faces.keySet()) {
                    Direction direction = (Direction) key;
                    hash = mix(hash, direction.ordinal());
                    Object face = faces.get(key);
                    hash = mix(hash, faceTexture.getInt(face));
                    Object uv = faceUv.get(face);
                    if (uv == null) {
                        hash = mix(hash, 0x51f15e);
                    } else {
                        hash = mix(hash, Float.floatToRawIntBits(uvU0.getFloat(uv)));
                        hash = mix(hash, Float.floatToRawIntBits(uvV0.getFloat(uv)));
                        hash = mix(hash, Float.floatToRawIntBits(uvU1.getFloat(uv)));
                        hash = mix(hash, Float.floatToRawIntBits(uvV1.getFloat(uv)));
                    }
                }
            }
            return hash;
        }

        private long mixVector(long hash, Object vector) throws Exception {
            if (vector == null) return mix(hash, 0x42);
            hash = mix(hash, Float.floatToRawIntBits(vectorX.getFloat(vector)));
            hash = mix(hash, Float.floatToRawIntBits(vectorY.getFloat(vector)));
            return mix(hash, Float.floatToRawIntBits(vectorZ.getFloat(vector)));
        }

        private static long mix(long hash, long value) {
            hash ^= value;
            return hash * 0x100000001b3L;
        }

        private static int packNormal(float x, float y, float z) {
            int nx = (int) (x * 127.0F) & 255;
            int ny = (int) (y * 127.0F) & 255;
            int nz = (int) (z * 127.0F) & 255;
            return nx | ny << 8 | nz << 16;
        }

        private static Field field(Class<?> type, String name) throws Exception {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }

        private static Method method(Class<?> type, String name) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(name)) {
                    method.setAccessible(true);
                    return method;
                }
            }
            throw new IllegalStateException("Missing Decocraft method " + type.getName() + '.' + name);
        }
    }
}
