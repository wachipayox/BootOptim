import unittest
from check_decocraft_json_trial import check, check_semantic

def fixture():
    lines = ['BOOTOPTIM_JSON_TRIAL stage=initial_complete success=true',
             'BOOTOPTIM_JSON_TRIAL stage=initial_menu origin=jvm_uptime uptime_ms=90000']
    for generation in range(1, 10):
        index = generation-2
        candidate = 'true' if index in (2, 3, 4, 5) else 'false'
        common = ('tasks=10809 task_cpu_ns=500 task_wall_ns=1000 opens=10809 open_cpu_ns=200 '
                  'open_wall_ns=400 batches='+('1' if index == 2 else '0')+' batch_cpu_ns=0 batch_wall_ns=0')
        lines += [f'BOOTOPTIM_JSON_PREREQUISITE generation={generation} phase={phase} success=true wall_ns=1000'
                  for phase in ('models', 'states')]
        lines.append(f'BOOTOPTIM_JSON_OWNER generation={generation} candidate={candidate} success=true cpu_valid=true {common}')
        if index >= 0:
            measured = 'true' if index % 2 else 'false'
            lines.append(f'BOOTOPTIM_JSON_TRIAL stage=requested index={index} candidate={candidate} measured={measured}')
            lines.append(f'BOOTOPTIM_JSON_TRIAL stage=complete index={index} candidate={candidate} measured={measured} '
                         f'success=true {common} hits='+('10809' if candidate == 'true' else '0')+
                         ' retained_bytes='+('3129313' if candidate == 'true' else '0')+
                         ' reload_wall_ns=2000 gc_ms=0 heap_used_bytes=1000')
    lines.append('BOOTOPTIM_JSON_TRIAL stage=finished observations=4 controls=2 candidates=2 primers=4')
    return '\n'.join(lines)

class ValidityTests(unittest.TestCase):
    def test_valid(self):
        result = check(fixture())
        self.assertEqual(len(result['observations']), 4)
        self.assertEqual(result['candidate_minus_control'][0]['task_cpu_ns'], 0)
    def test_partial(self):
        with self.assertRaises(ValueError): check(fixture().split('BOOTOPTIM_JSON_TRIAL stage=finished')[0])
    def test_cpu_unavailable(self):
        with self.assertRaises(ValueError): check(fixture().replace('cpu_valid=true', 'cpu_valid=false', 1))
    def test_changed_workload(self):
        with self.assertRaises(ValueError): check(fixture().replace('tasks=10809', 'tasks=10808', 1))
    def test_reversed_mode(self):
        with self.assertRaises(ValueError): check(fixture().replace('index=2 candidate=true', 'index=2 candidate=false'))
    def test_duplicate_endpoint(self):
        with self.assertRaises(ValueError): check(fixture()+'\nBOOTOPTIM_JSON_TRIAL stage=complete index=7')
    def test_missing_future(self):
        with self.assertRaises(ValueError): check(fixture().replace('phase=models', 'phase=unknown', 1))
    def test_leaked_control_storage(self):
        with self.assertRaises(ValueError): check(fixture().replace('retained_bytes=0', 'retained_bytes=3129313', 1))
    def test_unexpected_batch_refill(self):
        with self.assertRaises(ValueError): check(fixture().replace('batches=0', 'batches=1', 1))
    def test_owner_trial_disagreement(self):
        with self.assertRaises(ValueError): check(fixture().replace('task_cpu_ns=500', 'task_cpu_ns=501', 2))
    def test_runtime_failure(self):
        with self.assertRaises(ValueError): check(fixture()+'\nInvalidInjectionException')
    def test_semantic_requires_verified_corpus(self):
        text = '\n'.join(line for line in fixture().splitlines() if 'generation=1 ' in line)
        text = text.replace('candidate=false', 'candidate=true').replace('batches=0', 'batches=1')
        text += '\nBOOTOPTIM_DECOCRAFT_MODEL_BATCH status=complete generation=1 success=true hits=10809 fallbacks=0 verified=10809 retained_bytes=3129313'
        self.assertTrue(check_semantic(text)['valid'])
        with self.assertRaises(ValueError): check_semantic(text.replace('verified=10809', 'verified=0'))

if __name__ == '__main__': unittest.main()
