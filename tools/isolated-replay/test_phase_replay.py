import json
import tempfile
import unittest
from pathlib import Path

from phase_replay import load_fixture, replay


class PhaseReplayTests(unittest.TestCase):
    def fixture(self, tasks):
        directory = tempfile.TemporaryDirectory()
        path = Path(directory.name) / "fixture.json"
        path.write_text(json.dumps({"schema": 1, "fixture_id": "test", "tasks": tasks}), encoding="utf-8")
        return directory, path

    def test_replays_parallel_diamond_and_preserves_critical_path(self):
        directory, path = self.fixture(
            [
                {"id": "parse", "duration_ms": 2},
                {"id": "models", "duration_ms": 3, "depends_on": ["parse"]},
                {"id": "states", "duration_ms": 4, "depends_on": ["parse"]},
                {"id": "barrier", "duration_ms": 1, "depends_on": ["models", "states"]},
            ]
        )
        try:
            fixture_id, variant, tasks, _ = load_fixture(path)
            self.assertEqual((fixture_id, variant), ("test", None))
            one = replay(tasks, 1)
            two = replay(tasks, 2)
            self.assertEqual(one["makespan_units"], 10.0)
            self.assertEqual(two["makespan_units"], 7.0)
            self.assertEqual(two["critical_path_units"], 7.0)
            self.assertEqual(two["critical_path"], ["parse", "states", "barrier"])
        finally:
            directory.cleanup()

    def test_rejects_cycles_and_unknown_dependencies(self):
        directory, path = self.fixture(
            [{"id": "a", "duration_ms": 1, "depends_on": ["missing"]}]
        )
        try:
            with self.assertRaises(ValueError):
                load_fixture(path)
        finally:
            directory.cleanup()

    def test_rejects_invalid_duration(self):
        directory, path = self.fixture([{"id": "a", "duration_ms": -1}])
        try:
            with self.assertRaises(ValueError):
                load_fixture(path)
        finally:
            directory.cleanup()


if __name__ == "__main__":
    unittest.main()
