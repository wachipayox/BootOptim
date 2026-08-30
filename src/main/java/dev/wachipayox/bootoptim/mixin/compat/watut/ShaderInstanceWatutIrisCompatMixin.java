package dev.wachipayox.bootoptim.mixin.compat.watut;

import com.mojang.blaze3d.vertex.VertexFormat;
import dev.wachipayox.bootoptim.compat.watut.WatutIrisParticleShaderCompat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Wraps the particle ShaderInstance resource provider only when WATUT and Iris are present.
 *
 * <p>The handler must remain static: this injection runs at constructor HEAD before the
 * target instance has completed its {@code this()/super()} constructor invocation.</p>
 */
@Mixin(ShaderInstance.class)
public abstract class ShaderInstanceWatutIrisCompatMixin {
    @ModifyVariable(
            method = "<init>(Lnet/minecraft/server/packs/resources/ResourceProvider;Ljava/lang/String;Lcom/mojang/blaze3d/vertex/VertexFormat;)V",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0,
            require = 0)
    private static ResourceProvider bootoptim$wrapWatutParticleResources(
            ResourceProvider original,
            String shaderName,
            VertexFormat vertexFormat) {
        return WatutIrisParticleShaderCompat.wrapIfNeeded(original, shaderName);
    }
}
