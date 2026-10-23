"""No JVM, services or dependency downloads; only the runner success boundary."""
import importlib.util
from pathlib import Path
import unittest


spec = importlib.util.spec_from_file_location("c_proof_check", Path(__file__).with_name("c-proof-check.py"))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class ProofSuccessTest(unittest.TestCase):
    def test_clean_zero_exit_is_accepted(self):
        self.assertTrue(runner.proof_success(0, 0, True, True, True))

    def test_compiler_cleanup_error_cannot_hide_behind_zero_exit(self):
        self.assertFalse(runner.proof_success(0, 0, True, False, True))

    def test_test_stderr_is_not_a_clean_success(self):
        self.assertFalse(runner.proof_success(0, 0, True, True, False))

    def test_compile_failure_skipped_test_and_timeout_are_rejected(self):
        for compile_exit, test_exit in ((1, None), (0, None), (124, None), (0, 124), (0, 1)):
            with self.subTest(compile_exit=compile_exit, test_exit=test_exit):
                self.assertFalse(runner.proof_success(compile_exit, test_exit, True, True, True))

    def test_source_change_is_rejected(self):
        self.assertFalse(runner.proof_success(0, 0, False, True, True))


if __name__ == "__main__":
    unittest.main()
