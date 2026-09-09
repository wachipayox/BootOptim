package dev.wachipayox.bootoptim.mixin.client;

import java.lang.reflect.Field;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Version-pinned inherited-field binding correction for the Decocraft 3.0.11 experiment. */
@Pseudo
@Mixin(targets = "dev.wachipayox.bootoptim.optimization.client.DecocraftPreparedGeometry$Bindings", remap = false)
abstract class DecocraftPreparedGeometryBindingsMixin {
    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/wachipayox/bootoptim/optimization/client/DecocraftPreparedGeometry$Bindings;field(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/reflect/Field;"),
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
