import unittest

from test_multi_reload import MultiReloadTests
from test_variance_probe import VarianceProbeParserTests
from variance_probe import (_parse_tagged_lines, MODEL_DEEP_MARKER, MODEL_DEEP_INT_FIELDS,
                            MODEL_DEEP_FLOAT_FIELDS, MODEL_PACK_MARKER, MODEL_PACK_INT_FIELDS,
                            MODEL_PACK_FLOAT_FIELDS, OVERLAY_DEEP_MARKER, OVERLAY_DEEP_INT_FIELDS,
                            OVERLAY_DEEP_FLOAT_FIELDS, summarize)


class DeepReloadTests(unittest.TestCase):
    def fixture(self):
        helper = VarianceProbeParserTests()
        rows, listeners, reload_summaries = MultiReloadTests().fixture()
        for rid in (1, 2, 3):
            for phase in ("model_resource_listing", "model_resource_enqueue", "model_resource_collect",
                          "state_resource_listing", "state_resource_enqueue", "state_resource_collect"):
                rows.append(helper.record(phase, "start", 300 + len(rows), 300 + len(rows),
                                          scope=3000 + len(rows), subject=f"deep_{rid}"))
                start = rows[-1]
                rows.append(helper.record(phase, "end", 300 + len(rows), 300 + len(rows),
                                          scope=start["scope"], subject=f"deep_{rid}"))
        rows.append(helper.record("overlay_reload_done_seen", "point", 800, 800, subject="overlay_1"))
        rows.append(helper.record("overlay_fade_begin_seen", "point", 801, 801, subject="overlay_1"))
        model_deep = [dict(reload_id=rid, result="success", overlap="false", model_keys=2, model_tasks=2,
                           state_keys=1, state_resources=1, state_tasks=1, pack_rows=2)
                      for rid in (1, 2, 3)]
        packs = [dict(reload_id=rid, domain=domain, pack="mod/example", opens=2, parses=2)
                 for rid in (1, 2, 3) for domain in ("model", "state")]
        overlays = [dict(overlay_id=rid, frames=10, done_seen="true", fade_seen="true",
                         gap_ms_max=2.0, game_render_ms_max=3.0, display_ms_max=4.0)
                    for rid in (1, 2)]
        return rows, listeners, reload_summaries, model_deep, packs, overlays

    def test_valid_deep_run(self):
        rows, listeners, reloads, models, packs, overlays = self.fixture()
        report = summarize(rows, listeners=listeners, reload_summaries=reloads, model_deep=models,
                           model_packs=packs, overlay_deep=overlays, profile="multi_reload_deep")
        self.assertTrue(report["valid"], report["invalid_reasons"])

    def test_missing_per_generation_hook_invalid(self):
        rows, listeners, reloads, models, packs, overlays = self.fixture()
        rows = [r for r in rows if not (r["phase"] == "state_resource_collect" and r["subject"] == "deep_3")]
        report = summarize(rows, listeners=listeners, reload_summaries=reloads, model_deep=models,
                           model_packs=packs, overlay_deep=overlays, profile="multi_reload_deep")
        self.assertIn("model_deep_phase_missing:3", report["invalid_reasons"])

    def test_structured_deep_rows_parse_numeric_fields(self):
        rows = _parse_tagged_lines(
            ["[INFO] BOOTOPTIM_MODEL_DEEP reload_id=3 model_keys=44102 model_tasks=44102 "
             "model_task_ms_sum=150000.250 state_keys=11435 result=success overlap=false"],
            MODEL_DEEP_MARKER, MODEL_DEEP_INT_FIELDS, MODEL_DEEP_FLOAT_FIELDS)
        self.assertEqual((rows[0]["model_keys"], rows[0]["model_task_ms_sum"]), (44102, 150000.25))
        packs = _parse_tagged_lines(
            ["[INFO] BOOTOPTIM_MODEL_PACK reload_id=3 domain=model pack=mod/create opens=200 "
             "open_ms_sum=32.500 parse_ms_sum=81.125"],
            MODEL_PACK_MARKER, MODEL_PACK_INT_FIELDS, MODEL_PACK_FLOAT_FIELDS)
        self.assertEqual(packs[0]["parse_ms_sum"], 81.125)
        overlays = _parse_tagged_lines(
            ["[INFO] BOOTOPTIM_OVERLAY_DEEP overlay_id=2 frames=15 done_seen=true fade_seen=true "
             "gap_ms_max=41000.000 game_render_ms_max=120.000"],
            OVERLAY_DEEP_MARKER, OVERLAY_DEEP_INT_FIELDS, OVERLAY_DEEP_FLOAT_FIELDS)
        self.assertEqual(overlays[0]["gap_ms_max"], 41000.0)


if __name__ == "__main__":
    unittest.main()
