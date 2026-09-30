import json
import tempfile
import unittest
from pathlib import Path
from summarize_express_sweep import summarize

class SummaryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='bootoptim-summary-')
        self.root = Path(self.temp.name)
        names = ['control', 'decocraft-v2', 'ferrite-capacity', 'sodium-axis', 'layer-delta']
        runs = []
        for i, name in enumerate(names):
            folder = self.root / name
            folder.mkdir()
            runs.append(dict(name=name, valid=True, restored=True, jarSha256='a'*64,
                             startupMs=1000+i, initialReloadCompleteMs=900, gcMs=10, menuReloadMs=None))
            for f in ['options.before.txt', 'options.after.txt']:
                (folder/f).write_text('resourcePacks:["vanilla","file/test.zip"]\n', encoding='utf-8')
            marker = {'decocraft-v2': 'BOOTOPTIM_DECOCRAFT_CORNER_ROTATION mode=substitute_v2', 'ferrite-capacity': 'BOOTOPTIM_FERRITE_QUAD_CAPACITY previous_unique=1 success=true', 'sodium-axis': 'BOOTOPTIM_SODIUM_AXIS_QUAD_FLAGS status=active'}.get(name, '')
            (folder/'latest.log').write_text('Reloading ResourceManager: vanilla, file/test.zip\n' + marker + '\n', encoding='utf-8')
            keys = dict(zip(names[1:], ['experimentalDecocraftCornerRotationReuseV2','ferriteCoreQuadCapacity','sodiumAxisQuadFlags','generatedItemLayerDeltaHoist']))
            flags = [f"-Dboot_optim.{key}={'true' if name == mode else 'false'}" for mode, key in keys.items()]
            (folder/'transaction.finished.json').write_text(json.dumps(dict(valid=True, effectiveCommandLineSha256='b'*64, javaCreationDate='2026-09-30T00:00:00Z',validatedRequiredJvmArgs=flags)), encoding='utf-8')
        self.summary = dict(status='finished', runs=runs)
        self.save()

    def tearDown(self):
        self.temp.cleanup()

    def save(self):
        (self.root/'summary.json').write_text(json.dumps(self.summary), encoding='utf-8')

    def test_valid_is_exploratory_and_utf8(self):
        result = summarize(self.root)
        self.assertTrue(result['valid'])
        self.assertEqual(result['conclusion'], 'exploratory_only_not_performance_proof')
        self.assertIn('JVM→menu', (self.root/'summary.md').read_text(encoding='utf-8'))

    def test_effective_resource_fallback_rejected(self):
        (self.root/'sodium-axis/latest.log').write_text('Reloading ResourceManager: vanilla\n', encoding='utf-8')
        self.assertFalse(summarize(self.root)['valid'])

    def test_partial_failed_run_returns_invalid_report(self):
        (self.root/'decocraft-v2/transaction.finished.json').unlink()
        self.summary['status'] = 'failed'
        self.save()
        self.assertFalse(summarize(self.root)['valid'])
        self.assertTrue((self.root/'checked-summary.json').exists())

    def test_two_reload_generation_count_matches_mode(self):
        for r in self.summary['runs']:
            r['menuReloadMs'] = 400
            with (self.root/r['name']/'latest.log').open('a', encoding='utf-8') as f:
                f.write('Reloading ResourceManager: vanilla, file/test.zip\n')
        self.save()
        self.assertTrue(summarize(self.root)['valid'])

    def test_missing_target_activation_is_not_a_comparison(self):
        (self.root/'sodium-axis/latest.log').write_text('Reloading ResourceManager: vanilla, file/test.zip\n', encoding='utf-8')
        self.assertFalse(summarize(self.root)['valid'])

if __name__ == '__main__':
    unittest.main()
