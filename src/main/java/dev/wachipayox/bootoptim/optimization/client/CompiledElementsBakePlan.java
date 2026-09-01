package dev.wachipayox.bootoptim.optimization.client;

import com.mojang.math.Transformation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockElement;
import net.minecraft.client.renderer.block.model.BlockElementFace;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.client.model.EmptyModel;
import net.neoforged.neoforge.client.model.IModelBuilder;
import net.neoforged.neoforge.client.model.geometry.BlockGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.UnbakedGeometryHelper;
import org.jetbrains.annotations.Nullable;

/**
 * Experimental live-reference traversal plan for NeoForge's ElementsModel.
 *
 * <p>The plan deliberately does not freeze geometry, face metadata, materials, sprites, ModelState or
 * NeoForge FaceBakery behavior. It only flattens the stable element/face traversal order. The defining
 * BlockModel owns the plan, so inherited child models sharing the same element list also share the plan.
 */
public final class CompiledElementsBakePlan {
    private final List<BlockElement> sourceElements;
    private final BlockElement[] elements;
    private final int[] expectedFaceCounts;
    private final BlockElement[] faceElements;
    private final Direction[] directions;
    private final BlockElementFace[] faces;

    private CompiledElementsBakePlan(
            List<BlockElement> sourceElements,
            BlockElement[] elements,
            int[] expectedFaceCounts,
            BlockElement[] faceElements,
            Direction[] directions,
            BlockElementFace[] faces) {
        this.sourceElements = sourceElements;
        this.elements = elements;
        this.expectedFaceCounts = expectedFaceCounts;
        this.faceElements = faceElements;
        this.directions = directions;
        this.faces = faces;
    }

    /**
     * Returns a plan only after the defining geometry has been observed once through stock ElementsModel.
     * This avoids paying compilation cost for geometry lists that are never reused.
     */
    @Nullable
    public static CompiledElementsBakePlan acquire(
            IGeometryBakingContext context,
            List<BlockElement> elements) {
        if (!(context instanceof BlockGeometryBakingContext blockContext)) {
            return null;
        }

        BlockModel owner = blockContext.owner;
        if (owner.customData.hasCustomGeometry() || owner.getElements() != elements) {
            return null;
        }

        // getElements() inherits the parent's exact list when the child has no local elements. Walk to the
        // persistent model that actually defines that list so all such children share one compiled plan.
        BlockModel definingModel = owner;
        while (definingModel.parent != null && definingModel.parent.getElements() == elements) {
            definingModel = definingModel.parent;
        }
        if (definingModel.getElements() != elements) {
            return null;
        }

        CompiledElementsPlanHolder holder = (CompiledElementsPlanHolder) (Object) definingModel;
        CompiledElementsBakePlan existing = holder.bootoptim$getCompiledElementsPlan();
        if (existing != null) {
            return existing.sourceElements == elements ? existing : null;
        }

        synchronized (definingModel) {
            existing = holder.bootoptim$getCompiledElementsPlan();
            if (existing != null) {
                return existing.sourceElements == elements ? existing : null;
            }
            if (!holder.bootoptim$hasSeenElementsBake()) {
                holder.bootoptim$markElementsBakeSeen();
                return null;
            }

            CompiledElementsBakePlan compiled = compile(elements);
            if (compiled != null) {
                holder.bootoptim$setCompiledElementsPlan(compiled);
            }
            return compiled;
        }
    }

    @Nullable
    private static CompiledElementsBakePlan compile(List<BlockElement> source) {
        BlockElement[] elements = source.toArray(BlockElement[]::new);
        int[] faceCounts = new int[elements.length];
        int totalFaces = 0;
        for (int i = 0; i < elements.length; i++) {
            int size = elements[i].faces.size();
            faceCounts[i] = size;
            totalFaces += size;
        }
        if (totalFaces == 0) {
            return null;
        }

        BlockElement[] faceElements = new BlockElement[totalFaces];
        Direction[] directions = new Direction[totalFaces];
        BlockElementFace[] faces = new BlockElementFace[totalFaces];
        int index = 0;
        for (BlockElement element : elements) {
            for (Direction direction : element.faces.keySet()) {
                BlockElementFace face = element.faces.get(direction);
                if (face == null) {
                    return null;
                }
                faceElements[index] = element;
                directions[index] = direction;
                faces[index] = face;
                index++;
            }
        }
        if (index != totalFaces) {
            return null;
        }
        return new CompiledElementsBakePlan(source, elements, faceCounts, faceElements, directions, faces);
    }

    /**
     * Bakes the planned traversal into {@code modelBuilder}. Returns false before writing any quad when
     * structural assumptions no longer hold, allowing the caller to fail open to stock.
     */
    public boolean bake(
            IGeometryBakingContext context,
            IModelBuilder<?> modelBuilder,
            ModelBaker baker,
            Function<Material, TextureAtlasSprite> spriteGetter,
            ModelState modelState) {
        if (!validateLiveStructure()) {
            return false;
        }

        Transformation rootTransform = context.getRootTransform();
        if (!rootTransform.isIdentity()) {
            modelState = UnbakedGeometryHelper.composeRootTransformIntoModelState(modelState, rootTransform);
        }

        for (int i = 0; i < faces.length; i++) {
            BlockElement element = faceElements[i];
            Direction direction = directions[i];
            BlockElementFace face = faces[i];
            TextureAtlasSprite sprite = spriteGetter.apply(context.getMaterial(face.texture()));
            BakedQuad quad = BlockModel.bakeFace(element, face, sprite, direction, modelState);

            Direction cull = face.cullForDirection();
            if (cull == null) {
                modelBuilder.addUnculledFace(quad);
            } else {
                modelBuilder.addCulledFace(modelState.getRotation().rotateTransform(cull), quad);
            }
        }
        return true;
    }

    private boolean validateLiveStructure() {
        if (sourceElements.size() != elements.length) {
            return false;
        }
        for (int i = 0; i < elements.length; i++) {
            BlockElement element = elements[i];
            if (sourceElements.get(i) != element || element.faces.size() != expectedFaceCounts[i]) {
                return false;
            }
        }

        // One live map lookup per face is intentional. Stock already performs it, and keeping this identity check
        // means face replacement/key mutation fails open before the candidate writes anything. The second pass can
        // then reuse the live face reference without another map lookup.
        for (int i = 0; i < faces.length; i++) {
            if (faceElements[i].faces.get(directions[i]) != faces[i]) {
                return false;
            }
        }
        return true;
    }

    public int faceCount() {
        return faces.length;
    }

    public static String compare(List<QuadRecord> stock, List<QuadRecord> candidate) {
        if (stock.size() != candidate.size()) {
            return "quad count stock=" + stock.size() + " candidate=" + candidate.size();
        }
        for (int i = 0; i < stock.size(); i++) {
            QuadRecord expectedRecord = stock.get(i);
            QuadRecord actualRecord = candidate.get(i);
            if (expectedRecord.cullDirection != actualRecord.cullDirection) {
                return "cull direction at " + i + " stock=" + expectedRecord.cullDirection
                        + " candidate=" + actualRecord.cullDirection;
            }

            BakedQuad expected = expectedRecord.quad;
            BakedQuad actual = actualRecord.quad;
            if (!Arrays.equals(expected.getVertices(), actual.getVertices())) {
                return "vertices at " + i;
            }
            if (expected.getTintIndex() != actual.getTintIndex()) {
                return "tint at " + i;
            }
            if (expected.getDirection() != actual.getDirection()) {
                return "direction at " + i;
            }
            if (expected.isShade() != actual.isShade()) {
                return "shade at " + i;
            }
            if (expected.hasAmbientOcclusion() != actual.hasAmbientOcclusion()) {
                return "ambient occlusion at " + i;
            }
            if (expected.getSprite() != actual.getSprite()) {
                return "sprite identity at " + i;
            }
        }
        return null;
    }

    public record QuadRecord(@Nullable Direction cullDirection, BakedQuad quad) {
    }

    /** Builder used only by verification mode; it records both quad order and the final culled direction. */
    public static final class RecordingBuilder implements IModelBuilder<RecordingBuilder> {
        private final ArrayList<QuadRecord> records = new ArrayList<>();

        @Override
        public RecordingBuilder addCulledFace(Direction facing, BakedQuad quad) {
            records.add(new QuadRecord(facing, quad));
            return this;
        }

        @Override
        public RecordingBuilder addUnculledFace(BakedQuad quad) {
            records.add(new QuadRecord(null, quad));
            return this;
        }

        @Override
        public BakedModel build() {
            return EmptyModel.BAKED;
        }

        public List<QuadRecord> records() {
            return records;
        }
    }
}
