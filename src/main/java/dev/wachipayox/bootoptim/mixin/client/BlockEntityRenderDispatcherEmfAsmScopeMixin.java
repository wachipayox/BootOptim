package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.EmfAsmCompileRepeatProfiler;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Diagnostic-only scope around block-entity renderer reconstruction. */
@Mixin(BlockEntityRenderDispatcher.class)
abstract class BlockEntityRenderDispatcherEmfAsmScopeMixin {
    @Inject(method = "onResourceManagerReload", at = @At("HEAD"), require = 1)
    private void bootoptim$beginEmfAsmScope(ResourceManager resourceManager, CallbackInfo ci) {
        EmfAsmCompileRepeatProfiler.begin(EmfAsmCompileRepeatProfiler.Scope.BLOCK_ENTITY);
    }

    @Inject(method = "onResourceManagerReload", at = @At("RETURN"), require = 1)
    private void bootoptim$endEmfAsmScope(ResourceManager resourceManager, CallbackInfo ci) {
        EmfAsmCompileRepeatProfiler.end(EmfAsmCompileRepeatProfiler.Scope.BLOCK_ENTITY);
    }
}
