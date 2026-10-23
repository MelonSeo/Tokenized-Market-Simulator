"""Pure source-lineage tests. No compiler, sockets, Docker, accounts, or API calls."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("c_browser_prepare", Path(__file__).with_name("c-direct-browser-prepare.py"))
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class BrowserPreparePolicyTest(unittest.TestCase):
    def test_ready_publication_gap_is_not_success_and_complete_document_is_required(self):
        import tempfile
        spec=importlib.util.spec_from_file_location("ready_reader",Path(__file__).with_name("c-direct-browser-run.py"))
        runner=importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/"ready.json"
            self.assertIsNone(runner.published_ready(path))
            path.write_bytes(b"");self.assertIsNone(runner.published_ready(path))
            path.write_bytes(b'{"runId":');self.assertIsNone(runner.published_ready(path))
            path.write_bytes(b'{"runId":"new"}');self.assertEqual(runner.published_ready(path),{"runId":"new"})
            path.write_bytes(b'[]')
            with self.assertRaisesRegex(ValueError,"FILE_SHAPE"):runner.published_ready(path)
            path.write_bytes(b' '*16385)
            with self.assertRaisesRegex(ValueError,"FILE_SIZE"):runner.published_ready(path)

    def test_one_hour_oracle_keeps_exact_command_and_base_isolation(self):
        import copy
        from test_c_direct_demo_check import DirectDemoPreparationTests
        fixture=DirectDemoPreparationTests()
        base=prepare.load_base(Path(__file__).resolve().parent.parent)
        spec=importlib.util.spec_from_file_location("browser_session_runner",Path(__file__).with_name("c-direct-browser-run.py"))
        runner=importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)
        info=fixture.inspection("oracle");info["Config"]["Cmd"][-1]="3600"
        original=copy.deepcopy(info)
        runner.validate_oracle(base,info,fixture.run_id,fixture.paths(),fixture.env_files(),3600)
        self.assertEqual(info,original)
        for mutation in ("wrong_duration","wrong_executable","published_port"):
            bad=copy.deepcopy(info)
            if mutation=="wrong_duration":bad["Config"]["Cmd"][-1]="900"
            elif mutation=="wrong_executable":bad["Config"]["Cmd"][0]="-Xmx999m"
            else:bad["HostConfig"]["PortBindings"]={"8091/tcp":[{}]}
            with self.subTest(mutation=mutation),self.assertRaises(ValueError):
                runner.validate_oracle(base,bad,fixture.run_id,fixture.paths(),fixture.env_files(),3600)

    def test_relay_accepts_one_hour_but_not_unbounded(self):
        from test_c_direct_browser_relay import BrowserRelayPolicyTest
        from c_direct_browser_relay import validate_plan
        fixture=BrowserRelayPolicyTest();fixture.setUp()
        self.assertEqual(validate_plan(dict(fixture.meta,seconds=3600)),fixture.run)
        for seconds in (0,29,3601,True):
            with self.subTest(seconds=seconds),self.assertRaises(ValueError):validate_plan(dict(fixture.meta,seconds=seconds))

    def test_source_identity_binds_parent_and_extension(self):
        parent, extension = {"old": "a" * 64}, {"new": "b" * 64}
        original = prepare.source_identity(parent, extension)
        self.assertNotEqual(original, prepare.source_identity({"old": "c" * 64}, extension))
        self.assertNotEqual(original, prepare.source_identity(parent, {"new": "c" * 64}))
        self.assertEqual(original, prepare.source_identity(dict(parent), dict(extension)))

    def test_extension_cannot_shadow_parent(self):
        with self.assertRaises(ValueError): prepare.source_identity({"same": "a"}, {"same": "b"})

    def test_fixed_base_and_selected_sources(self):
        self.assertEqual(prepare.BASE_ID, "20261023T140536Z-654b77de")
        self.assertEqual(len(prepare.SOURCE_PATHS), len(set(prepare.SOURCE_PATHS)))
        self.assertIn("deployment/CDirectBrowserBootstrap.java", prepare.SOURCE_PATHS)
        self.assertNotIn("deployment/CDirectDemoDriver.java", prepare.SOURCE_PATHS)


if __name__ == "__main__":
    unittest.main()
