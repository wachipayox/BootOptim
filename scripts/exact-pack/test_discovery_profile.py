import unittest
from validate_discovery_profile import validate


GOOD = '''BOOTOPTIM_DISCOVERY_WORK success=true available=true dependencies_ms=12 dependency_calls=4
BOOTOPTIM_DEPENDENCY_TYPE class=A calls=3 distinct_models=1 dependency_wall_ms=10
BOOTOPTIM_DEPENDENCY_TYPE class=B calls=1 distinct_models=1 dependency_wall_ms=2'''


class Discovery(unittest.TestCase):
    def test_complete(self):
        self.assertEqual(validate(GOOD), 1)
        self.assertEqual(validate(GOOD + '\n' + GOOD), 2)

    def test_rejects(self):
        for text in ('', GOOD.replace('available=true', 'available=false'), GOOD.replace('class=B', 'class=A'), GOOD.replace('distinct_models=1', 'distinct_models=0', 1), GOOD.replace('dependency_wall_ms=2', 'dependency_wall_ms=3'), GOOD.replace('dependency_calls=4', 'dependency_calls=3')):
            with self.subTest(text=text):
                with self.assertRaises(ValueError):
                    validate(text)


if __name__ == '__main__':
    unittest.main()
