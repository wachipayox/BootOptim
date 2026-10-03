package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.wachipayox.bootoptim.optimization.StrictPathSegmentValidator;
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
        boolean result = StrictPathSegmentValidator.ENABLED && segment != null
                && StrictPathSegmentValidator.compatible(STRICT_PATH_SEGMENT_CHECK)
                ? StrictPathSegmentValidator.matches(segment) : original.call(segment);
        if (StrictPathSegmentBudget.ENABLED) StrictPathSegmentBudget.record(STRICT_PATH_SEGMENT_CHECK, segment, result);
        return result;
    }
}
