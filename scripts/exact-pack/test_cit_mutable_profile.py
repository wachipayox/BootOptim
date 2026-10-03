import unittest
from validate_cit_mutable_profile import validate

GOOD = "BOOTOPTIM_CIT_MUTABLE_CONTENT calls=20 unique=2 retained_chars=100 inflight=0 truncated=false\n" + "\n".join(
    f"BOOTOPTIM_CIT_MUTABLE_STAGE stage={name} calls={calls} failures=0 nulls=0 samples={samples} cpu_samples={samples} sampled_cpu_ns=3 sampled_wall_ns=4"
    for name, calls, samples in [("first", 10, 1), ("override", 10, 1), ("read", 20, 2), ("parse", 20, 2)])


class Accounting(unittest.TestCase):
    def test_complete(self):
        self.assertTrue(validate(GOOD)["valid"])

    def test_bad_gates(self):
        for old, new in [("inflight=0", "inflight=1"), ("truncated=false", "truncated=true"),
                         ("nulls=0", "nulls=1"), ("cpu_samples=2", "cpu_samples=0"),
                         ("stage=parse calls=20", "stage=parse calls=19")]:
            with self.subTest(new=new), self.assertRaises(ValueError):
                validate(GOOD.replace(old, new))


if __name__ == "__main__":
    unittest.main()
