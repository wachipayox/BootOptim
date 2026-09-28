package dev.wachipayox.bootoptim.mixin.client;

import com.mojang.logging.LogUtils;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.renderer.block.model.MultiVariant;
import net.minecraft.client.renderer.block.model.Variant;
import net.minecraft.client.renderer.block.model.multipart.MultiPart;
import net.minecraft.client.renderer.block.model.multipart.Selector;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;

/**
 * Builds the same fresh dependency set without a fresh stream and set for each selector.
 * This deliberately retains no result between calls or reload generations.
 */
@Mixin(MultiPart.class)
abstract class MultiPartDirectDependenciesMixin {
    private static final boolean BOOTOPTIM_ENABLED = Boolean.getBoolean("boot_optim.multipartDirectDependencies");
    private static final AtomicBoolean BOOTOPTIM_LOGGED = new AtomicBoolean();
    private static final Logger BOOTOPTIM_LOGGER = LogUtils.getLogger();

    @Shadow
    public abstract java.util.List<Selector> getSelectors();

    @Inject(method = "getDependencies", at = @At("HEAD"), cancellable = true, require = 0)
    private void bootoptim$collectDirectDependencies(CallbackInfoReturnable<Collection<ResourceLocation>> cir) {
        if (!BOOTOPTIM_ENABLED || ((Object) this).getClass() != MultiPart.class) {
            return;
        }

        Set<ResourceLocation> dependencies = new HashSet<>();
        for (Selector selector : this.getSelectors()) {
            MultiVariant variant = selector.getVariant();
            // A custom subclass may change getDependencies independently of getVariants.
            if (variant.getClass() != MultiVariant.class) {
                return;
            }
            for (Variant entry : variant.getVariants()) {
                dependencies.add(entry.getModelLocation());
            }
        }
        if (!BOOTOPTIM_LOGGED.get() && BOOTOPTIM_LOGGED.compareAndSet(false, true)) {
            BOOTOPTIM_LOGGER.info("BOOTOPTIM_MULTIPART_DIRECT_DEPENDENCIES status=active");
        }
        cir.setReturnValue(dependencies);
    }
}
