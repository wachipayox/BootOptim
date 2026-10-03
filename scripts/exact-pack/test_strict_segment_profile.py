import unittest
from validate_strict_segment_profile import validate

GOOD = "BOOTOPTIM_STRICT_SEGMENT status=complete rows=10 calls=100 characters=200 rejected=1 equivalent=true inflight=0 skipped=0 truncated=false c1_cpu_ns=10 b1_cpu_ns=8 b2_cpu_ns=7 c2_cpu_ns=11"


class Accounting(unittest.TestCase):
    def test_valid(self):
        self.assertEqual(validate(GOOD)["paired_cpu_removed_ns"], [2, 4])
        self.assertFalse(validate(GOOD)["ttmm_claim"])

    def test_reject_invalid(self):
        for old, new in [("equivalent=true", "equivalent=false"), ("inflight=0", "inflight=1"),
                         ("skipped=0", "skipped=1"), ("truncated=false", "truncated=true"),
                         ("rows=10", "rows=101"), ("b1_cpu_ns=8", "b1_cpu_ns=0")]:
            with self.subTest(new=new), self.assertRaises(ValueError):
                validate(GOOD.replace(old, new))


if __name__ == "__main__":
    unittest.main()
