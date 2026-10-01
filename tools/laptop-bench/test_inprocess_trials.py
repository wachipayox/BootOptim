import unittest
from check_inprocess_trials import check, plan


def fixture():
    lines = ['BOOTOPTIM_TRIAL stage=initial_menu origin=jvm_uptime uptime_ms=500000']
    for i, (feature, mask, pair, measured, kind) in enumerate(plan()):
        common = f'index={i} feature={feature} mask={mask} pair={pair} measured={str(measured).lower()}'
        lines += [f'BOOTOPTIM_TRIAL stage=requested {common} kind={kind}',
                  f'BOOTOPTIM_TRIAL stage=complete {common} success=true reload_wall_ns=30000000000 bake_wall_ns=10000000000 bake_cpu_ns={9000000000 if mask else 10000000000} gc_ms=2 heap_used_bytes=3000000000']
    lines += ['BOOTOPTIM_TRIAL stage=finished steps=21 measured=16 origin=reload_invocation endpoint=future_completion']
    lines += ['Reloading ResourceManager: vanilla, file/test'] * 22
    return '\n'.join(lines)


class Trials(unittest.TestCase):
    def test_valid(self):
        result = check(fixture())
        self.assertTrue(result['valid'])
        self.assertEqual(len(plan()), 21)
        self.assertTrue(all(f['decision'] == 'candidate_for_final_uninstrumented_gate' for f in result['features'].values()))

    def test_invalid(self):
        text = fixture()
        for mutated in (text.replace('mask=1', 'mask=4', 1), text.replace('success=true', 'success=false', 1), text.replace('bake_cpu_ns=10000000000', 'bake_cpu_ns=-1', 1), text.replace('kind=conditioning', 'kind=measurement', 1), text.replace('steps=21', 'steps=20'), text + '\nReloading ResourceManager: vanilla', text + '\nBOOTOPTIM_TRIAL stage=finished'):
            with self.subTest(mutated=mutated[-100:]):
                self.assertFalse(check(mutated)['valid'])

    def test_no_practical_gain(self):
        result = check(fixture().replace('bake_cpu_ns=9000000000', 'bake_cpu_ns=10000000000'))
        self.assertTrue(all(f['decision'] == 'no_demonstrated_practical_win' for f in result['features'].values()))


if __name__ == '__main__':
    unittest.main()
