import unittest
from summarize_startup import parse_repeat_reloads, aggregate_repeat_reloads


def valid_lines():
    return [
        "Reloading ResourceManager: vanilla",
        "BOOTOPTIM_REPEAT_RELOAD stage=armed count=2 origin=reload_invocation endpoint=future_completion world=none",
        "BOOTOPTIM_REPEAT_RELOAD stage=begin generation=1",
        "Reloading ResourceManager: vanilla",
        "BOOTOPTIM_REPEAT_RELOAD stage=end generation=1 success=true wall_ns=1500000000",
        "BOOTOPTIM_REPEAT_RELOAD stage=begin generation=2",
        "Reloading ResourceManager: vanilla",
        "BOOTOPTIM_REPEAT_RELOAD stage=end generation=2 success=true wall_ns=1000000000",
        "BOOTOPTIM_REPEAT_RELOAD stage=done requested=2 success=true",
    ]


class RepeatReloadSummaryTest(unittest.TestCase):
    def test_stock_run_remains_distinct(self):
        self.assertIsNone(parse_repeat_reloads(["Reloading ResourceManager: vanilla"]))

    def test_valid_sequence(self):
        result = parse_repeat_reloads(valid_lines())
        self.assertTrue(result["valid"])
        self.assertEqual(result["wall_ms"], [1500, 1000])

    def test_failed_future(self):
        lines = valid_lines()
        lines[4] = lines[4].replace("success=true", "success=false")
        self.assertFalse(parse_repeat_reloads(lines)["valid"])

    def test_duplicate_marker(self):
        lines = valid_lines()
        lines.insert(5, lines[4])
        self.assertFalse(parse_repeat_reloads(lines)["valid"])

    def test_truncated_sequence(self):
        self.assertFalse(parse_repeat_reloads(valid_lines()[:-1])["valid"])

    def test_unexpected_reload(self):
        lines = valid_lines() + ["Reloading ResourceManager: fallback"]
        self.assertFalse(parse_repeat_reloads(lines)["valid"])

    def test_wrong_origin(self):
        lines = valid_lines()
        lines[1] = lines[1].replace("origin=reload_invocation", "origin=process_start")
        self.assertFalse(parse_repeat_reloads(lines)["valid"])

    def test_separate_paired_generations(self):
        rows = []
        for pair, control, candidate in ((1, [1500, 1800], [1200, 1600]), (2, [1700, 2000], [1600, 1900])):
            for variant, walls in (("control", control), ("candidate", candidate)):
                item = parse_repeat_reloads(valid_lines())
                item["wall_ms"] = walls
                rows.append({"variant": variant, "paired_pair": pair, "paired_same_vm": True,
                             "paired_order": "control->candidate" if pair == 1 else "candidate->control",
                             "repeat_reloads": item})
        result = aggregate_repeat_reloads(rows)
        self.assertTrue(result["valid"])
        self.assertEqual(result["variants"]["candidate"]["wall_ms"], [1400, 1750])
        self.assertEqual(result["paired_median_deltas_ms"], [-200, -150])
        self.assertEqual(result["paired"][1]["order"], "candidate->control")

    def test_mixed_repeat_contract_rejected(self):
        rows = [{"variant": "candidate", "repeat_reloads": parse_repeat_reloads(valid_lines())},
                {"variant": "control", "repeat_reloads": None}]
        self.assertFalse(aggregate_repeat_reloads(rows)["valid"])
        rows[1]["repeat_reloads"] = parse_repeat_reloads(valid_lines())
        rows[1]["repeat_reloads"]["world"] = "in_world"
        self.assertFalse(aggregate_repeat_reloads(rows)["valid"])

    def test_duplicate_paired_process_rejected(self):
        row = {"variant": "candidate", "paired_same_vm": True, "paired_pair": 1,
               "repeat_reloads": parse_repeat_reloads(valid_lines())}
        self.assertFalse(aggregate_repeat_reloads([row, row])["valid"])

    def test_ordinary_aggregate_unaffected(self):
        self.assertIsNone(aggregate_repeat_reloads([{"variant": "control"}]))


if __name__ == "__main__":
    unittest.main()
