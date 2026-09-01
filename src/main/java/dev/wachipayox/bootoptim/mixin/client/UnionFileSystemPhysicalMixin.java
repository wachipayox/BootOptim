package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.profiling.client.ResourceOpenPhysicalProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;

/**
 * Diagnostic-only SecureJar UnionFS boundary timing. The target is optional and
 * referenced by name so BootOptim keeps no hard SecureJar API dependency.
 */
@Pseudo
@Mixin(targets = "cpw.mods.niofs.union.UnionFileSystem", remap = false)
abstract class UnionFileSystemPhysicalMixin {
    @Unique private static final ThreadLocal<Long> bootoptim$channelStart = new ThreadLocal<>();
    @Unique private static final ThreadLocal<Long> bootoptim$dirStart = new ThreadLocal<>();

    @Inject(method = "newReadByteChannel", at = @At("HEAD"), require = 0)
    private void bootoptim$startChannel(@Coerce Object path, CallbackInfoReturnable<SeekableByteChannel> cir) {
        long started = ResourceOpenPhysicalProfiler.beginUnionStage();
        if (started != 0L) bootoptim$channelStart.set(started);
    }

    @Inject(method = "newReadByteChannel", at = @At("RETURN"), require = 0)
    private void bootoptim$endChannel(@Coerce Object path, CallbackInfoReturnable<SeekableByteChannel> cir) {
        Long started = bootoptim$channelStart.get();
        bootoptim$channelStart.remove();
        ResourceOpenPhysicalProfiler.endUnionStage("unionfs.open_channel", started == null ? 0L : started);
    }

    @Inject(method = "newDirStream", at = @At("HEAD"), require = 0)
    private void bootoptim$startDir(@Coerce Object path, DirectoryStream.Filter<? super Path> filter,
                                    CallbackInfoReturnable<DirectoryStream<Path>> cir) {
        long started = ResourceOpenPhysicalProfiler.beginUnionStage();
        if (started != 0L) bootoptim$dirStart.set(started);
    }

    @Inject(method = "newDirStream", at = @At("RETURN"), require = 0)
    private void bootoptim$endDir(@Coerce Object path, DirectoryStream.Filter<? super Path> filter,
                                  CallbackInfoReturnable<DirectoryStream<Path>> cir) {
        Long started = bootoptim$dirStart.get();
        bootoptim$dirStart.remove();
        ResourceOpenPhysicalProfiler.endUnionStage("unionfs.open_directory_stream", started == null ? 0L : started);
    }
}
