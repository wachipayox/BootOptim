import copy
import unittest

from check_measurement_result import classify_run, compare_runs


def base_run():
    return {
        "schema_version": 1,
        "run_id": "physical-control-001",
        "purpose": "candidate_control",
        "variant": "control",
        "origin": "physical_laptop",
        "endpoint": "main_menu",
        "clock": {
            "origin": "process",
            "process_start_verified": True,
            "jvm_start_epoch_ms": 1_000_000,
            "first_probe_wall_epoch_ms": 1_001_000,
            "first_probe_uptime_ms": 1_000,
        },
        "identity": {
            "java_pid": 4242,
            "observed_java_pid": 4242,
            "stale_java_detected": False,
            "surviving_previous_java": False,
            "bootoptim_jar_count": 1,
            "effective_jvm_args_verified": True,
            "instance_cfg_verified": True,
        },
        "state": {
            "pack_fingerprint": "pack-a",
            "config_fingerprint": "cfg-a",
            "jvm_fingerprint": "jvm-a",
            "resource_selection_valid": True,
            "cache_state": "session_uncontrolled",
        },
        "markers": {
            "main_menu": 1,
            "main_menu_presented": 0,
            "initial_resource_reload": 1,
        },
        "metrics": {
            "main_menu_ms": 350_330,
            "main_menu_presented_ms": None,
            "process_cpu_ms": 1_053_438,
            "gc_time_ms": 15_421,
            "heap_used_mib": 3_700.0,
            "available_memory_mib": 900.0,
        },
        "instrumentation": {
            "profile": "production",
            "observer_perturbation_known": False,
        },
        "pair": {
            "same_vm": False,
            "id": None,
            "order": None,
        },
    }


class MeasurementResultTests(unittest.TestCase):
    def test_clean_physical_run_is_valid(self):
        self.assertEqual(classify_run(base_run())["status"], "valid")

    def test_stale_jvm_is_invalid(self):
        run = base_run()
        run["identity"]["stale_java_detected"] = True
        result = classify_run(run)
        self.assertEqual(result["status"], "invalid")
        self.assertIn("stale_java_detected", result["invalid_reasons"])

    def test_duplicate_endpoint_marker_is_invalid(self):
        run = base_run()
        run["markers"]["main_menu"] = 2
        self.assertEqual(classify_run(run)["status"], "invalid")

    def test_presented_endpoint_requires_presented_marker_and_metric(self):
        run = base_run()
        run["endpoint"] = "main_menu_presented"
        result = classify_run(run)
        self.assertEqual(result["status"], "invalid")
        self.assertTrue(any(reason.startswith("main_menu_presented_marker_count") for reason in result["invalid_reasons"]))

    def test_known_observer_effect_blocks_absolute_comparison_but_not_phase_attribution(self):
        run = base_run()
        run["instrumentation"]["profile"] = "low_noise_variance"
        run["instrumentation"]["observer_perturbation_known"] = True
        self.assertEqual(classify_run(run)["status"], "inconclusive")

        run["purpose"] = "phase_attribution"
        result = classify_run(run)
        self.assertEqual(result["status"], "valid")
        self.assertTrue(any("observer_perturbation_known" in warning for warning in result["warnings"]))

    def test_host_pressure_marks_hosted_run_inconclusive(self):
        run = base_run()
        run["origin"] = "hosted_exact_pack"
        run["host_pressure"] = {
            "io_wait_p95_pct": 40.0,
            "blocked_processes_max": 2,
            "swap_out_kib_s_max": 0.0,
            "stolen_cpu_max_pct": 0.0,
        }
        result = classify_run(run)
        self.assertEqual(result["status"], "inconclusive")
        self.assertTrue(any("host_iowait" in reason for reason in result["inconclusive_reasons"]))

    def test_comparison_rejects_endpoint_mismatch(self):
        control = base_run()
        candidate = copy.deepcopy(control)
        candidate["run_id"] = "candidate-001"
        candidate["variant"] = "candidate"
        candidate["endpoint"] = "main_menu_presented"
        candidate["markers"]["main_menu_presented"] = 1
        candidate["metrics"]["main_menu_presented_ms"] = 355_000
        result = compare_runs(control, candidate)
        self.assertEqual(result["status"], "invalid")
        self.assertIn("comparison_mismatch:endpoint", result["invalid_reasons"])

    def test_comparison_rejects_pack_mismatch(self):
        control = base_run()
        candidate = copy.deepcopy(control)
        candidate["run_id"] = "candidate-001"
        candidate["variant"] = "candidate"
        candidate["state"]["pack_fingerprint"] = "pack-b"
        result = compare_runs(control, candidate)
        self.assertEqual(result["status"], "invalid")
        self.assertIn("comparison_mismatch:state.pack_fingerprint", result["invalid_reasons"])

    def test_same_vm_pair_requires_id_order_and_paired_cache_label(self):
        control = base_run()
        candidate = copy.deepcopy(control)
        control["origin"] = candidate["origin"] = "hosted_exact_pack"
        control["state"]["cache_state"] = candidate["state"]["cache_state"] = "paired_shared"
        control["pair"] = {"same_vm": True, "id": 1, "order": "control->candidate"}
        candidate["pair"] = {"same_vm": True, "id": 1, "order": "control->candidate"}
        candidate["run_id"] = "candidate-001"
        candidate["variant"] = "candidate"
        candidate["metrics"]["main_menu_ms"] = 345_000
        result = compare_runs(control, candidate)
        self.assertEqual(result["status"], "valid")
        self.assertEqual(result["candidate_minus_control_ms"], -5_330)


if __name__ == "__main__":
    unittest.main()
