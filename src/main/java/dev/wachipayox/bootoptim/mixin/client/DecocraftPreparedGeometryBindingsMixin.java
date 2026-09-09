package dev.wachipayox.bootoptim.mixin.client;

import java.lang.reflect.Field;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Version-pinned binding correction for the Agent85 Decocraft 3.0.11 experiment.
 *
 * <p>In 3.0.11, origin/rotation/parent are declared on ElementBase and inherited by
 * OutlinerGroup. The first prototype used getDeclaredField on OutlinerGroup, so binding
 * failed before the experiment could run. Redirect only those inherited group fields to
 * their declaring class; all other bindings remain strict and fail open on layout drift.</p>
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
        if (owner.getName().equals("com.razz.decocraft.models.bbmodel.BBModelParts$OutlinerGroup")
                && (name.equals("origin") || name.equals("rotation") || name.equals("parent"))) {
            owner = Class.forName("com.razz.decocraft.models.bbmodel.BBModelParts$ElementBase");
        }
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
