package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.wachipayox.bootoptim.profiling.client.StrictPathSegmentBudget;
import java.util.regex.Pattern;
import net.minecraft.FileUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(FileUtil.class)
abstract class FileUtilStrictPathSegmentMixin {
    @Shadow @Final private static Pattern STRICT_PATH_SEGMENT_CHECK;

    @WrapMethod(method = "isValidStrictPathSegment")
    private static boolean bootoptim$scan(String segment, Operation<Boolean> original) {
        // Census only: always invoke the complete original method/other wrapper chain.
        // The plugin changes solely the adjacent Pattern.matcher().matches() instructions.
        boolean result = original.call(segment);
        if (StrictPathSegmentBudget.ENABLED) StrictPathSegmentBudget.record(STRICT_PATH_SEGMENT_CHECK, segment, result);
        return result;
    }
}
