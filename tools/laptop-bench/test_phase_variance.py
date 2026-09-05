import tempfile
from pathlib import Path
import unittest

from phase_variance import analyze


class PhaseVarianceTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / 'bootoptim-startup.log').write_text(
            'PHASE name=mod_entrypoint uptime_ms=1000\nPHASE name=main_menu uptime_ms=10000\n', encoding='utf-8')
        self.lines = [
            '[05Sept2026 12:00:01.000] BOOTOPTIM_STARTUP phase=mod_entrypoint',
            '[05Sept2026 12:00:02.000] Reloading ResourceManager: vanilla, file/Pack.zip',
            '[05Sept2026 12:00:04.000] Created: 8192x8192x2 minecraft:textures/atlas/blocks.png-atlas',
            '[05Sept2026 12:00:07.000] BOOTOPTIM_FANCYMENU_PANORAMA_PRELOAD preload_ms=2000.000',
            '[05Sept2026 12:00:08.000] Minecraft resource reload: FINISHED',
        ]

    def result(self):
        (self.root / 'latest.log').write_text('\n'.join(self.lines), encoding='utf-8')
        return analyze(self.root)

    def test_nonoverlapping_partition(self):
        result = self.result()
        self.assertEqual(list(result['segments'].values()), [1000, 1000, 2000, 1000, 2000, 1000, 2000])
        self.assertEqual(result['segment_sum_ms'], 10000)
        self.assertEqual(result['partition_issues'], [])

    def test_missing_preload_stays_missing(self):
        self.lines.pop(3)
        result = self.result()
        self.assertIsNone(result['segment_sum_ms'])
        self.assertTrue(result['partition_issues'])

    def test_multiple_reloads_flag_ambiguity(self):
        self.lines.append('[05Sept2026 12:00:09.000] Reloading ResourceManager: vanilla')
        self.assertTrue(self.result()['partition_issues'])

    def test_clock_discontinuity_flagged(self):
        self.lines[-1] = '[05Sept2026 12:30:00.000] Minecraft resource reload: FINISHED'
        self.assertTrue(self.result()['partition_issues'])


if __name__ == '__main__':
    unittest.main()
