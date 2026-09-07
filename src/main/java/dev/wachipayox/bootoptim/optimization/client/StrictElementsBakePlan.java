package dev.wachipayox.bootoptim.optimization.client;

import com.mojang.math.Transformation;
import net.minecraft.client.renderer.block.model.BlockElement;
import net.minecraft.client.renderer.block.model.BlockElementFace;
import net.minecraft.client.renderer.block.model.BlockElementRotation;
import net.minecraft.client.renderer.block.model.FaceBakery;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.core.Direction;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.neoforged.neoforge.client.model.ExtraFaceData;
import net.neoforged.neoforge.client.model.IModelBuilder;
import net.neoforged.neoforge.client.model.geometry.BlockGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import dev.wachipayox.bootoptim.profiling.StartupReport;

/**
 * Experimental reload-local lowering plan for the strict ordinary-elements
 * path. It removes the per-face map traversal and BlockModel material-chain
 * lookup, but keeps the current sprite getter, FaceBakery and model builder.
 *
 * <p>The plan is deliberately opt-in. It is not a persistent cache and does
 * not replace the observable ModelBakery graph or NeoForge callbacks.</p>
 */
public final class StrictElementsBakePlan {
    private static final String PROPERTY = "boot_optim.compiledElementsMaterialPlan";
    private static final FaceBakery FACE_BAKERY = new FaceBakery();
    private static final AtomicBoolean ACTIVE_REPORTED = new AtomicBoolean();

    private final BlockElement[] elements;
    private final Direction[] directions;
    private final BlockElementFace[] faces;
    private final Material[] materials;
    private final Vector3f[] from;
    private final Vector3f[] to;
    private final BlockElementRotation[] rotations;
    private final boolean[] shades;
    private final Direction[] cullDirections;

    private StrictElementsBakePlan(
            BlockElement[] elements,
            Direction[] directions,
            BlockElementFace[] faces,
            Material[] materials,
            Vector3f[] from,
            Vector3f[] to,
            BlockElementRotation[] rotations,
            boolean[] shades,
            Direction[] cullDirections) {
        this.elements = elements;
        this.directions = directions;
        this.faces = faces;
        this.materials = materials;
        this.from = from;
        this.to = to;
        this.rotations = rotations;
        this.shades = shades;
        this.cullDirections = cullDirections;
    }

    public static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty(PROPERTY, "false"));
    }

    /**
     * Builds a plan from the already-resolved vanilla model. The strict plan
     * refuses non-default face metadata because that data is owned by
     * NeoForge/model extensions rather than the ordinary vanilla path.
     */
    public static StrictElementsBakePlan compile(BlockModel owner) {
        if (!enabled() || owner == null) {
            return null;
        }
        List<BlockElement> sourceElements = owner.getElements();
        if (sourceElements.isEmpty()) {
            return null;
        }

        List<BlockElement> elements = new ArrayList<>();
        List<Direction> directions = new ArrayList<>();
        List<BlockElementFace> faces = new ArrayList<>();
        List<Material> materials = new ArrayList<>();
        List<Vector3f> from = new ArrayList<>();
        List<Vector3f> to = new ArrayList<>();
        List<BlockElementRotation> rotations = new ArrayList<>();
        List<Boolean> shades = new ArrayList<>();
        List<Direction> culls = new ArrayList<>();

        for (BlockElement element : sourceElements) {
            if (element == null || element.faces == null || element.faces.isEmpty()) {
                return null;
            }
            if (element.getFaceData() != null && !ExtraFaceData.DEFAULT.equals(element.getFaceData())) {
                return null;
            }
            for (Direction direction : element.faces.keySet()) {
                BlockElementFace face = element.faces.get(direction);
                if (face == null || face.faceData() != null && !ExtraFaceData.DEFAULT.equals(face.faceData())) {
                    return null;
                }
                elements.add(element);
                directions.add(direction);
                faces.add(face);
                materials.add(owner.getMaterial(face.texture()));
                from.add(element.from);
                to.add(element.to);
                rotations.add(element.rotation);
                shades.add(element.shade);
                culls.add(face.cullForDirection());
            }
        }
        if (faces.isEmpty()) {
            return null;
        }
        return new StrictElementsBakePlan(
                elements.toArray(BlockElement[]::new),
                directions.toArray(Direction[]::new),
                faces.toArray(BlockElementFace[]::new),
                materials.toArray(Material[]::new),
                from.toArray(Vector3f[]::new),
                to.toArray(Vector3f[]::new),
                rotations.toArray(BlockElementRotation[]::new),
                toBooleanArray(shades),
                culls.toArray(Direction[]::new));
    }

    private static boolean[] toBooleanArray(List<Boolean> values) {
        boolean[] result = new boolean[values.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = values.get(i);
        }
        return result;
    }

    /**
     * Writes the same face order and builder callbacks as stock
     * ElementsModel.addQuads. The current ModelState and sprite getter remain
     * authoritative for every invocation.
     */
    public void addQuads(
            IGeometryBakingContext context,
            IModelBuilder<?> modelBuilder,
            ModelBaker baker,
            Function<Material, TextureAtlasSprite> spriteGetter,
            ModelState modelState) {
        Transformation rootTransform = context.getRootTransform();
        if (!rootTransform.isIdentity()) {
            return;
        }
        Transformation rotation = modelState.getRotation();
        for (int i = 0; i < faces.length; i++) {
            TextureAtlasSprite sprite = spriteGetter.apply(materials[i]);
            var quad = FACE_BAKERY.bakeQuad(
                    from[i], to[i], faces[i], sprite, directions[i], modelState, rotations[i], shades[i]);
            Direction cull = cullDirections[i];
            if (cull == null) {
                modelBuilder.addUnculledFace(quad);
            } else {
                modelBuilder.addCulledFace(rotation.rotateTransform(cull), quad);
            }
        }
    }

    public static void reportActive() {
        if (ACTIVE_REPORTED.compareAndSet(false, true)
                && (Boolean.getBoolean("boot_optim.profileStartup")
                || Boolean.getBoolean("boot_optim.benchmark.exitOnTitle"))) {
            StartupReport.optimization("compiled_elements_material_plan", true,
                    "reload_local_direct_materials");
        }
    }
}
