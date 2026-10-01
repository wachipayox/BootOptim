import unittest
from check_ferrite_phase import check


def fixture(candidate_cpu=0, global_candidate_wall=90000000000):
    lines = ['BOOTOPTIM_FERRITE_PHASE stage=initial_complete success=true',
             'BOOTOPTIM_FERRITE_PHASE stage=initial_menu origin=jvm_uptime uptime_ms=1000']
    for i, mask in enumerate((0, 0, 2, 2, 2, 2, 0, 0)):
        lines.append(f'BOOTOPTIM_FERRITE_PHASE stage=requested index={i} mask={mask} measured={str(i%2==1).lower()}')
        cpu = candidate_cpu if mask else 500000000
        lines.append(f'BOOTOPTIM_FERRITE_PHASE stage=complete index={i} mask={mask} measured={str(i%2==1).lower()} success=true '
                     f'growth_cpu_ns={cpu} growth_wall_ns={cpu} growth_calls={1 if mask else 19} '
                     f'clear_cpu_ns=1000 clear_wall_ns=2000 trim_cpu_ns=1000 trim_wall_ns=2000 previous_unique=479905 '
                     f'empty_slots=1 reload_wall_ns={global_candidate_wall if mask else 20000000000} '
                     f'gc_ms=10 heap_used_bytes=4000000000 insertion_cpu_ns={cpu} insertion_wall_ns={cpu} '
                     f'insertions=1000000 pending_expected={479905 if mask else 0}')
    lines.append('BOOTOPTIM_FERRITE_PHASE stage=finished observations=4 controls=2 candidates=2 primers=4')
    lines += ['Reloading ResourceManager:'] * 9
    return '\n'.join(lines)


class PhaseTest(unittest.TestCase):
    def test_explicit_contrast_units_preserve_raw_ns(self):
        r = check(fixture())
        self.assertEqual(r['observations'][0]['growth_cpu_ns'], 500000000)
        d = r['candidate_minus_control'][0]
        self.assertEqual(d['growth_cpu_ms'], -500)
        self.assertEqual(d['reload_wall_ms'], 70000)
        self.assertFalse(any(k.endswith('_ns') for k in d))

    def test_owner_first_unrelated_global_noise_not_veto(self):
        r = check(fixture())
        self.assertTrue(r['valid'])
        self.assertEqual(r['segment_verdict'], 'retain_segment_mechanism')
        self.assertEqual(r['median_owner_cpu_saved_ms'], 500)

    def test_no_saving_retires(self):
        self.assertEqual(check(fixture(candidate_cpu=600000000))['segment_verdict'], 'retire_segment_mechanism')

    def test_invalid_clock(self):
        self.assertFalse(check(fixture().replace('growth_cpu_ns=0', 'growth_cpu_ns=-1'))['valid'])

    def test_missing_trial(self):
        self.assertFalse(check(fixture().replace('stage=requested index=7', 'stage=missing index=7'))['valid'])

    def test_duplicate_process(self):
        self.assertFalse(check(fixture()+'\n'+fixture())['valid'])

    def test_wrong_capacity_or_primer(self):
        self.assertFalse(check(fixture().replace('empty_slots=1', 'empty_slots=1048576'))['valid'])
        self.assertFalse(check(fixture().replace('measured=false', 'measured=true'))['valid'])

    def test_failed_generation(self):
        self.assertFalse(check(fixture().replace('success=true growth', 'success=false growth'))['valid'])

    def test_growth_is_nested_not_summed(self):
        r = check(fixture().replace('growth_cpu_ns=0', 'growth_cpu_ns=100000000'))
        self.assertEqual(r['median_owner_cpu_saved_ms'], 500)

    def test_no_retired_runtime_or_insertion_count_mismatch(self):
        self.assertFalse(check(fixture() + '\nBOOTOPTIM_FERRITE_QUAD_CAPACITY success=true')['valid'])
        self.assertFalse(check(fixture().replace('pending_expected=479905', 'pending_expected=0'))['valid'])
        self.assertFalse(check(fixture().replace('insertions=1000000 pending_expected=479905', 'insertions=900000 pending_expected=479905'))['valid'])

    def test_generation_mismatch(self):
        self.assertFalse(check(fixture()+'\nReloading ResourceManager:')['valid'])

    def test_invalid_numeric(self):
        self.assertFalse(check(fixture().replace('gc_ms=10', 'gc_ms=NaN'))['valid'])

    def test_variable_workload(self):
        self.assertFalse(check(fixture().replace('previous_unique=479905 empty_slots=1 reload_wall_ns=20000000000', 'previous_unique=200000 empty_slots=1 reload_wall_ns=20000000000'))['valid'])


if __name__ == '__main__':
    unittest.main()
