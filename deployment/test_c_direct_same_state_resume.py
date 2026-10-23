"""Pure resume admission/isolation policy. No sockets, Docker, SQL or original data access."""
import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("c_same_resume", Path(__file__).with_name("c-direct-same-state-resume.py"))
resume = importlib.util.module_from_spec(spec);spec.loader.exec_module(resume)


class SameStateResumePolicyTest(unittest.TestCase):
    def test_pre_sync_natural_block_does_not_end_clock_wait(self):
        before = {"number": "0x10", "timestamp": "0x64"}
        self.assertFalse(resume.clock_block_applied({"number": "0x11", "timestamp": "0x65"}, before, 1000))
        self.assertFalse(resume.clock_block_applied({"number": "0x10", "timestamp": "0x3e8"}, before, 1000))
        self.assertTrue(resume.clock_block_applied({"number": "0x12", "timestamp": "0x3e8"}, before, 1000))

    def failed_parent_records(self):
        root, sha = "cdemo-" + "b" * 16, "c" * 64
        result = {"runId": resume.FAILED_PARENT, "parent": root, "parentState": sha, "status": "BLOCKED", "stage": "NEW_PG_ANVIL", "safeError": "C_RESUME_ANVIL_MOUNTS", "clockSyncRequests": 0, "automaticTransactions": 0, "bootstrapCalls": 0, "newKeys": 0, "originalUnchanged": True, "anvilState": None, "sourceIdentity": "source", "stopped": {"copy": {"normalStop": True}, "pg": {"normalStop": True}, "anvil": {"exitCode": 137, "oomKilled": False}}}
        lineage = {"child": resume.FAILED_PARENT, "parent": root, "parentState": sha, "recoverySourceIdentity": "source"}
        pg = {"systemIdentifier": "7699878592856473638", "byteClone": True, "sourceReadOnly": True}
        return root, sha, result, lineage, pg

    def test_fixed_prepublic_failure_is_only_admitted_parent_exception(self):
        root, sha, result, lineage, pg = self.failed_parent_records()
        resume.validate_failed_parent(result, lineage, pg, root, sha)
        for key, value in (("clockSyncRequests", 1), ("automaticTransactions", 1), ("stage", "BOUNDED_USER_WINDOW"), ("safeError", "OTHER"), ("originalUnchanged", False)):
            bad = dict(result, **{key: value})
            with self.subTest(key=key), self.assertRaises(ValueError): resume.validate_failed_parent(bad, lineage, pg, root, sha)
        bad = copy.deepcopy(result);bad["stopped"]["app"] = {"normalStop": True}
        with self.assertRaises(ValueError): resume.validate_failed_parent(bad, lineage, pg, root, sha)

    def test_docker_desktop_exact_path_alias_not_sibling(self):
        source = Path("owned") / "snapshot.json"
        self.assertEqual(resume.mount_path(source), resume.mount_path(source.resolve()))
        self.assertNotEqual(resume.mount_path(source), resume.mount_path(source.with_name("snapshot-other.json")))
        if resume.os.name == "nt":
            for linux in ("/run/desktop/mnt/host/c/owned/snapshot.json", "/host_mnt/c/owned/snapshot.json"):
                self.assertEqual(resume.mount_path(linux), resume.mount_path("C:/owned/snapshot.json"))
                self.assertNotEqual(resume.mount_path(linux), resume.mount_path("C:/owned-other/snapshot.json"))

    def test_parent_snapshot_claim_is_stable_and_not_original_claim(self):
        path = resume.claim_path(Path("owned"), "cdemo-" + "a" * 16, "b" * 64)
        self.assertIn("c-direct-resume-claims", str(path));self.assertTrue(path.name.endswith("b" * 64 + ".claim"))
        with self.assertRaises(ValueError): resume.claim_path(Path("owned"), "../../old", "b" * 64)

    def test_even_stopped_previous_live_descendant_blocks_ancestor_replay(self):
        old = {"Config": {"Labels": {"tms.c.parent": "parent", "tms.c.parent-state": "sha"}}, "State": {"Running": False}}
        with self.assertRaises(ValueError): resume.no_live_parent_children([old], "parent", "sha")
        resume.no_live_parent_children([old], "other", "sha")

    def test_parent_must_be_owned_stopped_and_original_image(self):
        info = {"Name": "/parent-app", "Image": "image", "Config": {"Labels": {"tms.c.owner": "parent"}}, "State": {"Running": False, "ExitCode": 0}}
        resume.check_old_role(info, "parent", "app", "image")
        for value in (True,):
            bad = copy.deepcopy(info);bad["State"]["Running"] = value
            with self.assertRaises(ValueError): resume.check_old_role(bad, "parent", "app", "image")
        with self.assertRaises(ValueError): resume.check_old_role(info, "parent", "app", "wrong-image")

    def copy_info(self):
        return {"Name": "/child-copy", "Image": "pg", "Config": {"Labels": {"tms.c.owner": "child", "tms.c.parent": "parent", "tms.c.parent-state": "sha"}, "Entrypoint": ["/bin/bash"], "Cmd": ["-ceu", resume.COPY]},
                "HostConfig": {"NetworkMode": "none", "ReadonlyRootfs": True, "Memory": 256 * 1024**2, "CapDrop": ["ALL"], "CapAdd": ["DAC_OVERRIDE", "CHOWN", "FOWNER"], "SecurityOpt": ["no-new-privileges"], "Tmpfs": {"/var/lib/postgresql/data": "rw,noexec,nosuid,size=1m"}}, "NetworkSettings": {"Networks": {"none": {}}},
                "Mounts": [{"Destination": "/from", "Name": "old-volume", "Type": "volume", "RW": False}, {"Destination": "/to", "Name": "new-volume", "Type": "volume", "RW": True}]}

    def test_exact_cold_copy_boundary_and_capability_alias(self):
        info = self.copy_info();resume.validate_copy(info, "child", "parent", "sha", "old-volume", "new-volume", "pg")
        info["HostConfig"]["CapAdd"] = ["CAP_DAC_OVERRIDE", "CAP_CHOWN", "CAP_FOWNER"]
        resume.validate_copy(info, "child", "parent", "sha", "old-volume", "new-volume", "pg")

    def test_source_write_egress_wrong_caps_or_copy_shell_refused(self):
        mutations = (lambda i: i["Mounts"][0].update(RW=True), lambda i: i["HostConfig"].update(NetworkMode="bridge"), lambda i: i["HostConfig"].update(CapAdd=["SYS_ADMIN"]), lambda i: i["Config"].update(Cmd=["-c", "other"]))
        for mutate in mutations:
            info = self.copy_info();mutate(info)
            with self.assertRaises(ValueError): resume.validate_copy(info, "child", "parent", "sha", "old-volume", "new-volume", "pg")

    def test_declared_image_data_volume_requires_explicit_tmpfs_mask(self):
        info = self.copy_info();info["HostConfig"].pop("Tmpfs")
        with self.assertRaises(ValueError): resume.validate_copy(info, "child", "parent", "sha", "old-volume", "new-volume", "pg")
        info = self.copy_info();info["Mounts"].append({"Destination": "/var/lib/postgresql/data", "Type": "volume", "Name": "anonymous", "RW": True})
        with self.assertRaises(ValueError): resume.validate_copy(info, "child", "parent", "sha", "old-volume", "new-volume", "pg")

    def test_sorted_tar_preserves_uid_directory_metadata_and_pipe_errors(self):
        self.assertIn("set -euo pipefail", resume.COPY)
        self.assertIn("tar -C /to -xpf -", resume.COPY)
        self.assertEqual(resume.COPY.count("tar --sort=name -C /from"), 3)
        self.assertIn('test "$before" = "$after"', resume.COPY)
        self.assertIn('test "$before" = "$copied"', resume.COPY)
        self.assertIn("postmaster.pid", resume.COPY);self.assertIn("PG_VERSION", resume.COPY)

    def test_no_bootstrap_or_transactions_and_all_pending_metadata_checked(self):
        text = Path(resume.__file__).read_text(encoding="utf-8")
        self.assertNotIn('launch("bootstrap")', text)
        self.assertNotIn('rpc("eth_sendRawTransaction"', text)
        self.assertNotIn('rpc("eth_sendTransaction"', text)
        self.assertNotIn('rpc("evm_mine"', text)
        self.assertIn('db["drafts"] == db["consents"] == db["executions"] == db["claims"] == 0', text)
        self.assertIn('clock_requests == 0', text)
        self.assertIn('abs(params[0] - time.time()) <= 1.5', text)


if __name__ == "__main__":
    unittest.main()
