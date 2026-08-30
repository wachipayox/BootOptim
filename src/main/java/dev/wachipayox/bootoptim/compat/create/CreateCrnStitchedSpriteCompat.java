package dev.wachipayox.bootoptim.compat.create;

import com.mojang.logging.LogUtils;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

/**
 * Compatibility gate for the legacy Ponder StitchedSprite cache race exposed during NeoForge parallel mod construction.
 *
 * <p>Ponder 1.0.87+ made this cache concurrent upstream. BootOptim only backports that behavior when Create and
 * Create Railways Navigator are both loaded and the runtime cache is still the legacy non-concurrent implementation.</p>
 */
public final class CreateCrnStitchedSpriteCompat {
    public static final String CREATE_MOD_ID = "create";
    public static final String CRN_MOD_ID = "createrailwaysnavigator";
    private static final Logger LOGGER = LogUtils.getLogger();

    private CreateCrnStitchedSpriteCompat() {}

    public static boolean shouldPatch(Map<?, ?> cache) {
        try {
            ModList mods = ModList.get();
            return mods != null && shouldPatch(
                    mods.isLoaded(CREATE_MOD_ID),
                    mods.isLoaded(CRN_MOD_ID),
                    cache instanceof ConcurrentMap<?, ?>);
        } catch (Throwable ignored) {
            // Compatibility code must fail open if queried outside the normal mod-construction window.
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
