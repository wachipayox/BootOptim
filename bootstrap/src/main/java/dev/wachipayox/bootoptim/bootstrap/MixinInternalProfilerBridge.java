package dev.wachipayox.bootoptim.bootstrap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.spongepowered.asm.util.perf.Profiler;

/** Diagnostic bridge to Sponge Mixin's built-in profiler. */
final class MixinInternalProfilerBridge {
    private static final String PROPERTY = "mixin.debug.profiler";
    private static final int TOP_SECTIONS = 100;
    private static final AtomicBoolean ENABLED = new AtomicBoolean();

    private MixinInternalProfilerBridge() {
    }

    static void enable() {
        if (!ENABLED.compareAndSet(false, true)) {
            return;
        }

        System.setProperty(PROPERTY, "true");
        Runtime.getRuntime().addShutdownHook(new Thread(
                MixinInternalProfilerBridge::report,
                "BootOptim Mixin Internal Profile Reporter"));
        emit("status=enabled property=" + PROPERTY);
    }

    private static void report() {
        try {
            Profiler profiler = Profiler.getProfiler("mixin");
            List<Profiler.Section> sections = new ArrayList<>(profiler.getSections());
            sections.sort(Comparator.comparingLong(Profiler.Section::getTotalTime).reversed());

            emitNamed(profiler, "mixin");
            emitNamed(profiler, "mixin.prepare");
            emitNamed(profiler, "mixin.read");
            emitNamed(profiler, "mixin.apply");
            emitNamed(profiler, "mixin.write");
            emitNamed(profiler, "class.load");
            emitNamed(profiler, "class.transform");
            emitNamed(profiler, "mixin.plugin");

            int rank = 0;
            for (Profiler.Section section : sections) {
                long totalMs = section.getTotalTime();
                if (totalMs <= 0L) {
                    continue;
                }
                rank++;
                if (rank > TOP_SECTIONS) {
                    break;
                }
                emit(String.format(
                        Locale.ROOT,
                        "dimension=section rank=%d name=%s total_ms=%d count=%d avg_ms=%.3f root=%s fine=%s info=%s",
                        rank,
                        sanitize(section.getName()),
                        totalMs,
                        section.getTotalCount(),
                        section.getTotalAverageTime(),
                        section.isRoot(),
                        section.isFine(),
                        sanitize(section.getInfo())));
            }
        } catch (Throwable t) {
            StartupDiagnostics.failure("mixin_internal_profiler", t);
            System.out.println("BOOTOPTIM_MIXIN_INTERNAL_PROFILE status=failed type=" + t.getClass().getName());
        }
    }

    private static void emitNamed(Profiler profiler, String name) {
        Profiler.Section section = profiler.get(name);
        emit(String.format(
                Locale.ROOT,
                "dimension=summary name=%s total_ms=%d count=%d avg_ms=%.3f",
                sanitize(name),
                section.getTotalTime(),
                section.getTotalCount(),
                section.getTotalAverageTime()));
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "<null>";
        }
        return value.replace(' ', '_').replace('\t', '_').replace('\r', '_').replace('\n', '_');
    }

    private static void emit(String payload) {
        System.out.println("BOOTOPTIM_MIXIN_INTERNAL_PROFILE " + payload);
        StartupDiagnostics.event("BOOTOPTIM_MIXIN_INTERNAL_PROFILE", payload);
    }
}
