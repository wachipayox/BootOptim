import unittest
from summarize_startup import parse_repeat_reloads


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


if __name__ == "__main__":
    unittest.main()
