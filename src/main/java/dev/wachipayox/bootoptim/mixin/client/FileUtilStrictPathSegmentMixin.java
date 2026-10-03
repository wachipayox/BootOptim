package dev.wachipayox.bootoptim.mixin.client;

import net.minecraft.FileUtil;
import org.spongepowered.asm.mixin.Mixin;

/** Target registration only; the final extension preserves all existing method code. */
@Mixin(FileUtil.class)
abstract class FileUtilStrictPathSegmentMixin {}
