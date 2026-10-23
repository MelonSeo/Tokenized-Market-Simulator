"""Pure Gate guard/argv tests: never invoke a process, socket or database."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("consent_pg", Path(__file__).with_name("c-consent-postgres-check.py"))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class CConsentPostgresRunnerTest(unittest.TestCase):
    def setUp(self):
        self.run = "ccpg-" + "a" * 16
        self.fixture, self.evidence = Path("C:/new/fixture"), Path("C:/new/evidence")

    def info(self, kind):
        java = kind == "java"
        command = gate.args(kind, self.run, self.fixture, self.evidence, Path("C:/new/private.env"))
        image = gate.JAVA_IMAGE if java else gate.PG_IMAGE
        mounts = [{"Type": "bind", "Source": str(self.fixture.resolve()), "Destination": "/fixture", "RW": False},
                  {"Type": "bind", "Source": str(self.evidence.resolve()), "Destination": "/evidence", "RW": True}] if java else [
                  {"Type": "volume", "Name": self.run + "-data", "Destination": "/var/lib/postgresql/data", "RW": True}]
        return {"Image": image, "Config": {"Labels": {"tms.c.owner": self.run}, "User": "10001:10001" if java else "",
            "Entrypoint": ["java"] if java else ["docker-entrypoint.sh"], "Cmd": command[command.index(image)+1:] if java else ["postgres"]},
            "HostConfig": {"NetworkMode": self.run + "-network", "Memory": (384 if java else 256)*1024**2,
                "Privileged": False, "ReadonlyRootfs": java, "CapDrop": ["ALL"] if java else ["CAP_NET_RAW"],
                "SecurityOpt": ["no-new-privileges"] if java else []},
            "NetworkSettings": {"Networks": {self.run + "-network": {}}, "Ports": {}}, "Mounts": mounts}

    def test_resources_and_identity_fail_closed(self):
        gate.resources(1920*1024**2, 2*1024**3)
        for ram, disk in ((1919*1024**2, gate.MIN_DISK), (gate.MIN_RAM, gate.MIN_DISK-1)):
            with self.assertRaises(ValueError):
                gate.resources(ram, disk)
        for run in ("exchange", "../ccpg-"+"a"*16, "cpg-"+"a"*16):
            with self.assertRaises(ValueError):
                gate.args("pg", run, self.fixture, self.evidence, Path("p"))

    def test_fixed_launch_has_no_publishing_pull_or_private_inline_values(self):
        for kind in ("pg", "java"):
            argv = gate.args(kind, self.run, self.fixture, self.evidence, Path("private.env"))
            self.assertIn("--pull=never", argv)
            self.assertNotIn("-p", argv)
            self.assertNotIn("--network=host", argv)
            self.assertNotIn("-e", argv)
            gate.validate(self.info(kind), kind, self.run, self.fixture, self.evidence)
        java = gate.args("java", self.run, self.fixture, self.evidence, Path("p"))
        self.assertEqual(java[-3:], ["CConsentPostgresTests", "/fixture", "/evidence"])
        self.assertIn("-Xmx192m", java)

    def test_pg_capability_equivalence_is_exact_single_entry(self):
        for cap in (["NET_RAW"], ["CAP_NET_RAW"]):
            value = self.info("pg"); value["HostConfig"]["CapDrop"] = cap
            gate.validate(value, "pg", self.run, self.fixture, self.evidence)
        for cap in (None, [], ["ALL"], ["NET_RAW", "CAP_NET_RAW"], ["NET_RAW", "NET_RAW"]):
            value = self.info("pg"); value["HostConfig"]["CapDrop"] = cap
            with self.assertRaises(ValueError):
                gate.validate(value, "pg", self.run, self.fixture, self.evidence)

    def test_actual_inspect_isolation_and_classpath_cannot_be_weakened(self):
        for kind in ("pg", "java"):
            for mutation in (
                lambda i: i["HostConfig"].update(Privileged=True),
                lambda i: i["HostConfig"].update(CapAdd=["SYS_ADMIN"]),
                lambda i: i["HostConfig"].update(PortBindings={"5432/tcp": [{"HostPort": "1234"}]}),
                lambda i: i["HostConfig"].update(Memory=0),
                lambda i: i["Config"]["Labels"].update({"tms.c.owner": "other"}),
            ):
                value = self.info(kind); mutation(value)
                with self.assertRaises(ValueError):
                    gate.validate(value, kind, self.run, self.fixture, self.evidence)
        for mutation in (lambda i: i["Config"].update(User="0:0"), lambda i: i["Config"].update(Cmd=["OtherMain"]),
                         lambda i: i["Mounts"][0].update(RW=True), lambda i: i["HostConfig"].update(ReadonlyRootfs=False)):
            value = self.info("java"); mutation(value)
            with self.assertRaises(ValueError):
                gate.validate(value, "java", self.run, self.fixture, self.evidence)

    def test_source_selection_has_only_seven_production_and_single_pg_launcher(self):
        self.assertEqual(len(gate.SOURCES), 8)
        self.assertEqual(sum("src/main/java/" in p for p in gate.SOURCES), 7)
        self.assertFalse(any("src/test/" in p or "CAnvil" in p for p in gate.SOURCES))
        self.assertEqual(len(gate.RESOURCES), 2)

    def test_raw_inspect_password_and_sql_stderr_are_never_recorded(self):
        secret = "sentinel-password-do-not-record"
        result = subprocess.CompletedProcess([], 1, '{"Config":{"Env":["POSTGRES_PASSWORD='+secret+'"]}}', "SQL secret="+secret)
        stored = json.dumps(gate.observation(["docker", "container", "inspect", self.run+"-pg"], result))
        self.assertNotIn(secret, stored)
        self.assertNotIn("POSTGRES_PASSWORD", stored)
        self.assertNotIn("SQL secret", stored)
        with self.assertRaises(ValueError):
            gate.observation(["docker", "run", "--env=PASSWORD="+secret], result)

    def test_cleanup_discovers_both_names_even_when_run_timed_out(self):
        self.assertEqual(gate.cleanup_targets(self.run), [("java", self.run+"-java"), ("pg", self.run+"-pg")])
        self.assertTrue(gate.missing_object(subprocess.CompletedProcess([], 1, "", "Error: No such object: owned")))
        self.assertFalse(gate.missing_object(subprocess.CompletedProcess([], 1, "", "daemon unavailable")))
        self.assertEqual(gate.TOTAL_SECONDS, 180)
        self.assertEqual(gate.TOTAL_SECONDS-gate.WORK_SECONDS, 30)

    def test_pg_entrypoint_and_command_are_exact(self):
        for field, value in (("Entrypoint", ["sh"]), ("Cmd", ["postgres", "-c", "some_override=on"]), ("Cmd", [])):
            info = self.info("pg"); info["Config"][field] = value
            with self.assertRaises(ValueError):
                gate.validate(info, "pg", self.run, self.fixture, self.evidence)

    def test_fixture_hashes_detect_mutation_and_unknown_added_file(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root/"manifest.properties").write_text("fixed", encoding="utf-8")
            original = gate.tree_hashes(root)
            self.assertEqual(gate.tree_hashes(root), original)
            (root/"manifest.properties").write_text("changed", encoding="utf-8")
            self.assertNotEqual(gate.tree_hashes(root), original)
            (root/"manifest.properties").write_text("fixed", encoding="utf-8")
            (root/"unknown").write_text("extra", encoding="utf-8")
            self.assertNotEqual(gate.tree_hashes(root), original)

    def test_sql_resources_are_exact_in_fixture_and_classpath_and_missing_fails(self):
        with tempfile.TemporaryDirectory() as d:
            snapshot, fixture = Path(d)/"snapshot", Path(d)/"fixture"
            (fixture/"classes").mkdir(parents=True)
            for rel in gate.RESOURCES:
                source = snapshot/rel
                source.parent.mkdir(parents=True, exist_ok=True)
                source.write_bytes(("untouched SQL including trigger " + rel).encode("utf-8"))
            expected = gate.copy_resources(snapshot, fixture)
            self.assertEqual(expected, gate.verify_resources(snapshot, fixture))
            for rel in gate.RESOURCES:
                self.assertEqual((fixture/Path(rel).name).read_bytes(), (fixture/"classes"/Path(rel).name).read_bytes())
            target = fixture/"classes"/"c-consent-schema.sql"
            original = target.read_bytes()
            target.write_bytes(b"changed")
            with self.assertRaises(ValueError):
                gate.verify_resources(snapshot, fixture)
            target.write_bytes(original)
            target.unlink()
            with self.assertRaises(ValueError):
                gate.verify_resources(snapshot, fixture)


if __name__ == "__main__":
    unittest.main()
