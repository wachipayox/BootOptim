package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.CampaignModelResidualProfiler;
import java.util.function.Function;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.neoforged.neoforge.client.model.ElementsModel;
import net.neoforged.neoforge.client.model.IModelBuilder;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Whole-call ElementsModel timing only; deliberately avoids per-face instrumentation. */
@Mixin(ElementsModel.class)
abstract class ElementsModelCampaignProfilingMixin {
    @Unique
    private static final ThreadLocal<long[]> BOOTOPTIM$START = ThreadLocal.withInitial(() -> new long[1]);

    @Inject(method = "addQuads", at = @At("HEAD"))
    private void bootoptim$beginElements(
            IGeometryBakingContext context,
            IModelBuilder<?> modelBuilder,
            ModelBaker baker,
            Function<Material, TextureAtlasSprite> spriteGetter,
            ModelState modelState,
            CallbackInfo ci) {
        if (CampaignModelResidualProfiler.active()) {
            BOOTOPTIM$START.get()[0] = System.nanoTime();
        }
    }

    @Inject(method = "addQuads", at = @At("RETURN"))
    private void bootoptim$endElements(
            IGeometryBakingContext context,
            IModelBuilder<?> modelBuilder,
            ModelBaker baker,
            Function<Material, TextureAtlasSprite> spriteGetter,
            ModelState modelState,
            CallbackInfo ci) {
        long[] holder = BOOTOPTIM$START.get();
        long started = holder[0];
        holder[0] = 0L;
        if (started != 0L) {
            CampaignModelResidualProfiler.recordElements(System.nanoTime() - started);
        }
    }
}
