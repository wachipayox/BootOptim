package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.logging.LogUtils;
import dev.wachipayox.bootoptim.optimization.client.OrderedZipPrefixIndex;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

/** Bounded candidate. Unknown subclasses and any index failure retain stock enumeration. */
@Mixin(FilePackResources.class)
abstract class FilePackOrderedPrefixIndexMixin {
    @Unique private static final boolean BOOTOPTIM_INDEX = Boolean.getBoolean("boot_optim.zipPrefixIndex");
    @Unique private static final boolean BOOTOPTIM_VERIFY = Boolean.getBoolean("boot_optim.verifyZipPrefixIndex");
    @Unique private volatile OrderedZipPrefixIndex bootoptim$index;
    @Unique private volatile boolean bootoptim$disabled;
    @Shadow private String addPrefix(String path) { throw new AssertionError(); }

    @WrapOperation(method = "listResources", at = @At(value = "INVOKE", target = "Ljava/util/zip/ZipFile;entries()Ljava/util/Enumeration;"))
    private Enumeration<? extends ZipEntry> bootoptim$entries(ZipFile zip,
            Operation<Enumeration<? extends ZipEntry>> original, PackType type, String namespace,
            String path, PackResources.ResourceOutput output) {
        if (!BOOTOPTIM_INDEX || bootoptim$disabled || ((Object) this).getClass() != FilePackResources.class) return original.call(zip);
        try {
            OrderedZipPrefixIndex index = bootoptim$index;
            if (index == null || !index.owns(zip)) {
                synchronized (this) {
                    index = bootoptim$index;
                    if (index == null || !index.owns(zip)) {
                        index = OrderedZipPrefixIndex.create(zip);
                        bootoptim$index = index;
                        LogUtils.getLogger().info("BOOTOPTIM_ZIP_PREFIX status=ready entries={}", index.size());
                    }
                }
            }
            String prefix = addPrefix(type.getDirectory() + "/" + namespace + "/") + path + "/";
            Enumeration<? extends ZipEntry> selected = index.entries(prefix);
            if (BOOTOPTIM_VERIFY) {
                var expected = new ArrayList<String>();
                var stock = original.call(zip);
                while (stock.hasMoreElements()) {
                    ZipEntry entry = stock.nextElement();
                    if (entry.getName().startsWith(prefix)) expected.add(entry.getName());
                }
                var entries = new ArrayList<ZipEntry>();
                while (selected.hasMoreElements()) entries.add(selected.nextElement());
                if (!expected.equals(entries.stream().map(ZipEntry::getName).toList()))
                    throw new IllegalStateException("ordered prefix mismatch");
                LogUtils.getLogger().info("BOOTOPTIM_ZIP_PREFIX status=verified prefix={} entries={}", prefix, entries.size());
                return Collections.enumeration(entries);
            }
            return selected;
        } catch (RuntimeException exception) {
            bootoptim$disabled = true;
            bootoptim$index = null;
            LogUtils.getLogger().warn("BOOTOPTIM_ZIP_PREFIX status=fallback", exception);
            return original.call(zip);
        }
    }
}
