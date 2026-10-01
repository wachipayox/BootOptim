package dev.wachipayox.bootoptim.profiling.client;

import com.mojang.logging.LogUtils;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Replay real pinned-pack immutable quads through the actual transformed method in both arms. */
public final class SodiumPhaseReplay {
    private record Input(Object quad, Direction face) {}
    private static final List<Input> INPUTS = new ArrayList<>();
    private static final AtomicBoolean INITIAL = new AtomicBoolean();
    private static volatile boolean collecting = true, ready, initialFailed;
    private static boolean finished;
    private static long eligible, stableSince;
    private static volatile long sink;
    private SodiumPhaseReplay() {}

    public static void capture(Object quad, Direction face) {
        if (!TrialFeatureGate.ENABLED || !collecting || quad == null || quad.getClass() != BakedQuad.class || face == null) return;
        // Startup capture is diagnostic, not a timed startup comparison. No extra virtual getter reads.
        synchronized (SodiumPhaseReplay.class) {
            if (collecting && (eligible++ & 2047) == 0 && INPUTS.size() < 4096) INPUTS.add(new Input(quad, face));
        }
    }

    public static void observe(ReloadableResourceManager manager, ReloadInstance reload) {
        if (!TrialFeatureGate.ENABLED || Minecraft.getInstance() == null
                || Minecraft.getInstance().getResourceManager() != manager || !INITIAL.compareAndSet(false, true)) return;
        reload.done().whenComplete((ignored, failure) -> {
            synchronized (SodiumPhaseReplay.class) { collecting = false; }
            initialFailed = failure != null; ready = true;
            LogUtils.getLogger().info("BOOTOPTIM_SODIUM_REPLAY stage=initial_complete success={}", failure == null);
        });
    }

    public static void install() {
        if (TrialFeatureGate.ENABLED) NeoForge.EVENT_BUS.addListener(SodiumPhaseReplay::frame);
    }

    private static void frame(ClientTickEvent.Post ignored) {
        if (finished) return;
        Minecraft client = Minecraft.getInstance();
        if (Boolean.getBoolean("boot_optim.benchmark.expressDeclineOptionalWelcome")
                && client.getOverlay() == null && client.screen != null && client.screen.getClass().getName().equals(
                "com.palm1.analogaudio.client.gui.LavaplayerWelcomeScreen")) { client.screen.onClose(); return; }
        if (!ready || client.getOverlay() != null || !(client.screen instanceof TitleScreen)) return;
        if (stableSince == 0) { stableSince = System.nanoTime(); return; }
        if (System.nanoTime() - stableSince < 2_000_000_000L) return;
        finished = true;
        try {
            if (initialFailed || client.level != null || !Boolean.getBoolean("boot_optim.sodiumAxisQuadFlags"))
                throw new IllegalStateException("Invalid stock initial generation/properties");
            Input[] corpus;
            synchronized (SodiumPhaseReplay.class) { corpus = INPUTS.toArray(Input[]::new); }
            if (corpus.length < 1024) throw new IllegalStateException("Too few actual quads");
            Class<?> type = Class.forName("net.caffeinemc.mods.sodium.client.model.quad.ModelQuadView");
            Class<?> flags = Class.forName("net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFlags");
            MethodHandle actual = MethodHandles.publicLookup().findStatic(flags, "getQuadFlags",
                    MethodType.methodType(int.class, type, Direction.class))
                    .asType(MethodType.methodType(int.class, Object.class, Direction.class));
            for (Input input : corpus) {
                TrialFeatureGate.select(0);
                int a = (int) actual.invokeExact(input.quad(), input.face());
                TrialFeatureGate.select(4);
                int b = (int) actual.invokeExact(input.quad(), input.face());
                if (a != b) throw new IllegalStateException("Actual quad flags differ");
            }
            LogUtils.getLogger().info("BOOTOPTIM_SODIUM_REPLAY stage=corpus eligible={} samples={} semantic_equal=true origin=method_block endpoint=method_block returned_type=flags", eligible, corpus.length);
            // Same original callback/guard/MethodHandle adapter in both arms, warmed outside observations.
            for (int i = 0; i < 20; i++) { TrialFeatureGate.select((i & 1) == 0 ? 0 : 4); run(actual, corpus, 200); }
            var clock = ManagementFactory.getThreadMXBean();
            if (!clock.isCurrentThreadCpuTimeSupported()) throw new IllegalStateException("Missing CPU clock");
            if (!clock.isThreadCpuTimeEnabled()) clock.setThreadCpuTimeEnabled(true);
            int repetitions = Math.max(1, 8_000_000 / corpus.length);
            Long expected = null;
            for (int index = 0; index < 4; index++) {
                int mask = index == 0 || index == 3 ? 0 : 4;
                TrialFeatureGate.select(mask);
                long cpuStart = clock.getCurrentThreadCpuTime(), wallStart = System.nanoTime();
                long checksum = run(actual, corpus, repetitions);
                long wall = System.nanoTime()-wallStart, cpu = clock.getCurrentThreadCpuTime()-cpuStart;
                if (cpuStart < 0 || cpu < 0 || expected != null && checksum != expected)
                    throw new IllegalStateException("Invalid clock or changed flags");
                expected = checksum;
                LogUtils.getLogger().info("BOOTOPTIM_SODIUM_REPLAY stage=observation index={} mask={} calls={} cpu_ns={} wall_ns={} checksum={} gc_total_ms={}",
                        index, mask, (long) corpus.length * repetitions, cpu, wall, checksum,
                        ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> Math.max(0,b.getCollectionTime())).sum());
            }
            TrialFeatureGate.select(0);
            INPUTS.clear();
            LogUtils.getLogger().info("BOOTOPTIM_SODIUM_REPLAY stage=finished controls=2 candidates=2");
        } catch (Throwable failure) {
            LogUtils.getLogger().error("BOOTOPTIM_SODIUM_REPLAY stage=invalid", failure);
        }
        client.stop();
    }

    private static long run(MethodHandle actual, Input[] corpus, int repetitions) throws Throwable {
        long total = 0;
        for (int rep = 0; rep < repetitions; rep++)
            for (Input input : corpus) total += (int) actual.invokeExact(input.quad(), input.face());
        sink = total; return total;
    }
}
