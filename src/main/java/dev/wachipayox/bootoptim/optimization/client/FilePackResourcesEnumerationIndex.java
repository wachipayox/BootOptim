package dev.wachipayox.bootoptim.optimization.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Optional per-open-ZIP snapshot for vanilla FilePackResources enumeration.
 *
 * <p>Vanilla creates a fresh ZipFile enumeration for every namespace/path
 * query. This candidate snapshots the same entries once and returns a fresh
 * enumeration over that immutable, order-preserving list. It deliberately
 * does not change filtering, duplicate handling, resource suppliers or pack
 * precedence. The weak key ties the snapshot lifetime to the open ZipFile.</p>
 */
public final class FilePackResourcesEnumerationIndex {
    public static final String PROPERTY = "boot_optim.filePackResourcesIndex";

    private static final boolean ENABLED = Boolean.getBoolean(PROPERTY);
    private static final Map<ZipFile, List<ZipEntry>> ENTRIES = new WeakHashMap<>();

    private FilePackResourcesEnumerationIndex() {
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static Enumeration<? extends ZipEntry> entries(ZipFile zipFile) {
        if (!ENABLED) {
            return zipFile.entries();
        }

        List<ZipEntry> snapshot;
        synchronized (ENTRIES) {
            snapshot = ENTRIES.get(zipFile);
            if (snapshot == null) {
                ArrayList<ZipEntry> collected = new ArrayList<>();
                Enumeration<? extends ZipEntry> source = zipFile.entries();
                while (source.hasMoreElements()) {
                    collected.add(source.nextElement());
                }
                snapshot = List.copyOf(collected);
                ENTRIES.put(zipFile, snapshot);
            }
        }
        return Collections.enumeration(snapshot);
    }
}
