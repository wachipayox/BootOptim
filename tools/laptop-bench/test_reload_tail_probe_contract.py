import pathlib
import re
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
TRACE = ROOT / "src/main/java/dev/wachipayox/bootoptim/profiling/client/ResourceReloadDagTrace.java"
MIXIN = ROOT / "src/main/java/dev/wachipayox/bootoptim/mixin/client/SimpleReloadInstanceDagMixin.java"


class ReloadTailProbeContractTest(unittest.TestCase):
    def test_tail_barrier_calls_stock_once_and_returns_same_future(self):
        source = TRACE.read_text(encoding="utf-8")
        match = re.search(
            r"wrapTailListenerBarrier\(.*?return new PreparableReloadListener\.PreparationBarrier\(\) \{(.*?)\n        \};",
            source,
            re.DOTALL,
        )
        self.assertIsNotNone(match, "tail barrier wrapper not found")
        body = match.group(1)
        self.assertEqual(body.count("original.wait(value)"), 1)
        self.assertIn("CompletableFuture<V> future = original.wait(value);", body)
        self.assertIn("return future;", body)
        self.assertNotIn("thenApply", body)
        self.assertNotIn("thenCompose", body)
        self.assertNotIn("handle(", body)
        self.assertNotIn("exceptionally", body)

    def test_probe_observes_only_listeners_after_model_manager(self):
        source = MIXIN.read_text(encoding="utf-8")
        self.assertIn("boolean tailListener = bootoptim$modelManagerSeen && !modelManager;", source)
        self.assertIn("bootoptim$modelManagerSeen = true;", source)
        self.assertIn("wrapTailListenerBarrier", source)
        self.assertIn("observeTailListenerCompletion", source)
        self.assertNotIn("thenApply", source)
        self.assertNotIn("thenCompose", source)

    def test_probe_does_not_wrap_or_replace_executors(self):
        source = MIXIN.read_text(encoding="utf-8")
        bridge_call = re.search(
            r"SimpleReloadStateFactoryBridge\.create\(factory, actualBarrier, resourceManager,\s*"
            r"listener, prepareExecutor, applyExecutor\)",
            source,
        )
        self.assertIsNotNone(bridge_call)
        self.assertNotIn("new Executor", source)
        self.assertNotIn("ExecutorService", source)


if __name__ == "__main__":
    unittest.main()
