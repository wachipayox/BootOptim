package dev.wachipayox.bootoptim.compat.client;

import dev.wachipayox.bootoptim.profiling.StartupReport;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single grep-able channel for third-party mod compatibility state.
 *
 * <p>Policy (wbombo, 2026-10-14): compatibility code is never pinned to an exact mod version.
 * It falls back to stock only when the code it touches changed, and every fallback is logged
 * at WARN with the {@code [WBOMBO-COMPAT]} prefix so it can be found after updating mods.</p>
 */
public final class CompatLog {
    public static final String PREFIX = "[WBOMBO-COMPAT]";
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/Compat");
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private CompatLog() {}

    /** Logs once per mechanism that it is active, with the actual mod version. */
    public static void active(String mechanism, String modId) {
        if (REPORTED.add("active:" + mechanism)) {
            LOGGER.info("{} ACTIVE project=BootOptim mechanism={} mod={}", PREFIX, mechanism, describe(modId));
            StartupReport.optimization(mechanism, true, "compat_active");
        }
    }

    /** Logs once per mechanism that it fell back to stock behaviour, and why. */
    public static void fallback(String mechanism, String modId, String reason) {
        if (REPORTED.add("fallback:" + mechanism)) {
            LOGGER.warn("{} FALLBACK project=BootOptim mechanism={} mod={} reason={} -> stock behaviour",
                    PREFIX, mechanism, describe(modId), reason);
            StartupReport.optimization(mechanism, false, "compat_fallback " + reason);
        }
    }

    private static String describe(String modId) {
        if (modId == null) {
            return "minecraft";
        }
        try {
            ModList mods = ModList.get();
            if (mods == null) {
                return modId;
            }
            return modId + "@" + mods.getModContainerById(modId)
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("absent");
        } catch (RuntimeException failure) {
            return modId;
        }
    }
}
