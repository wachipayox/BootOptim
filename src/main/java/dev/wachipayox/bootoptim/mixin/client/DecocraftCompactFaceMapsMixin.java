package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.DecocraftFaceMaps;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Compacts the parser's small face maps before the model is published; see {@link DecocraftFaceMaps}. */
@Pseudo
@Mixin(targets = "com.razz.decocraft.models.bbmodel.BBModelLoader", remap = false)
abstract class DecocraftCompactFaceMapsMixin {
    @Inject(
            method = {
                "loadModel(Ljava/io/Reader;)Lcom/razz/decocraft/models/bbmodel/BBModel;",
                "loadModel(Lcom/google/gson/JsonDeserializationContext;Lcom/google/gson/JsonObject;)Lcom/razz/decocraft/models/bbmodel/BBModel;"
            },
            at = @At("RETURN"),
            require = 0)
    private void bootoptim$compactFaces(CallbackInfoReturnable<Object> result) {
        DecocraftFaceMaps.compact(result.getReturnValue());
    }
}
