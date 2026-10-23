"""Offline live-mode boundary and bounded archive tests; never starts the live successor."""
import ast
from contextlib import redirect_stdout
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import c_live_resume_support as support

spec = importlib.util.spec_from_file_location("c_live_launcher", Path(__file__).with_name("c-checkpoint-live-launch.py"))
launcher = importlib.util.module_from_spec(spec);spec.loader.exec_module(launcher)


def archive(*entries):
    out = io.BytesIO()
    with tarfile.open(fileobj=out, mode="w") as tar:
        for name, data, kind in entries:
            item = tarfile.TarInfo(name);item.type = kind;item.size = len(data)
            if kind == tarfile.SYMTYPE: item.linkname = "elsewhere"
            tar.addfile(item, io.BytesIO(data) if kind == tarfile.REGTYPE else None)
    return out.getvalue()


class LiveSuccessorPolicyTest(unittest.TestCase):
    def frozen_fixture(self, directory):
        root = Path(directory)
        profile = {"scope": "C_LOCAL_EXPLICIT_LIVE_SUCCESSOR_CHECKPOINT", "parent": "cdemo-" + "a" * 16, "sessionSeconds": 3600, "appPort": 32171, "rpcPort": 32172, "frozenHelpers": {}}
        for rel in support.FROZEN_HELPER_PATHS:
            path = root / rel;path.parent.mkdir(parents=True, exist_ok=True);path.write_text("trusted " + rel, encoding="utf-8")
            profile["frozenHelpers"][rel] = support.sha(path)
        (root / "deployment/c-live-latest-checkpoint.json").write_text(json.dumps(profile), encoding="utf-8")
        return root, profile

    def test_untracked_dynamic_helper_mutation_blocks_default_and_preclaim(self):
        for rel in ("deployment/c-direct-browser-run.py", "deployment/c-anvil-snapshot-clone-check.py"):
            with self.subTest(helper=rel), tempfile.TemporaryDirectory() as tmp:
                root, profile = self.frozen_fixture(tmp);support.verify_frozen(root, profile)
                (root / rel).write_text("changed helper", encoding="utf-8")
                with patch("subprocess.run", side_effect=AssertionError("PROCESS")), patch("socket.socket", side_effect=AssertionError("SOCKET")), self.assertRaisesRegex(ValueError, "FROZEN_HELPERS"):
                    launcher.main([], root)
                with self.assertRaisesRegex(ValueError, "FROZEN_HELPERS"):
                    support.verify_artifacts(SimpleNamespace(root=root, p=profile))
                self.assertFalse((root / "deployment/runtime").exists())

    def test_all_five_frozen_helpers_are_runtime_source_inputs(self):
        text = Path(launcher.__file__).read_text(encoding="utf-8")
        self.assertIn('tracked.update({str(root / rel): digest for rel, digest in p["frozenHelpers"].items()})', text)
        self.assertLess(text.index('support.verify_artifacts(c)  # Exact'), text.index('with claim.open("x"'))
        self.assertIn('support.verify_artifacts(c)\n        except Exception: unchanged = False', text)
        self.assertEqual(len(support.FROZEN_HELPER_PATHS), 5)
    def test_default_cli_is_plan_only_no_docker_socket_or_claim(self):
        with tempfile.TemporaryDirectory() as tmp:
            claim = Path(tmp, "new.claim")
            context = SimpleNamespace(root=Path(tmp), p={"parent": "latest", "snapshotSha256": "a" * 64, "dataRun": "original"}, reuse=SimpleNamespace(claim_path=lambda *unused: claim))
            output = io.StringIO()
            with patch.object(support, "load_context", return_value=context), patch.object(launcher, "live", side_effect=AssertionError("LIVE")), patch("subprocess.run", side_effect=AssertionError("PROCESS")), patch("socket.socket", side_effect=AssertionError("SOCKET")), redirect_stdout(output):
                self.assertEqual(launcher.main([], Path(tmp)), 0)
            value = json.loads(output.getvalue());self.assertEqual(value["dockerCalls"], 0);self.assertEqual(value["claimsCreated"], 0);self.assertFalse(claim.exists())

    def test_explicit_option_is_only_live_entry_and_has_no_implicit_retry(self):
        fake = object()
        with patch.object(support, "load_context", return_value=fake), patch.object(launcher, "live", return_value=7) as live:
            self.assertEqual(launcher.main(["--live-window"], Path("owned")), 7)
            live.assert_called_once_with(fake)
        with self.assertRaises(SystemExit), redirect_stdout(io.StringIO()): launcher.main(["--unknown"])

    def test_existing_parent_claim_cannot_reuse_checkpoint(self):
        with tempfile.TemporaryDirectory() as tmp:
            claim = Path(tmp, "used.claim");claim.write_text("PRESERVE", encoding="ascii")
            c = SimpleNamespace(root=Path(tmp), p={"parent": "latest", "snapshotSha256": "a" * 64}, reuse=SimpleNamespace(claim_path=lambda *unused: claim))
            with self.assertRaisesRegex(ValueError, "PARENT_ALREADY_CONSUMED"): launcher.plan(c)
            self.assertEqual(claim.read_text(encoding="ascii"), "PRESERVE")

    def test_live_archive_cap_is_32_not_changed_legacy_16(self):
        self.assertEqual(support.STATE_CAP, 32 * 1024**2)
        with patch.object(support, "STATE_CAP", 8):
            self.assertEqual(support.extract_live_state_tar(archive(("anvil.json", b"12345678", tarfile.REGTYPE))), b"12345678")
            with self.assertRaises(ValueError): support.extract_live_state_tar(archive(("anvil.json", b"123456789", tarfile.REGTYPE)))
            with self.assertRaises(ValueError): support.extract_live_state_tar(b"x" * (8 + 10241))

    def test_archive_rejects_path_link_empty_multiple_and_corruption(self):
        for name in ("../anvil.json", "/anvil.json", "dir/anvil.json", "other.json"):
            with self.subTest(name=name), self.assertRaises(ValueError): support.extract_live_state_tar(archive((name, b"{}", tarfile.REGTYPE)))
        for data in (archive(("anvil.json", b"", tarfile.REGTYPE)), archive(("anvil.json", b"", tarfile.SYMTYPE)), archive(("anvil.json", b"{}", tarfile.REGTYPE), ("anvil.json", b"{}", tarfile.REGTYPE)), b"not a tar"):
            with self.assertRaises(ValueError): support.extract_live_state_tar(data)

    def test_cli_ast_keeps_user_window_after_initial_proof(self):
        text = Path(launcher.__file__).read_text(encoding="utf-8");ast.parse(text)
        self.assertLess(text.index("support.verify_http"), text.index('stage = "USER_WINDOW"'))
        self.assertLess(text.index('initial_after == before'), text.index('stage = "USER_WINDOW"'))
        final = text[text.index('stage = "FINAL_OBSERVATION_NOT_BASELINE_ASSERTION"'):text.index("    except Exception as failure:")]
        self.assertIn("raw_call, False", final)
        self.assertNotIn("== before", final);self.assertNotIn("verify_metadata", final)
        self.assertIn("C_LIVE_PENDING_PRESERVED_NO_RETRANSMISSION", final)

    def test_no_script_user_signing_broadcast_bootstrap_or_warp(self):
        text = Path(launcher.__file__).read_text(encoding="utf-8")
        for forbidden in ('launch("bootstrap")', 'rpc("eth_send', 'rpc("evm_mine"', 'rpc("evm_increaseTime"', 'request("/api/c-futures/direct/intents', 'wallet.seed', 'personal_sign'):
            self.assertNotIn(forbidden, text)
        self.assertIn('clock_requests == 0', text);self.assertIn('time.time()) <= 1.5', text)
        self.assertIn('data is None or path == "/api/auth/login"', text)

    def test_profile_is_latest_8d_and_old_gate_sources_pinned(self):
        p = json.loads(Path(__file__).with_name("c-live-latest-checkpoint.json").read_text(encoding="utf-8"))
        self.assertEqual(p["parent"], "cdemo-8d5fd2ff832345bb");self.assertEqual(p["snapshotBytes"], 14800699)
        self.assertNotEqual(p["parent"], p["dataRun"])
        self.assertEqual(p["frozenHelpers"]["deployment/c-checkpoint-resume-gate.py"], "30801a29dad0be079822d3cf457ab34466662c9b6931ece59065ba4b610a6d29")


if __name__ == "__main__":
    unittest.main()
