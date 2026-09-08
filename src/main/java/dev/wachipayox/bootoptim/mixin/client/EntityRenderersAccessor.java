package dev.wachipayox.bootoptim.mixin.client;

import java.util.Map;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EntityRenderers.class)
public interface EntityRenderersAccessor {
    @Accessor("PROVIDERS")
    static Map<EntityType<?>, EntityRendererProvider<?>> bootoptim$getProviders() {
        throw new AssertionError();
    }

    @Accessor("PLAYER_PROVIDERS")
    static Map<PlayerSkin.Model, EntityRendererProvider<?>> bootoptim$getPlayerProviders() {
        throw new AssertionError();
    }
}
