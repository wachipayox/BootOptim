package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.FilePackResourcesEnumerationIndex;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.server.packs.FilePackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Opt-in, semantics-preserving reuse of a FilePackResources ZIP entry snapshot. */
@Mixin(FilePackResources.class)
abstract class FilePackResourcesEnumerationIndexMixin {
    @Redirect(
            method = {"getNamespaces", "listResources"},
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/zip/ZipFile;entries()Ljava/util/Enumeration;"),
            require = 0)
    private Enumeration<? extends ZipEntry> bootoptim$reuseEntries(ZipFile zipFile) {
        return FilePackResourcesEnumerationIndex.entries(zipFile);
    }
}
