package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.DirectGeneratedItemBaker;
import dev.wachipayox.bootoptim.profiling.client.CampaignModelResidualProfiler;
import java.util.function.Function;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Whole-call timing of the production direct generated-item path. */
@Mixin(DirectGeneratedItemBaker.class)
abstract class DirectGeneratedItemBakerCampaignProfilingMixin {
    @Unique
    private static final ThreadLocal<long[]> BOOTOPTIM$START = ThreadLocal.withInitial(() -> new long[1]);

    @Inject(method = "tryBake", at = @At("HEAD"), require = 0)
    private static void bootoptim$beginDirectBake(
            BlockModel blockModel,
            ModelBaker modelBaker,
            Function<Material, TextureAtlasSprite> spriteGetter,
            ModelState modelState,
            boolean guiLight3d,
            CallbackInfoReturnable<BakedModel> cir) {
        if (CampaignModelResidualProfiler.active()) {
            BOOTOPTIM$START.get()[0] = System.nanoTime();
        }
    }

    @Inject(method = "tryBake", at = @At("RETURN"), require = 0)
    private static void bootoptim$endDirectBake(
            BlockModel blockModel,
            ModelBaker modelBaker,
            Function<Material, TextureAtlasSprite> spriteGetter,
            ModelState modelState,
            boolean guiLight3d,
            CallbackInfoReturnable<BakedModel> cir) {
        long[] holder = BOOTOPTIM$START.get();
        long started = holder[0];
        holder[0] = 0L;
        if (started != 0L) {
            CampaignModelResidualProfiler.recordDirectGenerated(System.nanoTime() - started);
        }
    }
}
