"""Offline tests of the evidence gate, not performance measurements."""

import copy
import unittest

from analyze_balanced_trials import analyze


def evidence(cpu_effect=-300, wall_effect=-100, trend=20):
    contract = dict(feature="test", source_sha="a" * 40, jar_sha256="b" * 64,
                    pack_sha256="c" * 64, options_sha256="d" * 64, jvm="Oracle 25.0.4",
                    jvm_args_sha256="e" * 64, owner_cpu_scope="single-worker loadAll",
                    origin="reload_invocation", endpoint="future_completion",
                    minimum_cpu_ms=200, minimum_cpu_fraction=.01,
                    wall_regression_ms=1000, wall_regression_fraction=.02,
                    requires_same_mode_primer=False)
    result = dict(schema=1, contract=contract, campaigns=[])
    for i in range(2):
        steps = [dict(generation=2, kind="warmup", mode="control", success=True, pack_selection_valid=True)]
        for b, (kind, order) in enumerate((("calibration", "ABBA"), ("measurement", "ABBA"),
                                         ("measurement", "BAAB"), ("calibration", "BAAB"))):
            for label in order:
                candidate = kind == "measurement" and label == "B"
                n = len(steps)
                steps.append(dict(generation=n + 2, kind=kind, block=f"block-{b}",
                                  label="candidate" if label == "B" else "control",
                                  mode="candidate" if candidate else "control",
                                  success=True, pack_selection_valid=True,
                                  owner_calls=1, environment_valid=True,
                                  owner_cpu_ms=6000 + trend * n + (cpu_effect if candidate else 0),
                                  reload_wall_ms=28000 + trend * n + (wall_effect if candidate else 0), gc_ms=30))
        result["campaigns"].append(dict(id=f"process-{i}", host_id=f"host-{i}", pid=123,
                                       process_created=f"2026-10-01T00:00:0{i}Z", contract=copy.deepcopy(contract),
                                       complete=True, steps=steps))
    return result


class BalancedTrials(unittest.TestCase):
    def test_effect_survives_linear_drift_and_orders(self):
        result = analyze(evidence(trend=100))
        self.assertTrue(result["valid"])
        self.assertEqual(result["decision"], "support_final_uninstrumented_gate")
        self.assertEqual(result["campaigns"][0]["cpu_delta_range_ms"], [-300, -300])
        self.assertFalse(result["wall_win_supported"])

    def test_linear_warmup_is_not_a_win(self):
        self.assertEqual(analyze(evidence(cpu_effect=0, trend=-100))["decision"], "no_repeatable_cpu_win")

    def test_wall_win_does_not_mask_cpu_regression(self):
        result = analyze(evidence(cpu_effect=142, wall_effect=-1500))
        self.assertEqual(result["decision"], "no_repeatable_cpu_win")
        self.assertTrue(result["wall_win_supported"])

    def test_repeatable_wall_regression_rejects_cpu_improvement(self):
        self.assertEqual(analyze(evidence(wall_effect=2000))["decision"], "reject_repeatable_wall_regression")

    def test_sham_noise_larger_than_effect_blocks_decision(self):
        data = evidence()
        for campaign in data["campaigns"]:
            for row in campaign["steps"]:
                if row["kind"] == "calibration" and row["label"] == "candidate":
                    row["owner_cpu_ms"] -= 600
        result = analyze(data)
        self.assertEqual(result["decision"], "no_repeatable_cpu_win")
        self.assertEqual(result["campaigns"][0]["cpu_noise_bound_ms"], 600)

    def test_one_process_or_no_calibration_is_insufficient(self):
        data = evidence()
        data["campaigns"].pop()
        self.assertEqual(analyze(data)["decision"], "insufficient_design")
        data = evidence()
        for campaign in data["campaigns"]:
            for row in campaign["steps"]:
                if row["kind"] == "calibration":
                    row["kind"] = "warmup"
        self.assertEqual(analyze(data)["decision"], "insufficient_design")

    def test_reject_mismatched_contracts_duplicates_fallback_and_missing_clock(self):
        mutations = [lambda d: d["campaigns"][1]["contract"].update(options_sha256="different"),
                     lambda d: d["campaigns"][0]["steps"][3].update(generation=3),
                     lambda d: d["campaigns"][0]["steps"][3].update(pack_selection_valid=False),
                     lambda d: d["campaigns"][0]["steps"][3].update(owner_cpu_ms=-1),
                     lambda d: d["campaigns"][0]["steps"][3].update(owner_calls=2),
                     lambda d: d["campaigns"][0]["steps"][3].update(mode="candidate"),
                     lambda d: d["campaigns"][0]["steps"][3].update(environment_valid=False),
                     lambda d: d["campaigns"][0].update(complete=False)]
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                data = evidence()
                mutate(data)
                self.assertFalse(analyze(data)["valid"])

    def test_ferrite_requires_a_real_same_mode_primer(self):
        data = evidence()
        data["contract"]["requires_same_mode_primer"] = True
        for campaign in data["campaigns"]:
            campaign["contract"] = copy.deepcopy(data["contract"])
        self.assertFalse(analyze(data)["valid"])
        for campaign in data["campaigns"]:
            steps = []
            for row in campaign["steps"]:
                if row["kind"] in ("measurement", "calibration"):
                    steps.append(dict(kind="conditioning", mode=row["mode"], success=True, pack_selection_valid=True))
                steps.append(row)
            for n, row in enumerate(steps, 2):
                row["generation"] = n
            campaign["steps"] = steps
        self.assertEqual(analyze(data)["decision"], "support_final_uninstrumented_gate")

    def test_opposite_jvm_or_block_results_do_not_pass(self):
        data = evidence()
        for row in data["campaigns"][1]["steps"]:
            if row["kind"] == "measurement" and row["mode"] == "candidate":
                row["owner_cpu_ms"] += 600
        self.assertEqual(analyze(data)["decision"], "no_repeatable_cpu_win")

    def test_legacy_results_cannot_be_promoted_as_new_evidence(self):
        for legacy in ({"valid": True, "features": {"ferrite": {"decision": "candidate_for_final_uninstrumented_gate"}}},
                       {"variant": "candidate", "iteration": 3, "success": True}):
            with self.subTest(legacy=legacy):
                self.assertEqual(analyze(legacy)["decision"], "invalid")


if __name__ == "__main__":
    unittest.main()
