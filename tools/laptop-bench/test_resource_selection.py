"""Regression cases for accepting/rejecting resource benchmark evidence."""
import json
from pathlib import Path
import tempfile
import unittest

from check_resource_selection import check


class ResourceContractTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.reference = self.root / 'reference.txt'
        self.actual = self.root / 'options.txt'
        self.log = self.root / 'latest.log'
        self.packs = ['vanilla', 'file/First.zip', 'file/Second.zip']
        self.write_options(self.reference, self.packs)
        self.write_options(self.actual, self.packs)

    @staticmethod
    def write_options(path, packs):
        path.write_text('resourcePacks:' + json.dumps(packs), encoding='utf-8')

    def test_realistic_combined_mod_ids(self):
        self.log.write_text('Reloading ResourceManager: vanilla, mod/foo,bar, '
                            'file/First.zip, file/Second.zip, dynamic', encoding='utf-8')
        self.assertTrue(check(self.reference, self.actual, self.log)['valid'])

    def test_order_changes_resource_priority(self):
        self.write_options(self.actual, list(reversed(self.packs)))
        self.assertFalse(check(self.reference, self.actual)['valid'])

    def test_final_good_reload_does_not_hide_earlier_bad_reload(self):
        self.log.write_text('Reloading ResourceManager: vanilla\n'
                            'Reloading ResourceManager: vanilla, file/First.zip, file/Second.zip',
                            encoding='utf-8')
        result = check(self.reference, self.actual, self.log)
        self.assertFalse(result['valid'])
        self.assertEqual(result['reload_count'], 2)

    def test_options_alone_do_not_prove_pack_loaded(self):
        self.log.write_text('Reloading ResourceManager: vanilla, mod/foo', encoding='utf-8')
        self.assertFalse(check(self.reference, self.actual, self.log)['valid'])

    def test_empty_reference_cannot_authorize_empty_workload(self):
        self.write_options(self.reference, ['vanilla'])
        with self.assertRaises(ValueError):
            check(self.reference, self.actual)

    def test_no_reload_is_not_success(self):
        self.log.write_text('SUMMARY status=main_menu_reached', encoding='utf-8')
        self.assertFalse(check(self.reference, self.actual, self.log)['valid'])


if __name__ == '__main__':
    unittest.main()
