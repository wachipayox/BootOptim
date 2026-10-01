import dev.wachipayox.bootoptim.profiling.client.SegmentOwnerMetrics;

/** Diagnostic scope correctness, not optimization-performance evidence. Never packaged. */
public final class OwnerScopeEquivalence {
    public static void main(String[] args) throws Exception {
        SegmentOwnerMetrics.reset();
        SegmentOwnerMetrics.begin(0); SegmentOwnerMetrics.begin(0);
        SegmentOwnerMetrics.work(0, 7, 3);
        SegmentOwnerMetrics.end(0); SegmentOwnerMetrics.end(0);
        var nested = SegmentOwnerMetrics.result(0);
        require(nested.valid() && nested.calls() == 1 && nested.work() == 7 && nested.matching() == 3,
                "Recursive scope double-counted or lost work");
        Thread[] workers = new Thread[4];
        for (int t = 0; t < workers.length; t++) {
            workers[t] = new Thread(() -> {
                for (int i = 0; i < 10; i++) {
                    SegmentOwnerMetrics.begin(1); SegmentOwnerMetrics.work(1, 100, 2); SegmentOwnerMetrics.end(1);
                }
            }); workers[t].start();
        }
        for (Thread worker : workers) worker.join();
        var parallel = SegmentOwnerMetrics.result(1);
        require(parallel.valid() && parallel.calls() == 40 && parallel.work() == 4000 && parallel.matching() == 80,
                "Worker-local scopes lost counts");
        SegmentOwnerMetrics.begin(2);
        require(!SegmentOwnerMetrics.result(2).valid(), "Unfinished owner scope accepted");
        SegmentOwnerMetrics.end(2);
        require(SegmentOwnerMetrics.result(2).valid(), "Finished owner scope invalid");
        SegmentOwnerMetrics.reset();
        require(!SegmentOwnerMetrics.result(0).valid(), "Old generation survived reset");
        SegmentOwnerMetrics.end(0);
        require(!SegmentOwnerMetrics.result(0).valid(), "Unmatched return accepted");
        SegmentOwnerMetrics.reset();
        SegmentOwnerMetrics.begin(0); SegmentOwnerMetrics.end(0);
        require(SegmentOwnerMetrics.result(0).valid(), "New generation inherited stale failure");
        System.out.println("PASS owner_scopes recursive=exclusive workers=40 generation=isolated unmatched=invalid");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
