package dev.wachipayox.bootoptim.compat.create;

import com.mojang.logging.LogUtils;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import org.slf4j.Logger;

/**
 * Compatibility gate for the legacy Ponder StitchedSprite cache race exposed during NeoForge parallel mod construction.
 *
 * <p>Ponder 1.0.87+ made this cache concurrent upstream. BootOptim only backports that behavior when Create and
 * Create Railways Navigator are both present and the runtime cache is still the legacy non-concurrent implementation.</p>
 */
public final class CreateCrnStitchedSpriteCompat {
    public static final String CREATE_MOD_ID = "create";
    public static final String CRN_MOD_ID = "createrailwaysnavigator";
    private static final Logger LOGGER = LogUtils.getLogger();

    private CreateCrnStitchedSpriteCompat() {}

    public static boolean shouldPatch(Map<?, ?> cache) {
        if (cache instanceof ConcurrentMap<?, ?>) {
            return false;
        }

        try {
            ModList mods = ModList.get();
            if (mods != null) {
                return shouldPatch(
                        mods.isLoaded(CREATE_MOD_ID),
                        mods.isLoaded(CRN_MOD_ID),
                        false);
            }
        } catch (Throwable ignored) {
            // Fall through to the loading list. StitchedSprite can be linked before ModList is published.
        }

        try {
            var loadingMods = FMLLoader.getCurrent().getLoadingModList();
            return loadingMods != null && shouldPatch(
                    loadingMods.getModFileById(CREATE_MOD_ID) != null,
                    loadingMods.getModFileById(CRN_MOD_ID) != null,
                    false);
        } catch (Throwable ignored) {
            // Compatibility code must fail open outside the normal loading context.
            return false;
        }
    }

    public static void markApplied() {
        LOGGER.info(
                "BOOTOPTIM_COMPAT id=create_crn_stitched_sprite status=applied strategy=ponder_threadsafe_cache_backport");
    }

    static boolean shouldPatch(boolean createLoaded, boolean crnLoaded, boolean alreadyConcurrent) {
        return createLoaded && crnLoaded && !alreadyConcurrent;
    }
}
