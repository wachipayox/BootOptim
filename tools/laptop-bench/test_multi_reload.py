import tempfile
import unittest
from pathlib import Path

from check_multi_reload_selection import check
from test_variance_probe import VarianceProbeParserTests
from variance_probe import MULTI_RELOAD_REQUIRED, parse_reload_line, summarize


class MultiReloadTests(unittest.TestCase):
    def fixture(self):
        helper = VarianceProbeParserTests()
        rows = helper.valid_records()
        rows = [row for row in rows if row["phase"] != "fancymenu_preload"]
        rows = [row for row in rows if row["phase"] != "resource_reload"]
        for phase, event in sorted(MULTI_RELOAD_REQUIRED - {(r["phase"], r["event"]) for r in rows}):
            if phase not in {"resource_reload", "manual_resource_pack_reload", "reload_frame_presented", "loading_overlay_exit_call"}:
                rows.append(helper.record(phase, event, 40 + len(rows), 2000 + len(rows)))
        for rid, base in ((1, 5), (2, 100), (3, 200)):
            scope = 300 + rid
            if rid > 1:
                rows.append(helper.record("manual_resource_pack_reload", "start", base - 2, base - 2, scope=400 + rid))
            rows.append(helper.record("resource_reload", "start", base, base, scope=scope, subject=f"reload_{rid}"))
            rows.append(helper.record("resource_reload", "end", base + 10, base + 10, scope=scope,
                                      subject=f"reload_{rid}_listeners_1_result_success"))
            if rid > 1:
                rows.append(helper.record("manual_resource_pack_reload", "end", base + 11, base + 11,
                                          scope=400 + rid, subject="result_success_entries_-1"))
                rows.append(helper.record("loading_overlay_exit_call", "point", base + 12, base + 12))
            rows.append(helper.record("reload_frame_presented", "point", base + 13, base + 13,
                                      subject=f"reload_{rid}_screen_TitleScreen"))
        listeners = [dict(reload_id=rid, index=0, barrier_calls=1, result="success", turn_result="success")
                     for rid in (1, 2, 3)]
        summaries = [dict(reload_id=rid, expected_listeners=1, observed_listeners=1, result="success")
                     for rid in (1, 2, 3)]
        return rows, listeners, summaries

    def test_valid_three_generation_run(self):
        rows, listeners, summaries = self.fixture()
        report = summarize(rows, listeners=listeners, reload_summaries=summaries, profile="multi_reload")
        self.assertTrue(report["valid"], report["invalid_reasons"])

    def test_missing_frame_and_failed_listener_rejected(self):
        rows, listeners, summaries = self.fixture()
        rows = [r for r in rows if not (r["phase"] == "reload_frame_presented" and "reload_3_" in r["subject"])]
        listeners[1]["result"] = "failed"
        report = summarize(rows, listeners=listeners, reload_summaries=summaries, profile="multi_reload")
        self.assertIn("reload_start_end_frame_ids_differ", report["invalid_reasons"])
        self.assertIn("listener_lifecycle_invalid:2", report["invalid_reasons"])

    def test_reload_summary_parser(self):
        row = parse_reload_line("[INFO] BOOTOPTIM_VARIANCE_RELOAD reload_id=2 expected_listeners=70 "
                                "observed_listeners=70 all_preparations_ms=300.123 all_done_ms=400.456 result=success")
        self.assertEqual((row["reload_id"], row["all_done_ms"]), (2, 400.456))

    def test_selection_must_change_then_restore(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            before, after, log = (root / name for name in ("before", "after", "latest.log"))
            options = 'resourcePacks:["vanilla","file/A.zip","file/B.zip"]\nincompatibleResourcePacks:[]\n'
            before.write_text(options)
            after.write_text(options)
            log.write_text("\n".join("Reloading ResourceManager: " + packs for packs in
                                     ("vanilla, file/A.zip, file/B.zip", "vanilla, file/B.zip",
                                      "vanilla, file/A.zip, file/B.zip")))
            variance = {"valid": True, "profile": "multi_reload",
                        "reload_summaries": [{"reload_id": n} for n in (1, 2, 3)]}
            self.assertTrue(check(before, after, log, variance)["valid"])
            log.write_text("Reloading ResourceManager: vanilla, file/A.zip, file/B.zip\n" * 3)
            self.assertIn("no_changed_intermediate_effective_reload",
                          check(before, after, log, variance)["issues"])


if __name__ == "__main__":
    unittest.main()
