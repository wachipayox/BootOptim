package dev.wachipayox.bootoptim.bootstrap;

import cpw.mods.jarhandling.JarContents;
import cpw.mods.jarhandling.SecureJar;
import net.neoforged.fml.loading.moddiscovery.ModFileParser;
import net.neoforged.fml.loading.moddiscovery.ModJarMetadata;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.IModFileReader;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
import org.jetbrains.annotations.Nullable;

/**
 * High-priority reader for the standard NeoForge metadata format. Non-standard
 * formats are deliberately left to FML/other loaders so BootOptim cannot steal
 * ownership from Connector or compatibility readers.
 */
public final class BootOptimModFileReader implements IModFileReader {
    private static final String MODS_TOML = "META-INF/neoforge.mods.toml";

    @Override
    public @Nullable IModFile read(JarContents contents, ModFileDiscoveryAttributes discoveryAttributes) {
        if (contents.findFile(MODS_TOML).isEmpty()) {
            return null;
        }

        var metadata = new ModJarMetadata(contents);
        var modFile = new CachingModFile(
                SecureJar.from(contents, metadata),
                ModFileParser::modsTomlParser,
                discoveryAttributes.withReader(this));
        metadata.setModFile(modFile);
        return modFile;
    }

    @Override
    public int getPriority() {
        return HIGHEST_SYSTEM_PRIORITY;
    }

    @Override
    public String toString() {
        return "BootOptim cached NeoForge mod reader";
    }
}
