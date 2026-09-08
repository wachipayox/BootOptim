package dev.wachipayox.bootoptim.mixin.client;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.renderer.block.model.BlockElement;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.block.model.ItemOverride;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Experimental allocation-only fast path for the stock BlockModel deserializer.
 *
 * <p>The JSON tree, vanilla/NeoForge deserializers, element/override/material objects and insertion
 * order remain stock. This changes only the initial backing capacity of collections whose final
 * cardinality is already present in the parsed JsonArray/JsonObject. Any unexpected JSON shape
 * fails open to the same zero-capacity collection stock creates; stock validation then remains
 * authoritative.</p>
 */
@Mixin(BlockModel.Deserializer.class)
abstract class BlockModelDeserializerPresizeMixin {
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("boot_optim.blockModelDeserializerPresize", "true"));
    private static final AtomicBoolean MARKED = new AtomicBoolean();

    @Redirect(
            method = "getElements",
            at = @At(value = "INVOKE", target = "Lcom/google/common/collect/Lists;newArrayList()Ljava/util/ArrayList;"),
            require = 0)
    private ArrayList<BlockElement> bootoptim$presizeElements(
            JsonDeserializationContext context,
            JsonObject json) {
        return newList(json, "elements");
    }

    @Redirect(
            method = "getOverrides",
            at = @At(value = "INVOKE", target = "Lcom/google/common/collect/Lists;newArrayList()Ljava/util/ArrayList;"),
            require = 0)
    private ArrayList<ItemOverride> bootoptim$presizeOverrides(
            JsonDeserializationContext context,
            JsonObject json) {
        return newList(json, "overrides");
    }

    @Redirect(
            method = "getTextureMap",
            at = @At(value = "INVOKE", target = "Lcom/google/common/collect/Maps;newHashMap()Ljava/util/HashMap;"),
            require = 0)
    private HashMap<String, Object> bootoptim$presizeTextures(JsonObject json) {
        if (!ENABLED) {
            return new HashMap<>();
        }
        markEnabled();
        try {
            JsonElement value = json.get("textures");
            if (value != null && value.isJsonObject()) {
                int expected = value.getAsJsonObject().size();
                return expected == 0 ? new HashMap<>() : new HashMap<>(hashCapacity(expected));
            }
        } catch (RuntimeException ignored) {
            // Stock parsing/validation below remains authoritative for malformed or unusual shapes.
        }
        return new HashMap<>();
    }

    private static <T> ArrayList<T> newList(JsonObject json, String key) {
        if (!ENABLED) {
            return new ArrayList<>();
        }
        markEnabled();
        try {
            JsonElement value = json.get(key);
            if (value != null && value.isJsonArray()) {
                return new ArrayList<>(value.getAsJsonArray().size());
            }
        } catch (RuntimeException ignored) {
            // Fall open; the original stock access still performs validation and raises its error.
        }
        return new ArrayList<>();
    }

    private static int hashCapacity(int expected) {
        if (expected < 3) {
            return expected + 1;
        }
        if (expected < (1 << 30)) {
            return (int) ((float) expected / 0.75F + 1.0F);
        }
        return Integer.MAX_VALUE;
    }

    private static void markEnabled() {
        if (MARKED.compareAndSet(false, true)) {
            System.out.println("BOOTOPTIM_BLOCKMODEL_DESERIALIZER_PRESIZE status=enabled");
        }
    }
}
