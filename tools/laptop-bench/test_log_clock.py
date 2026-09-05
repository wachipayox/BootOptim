"""The Windows laptop uses Sept (four letters), unlike hosted Sep logs."""
import importlib.util
from pathlib import Path
import unittest

script = Path(__file__).resolve().parents[2] / 'scripts/exact-pack/summarize_startup.py'
spec = importlib.util.spec_from_file_location('startup_summary', script)
summary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(summary)


class LogClockTests(unittest.TestCase):
    def test_hosted_clock(self):
        self.assertEqual(summary.parse_clock_ms('[12:05:49.820] [Render thread]'), 43549820)

    def test_english_date(self):
        self.assertEqual(summary.parse_clock_ms('[05Sep2026 12:05:49.820] message'), 43549820)

    def test_laptop_four_letter_month(self):
        self.assertEqual(summary.parse_clock_ms('[05Sept2026 12:05:49.820] message'), 43549820)

    def test_localized_month(self):
        self.assertEqual(summary.parse_clock_ms('[05févr.2026 12:05:49.820] message'), 43549820)

    def test_no_millisecond_and_midnight(self):
        self.assertEqual(summary.delta_ms(summary.parse_clock_ms('[23:59:59] message'),
                                         summary.parse_clock_ms('[00:00:01] message')), 2000)

    def test_missing_clock_is_not_zero(self):
        self.assertIsNone(summary.parse_clock_ms('no clock'))
        self.assertIsNone(summary.delta_ms(None, 123))


if __name__ == '__main__':
    unittest.main()
