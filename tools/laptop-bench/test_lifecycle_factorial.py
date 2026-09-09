import importlib.util
import sys
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[2] / "scripts/exact-pack/analyze_lifecycle_factorial.py"
SPEC = importlib.util.spec_from_file_location("analyze_lifecycle_factorial", SCRIPT)
ANALYZER = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = ANALYZER
SPEC.loader.exec_module(ANALYZER)


class LifecycleFactorialStructureTest(unittest.TestCase):
    def test_independent_monotone_factorial_is_accepted(self):
        structure = ANALYZER.validate_factorial_structure({
            "full": frozenset({"base", "a", "b", "other"}),
            "remove_a": frozenset({"base", "b", "other"}),
            "remove_b": frozenset({"base", "a", "other"}),
            "remove_ab": frozenset({"base", "other"}),
        })
        self.assertEqual(structure["removed_a_artifacts"], 1)
        self.assertEqual(structure["removed_b_artifacts"], 1)
        self.assertEqual(structure["removed_by_both_artifacts"], 0)

    def test_combined_cell_may_not_readd_artifact(self):
        with self.assertRaisesRegex(SystemExit, "remove_ab re-adds artifact"):
            ANALYZER.validate_factorial_structure({
                "full": frozenset({"base", "a", "b", "other"}),
                "remove_a": frozenset({"base", "b", "other"}),
                "remove_b": frozenset({"base", "a"}),
                "remove_ab": frozenset({"base", "other"}),
            })

    def test_factor_removal_may_not_subsume_other_factor(self):
        with self.assertRaisesRegex(SystemExit, "factor B removal subsumes"):
            ANALYZER.validate_factorial_structure({
                "full": frozenset({"base", "a", "b", "other"}),
                "remove_a": frozenset({"base", "b", "other"}),
                "remove_b": frozenset({"base", "other"}),
                "remove_ab": frozenset({"base", "other"}),
            })


if __name__ == "__main__":
    unittest.main()
