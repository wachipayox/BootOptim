import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

ANALYZER = Path(__file__).resolve().parents[1] / "boot-trace" / "analyze_boot_trace.py"
spec = importlib.util.spec_from_file_location("bootoptim_trace_analyzer", ANALYZER)
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = module
spec.loader.exec_module(module)
summarize = module.summarize


def header(jvm_id="jvm-1", dropped=0):
    return {
        "record": "trace_header", "schema": "bootoptim.boottrace", "schema_version": 1,
        "mode": "profile", "jvm_id": jvm_id, "pid": 12,
        "jvm_start_epoch_ms": 100, "trace_origin_epoch_ms": 200, "trace_origin_mono_ns": 1000,
        "clock_kind": "monotonic", "clock_source": "System.nanoTime", "clock_origin": "trace_init",
        "measurement_origin": "hosted_exact_pack", "endpoint": "main_menu", "development_endpoint": "none",
        "dropped_events": dropped,
    }


def event(seq, kind, mono_ns, task_id=0, parent=0, deps=None, phase=None, cpu_ns=None, jvm_id="jvm-1"):
    value = {"record": "event", "v": 1, "jvm_id": jvm_id, "seq": seq, "type": kind,
             "mono_ns": mono_ns, "thread_id": 7, "thread": "worker"}
    if task_id: value["task_id"] = task_id
    if parent: value["parent_task_id"] = parent
    if deps: value["dependency_ids"] = deps
    if phase: value["phase"] = phase
    if cpu_ns is not None: value["cpu_ns"] = cpu_ns
    return value


def summary(jvm_id="jvm-1", dropped=0):
    return {"record": "trace_summary", "schema_version": 1, "jvm_id": jvm_id,
            "buffered_events": 0, "dropped_events": dropped, "flush_failures": 0,
            "development_sink_failures": 0, "event_counters": {}}


class BootTraceAnalyzerTest(unittest.TestCase):
    def write(self, records):
        temp = tempfile.NamedTemporaryFile("w", suffix=".jsonl", delete=False, encoding="utf-8")
        with temp:
            for record in records:
                temp.write(json.dumps(record) + "\n")
        return Path(temp.name)

    def test_critical_path_unions_overlapping_inclusive_spans(self):
        records = [header(),
            event(0, "task_begin", 0, task_id=3, phase="dependency"),
            event(1, "task_end", 60_000_000, task_id=3, phase="dependency", cpu_ns=10_000_000),
            event(2, "task_begin", 50_000_000, task_id=1, phase="parent"),
            event(3, "task_begin", 60_000_000, task_id=2, parent=1, deps=[3], phase="child"),
            event(4, "task_end", 130_000_000, task_id=2, phase="child", cpu_ns=20_000_000),
            event(5, "task_end", 140_000_000, task_id=1, phase="parent", cpu_ns=30_000_000),
            summary()]
        result = summarize(self.write(records))
        self.assertEqual(220.0, result["inclusive_task_wall_ms"])
        self.assertEqual(130.0, result["critical_path_wall_ms"])
        self.assertEqual([3, 2], result["critical_path_task_ids"])
        self.assertEqual(60.0, result["reported_task_cpu_sum_ms"])

    def test_declared_loss_is_rejected_unless_explicitly_allowed(self):
        path = self.write([header(dropped=1), summary(dropped=1)])
        with self.assertRaisesRegex(ValueError, "lost events"):
            summarize(path)
        self.assertEqual(1, summarize(path, allow_loss=True)["dropped_events"])

    def test_bad_lexical_nesting_is_rejected(self):
        path = self.write([header(), event(0, "task_begin", 0, task_id=1, phase="parent"),
            event(1, "task_begin", 1, task_id=2, parent=99, phase="child"),
            event(2, "task_end", 2, task_id=2, phase="child"),
            event(3, "task_end", 3, task_id=1, phase="parent"), summary()])
        with self.assertRaisesRegex(ValueError, "lexical parent"):
            summarize(path)

    def test_unclosed_barrier_pair_is_rejected(self):
        wait = event(0, "barrier_wait", 10, task_id=1, phase="reload")
        wait["reload_generation"] = 2
        path = self.write([header(), wait, summary()])
        with self.assertRaisesRegex(ValueError, "unterminated paired events"):
            summarize(path)

    def test_jvm_identity_and_clock_origin_are_validated(self):
        bad = header(); bad["trace_origin_epoch_ms"] = 50
        with self.assertRaisesRegex(ValueError, "precedes JVM start"):
            summarize(self.write([bad, summary()]))
        with self.assertRaisesRegex(ValueError, "JVM identity mismatch"):
            summarize(self.write([header(), summary(jvm_id="other")]))


if __name__ == "__main__":
    unittest.main()
