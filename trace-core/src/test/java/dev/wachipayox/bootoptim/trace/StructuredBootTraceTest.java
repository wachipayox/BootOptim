package dev.wachipayox.bootoptim.trace;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StructuredBootTraceTest {
    @TempDir Path tempDir;

    @Test
    void benchmarkCountsWithoutClockCaptureOrEventBuffering() throws Exception {
        Path output = tempDir.resolve("benchmark.jsonl");
        AtomicInteger clockCalls = new AtomicInteger();
        StructuredBootTrace trace = StructuredBootTrace.createForTest(
                new StructuredBootTrace.Config(StructuredBootTrace.Mode.BENCHMARK, output, 16,
                        "hosted_exact_pack", "main_menu", "none"),
                () -> { clockCalls.incrementAndGet(); return 123L; },
                100L, 1_000L, 500L, 42L, "jvm-test-benchmark");

        long task = trace.beginTask("root_discovery", 0L, null, null, null, -1L);
        trace.endTask(task, "root_discovery", 100L, null);
        assertEquals(1L, trace.count(StructuredBootTrace.EventType.TASK_BEGIN));
        assertEquals(1L, trace.count(StructuredBootTrace.EventType.TASK_END));
        assertEquals(0L, trace.bufferedEventCount());
        assertEquals(0, clockCalls.get());

        trace.close();
        List<String> lines = Files.readAllLines(output);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("\"mode\":\"benchmark\""));
        assertTrue(lines.get(1).contains("\"task_begin\":1"));
        assertTrue(lines.stream().noneMatch(line -> line.contains("\"record\":\"event\"")));
    }

    @Test
    void profileKeepsVersionedJvmAndSeparateClockOrigins() throws Exception {
        Path output = tempDir.resolve("profile.jsonl");
        AtomicLong clock = new AtomicLong(1_000L);
        StructuredBootTrace trace = StructuredBootTrace.createForTest(
                new StructuredBootTrace.Config(StructuredBootTrace.Mode.PROFILE, output, 16,
                        "physical_laptop", "main_menu_presented", "none"),
                () -> clock.addAndGet(100L), 1_000L, 2_000L, 1_500L, 77L, "jvm-fixed");

        long task = trace.beginTask("model_prepare", 0L, new long[] {9L}, "minecraft",
                "minecraft:models/block/stone.json", 3L);
        trace.record(StructuredBootTrace.EventType.BLOCKED_ON, task, 0L, new long[] {9L},
                "model_prepare", -1L, null, null, 3L, "atlas_barrier");
        trace.endTask(task, "model_prepare", 55L, "ok");
        trace.close();

        String text = Files.readString(output);
        assertTrue(text.contains("\"schema\":\"bootoptim.boottrace\""));
        assertTrue(text.contains("\"schema_version\":1"));
        assertTrue(text.contains("\"jvm_id\":\"jvm-fixed\""));
        assertTrue(text.contains("\"jvm_start_epoch_ms\":1500"));
        assertTrue(text.contains("\"trace_origin_epoch_ms\":2000"));
        assertTrue(text.contains("\"trace_origin_mono_ns\":1000"));
        assertTrue(text.contains("\"mono_ns\":100"));
        assertTrue(text.contains("\"dependency_ids\":[9]"));
        assertTrue(text.contains("\"reload_generation\":3"));
        assertTrue(text.contains("\"resource\":\"minecraft:models/block/stone.json\""));
    }

    @Test
    void boundedBufferDeclaresLossInsteadOfOverwriting() throws Exception {
        Path output = tempDir.resolve("loss.jsonl");
        AtomicLong clock = new AtomicLong();
        StructuredBootTrace trace = StructuredBootTrace.createForTest(
                new StructuredBootTrace.Config(StructuredBootTrace.Mode.PROFILE, output, 2,
                        "test", "test", "none"), clock::incrementAndGet,
                0L, 10L, 1L, 1L, "jvm-loss");
        trace.record(StructuredBootTrace.EventType.PHASE_BEGIN, 0L, "a");
        trace.record(StructuredBootTrace.EventType.PHASE_END, 0L, "a");
        trace.record(StructuredBootTrace.EventType.ERROR, 0L, "a");
        assertEquals(1L, trace.droppedEventCount());
        trace.close();
        String text = Files.readString(output);
        assertTrue(text.contains("\"dropped_events\":1"));
        assertTrue(text.contains("\"buffered_events\":2"));
    }

    @Test
    void developmentSinkReplaysThenStreamsAndFailsOpen() {
        AtomicLong clock = new AtomicLong();
        StructuredBootTrace trace = StructuredBootTrace.createForTest(
                new StructuredBootTrace.Config(StructuredBootTrace.Mode.DEVELOPMENT, null, 8,
                        "dev", "menu", "pipe:future"), clock::incrementAndGet,
                0L, 10L, 1L, 2L, "jvm-dev");
        trace.record(StructuredBootTrace.EventType.PHASE_BEGIN, 0L, "boot");
        List<String> lines = new ArrayList<>();
        assertTrue(trace.installDevelopmentSink(lines::add));
        trace.record(StructuredBootTrace.EventType.PHASE_END, 0L, "boot");
        trace.close();
        assertEquals(4, lines.size());
        assertTrue(lines.get(0).contains("trace_header"));
        assertTrue(lines.get(1).contains("phase_begin"));
        assertTrue(lines.get(2).contains("phase_end"));
        assertTrue(lines.get(3).contains("trace_summary"));

        StructuredBootTrace failing = StructuredBootTrace.createForTest(
                new StructuredBootTrace.Config(StructuredBootTrace.Mode.DEVELOPMENT, null, 8,
                        "dev", "menu", "pipe:future"), clock::incrementAndGet,
                0L, 10L, 1L, 3L, "jvm-fail-open");
        assertFalse(failing.installDevelopmentSink(line -> { throw new IllegalStateException("boom"); }));
        assertDoesNotThrow(() -> failing.record(StructuredBootTrace.EventType.ERROR, 0L, "after_sink_failure"));
        failing.close();
    }

    @Test
    void shutdownHookFlushesFromARealJvm() throws Exception {
        Path output = tempDir.resolve("shutdown.jsonl");
        String javaName = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", javaName);
        Process process = new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
                StructuredBootTraceShutdownProbe.class.getName(), output.toString())
                .redirectErrorStream(true).start();
        String console = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), console);
        String text = Files.readString(output);
        assertTrue(text.contains("\"record\":\"trace_header\""));
        assertTrue(text.contains("\"type\":\"phase_begin\""));
        assertTrue(text.contains("\"record\":\"trace_summary\""));
        assertTrue(text.contains("\"clock_origin\":\"trace_init\""));
    }
}
