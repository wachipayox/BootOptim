import unittest
from run_startup import validate_express_endpoint

ARGS = '-Dboot_optim.benchmark.expressSweep=true\n-Dboot_optim.benchmark.expressMenuReloads=1'
LOG = '''Reloading ResourceManager: vanilla
BOOTOPTIM_SWEEP stage=initial_reload_created uptime_ms=100
BOOTOPTIM_SWEEP stage=initial_reload_complete success=true uptime_ms=200
BOOTOPTIM_SWEEP stage=main_menu_presented origin=jvm_uptime uptime_ms=210
BOOTOPTIM_SWEEP stage=menu_reload_requested origin=reload_invocation endpoint=future_completion
Reloading ResourceManager: vanilla
BOOTOPTIM_SWEEP stage=menu_reload_complete success=true wall_ns=1000000
BOOTOPTIM_SWEEP stage=finished origin=jvm_uptime uptime_ms=300 gc_count=1 gc_ms=2
'''


class EndpointTests(unittest.TestCase):
    def test_complete_repeat(self):
        validate_express_endpoint(LOG, ARGS)

    def test_ordinary_startup_unchanged(self):
        validate_express_endpoint('BOOTOPTIM_STARTUP phase=main_menu', '')

    def test_premature_title_shutdown_rejected(self):
        with self.assertRaises(ValueError):
            validate_express_endpoint(LOG.split('BOOTOPTIM_SWEEP stage=main_menu_presented')[0], ARGS)

    def test_failed_repeat_rejected(self):
        with self.assertRaises(ValueError):
            validate_express_endpoint(LOG.replace('success=true wall_ns', 'success=false wall_ns'), ARGS)

    def test_duplicate_final_endpoint_rejected(self):
        with self.assertRaises(ValueError):
            validate_express_endpoint(LOG + 'BOOTOPTIM_SWEEP stage=finished uptime_ms=301\n', ARGS)

    def test_extra_generation_rejected(self):
        with self.assertRaises(ValueError):
            validate_express_endpoint(LOG + 'Reloading ResourceManager: vanilla\n', ARGS)

    def test_presentation_before_initial_completion_rejected(self):
        with self.assertRaises(ValueError):
            validate_express_endpoint(LOG.replace('uptime_ms=210', 'uptime_ms=190'), ARGS)


if __name__ == '__main__':
    unittest.main()
