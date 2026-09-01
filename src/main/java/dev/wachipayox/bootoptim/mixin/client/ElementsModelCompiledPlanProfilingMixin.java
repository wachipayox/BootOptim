package dev.wachipayox.bootoptim.mixin.client;

import com.mojang.math.Transformation;
import dev.wachipayox.bootoptim.profiling.client.CompiledElementsProfiler;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockElement;
import net.minecraft.client.renderer.block.model.BlockElementFace;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.client.model.ElementsModel;
import net.neoforged.neoforge.client.model.IModelBuilder;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.UnbakedGeometryHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Diagnostic-only sampled breakdown of ElementsModel#addQuads. */
@Mixin(ElementsModel.class)
abstract class ElementsModelCompiledPlanProfilingMixin {
    @Inject(method = "addQuads", at = @At("HEAD"), require = 0)
    private void bootoptim$compiledPlanBegin(
            IGeometryBakingContext context,
            IModelBuilder<?> modelBuilder,
            ModelBaker baker,
            Function<Material, TextureAtlasSprite> spriteGetter,
            ModelState modelState,
            CallbackInfo ci) {
        CompiledElementsProfiler.beginElements();
    }

    @Inject(method = "addQuads", at = @At("RETURN"), require = 0)
    private void bootoptim$compiledPlanEnd(
            IGeometryBakingContext context,
            IModelBuilder<?> modelBuilder,
            ModelBaker baker,
            Function<Material, TextureAtlasSprite> spriteGetter,
            ModelState modelState,
            CallbackInfo ci) {
        CompiledElementsProfiler.endElements();
    }

    @Redirect(
            method = "addQuads",
            at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/client/model/geometry/IGeometryBakingContext;getRootTransform()Lcom/mojang/math/Transformation;"),
            require = 0)
    private Transformation bootoptim$profileRootTransform(IGeometryBakingContext context) {
        long started = CompiledElementsProfiler.startRoot();
        try {
            return context.getRootTransform();
        } finally {
            CompiledElementsProfiler.endRoot(started);
        }
    }

    @Redirect(
            method = "addQuads",
            at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/client/model/geometry/UnbakedGeometryHelper;composeRootTransformIntoModelState(Lnet/minecraft/client/resources/model/ModelState;Lcom/mojang/math/Transformation;)Lnet/minecraft/client/resources/model/ModelState;"),
            require = 0)
    private ModelState bootoptim$profileRootCompose(ModelState state, Transformation rootTransform) {
        long started = CompiledElementsProfiler.startRootCompose();
        if (started == 0L) CompiledElementsProfiler.rootComposeObserved();
        try {
            return UnbakedGeometryHelper.composeRootTransformIntoModelState(state, rootTransform);
        } finally {
            CompiledElementsProfiler.endRootCompose(started);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(
            method = "addQuads",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;keySet()Ljava/util/Set;"),
            require = 0)
    private Set bootoptim$countElementMap(Map map) {
        CompiledElementsProfiler.elementMap();
        return map.keySet();
    }

    @Redirect(
            method = "addQuads",
            at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/client/model/geometry/IGeometryBakingContext;getMaterial(Ljava/lang/String;)Lnet/minecraft/client/resources/model/Material;"),
            require = 0)
    private Material bootoptim$profileMaterial(IGeometryBakingContext context, String name) {
        CompiledElementsProfiler.beginFace();
        long started = CompiledElementsProfiler.startMaterial();
        try {
            return context.getMaterial(name);
        } finally {
            CompiledElementsProfiler.endMaterial(started);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(
            method = "addQuads",
            at = @At(value = "INVOKE", target = "Ljava/util/function/Function;apply(Ljava/lang/Object;)Ljava/lang/Object;"),
            require = 0)
    private Object bootoptim$profileSprite(Function function, Object material) {
        long started = CompiledElementsProfiler.startSprite();
        try {
            return function.apply(material);
        } finally {
            CompiledElementsProfiler.endSprite(started);
        }
    }

    @Redirect(
            method = "addQuads",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/model/BlockModel;bakeFace(Lnet/minecraft/client/renderer/block/model/BlockElement;Lnet/minecraft/client/renderer/block/model/BlockElementFace;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;Lnet/minecraft/core/Direction;Lnet/minecraft/client/resources/model/ModelState;)Lnet/minecraft/client/renderer/block/model/BakedQuad;"),
            require = 0)
    private BakedQuad bootoptim$profileBlockModelFace(
            BlockElement element,
            BlockElementFace face,
            TextureAtlasSprite sprite,
            Direction direction,
            ModelState state) {
        long started = CompiledElementsProfiler.startBlockModelFace();
        try {
            return BlockModel.bakeFace(element, face, sprite, direction, state);
        } finally {
            CompiledElementsProfiler.endBlockModelFace(started);
        }
    }

    @Redirect(
            method = "addQuads",
            at = @At(value = "INVOKE", target = "Lcom/mojang/math/Transformation;rotateTransform(Lnet/minecraft/core/Direction;)Lnet/minecraft/core/Direction;"),
            require = 0)
    private Direction bootoptim$profileCullTransform(Transformation transform, Direction direction) {
        long started = CompiledElementsProfiler.startCullTransform();
        try {
            return transform.rotateTransform(direction);
        } finally {
            CompiledElementsProfiler.endCullTransform(started);
        }
    }

    @Redirect(
            method = "addQuads",
            at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/client/model/IModelBuilder;addUnculledFace(Lnet/minecraft/client/renderer/block/model/BakedQuad;)Lnet/neoforged/neoforge/client/model/IModelBuilder;"),
            require = 0)
    private IModelBuilder<?> bootoptim$profileUnculledBuilder(IModelBuilder<?> builder, BakedQuad quad) {
        long started = CompiledElementsProfiler.startBuilder();
        try {
            return builder.addUnculledFace(quad);
        } finally {
            CompiledElementsProfiler.endBuilder(started);
        }
    }

    @Redirect(
            method = "addQuads",
            at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/client/model/IModelBuilder;addCulledFace(Lnet/minecraft/core/Direction;Lnet/minecraft/client/renderer/block/model/BakedQuad;)Lnet/neoforged/neoforge/client/model/IModelBuilder;"),
            require = 0)
    private IModelBuilder<?> bootoptim$profileCulledBuilder(IModelBuilder<?> builder, Direction direction, BakedQuad quad) {
        long started = CompiledElementsProfiler.startBuilder();
        try {
            return builder.addCulledFace(direction, quad);
        } finally {
            CompiledElementsProfiler.endBuilder(started);
        }
    }
}
