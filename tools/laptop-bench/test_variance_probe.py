import unittest

from variance_probe import parse_line, parse_listener_line, summarize


class VarianceProbeParserTests(unittest.TestCase):
    def test_parse_structured_marker_from_prefixed_log_line(self):
        row = parse_line(
            "[12:00:00] [Render thread/INFO] BOOTOPTIM_VARIANCE seq=7 scope=3 event=end "
            "phase=bake_models subject=- mono_ns=123 wall_epoch_ms=2000 jvm_start_epoch_ms=1000 "
            "uptime_ms=1000 process_cpu_ms=500.000 thread_cpu_ms=100.000 thread_id=1 gc_count=0 "
            "gc_time_ms=0 heap_used_mib=100.000 heap_committed_mib=200.000 heap_max_mib=6000.000 "
            "available_memory_mib=900.000 elapsed_ms=250.000 process_cpu_delta_ms=200.000 "
            "owner_thread_cpu_delta_ms=150.000 gc_count_delta=0 gc_time_delta_ms=0 "
            "heap_used_delta_mib=10.000 available_memory_delta_mib=-20.000"
        )
        self.assertEqual(row["phase"], "bake_models")
        self.assertEqual(row["scope"], 3)
        self.assertEqual(row["elapsed_ms"], 250.0)
        self.assertEqual(row["available_memory_delta_mib"], -20.0)

    def test_parse_deferred_listener_row(self):
        row = parse_listener_line(
            "[INFO] BOOTOPTIM_VARIANCE_LISTENER reload_id=1 index=4 class=x.Y barrier_calls=1 "
            "prepare_done_ms=100.000 apply_turn_ms=250.000 complete_ms=300.000 global_wait_ms=120.000 "
            "order_wait_ms=30.000 post_turn_ms=50.000 turn_result=success result=success"
        )
        self.assertEqual(row["reload_id"], 1)
        self.assertEqual(row["index"], 4)
        self.assertEqual(row["order_wait_ms"], 30.0)

    def record(self, phase, event, mono, uptime, scope=0, process=100.0, **extra):
        row = {
            "seq": mono, "scope": scope, "event": event, "phase": phase, "subject": "-",
            "mono_ns": mono * 1_000_000, "wall_epoch_ms": 1_000_000 + uptime,
            "jvm_start_epoch_ms": 1_000_000, "uptime_ms": uptime, "process_cpu_ms": process,
            "thread_cpu_ms": 10.0, "thread_id": 1, "gc_count": 0, "gc_time_ms": 0,
            "heap_used_mib": 100.0, "heap_committed_mib": 200.0, "heap_max_mib": 6000.0,
            "available_memory_mib": 1000.0, "elapsed_ms": -1.0, "process_cpu_delta_ms": -1.0,
            "owner_thread_cpu_delta_ms": -1.0, "gc_count_delta": -1, "gc_time_delta_ms": -1,
            "heap_used_delta_mib": -1.0, "available_memory_delta_mib": -1.0,
        }
        row.update(extra)
        return row

    def valid_records(self):
        rows = [self.record("transformation_service_construct", "point", 1, 1000)]
        mono, uptime, scope = 2, 1100, 10
        scoped = [
            "root_mod_discovery", "dependency_discovery", "vanilla_bootstrap", "resource_reload", "block_models",
            "block_states", "atlas_schedule_load", "model_bakery_init", "bake_models",
            "load_models", "model_manager_reload", "fancymenu_preload",
        ]
        for phase in scoped:
            rows.append(self.record(phase, "start", mono, uptime, scope=scope, process=100.0 + mono))
            mono += 1; uptime += 100
            if phase == "resource_reload":
                rows.append(self.record("reload_all_preparations", "point", mono, uptime, process=100.0 + mono))
                mono += 1; uptime += 100
            rows.append(self.record(
                phase, "end", mono, uptime, scope=scope, process=100.0 + mono,
                elapsed_ms=100.0, process_cpu_delta_ms=50.0, owner_thread_cpu_delta_ms=25.0,
                gc_count_delta=0, gc_time_delta_ms=0, heap_used_delta_mib=5.0,
                available_memory_delta_mib=-5.0,
            ))
            mono += 1; uptime += 100; scope += 1
        rows.append(self.record("mod_entrypoint", "point", mono, uptime, process=100.0 + mono))
        mono += 1; uptime += 100
        rows.append(self.record("main_menu_opening", "point", mono, uptime, process=100.0 + mono))
        mono += 1; uptime += 100
        rows.append(self.record("main_menu_presented", "point", mono, uptime, process=100.0 + mono))
        return rows

    def test_valid_state_and_scope_cpu_are_separate_from_wall(self):
        summary = summarize(self.valid_records())
        self.assertTrue(summary["valid"], summary["invalid_reasons"])
        bake = next(scope for scope in summary["scopes"] if scope["phase"] == "bake_models")
        self.assertEqual(bake["wall_ms"], 100.0)
        self.assertEqual(bake["process_cpu_ms"], 50.0)
        self.assertEqual(bake["owner_thread_cpu_ms"], 25.0)
        self.assertEqual(bake["avg_process_cores"], 0.5)

    def test_stale_jvm_is_invalidated(self):
        records = self.valid_records()
        records[0]["uptime_ms"] = 120_001
        records[0]["wall_epoch_ms"] = 1_120_001
        summary = summarize(records)
        self.assertFalse(summary["valid"])
        self.assertIn("early_probe_uptime_exceeds_60000ms", summary["invalid_reasons"])

    def test_second_initial_reload_is_invalidated(self):
        records = self.valid_records()
        records.append(self.record("resource_reload", "start", 4, 1300, scope=99))
        summary = summarize(records)
        self.assertFalse(summary["valid"])
        self.assertIn("resource_reload_count_before_menu:2", summary["invalid_reasons"])

    def test_missing_presented_frame_is_invalidated(self):
        records = [row for row in self.valid_records() if row["phase"] != "main_menu_presented"]
        summary = summarize(records)
        self.assertFalse(summary["valid"])
        self.assertIn("missing:main_menu_presented:point", summary["invalid_reasons"])

    def test_analyzed_run_requires_listener_rows(self):
        summary = summarize(self.valid_records(), listeners=[])
        self.assertFalse(summary["valid"])
        self.assertIn("missing_listener_lifecycle_rows", summary["invalid_reasons"])


if __name__ == "__main__":
    unittest.main()
