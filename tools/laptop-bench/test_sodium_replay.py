import unittest
from check_sodium_replay import check


def fixture(candidate=300000000):
    lines = ['BOOTOPTIM_SODIUM_REPLAY stage=initial_complete success=true',
             'BOOTOPTIM_SODIUM_REPLAY stage=corpus eligible=6280704 samples=3067 semantic_equal=true origin=method_block endpoint=method_block']
    for i, mask in enumerate((0, 4, 4, 0)):
        calls = 3067 * (8_000_000 // 3067)
        cpu = candidate if mask else 400000000
        lines.append(f'BOOTOPTIM_SODIUM_REPLAY stage=observation index={i} mask={mask} calls={calls} cpu_ns={cpu} wall_ns=900000000 checksum=999 gc_total_ms=100')
    lines += ['BOOTOPTIM_SODIUM_REPLAY stage=finished controls=2 candidates=2',
              'Reloading ResourceManager:', 'BOOTOPTIM_SODIUM_AXIS_QUAD_FLAGS status=active']
    return '\n'.join(lines)


class ReplayTest(unittest.TestCase):
    def test_retain(self):
        self.assertEqual(check(fixture())['segment_verdict'], 'retain_segment_mechanism')

    def test_retire(self):
        self.assertEqual(check(fixture(500000000))['segment_verdict'], 'retire_segment_mechanism')

    def test_explicit_contrast_units(self):
        result = check(fixture())
        self.assertEqual(result['observations'][0]['cpu_ns'], 400000000)
        for contrast in result['candidate_minus_control']:
            self.assertEqual(contrast, {'cpu_ms': -100.0, 'wall_ms': 0.0, 'gc_total_ms': 0.0})

    def test_changed_flags(self):
        self.assertFalse(check(fixture().replace('checksum=999', 'checksum=888', 1))['valid'])

    def test_missing_activation(self):
        self.assertFalse(check(fixture().replace('status=active', 'status=stock-fallback'))['valid'])

    def test_wrong_calls(self):
        self.assertFalse(check(fixture().replace(f'calls={3067 * (8_000_000 // 3067)}', 'calls=1'))['valid'])

    def test_bad_clock(self):
        self.assertFalse(check(fixture().replace('cpu_ns=300000000', 'cpu_ns=0'))['valid'])

    def test_duplicate_process(self):
        self.assertFalse(check(fixture()+'\n'+fixture())['valid'])

    def test_reordered_arm(self):
        self.assertFalse(check(fixture().replace('index=2 mask=4', 'index=2 mask=0'))['valid'])

    def test_bad_numeric(self):
        self.assertFalse(check(fixture().replace('wall_ns=900000000', 'wall_ns=NaN'))['valid'])


if __name__ == '__main__':
    unittest.main()
