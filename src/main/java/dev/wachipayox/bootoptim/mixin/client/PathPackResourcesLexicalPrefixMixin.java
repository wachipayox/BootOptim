package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.nio.file.Path;
import net.minecraft.server.packs.PathPackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Opt-in reuse of the immutable assets/data lexical prefix for exact UnionPath packs. */
@Mixin(PathPackResources.class)
abstract class PathPackResourcesLexicalPrefixMixin {
    @Unique
    private static final boolean BOOTOPTIM_PREFIX_REUSE = Boolean.parseBoolean(
            System.getProperty("boot_optim.pathPrefixReuse", "false"));

    @Unique
    private static final String BOOTOPTIM_UNION_PATH = "cpw.mods.niofs.union.UnionPath";

    @Unique
    private volatile Path bootoptim$assetsPrefix;

    @Unique
    private volatile Path bootoptim$dataPrefix;

    @WrapOperation(
            method = "getResource(Lnet/minecraft/server/packs/PackType;Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/server/packs/resources/IoSupplier;",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/nio/file/Path;resolve(Ljava/lang/String;)Ljava/nio/file/Path;",
                    ordinal = 0))
    private Path bootoptim$reuseDirectoryPrefix(Path root, String directory, Operation<Path> original) {
        if (!BOOTOPTIM_PREFIX_REUSE
                || ((Object) this).getClass() != PathPackResources.class
                || !BOOTOPTIM_UNION_PATH.equals(root.getClass().getName())) {
            return original.call(root, directory);
        }

        if ("assets".equals(directory)) {
            Path cached = bootoptim$assetsPrefix;
            if (cached == null) {
                synchronized (this) {
                    cached = bootoptim$assetsPrefix;
                    if (cached == null) {
                        cached = original.call(root, directory);
                        bootoptim$assetsPrefix = cached;
                    }
                }
            }
            return cached;
        }

        if ("data".equals(directory)) {
            Path cached = bootoptim$dataPrefix;
            if (cached == null) {
                synchronized (this) {
                    cached = bootoptim$dataPrefix;
                    if (cached == null) {
                        cached = original.call(root, directory);
                        bootoptim$dataPrefix = cached;
                    }
                }
            }
            return cached;
        }

        return original.call(root, directory);
    }
}
