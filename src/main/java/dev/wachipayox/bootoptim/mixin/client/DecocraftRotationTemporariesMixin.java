package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.wachipayox.bootoptim.optimization.client.DecocraftRotationScratch;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/** Reuses two per-call temporaries of Decocraft's rotation helpers; see {@link DecocraftRotationScratch}. */
@Pseudo
@Mixin(targets = "com.razz.decocraft.models.bbmodel.BlockbenchBakery", remap = false)
abstract class DecocraftRotationTemporariesMixin {
    @Unique private static final Matrix4f bootoptim$identity = new Matrix4f();
    @Unique private volatile DecocraftRotationScratch bootoptim$originScratch;

    @Unique
    private synchronized DecocraftRotationScratch bootoptim$createOriginScratch() {
        if (bootoptim$originScratch == null) {
            bootoptim$originScratch = new DecocraftRotationScratch();
        }
        return bootoptim$originScratch;
    }

    @WrapOperation(
            method = "applyModelRotation(Lcom/razz/decocraft/models/libgdx/Vector3;Lcom/razz/decocraft/models/libgdx/Vector3;Lorg/joml/Matrix4f;)V",
            at = @At(value = "NEW", target = "()Lorg/joml/Matrix4f;"),
            require = 0)
    private Matrix4f bootoptim$identityComparison(Operation<Matrix4f> original, @Local(argsOnly = true) Matrix4f matrix) {
        // An overridden equals could retain or mutate its argument: only the exact audited class sees the private identity.
        if (!DecocraftRotationScratch.activeFor(matrix)) {
            return original.call();
        }
        return bootoptim$identity;
    }

    @WrapOperation(
            method = "applyElementRotation(Lcom/razz/decocraft/models/libgdx/Vector3;Lcom/razz/decocraft/models/bbmodel/BBModelParts$Element;)V",
            at = @At(value = "NEW", target = "(FFF)Lcom/razz/decocraft/models/libgdx/Vector3;"),
            require = 0)
    @Coerce
    private Object bootoptim$originSnapshot(float x, float y, float z, Operation<Object> original) {
        if (!DecocraftRotationScratch.active()) {
            return original.call(x, y, z);
        }
        DecocraftRotationScratch scratch = bootoptim$originScratch;
        if (scratch == null) {
            scratch = bootoptim$createOriginScratch();
        }
        Object cached = scratch.reuse(x, y, z);
        if (cached != null) {
            return cached;
        }
        Object created = original.call(x, y, z);
        scratch.initialize(created);
        return created;
    }
}
