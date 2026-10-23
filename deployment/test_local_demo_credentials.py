import os
import unittest
from unittest.mock import patch
from local_demo_credentials import local_demo_password


class LocalDemoCredentialsTest(unittest.TestCase):
    def test_missing_or_invalid_value_fails_without_disclosing_input(self):
        for value in ('', 'short', 'x' * 73, 'invalid\ncredential'):
            with patch.dict(os.environ, {'TMS_LOCAL_DEMO_PASSWORD': value}):
                with self.assertRaisesRegex(ValueError, '^LOCAL_DEMO_PASSWORD_REQUIRED$'):
                    local_demo_password()

    def test_explicit_operator_value_is_not_transformed(self):
        with patch.dict(os.environ, {'TMS_LOCAL_DEMO_PASSWORD': 'unit-fixture-not-live'}):
            self.assertEqual(local_demo_password(), 'unit-fixture-not-live')


if __name__ == '__main__':
    unittest.main()
