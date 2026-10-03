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
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Guarded ordered index. Unknown subclasses and any index failure retain stock enumeration. */
@Mixin(FilePackResources.class)
abstract class FilePackOrderedPrefixIndexMixin {
    @Unique private static final boolean BOOTOPTIM_INDEX = !"false".equalsIgnoreCase(System.getProperty("boot_optim.zipPrefixIndex", "true"));
    @Unique private volatile OrderedZipPrefixIndex bootoptim$index;
    @Unique private volatile boolean bootoptim$disabled;
    @Shadow private String addPrefix(String path) { throw new AssertionError(); }

    @WrapOperation(method = "listResources", at = @At(value = "INVOKE", target = "Ljava/util/zip/ZipFile;entries()Ljava/util/Enumeration;"))
    private Enumeration<? extends ZipEntry> bootoptim$entries(ZipFile zip,
            Operation<Enumeration<? extends ZipEntry>> original, PackType type, String namespace,
            String path, PackResources.ResourceOutput output) {
        if (!BOOTOPTIM_INDEX || bootoptim$disabled || ((Object) this).getClass() != FilePackResources.class || zip.getClass() != ZipFile.class) return original.call(zip);
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
            return selected;
        } catch (RuntimeException exception) {
            bootoptim$disabled = true;
            bootoptim$index = null;
            LogUtils.getLogger().warn("BOOTOPTIM_ZIP_PREFIX status=fallback", exception);
            return original.call(zip);
        }
    }
    @Inject(method = "close", at = @At("RETURN"))
    private void bootoptim$releaseIndex(CallbackInfo callback) { bootoptim$index = null; }

}
