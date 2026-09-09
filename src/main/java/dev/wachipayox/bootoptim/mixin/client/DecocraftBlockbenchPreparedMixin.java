package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.DecocraftPreparedGeometry;
import java.util.List;
import java.util.function.Function;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.razz.decocraft.models.bbmodel.BlockbenchModel", remap = false)
abstract class DecocraftBlockbenchPreparedMixin {
    @Inject(method = "buildQuads", at = @At("HEAD"), cancellable = true, require = 0)
    private void bootoptim$commitPrepared(
            Function<ResourceLocation, TextureAtlasSprite> spriteGetter,
            CallbackInfoReturnable<List<BakedQuad>> cir) {
        List<BakedQuad> prepared = DecocraftPreparedGeometry.commit(this, spriteGetter);
        if (prepared != null) {
            cir.setReturnValue(prepared);
        }
    }
}
