package dev.wachipayox.bootoptim.profiling.client;

import java.util.Arrays;

/** Pure monotonic event state used by the post-FancyMenu tail diagnostic. */
public final class PostFancyMenuTailTimeline {
    public enum Event {
        ALL_PREPARATIONS,
        FANCYMENU_TURN_READY,
        PRELOAD_RETURN,
        FANCYMENU_LISTENER_COMPLETE,
        ALL_DONE,
        TITLE_OPEN,
        TITLE_RENDER_RETURN,
        TITLE_PRESENT_RETURN
    }

    private final long[] timestamps = new long[Event.values().length];
    private boolean emissionClaimed;

    public PostFancyMenuTailTimeline() {
        Arrays.fill(timestamps, -1L);
    }

    /** Records the first observation only. Duplicate observations never replace the original timestamp. */
    public synchronized boolean record(Event event, long nanoTime) {
        int index = event.ordinal();
        if (nanoTime < 0L || timestamps[index] >= 0L) {
            return false;
        }
        timestamps[index] = nanoTime;
        return true;
    }

    public synchronized long timestamp(Event event) {
        return timestamps[event.ordinal()];
    }

    public synchronized boolean isComplete() {
        for (long timestamp : timestamps) {
            if (timestamp < 0L) {
                return false;
            }
        }
        return true;
    }

    /**
     * Validates the serial semantic order only. These timestamps are not exclusive work durations
     * and must not be added together as recoverable time.
     */
    public synchronized boolean isMonotonic() {
        long previous = -1L;
        for (Event event : Event.values()) {
            long current = timestamps[event.ordinal()];
            if (current < 0L || (previous >= 0L && current < previous)) {
                return false;
            }
            previous = current;
        }
        return true;
    }

    /** Claims the one allowed aggregate emission for this startup trace. */
    public synchronized boolean claimEmission() {
        if (emissionClaimed) {
            return false;
        }
        emissionClaimed = true;
        return true;
    }
}
