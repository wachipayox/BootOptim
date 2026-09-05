package dev.wachipayox.bootoptim.profiling.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PostFancyMenuTailTimelineTest {
    @Test
    void acceptsMonotonicSequenceAndKeepsFirstTimestamp() {
        PostFancyMenuTailTimeline timeline = new PostFancyMenuTailTimeline();
        long now = 10L;
        for (PostFancyMenuTailTimeline.Event event : PostFancyMenuTailTimeline.Event.values()) {
            assertTrue(timeline.record(event, now));
            now += 10L;
        }

        assertFalse(timeline.record(PostFancyMenuTailTimeline.Event.TITLE_PRESENT_RETURN, 999L));
        assertEquals(80L, timeline.timestamp(PostFancyMenuTailTimeline.Event.TITLE_PRESENT_RETURN));
        assertTrue(timeline.isComplete());
        assertTrue(timeline.isMonotonic());
    }

    @Test
    void rejectsNonMonotonicSemanticOrder() {
        PostFancyMenuTailTimeline timeline = new PostFancyMenuTailTimeline();
        assertTrue(timeline.record(PostFancyMenuTailTimeline.Event.ALL_PREPARATIONS, 10L));
        assertTrue(timeline.record(PostFancyMenuTailTimeline.Event.FANCYMENU_TURN_READY, 30L));
        assertTrue(timeline.record(PostFancyMenuTailTimeline.Event.PRELOAD_RETURN, 20L));
        assertTrue(timeline.record(PostFancyMenuTailTimeline.Event.FANCYMENU_LISTENER_COMPLETE, 40L));
        assertTrue(timeline.record(PostFancyMenuTailTimeline.Event.ALL_DONE, 50L));
        assertTrue(timeline.record(PostFancyMenuTailTimeline.Event.TITLE_OPEN, 60L));
        assertTrue(timeline.record(PostFancyMenuTailTimeline.Event.TITLE_RENDER_RETURN, 70L));
        assertTrue(timeline.record(PostFancyMenuTailTimeline.Event.TITLE_PRESENT_RETURN, 80L));

        assertTrue(timeline.isComplete());
        assertFalse(timeline.isMonotonic());
    }

    @Test
    void aggregateEmissionCanBeClaimedExactlyOnce() {
        PostFancyMenuTailTimeline timeline = new PostFancyMenuTailTimeline();
        assertTrue(timeline.claimEmission());
        assertFalse(timeline.claimEmission());
        assertFalse(timeline.claimEmission());
    }
}
