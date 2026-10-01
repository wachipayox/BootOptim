package dev.wachipayox.bootoptim.optimization.client;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.LoggerFactory;

/** Adapted from the existing Sodium owner replay. Post-menu CPU, never startup timing. */
public final class FerriteHashReplay {
    public static final boolean ENABLED = Boolean.getBoolean("boot_optim.ferriteHashReplay");
    private static final ArrayList<int[]> INPUTS = new ArrayList<>();
    private static final AtomicBoolean INITIAL = new AtomicBoolean();
    private static volatile boolean collecting = true, ready, failed;
    private static long seen, stableSince;
    private static boolean finished;
    private static volatile long sink;
    private FerriteHashReplay() {}

    public static void capture(int[] values) {
        if (!ENABLED || !collecting || values == null || values.length != 32) return;
        synchronized (INPUTS) {
            if (collecting && (seen++ & 2047) == 0 && INPUTS.size() < 4096) INPUTS.add(values.clone());
        }
    }

    public static void observe(ReloadableResourceManager manager, ReloadInstance reload) {
        if (!ENABLED || Minecraft.getInstance() == null
                || Minecraft.getInstance().getResourceManager() != manager || !INITIAL.compareAndSet(false, true)) return;
        NeoForge.EVENT_BUS.addListener(FerriteHashReplay::frame);
        reload.done().whenComplete((ignored, failure) -> {
            synchronized (INPUTS) { collecting = false; }
            failed = failure != null; ready = true;
        });
    }

    private static void frame(ClientTickEvent.Post event) {
        if (finished || !ready) return;
        Minecraft client = Minecraft.getInstance();
        if (client.getOverlay() == null && client.screen != null && client.screen.getClass().getName().equals(
                "com.palm1.analogaudio.client.gui.LavaplayerWelcomeScreen")) { client.screen.onClose(); return; }
        if (client.getOverlay() != null || !(client.screen instanceof TitleScreen)) return;
        if (stableSince == 0) { stableSince = System.nanoTime(); return; }
        if (System.nanoTime() - stableSince < 2_000_000_000L) return;
        finished = true;
        var log = LoggerFactory.getLogger("BootOptim/FerriteHashReplay");
        try {
            if (failed || client.level != null || !FerriteQuadHashTrial.REQUESTED || FerriteQuadHashTrial.VERIFY)
                throw new IllegalStateException("Invalid replay generation/properties");
            int[][] corpus;
            synchronized (INPUTS) { corpus = INPUTS.toArray(int[][]::new); INPUTS.clear(); }
            if (corpus.length < 256) throw new IllegalStateException("Too few sampled pack arrays");
            var method = Class.forName("malte0811.ferritecore.impl.Deduplicator")
                    .getDeclaredMethod("betterIntArrayHash", int[].class);
            method.setAccessible(true);
            MethodHandle actual = MethodHandles.lookup().unreflect(method);
            for (int[] input : corpus) {
                if (!FerriteQuadHashTrial.eligible(input)) throw new IllegalStateException("Inactive target");
                FerriteQuadHashTrial.selectReplay(false);
                int a = (int) actual.invokeExact(input);
                FerriteQuadHashTrial.selectReplay(true);
                int b = (int) actual.invokeExact(input);
                if (a != b) throw new IllegalStateException("Transformed hash mismatch");
            }
            log.info("BOOTOPTIM_FERRITE_HASH_REPLAY stage=corpus seen={} samples={} semantic_equal=true source=stride2048_copied_pack_arrays origin=method_block endpoint=return", seen, corpus.length);
            for (int warm = 0; warm < 20; ++warm) {
                FerriteQuadHashTrial.selectReplay((warm & 1) != 0); run(actual, corpus, 256);
            }
            var clock = ManagementFactory.getThreadMXBean();
            if (!clock.isCurrentThreadCpuTimeSupported()) throw new IllegalStateException("CPU clock missing");
            if (!clock.isThreadCpuTimeEnabled()) clock.setThreadCpuTimeEnabled(true);
            int repetitions = Math.max(1, 8_000_000 / corpus.length);
            Long expected = null;
            for (int index = 0; index < 4; ++index) {
                boolean candidate = index == 1 || index == 2;
                FerriteQuadHashTrial.selectReplay(candidate);
                long cpuStart = clock.getCurrentThreadCpuTime(), wallStart = System.nanoTime();
                long checksum = run(actual, corpus, repetitions);
                long wall = System.nanoTime() - wallStart, cpu = clock.getCurrentThreadCpuTime() - cpuStart;
                if (cpuStart < 0 || cpu < 0 || expected != null && checksum != expected)
                    throw new IllegalStateException("Invalid CPU/checksum");
                expected = checksum;
                log.info("BOOTOPTIM_FERRITE_HASH_REPLAY stage=observation index={} candidate={} calls={} cpu_ns={} wall_ns={} checksum={} gc_total_ms={}",
                        index, candidate, (long) repetitions * corpus.length, cpu, wall, checksum,
                        ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(gc -> Math.max(0, gc.getCollectionTime())).sum());
            }
            log.info("BOOTOPTIM_FERRITE_HASH_REPLAY stage=finished controls=2 candidates=2");
        } catch (Throwable failure) {
            log.error("BOOTOPTIM_FERRITE_HASH_REPLAY stage=invalid", failure);
        } finally {
            FerriteQuadHashTrial.selectReplay(null);
            synchronized (INPUTS) { INPUTS.clear(); }
            client.stop();
        }
    }

    private static long run(MethodHandle actual, int[][] corpus, int repetitions) throws Throwable {
        long sum = 0;
        for (int rep = 0; rep < repetitions; ++rep)
            for (int[] values : corpus) sum = 31 * sum + (int) actual.invokeExact(values);
        sink = sum;
        return sum;
    }
}
