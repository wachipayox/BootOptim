package dev.wachipayox.bootoptim.mixin.client;

import java.lang.reflect.Field;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Version-pinned binding correction for the Agent85 Decocraft 3.0.11 experiment.
 *
 * <p>The inspected 3.0.11 bytecode declares origin/rotation/parent/shade on Element,
 * while the first prototype asked getDeclaredField on ElementBase. Keep the correction
 * isolated so the optimization still fails open if the pinned layout changes.</p>
 */
@Pseudo
@Mixin(targets = "dev.wachipayox.bootoptim.optimization.client.DecocraftPreparedGeometry$Bindings", remap = false)
abstract class DecocraftPreparedGeometryBindingsMixin {
    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/wachipayox/bootoptim/optimization/client/DecocraftPreparedGeometry;field(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/reflect/Field;"),
            require = 0)
    private Field bootoptim$bindDeclaredOwner(Class<?> owner, String name) throws Exception {
        if (owner.getName().equals("com.razz.decocraft.models.bbmodel.BBModelParts$ElementBase")
                && (name.equals("origin") || name.equals("rotation") || name.equals("parent") || name.equals("shade"))) {
            owner = Class.forName("com.razz.decocraft.models.bbmodel.BBModelParts$Element");
        }
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
