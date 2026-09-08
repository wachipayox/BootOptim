package dev.wachipayox.bootoptim.bootstrap;

import dev.wachipayox.bootoptim.trace.StructuredBootTrace;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Trace-only literal boundaries inside the residual between dependency discovery and the
 * transformation-service transformers callback.
 *
 * <p>These are phases rather than tasks: they delimit observable temporal intervals in ModLauncher
 * control flow, but do not claim ownership of the intervening work or contribute to task CPU or
 * critical-path task sums.</p>
 */
final class PostDiscoveryResidualTraceHooks {
    static final String DISCOVERY_TO_COMPLETE_SCAN_PHASE =
            "dependency_discovery_to_bootoptim_complete_scan_callback";
    static final String COMPLETE_SCAN_TO_TRANSFORMERS_PHASE =
            "bootoptim_complete_scan_callback_to_transformers";

    private static final StructuredBootTrace TRACE = StructuredBootTrace.global();
    private static final AtomicBoolean DISCOVERY_PHASE_BEGUN = new AtomicBoolean();
    private static final AtomicBoolean DISCOVERY_PHASE_ENDED = new AtomicBoolean();
    private static final AtomicBoolean TRANSFORMERS_PHASE_BEGUN = new AtomicBoolean();
    private static final AtomicBoolean TRANSFORMERS_PHASE_ENDED = new AtomicBoolean();

    private PostDiscoveryResidualTraceHooks() {}

    static void beginAtDependencyDiscoveryEnd() {
        if (!TRACE.isEnabled() || !DISCOVERY_PHASE_BEGUN.compareAndSet(false, true)) return;
        try {
            TRACE.record(
                    StructuredBootTrace.EventType.PHASE_BEGIN,
                    0L,
                    DISCOVERY_TO_COMPLETE_SCAN_PHASE);
        } catch (Throwable ignored) {
            DISCOVERY_PHASE_BEGUN.set(false);
        }
    }

    static void atCompleteScanCallback() {
        if (!TRACE.isEnabled() || !DISCOVERY_PHASE_BEGUN.get()) return;

        if (DISCOVERY_PHASE_ENDED.compareAndSet(false, true)) {
            try {
                TRACE.record(
                        StructuredBootTrace.EventType.PHASE_END,
                        0L,
                        DISCOVERY_TO_COMPLETE_SCAN_PHASE);
            } catch (Throwable ignored) {
                DISCOVERY_PHASE_ENDED.set(false);
                return;
            }
        }

        if (!TRANSFORMERS_PHASE_BEGUN.compareAndSet(false, true)) return;
        try {
            TRACE.record(
                    StructuredBootTrace.EventType.PHASE_BEGIN,
                    0L,
                    COMPLETE_SCAN_TO_TRANSFORMERS_PHASE);
        } catch (Throwable ignored) {
            TRANSFORMERS_PHASE_BEGUN.set(false);
        }
    }

    static void endAtTransformersCallback() {
        if (!TRACE.isEnabled() || !TRANSFORMERS_PHASE_BEGUN.get()) return;
        if (!TRANSFORMERS_PHASE_ENDED.compareAndSet(false, true)) return;
        try {
            TRACE.record(
                    StructuredBootTrace.EventType.PHASE_END,
                    0L,
                    COMPLETE_SCAN_TO_TRANSFORMERS_PHASE);
        } catch (Throwable ignored) {
            TRANSFORMERS_PHASE_ENDED.set(false);
        }
    }
}
