package dev.wachipayox.bootoptim.bootstrap;

import cpw.mods.jarhandling.SecureJar;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.neoforgespi.language.ModFileScanData;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
import net.neoforged.neoforgespi.locating.ModFileInfoParser;

/** ModFile variant that can reuse immutable ASM scan results across launches. */
public final class CachingModFile extends ModFile {
    public CachingModFile(SecureJar jar, ModFileInfoParser parser, ModFileDiscoveryAttributes attributes) {
        super(jar, parser, attributes);
    }

    @Override
    public ModFileScanData compileContent() {
        if (!ScanCache.isEligible(this)) {
            ScanCache.recordBypass();
            return super.compileContent();
        }

        var cached = ScanCache.read(this);
        if (cached.isPresent()) {
            // Eligible jars contain no signing data, matching SecureJar's unsigned status.
            setSecurityStatus(SecureJar.Status.NONE);
            return cached.get();
        }

        ModFileScanData scanned = super.compileContent();
        ScanCache.write(this, scanned);
        return scanned;
    }
}
