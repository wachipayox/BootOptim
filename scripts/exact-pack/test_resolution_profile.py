import unittest
from validate_resolution_profile import validate

GOOD = """BOOTOPTIM_RESOLUTION status=snapshot calls=2 roots=2 unique=1 repeats=1 hits=1 failures=0 sampled_roots=1 cpu_samples=1 sampled_cpu_ns=9 sampled_wall_ns=10 sampled_provider_cpu_ns=7 inflight=0 truncated=false
BOOTOPTIM_RESOLUTION_PROVIDER class=example.Pack calls=3 hits=1 failures=0 samples=2 cpu_samples=2 sampled_cpu_ns=7 sampled_wall_ns=8
BOOTOPTIM_RESOLUTION_FAMILY family=minecraft:textures/point calls=2 repeats=1 hits=1 probes=3 samples=1 cpu_samples=1 sampled_cpu_ns=9 sampled_wall_ns=10 first_cpu_ns=0 repeat_cpu_ns=9
BOOTOPTIM_RESOLUTION_QUERY manager=abc id=minecraft:textures/a.png mode=point calls=2 hits=1 probes=3 samples=1 cpu_samples=1 sampled_cpu_ns=9
"""


class ResolutionAccountingTest(unittest.TestCase):
    def test_valid_accounting(self):
        self.assertTrue(validate(GOOD)["valid"])

    def test_reject_incomplete_or_inconsistent_evidence(self):
        for old, new in [("inflight=0", "inflight=1"), ("truncated=false", "truncated=true"),
                         ("unique=1", "unique=2"), ("probes=3", "probes=4"),
                         ("repeat_cpu_ns=9", "repeat_cpu_ns=8"),
                         ("cpu_samples=1", "cpu_samples=0")]:
            with self.subTest(new=new), self.assertRaises(ValueError):
                validate(GOOD.replace(old, new))

    def test_missing_details_are_not_an_empty_front(self):
        with self.assertRaises(ValueError):
            validate("\n".join(GOOD.splitlines()[:2]))


if __name__ == "__main__":
    unittest.main()
