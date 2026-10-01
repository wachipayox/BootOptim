import unittest
from check_owner_trials import check, OWNERS, BITS


def fixture(candidate=750000000, matching=50):
    lines = ['BOOTOPTIM_OWNER_TRIAL stage=initial_complete success=true',
             'BOOTOPTIM_OWNER_TRIAL stage=initial_menu origin=jvm_uptime uptime_ms=1000']
    for index in range(24):
        owner = OWNERS[index // 8]; mask = BITS[index // 8] * (0, 1, 1, 0)[(index // 2) % 4]
        measured = str(bool(index % 2)).lower()
        fields = f'index={index} owner={owner} mask={mask} measured={measured}'
        lines.append('BOOTOPTIM_OWNER_TRIAL stage=requested ' + fields)
        for other in OWNERS:
            cpu = candidate if other == owner and mask else 1000000000
            work = 1000 if other == 'layer' else 0
            match = matching if other == 'layer' else 0
            lines.append(f'BOOTOPTIM_OWNER_TRIAL stage=owner index={index} owner={other} calls=1000 cpu_ns={cpu} wall_ns=2000000000 work={work} matching={match} valid=true')
        lines.append('BOOTOPTIM_OWNER_TRIAL stage=complete ' + fields + ' success=true reload_wall_ns=10000000000 gc_ms=100 heap_used_bytes=1000000')
    lines.append('BOOTOPTIM_OWNER_TRIAL stage=finished observations=12 controls=6 candidates=6 primers=12')
    lines += ['Reloading ResourceManager:'] * 25
    lines += ['mode=substitute_v2 reason=version_3.0.11'] * 4
    lines += ['BOOTOPTIM_MULTIPART_UNION success=true enabled=true'] * 4
    return '\n'.join(lines)


class OwnerTests(unittest.TestCase):
    def test_retain_all(self):
        result = check(fixture()); self.assertTrue(result['valid'])
        for row in result['owners'].values():
            self.assertEqual(row['segment_verdict'], 'retain_segment_mechanism')
            self.assertEqual(len(row['observations']), 4)
            self.assertEqual(row['candidate_minus_control'][0]['cpu_ms'], -250.0)
    def test_retire_all(self):
        result = check(fixture(1250000000)); self.assertTrue(result['valid'])
        self.assertTrue(all(r['segment_verdict'] == 'retire_segment_mechanism' for r in result['owners'].values()))
    def test_no_matching_faces_retires_layer(self):
        result = check(fixture(matching=0)); self.assertTrue(result['valid'])
        self.assertEqual(result['owners']['layer']['segment_verdict'], 'retire_segment_mechanism')
    def test_no_fake_cpu_when_clock_missing(self):
        self.assertFalse(check(fixture().replace('cpu_ns=750000000', 'cpu_ns=-1', 1))['valid'])
    def test_missing_scope(self):
        self.assertFalse(check(fixture().replace('owner=layer calls=1000', 'owner=unknown calls=1000', 1))['valid'])
    def test_changed_workload(self):
        self.assertFalse(check(fixture().replace('index=1 owner=decocraft calls=1000', 'index=1 owner=decocraft calls=2000'))['valid'])
    def test_missing_generation(self):
        self.assertFalse(check(fixture().replace('Reloading ResourceManager:', '', 1))['valid'])
    def test_extra_process(self):
        self.assertFalse(check(fixture() + '\n' + fixture())['valid'])
    def test_invalid_target(self):
        self.assertFalse(check(fixture().replace('valid=true', 'valid=false', 1))['valid'])
    def test_bad_number(self):
        self.assertFalse(check(fixture().replace('cpu_ns=750000000', 'cpu_ns=NaN'))['valid'])
    def test_runtime_fallback(self):
        self.assertFalse(check(fixture() + '\nDirect generated-item bake failed open')['valid'])
    def test_unmatched_activation(self):
        self.assertFalse(check(fixture().replace('mode=substitute_v2', 'mode=disabled', 1))['valid'])


if __name__ == '__main__': unittest.main()
