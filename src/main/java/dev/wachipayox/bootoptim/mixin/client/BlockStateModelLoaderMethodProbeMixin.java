package dev.wachipayox.bootoptim.mixin.client;

import net.minecraft.client.resources.model.BlockStateModelLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;

/** Temporary CI-only probe to identify the exact javac synthetic method containing variant matching. */
@Mixin(BlockStateModelLoader.class)
abstract class BlockStateModelLoaderMethodProbeMixin {
    @Unique
    private static final Logger BOOTOPTIM$LOGGER = LoggerFactory.getLogger("BootOptim/BlockStateMethods");
    @Unique
    private static boolean bootoptim$reported;

    @Inject(method = "loadAllBlockStates", at = @At("HEAD"))
    private void bootoptim$reportSyntheticMethods(CallbackInfo ci) {
        if (bootoptim$reported) {
            return;
        }
        bootoptim$reported = true;

        Arrays.stream(BlockStateModelLoader.class.getDeclaredMethods())
                .filter(method -> method.getName().contains("loadBlockStateDefinitions"))
                .sorted(Comparator.comparing(Method::getName))
                .forEach(method -> BOOTOPTIM$LOGGER.info(
                        "BOOTOPTIM_BLOCKSTATE_METHOD name={} static={} synthetic={} params={} return={}",
                        method.getName(),
                        Modifier.isStatic(method.getModifiers()),
                        method.isSynthetic(),
                        Arrays.toString(method.getParameterTypes()),
                        method.getReturnType().getTypeName()));
    }
}
