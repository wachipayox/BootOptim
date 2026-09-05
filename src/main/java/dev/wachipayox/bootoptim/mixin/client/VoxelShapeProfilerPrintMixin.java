package dev.wachipayox.bootoptim.mixin.client;

import java.io.PrintStream;
import java.util.Locale;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Diagnostic-only compatibility shim for Minecraft's LoggedPrintStream.
 *
 * <p>The exact-pack run proved that PrintStream.printf rows from the voxel profiler are not
 * retained in latest.log, while println rows are. Render the same formatted row eagerly and emit
 * it through println so the diagnostic counters survive the client stdout wrapper. This changes
 * only profiler reporting; it does not touch shape construction or results.
 */
@Mixin(targets = "dev.wachipayox.bootoptim.profiling.client.VoxelShapeStartupProfiler", remap = false)
abstract class VoxelShapeProfilerPrintMixin {
    @Redirect(
            method = {"finishAndDump", "transitionTo"},
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/io/PrintStream;printf(Ljava/util/Locale;Ljava/lang/String;[Ljava/lang/Object;)Ljava/io/PrintStream;",
                    remap = false),
            require = 0,
            remap = false)
    private static PrintStream bootoptim$emitPrintfAsLoggedLine(
            PrintStream stream, Locale locale, String format, Object[] arguments) {
        String line = String.format(locale, format, arguments);
        int end = line.length();
        while (end > 0) {
            char last = line.charAt(end - 1);
            if (last != '\n' && last != '\r') {
                break;
            }
            end--;
        }
        stream.println(line.substring(0, end));
        return stream;
    }
}
