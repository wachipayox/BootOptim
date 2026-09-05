package dev.wachipayox.bootoptim.compat.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FancyMenuCooperativeWaitTest {
    @AfterEach
    void clearInterruptStatus() {
        Thread.interrupted();
    }

    @Test
    void completionWinsBeforeFailureAndDeadline() {
        List<String> events = new ArrayList<>();
        FakeSupport support = new FakeSupport(events, 1_000L, 1L);

        FancyMenuCooperativeWait.WaitOutcome outcome = FancyMenuCooperativeWait.waitFor(
                () -> {
                    events.add("completed");
                    return true;
                },
                () -> {
                    events.add("failed");
                    return true;
                },
                0L,
                support);

        assertEquals(List.of("clock", "completed"), events);
        assertEquals(0L, outcome.parkCalls());
    }

    @Test
    void failureWinsBeforeDeadline() {
        List<String> events = new ArrayList<>();
        FakeSupport support = new FakeSupport(events, 1_000L, 1L);

        FancyMenuCooperativeWait.WaitOutcome outcome = FancyMenuCooperativeWait.waitFor(
                () -> {
                    events.add("completed");
                    return false;
                },
                () -> {
                    events.add("failed");
                    return true;
                },
                0L,
                support);

        assertEquals(List.of("clock", "completed", "failed"), events);
        assertEquals(0L, outcome.parkCalls());
    }

    @Test
    void completionAfterParkRechecksCompletionFirst() {
        List<String> events = new ArrayList<>();
        AtomicBoolean completed = new AtomicBoolean();
        FakeSupport support = new FakeSupport(events, 1_000L, 0L);
        support.advanceOnParkMillis = 1L;
        support.onPark = () -> completed.set(true);

        FancyMenuCooperativeWait.WaitOutcome outcome = FancyMenuCooperativeWait.waitFor(
                () -> {
                    events.add("completed");
                    return completed.get();
                },
                () -> {
                    events.add("failed");
                    return false;
                },
                10L,
                support);

        assertEquals(1L, outcome.parkCalls());
        assertEquals(
                List.of("clock", "completed", "failed", "clock", "park", "completed"),
                events);
    }

    @Test
    void failureAfterParkRechecksCompletionThenFailure() {
        List<String> events = new ArrayList<>();
        AtomicBoolean failed = new AtomicBoolean();
        FakeSupport support = new FakeSupport(events, 1_000L, 0L);
        support.advanceOnParkMillis = 1L;
        support.onPark = () -> failed.set(true);

        FancyMenuCooperativeWait.WaitOutcome outcome = FancyMenuCooperativeWait.waitFor(
                () -> {
                    events.add("completed");
                    return false;
                },
                () -> {
                    events.add("failed");
                    return failed.get();
                },
                10L,
                support);

        assertEquals(1L, outcome.parkCalls());
        assertEquals(
                List.of("clock", "completed", "failed", "clock", "park", "completed", "failed"),
                events);
    }

    @Test
    void timeoutUsesStockAbsoluteWallClockDeadline() {
        List<String> events = new ArrayList<>();
        FakeSupport support = new FakeSupport(events, 1_000L, 1L);

        FancyMenuCooperativeWait.WaitOutcome outcome = FancyMenuCooperativeWait.waitFor(
                () -> false,
                () -> false,
                3L,
                support);

        assertEquals(1L, outcome.parkCalls());
        assertTrue(outcome.deadlineSpin());
        assertFalse(outcome.interruptFallback());
        assertEquals(1_004L, support.nowMillis);
    }

    @Test
    void shortDeadlineDoesNotParkPastFinalMillisecond() {
        List<String> events = new ArrayList<>();
        FakeSupport support = new FakeSupport(events, 5_000L, 1L);

        FancyMenuCooperativeWait.WaitOutcome outcome = FancyMenuCooperativeWait.waitFor(
                () -> false,
                () -> false,
                1L,
                support);

        assertEquals(0L, outcome.parkCalls());
        assertFalse(outcome.deadlineSpin());
        assertEquals(5_002L, support.nowMillis);
    }

    @Test
    void preInterruptedThreadKeepsBitAndFallsBackToBoundedSpin() {
        List<String> events = new ArrayList<>();
        FakeSupport support = new FakeSupport(events, 10_000L, 1L);
        Thread.currentThread().interrupt();

        FancyMenuCooperativeWait.WaitOutcome outcome = FancyMenuCooperativeWait.waitFor(
                () -> false,
                () -> false,
                3L,
                support);

        assertTrue(outcome.interruptFallback());
        assertEquals(0L, outcome.parkCalls());
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    void interruptionDuringParkKeepsBitAndStopsParking() {
        List<String> events = new ArrayList<>();
        FakeSupport support = new FakeSupport(events, 20_000L, 1L);
        support.advanceOnParkMillis = 1L;
        support.onPark = () -> Thread.currentThread().interrupt();

        FancyMenuCooperativeWait.WaitOutcome outcome = FancyMenuCooperativeWait.waitFor(
                () -> false,
                () -> false,
                5L,
                support);

        assertTrue(outcome.interruptFallback());
        assertEquals(1L, outcome.parkCalls());
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    void parkFailureFallsBackToSameDeadlineWithoutChangingOutcome() {
        List<String> events = new ArrayList<>();
        FakeSupport support = new FakeSupport(events, 30_000L, 1L);
        support.failNextPark = true;

        FancyMenuCooperativeWait.WaitOutcome outcome = FancyMenuCooperativeWait.waitFor(
                () -> false,
                () -> false,
                4L,
                support);

        assertTrue(outcome.parkFailure());
        assertEquals(1, support.parkAttempts);
        assertFalse(outcome.interruptFallback());
    }

    @Test
    void virtualThreadPathUsesBoundedSpinInsteadOfParking() {
        List<String> events = new ArrayList<>();
        FakeSupport support = new FakeSupport(events, 40_000L, 1L);
        support.virtualThread = true;

        FancyMenuCooperativeWait.WaitOutcome outcome = FancyMenuCooperativeWait.waitFor(
                () -> false,
                () -> false,
                3L,
                support);

        assertTrue(outcome.virtualFallback());
        assertEquals(0L, outcome.parkCalls());
        assertEquals(0, support.parkAttempts);
    }

    @Test
    void overflowedStockDeadlineTimesOutWithoutParking() {
        List<String> events = new ArrayList<>();
        FakeSupport support = new FakeSupport(events, 1_000L, 0L);

        FancyMenuCooperativeWait.WaitOutcome outcome = FancyMenuCooperativeWait.waitFor(
                () -> false,
                () -> false,
                Long.MAX_VALUE,
                support);

        assertEquals(0L, outcome.parkCalls());
        assertEquals(List.of("clock", "clock"), events);
    }

    private static final class FakeSupport implements FancyMenuCooperativeWait.WaitSupport {
        final List<String> events;
        long nowMillis;
        final long advanceOnClockMillis;
        long advanceOnParkMillis;
        int parkAttempts;
        boolean virtualThread;
        boolean failNextPark;
        Runnable onPark = () -> {};

        FakeSupport(List<String> events, long nowMillis, long advanceOnClockMillis) {
            this.events = events;
            this.nowMillis = nowMillis;
            this.advanceOnClockMillis = advanceOnClockMillis;
        }

        @Override
        public long currentTimeMillis() {
            events.add("clock");
            long value = nowMillis;
            nowMillis += advanceOnClockMillis;
            return value;
        }

        @Override
        public boolean isCurrentThreadInterrupted() {
            return Thread.currentThread().isInterrupted();
        }

        @Override
        public boolean isCurrentThreadVirtual() {
            return virtualThread;
        }

        @Override
        public void parkNanos(long nanos) {
            events.add("park");
            parkAttempts++;
            if (failNextPark) {
                failNextPark = false;
                throw new RuntimeException("synthetic park failure");
            }
            nowMillis += advanceOnParkMillis;
            onPark.run();
        }
    }
}
