package dev.wachipayox.bootoptim.mixin.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import dev.wachipayox.bootoptim.profiling.client.AtlasDecodeProfiler;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import net.minecraft.util.PngInfo;
import org.lwjgl.stb.STBImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Diagnostic-only decomposition of NativeImage.read while atlas sprite loading is active. */
@Mixin(NativeImage.class)
abstract class NativeImageAtlasDecodeMixin {
    @Inject(
            method = "read(Lcom/mojang/blaze3d/platform/NativeImage$Format;Ljava/io/InputStream;)Lcom/mojang/blaze3d/platform/NativeImage;",
            at = @At("HEAD"),
            require = 0)
    private static void bootoptim$nativeImageStart(
            NativeImage.Format format,
            InputStream input,
            CallbackInfoReturnable<NativeImage> cir) {
        AtlasDecodeProfiler.nativeImageStart();
    }

    @Inject(
            method = "read(Lcom/mojang/blaze3d/platform/NativeImage$Format;Ljava/io/InputStream;)Lcom/mojang/blaze3d/platform/NativeImage;",
            at = @At("RETURN"),
            require = 0)
    private static void bootoptim$nativeImageEnd(
            NativeImage.Format format,
            InputStream input,
            CallbackInfoReturnable<NativeImage> cir) {
        NativeImage image = cir.getReturnValue();
        AtlasDecodeProfiler.nativeImageEnd(
                image == null ? -1 : image.getWidth(),
                image == null ? -1 : image.getHeight());
    }

    @Redirect(
            method = "read(Lcom/mojang/blaze3d/platform/NativeImage$Format;Ljava/io/InputStream;)Lcom/mojang/blaze3d/platform/NativeImage;",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/platform/TextureUtil;readResource(Ljava/io/InputStream;)Ljava/nio/ByteBuffer;"),
            require = 0)
    private static ByteBuffer bootoptim$profileEncodedByteStaging(InputStream input) throws IOException {
        if (!AtlasDecodeProfiler.enabled()) {
            return TextureUtil.readResource(input);
        }
        long wall = AtlasDecodeProfiler.wallNow();
        long cpu = AtlasDecodeProfiler.cpuNow();
        ByteBuffer result = TextureUtil.readResource(input);
        // TextureUtil grows an allocation buffer with a minimum capacity, so ByteBuffer position/capacity is not a
        // trustworthy encoded-file length. AtlasDecodeProfiler's InputStream wrapper records authoritative bytes read.
        AtlasDecodeProfiler.textureRead(
                AtlasDecodeProfiler.elapsed(wall, AtlasDecodeProfiler.wallNow()),
                AtlasDecodeProfiler.elapsedCpu(cpu, AtlasDecodeProfiler.cpuNow()),
                0);
        return result;
    }

    @Redirect(
            method = "read(Lcom/mojang/blaze3d/platform/NativeImage$Format;Ljava/io/InputStream;)Lcom/mojang/blaze3d/platform/NativeImage;",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/platform/NativeImage;read(Lcom/mojang/blaze3d/platform/NativeImage$Format;Ljava/nio/ByteBuffer;)Lcom/mojang/blaze3d/platform/NativeImage;"),
            require = 0)
    private static NativeImage bootoptim$profileByteBufferDecode(NativeImage.Format format, ByteBuffer buffer) throws IOException {
        if (!AtlasDecodeProfiler.enabled()) {
            return NativeImage.read(format, buffer);
        }
        long wall = AtlasDecodeProfiler.wallNow();
        long cpu = AtlasDecodeProfiler.cpuNow();
        try {
            return NativeImage.read(format, buffer);
        } finally {
            AtlasDecodeProfiler.byteBufferDecode(
                    AtlasDecodeProfiler.elapsed(wall, AtlasDecodeProfiler.wallNow()),
                    AtlasDecodeProfiler.elapsedCpu(cpu, AtlasDecodeProfiler.cpuNow()));
        }
    }

    @Redirect(
            method = "read(Lcom/mojang/blaze3d/platform/NativeImage$Format;Ljava/nio/ByteBuffer;)Lcom/mojang/blaze3d/platform/NativeImage;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/PngInfo;validateHeader(Ljava/nio/ByteBuffer;)V"),
            require = 0)
    private static void bootoptim$profilePngHeader(ByteBuffer buffer) throws IOException {
        if (!AtlasDecodeProfiler.enabled()) {
            PngInfo.validateHeader(buffer);
            return;
        }
        long wall = AtlasDecodeProfiler.wallNow();
        long cpu = AtlasDecodeProfiler.cpuNow();
        try {
            PngInfo.validateHeader(buffer);
        } finally {
            AtlasDecodeProfiler.pngHeader(
                    AtlasDecodeProfiler.elapsed(wall, AtlasDecodeProfiler.wallNow()),
                    AtlasDecodeProfiler.elapsedCpu(cpu, AtlasDecodeProfiler.cpuNow()));
        }
    }

    @Redirect(
            method = "read(Lcom/mojang/blaze3d/platform/NativeImage$Format;Ljava/nio/ByteBuffer;)Lcom/mojang/blaze3d/platform/NativeImage;",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/stb/STBImage;stbi_load_from_memory(Ljava/nio/ByteBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;I)Ljava/nio/ByteBuffer;"),
            require = 0)
    private static ByteBuffer bootoptim$profileStbiDecode(
            ByteBuffer buffer,
            IntBuffer x,
            IntBuffer y,
            IntBuffer channels,
            int desiredChannels) {
        if (!AtlasDecodeProfiler.enabled()) {
            return STBImage.stbi_load_from_memory(buffer, x, y, channels, desiredChannels);
        }
        long wall = AtlasDecodeProfiler.wallNow();
        long cpu = AtlasDecodeProfiler.cpuNow();
        try {
            return STBImage.stbi_load_from_memory(buffer, x, y, channels, desiredChannels);
        } finally {
            AtlasDecodeProfiler.stbiDecode(
                    AtlasDecodeProfiler.elapsed(wall, AtlasDecodeProfiler.wallNow()),
                    AtlasDecodeProfiler.elapsedCpu(cpu, AtlasDecodeProfiler.cpuNow()));
        }
    }
}
