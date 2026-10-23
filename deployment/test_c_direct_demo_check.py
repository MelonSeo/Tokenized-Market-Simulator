import importlib.util
import os
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("c_direct_demo", Path(__file__).with_name("c-direct-demo-check.py"))
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)


class DirectDemoPreparationTests(unittest.TestCase):
    def test_local_cadence_and_pre_draft_tick_observation_do_not_retry_execution(self):
        source=Path(__file__).with_name("CDirectDemoDriver.java").read_text(encoding="utf-8")
        self.assertIn('p.setProperty("app.price.update-interval-ms",manifest.path("syntheticTickMs").asText())',source)
        self.assertIn('manifest.path("syntheticTickMs").asInt()==3000',source)
        self.assertIn('manifest.path("presentationLocalOnly").asBoolean()',source)
        observer=source.split('JsonNode nextMarketTick(String jwt)',1)[1].split('JsonNode execute(',1)[0]
        self.assertIn('n<40',observer)
        self.assertIn('Thread.sleep(100)',observer)
        self.assertNotIn('"POST"',observer)
        self.assertNotIn('g.send(',observer)
        execute=source.split('JsonNode execute(',1)[1].split('void databaseFinal()',1)[0]
        self.assertEqual(1,execute.count('"/execution",null,jwt'))
        self.assertEqual(1,execute.count('g.send('))

    def test_lombok_annotations_resolve_on_compile_classpath_not_only_processor_path(self):
        lombok=Path("C:/cached/lombok.jar")
        args=gate.javac_args("server", (), "C:/cached/runtime.jar", Path("C:/owned/out"), Path("C:/owned/snapshot"), lombok)
        self.assertEqual("C:/cached/runtime.jar"+os.pathsep+str(lombok),args[args.index("-cp")+1])
        self.assertEqual(str(lombok),args[args.index("-processorpath")+1])
        for role in ("driver","oracle"):
            selected=gate.javac_args(role, (), "C:/cached/runtime.jar", Path("C:/owned/out"), Path("C:/owned/snapshot"), lombok)
            self.assertEqual("C:/cached/runtime.jar",selected[selected.index("-cp")+1])
            self.assertNotIn("-processorpath",selected)

    run_id = "cdemo-" + "a" * 16
    def paths(self):
        return {name: Path("C:/new-owned/" + name) for name in ("server", "oracle", "driver", "web", "private", "appConfig", "oracleConfig", "driverControl", "appControl", "oracleControl", "appEvidence", "oracleEvidence")}

    def env_files(self):
        return {name: Path("C:/new-owned/" + name + ".private.env") for name in ("pg", "driver")}

    def inspection(self, role):
        args = gate.launch_args(role, self.run_id, self.paths(), self.env_files())
        mounts = []
        for at, value in enumerate(args):
            if value != "--mount":
                continue
            options = args[at + 1].split(",");values = dict(s.split("=", 1) for s in options if "=" in s)
            mounts.append({"Type": values["type"], "Source": values["source"], "Destination": values["target"],
                "RW": "readonly" not in options, "Name": values["source"] if values["type"] == "volume" else ""})
        image = gate.PG_IMAGE if role == "pg" else gate.FOUNDRY_IMAGE if role == "anvil" else gate.JAVA_IMAGE
        index = args.index("--entrypoint") if "--entrypoint" in args else -1
        return {"Image": image, "Config": {"Labels": {"tms.c.owner": self.run_id}, "User": "" if role == "pg" else "0:0" if role == "anvil" else "10001:10001",
            "Entrypoint": ["docker-entrypoint.sh"] if role == "pg" else [args[index + 1]], "Cmd": ["postgres"] if role == "pg" else args[index + 3:], "Env": []},
            "HostConfig": {"NetworkMode": self.run_id + "-network", "Privileged": False, "CapAdd": None, "PortBindings": {},
                "Memory": {"pg": 256, "anvil": 192, "oracle": 192, "app": 512, "bootstrap": 256, "verify": 256}[role] * 1024**2,
                "ReadonlyRootfs": role != "pg", "CapDrop": ["CAP_NET_RAW"] if role == "pg" else ["ALL"], "SecurityOpt": ["no-new-privileges:true"]},
            "NetworkSettings": {"Networks": {self.run_id + "-network": {}}, "Ports": None}, "Mounts": mounts}

    def test_real_compile_builder_always_retains_parameters(self):
        for role in ("server", "oracle", "driver"):
            args = gate.javac_args(role, ("source.java",), "fixed-jars", Path("out"), Path("snapshot"), Path("lombok.jar"))
            self.assertIn("-parameters", args)
            self.assertIn("--release", args)
            self.assertIn("21", args)
            self.assertEqual("-processorpath" in args, role == "server")

    def test_source_profiles_separate_role_entrypoints(self):
        self.assertIn("deployment/CDirectOracleServer.java", gate.ORACLE_SOURCES)
        self.assertNotIn("deployment/CDirectDemoDriver.java", gate.ORACLE_SOURCES)
        self.assertNotIn("deployment/CDirectDemoApplication.java", gate.DRIVER_SOURCES)
        self.assertFalse(any("/src/test/" in p for p in (*gate.ORACLE_SOURCES, *gate.DRIVER_SOURCES, *gate.APP_DEPENDENCIES)))

    def test_resource_copy_is_root_and_classpath_exact(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp);snapshot = root / "snapshot";server = root / "server";driver = root / "driver"
            (server / "classes").mkdir(parents=True);driver.mkdir()
            for n, rel in enumerate(gate.SQL):
                p = snapshot / rel;p.parent.mkdir(parents=True, exist_ok=True);p.write_bytes(b"-- exact original " + str(n).encode())
            hashes = gate.copy_resources(snapshot, server, driver)
            self.assertEqual(4, len(hashes))
            for rel in gate.SQL:
                self.assertEqual((snapshot / rel).read_bytes(), (server / "classes" / Path(rel).name).read_bytes())
            missing = server / "classes" / Path(gate.SQL[0]).name
            missing.unlink()
            with self.assertRaisesRegex(ValueError, "SQL_RESOURCE_MISMATCH"):
                gate.verify_resources(snapshot, ((server, True), (driver, False)))

    def test_resource_corruption_fails_closed(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp);snapshot = root / "snapshot";server = root / "server";driver = root / "driver"
            (server / "classes").mkdir(parents=True);driver.mkdir()
            for rel in gate.SQL:
                p = snapshot / rel;p.parent.mkdir(parents=True, exist_ok=True);p.write_bytes(b"-- original")
            gate.copy_resources(snapshot, server, driver)
            (driver / Path(gate.SQL[0]).name).write_bytes(b"-- changed")
            with self.assertRaisesRegex(ValueError, "SQL_RESOURCE_MISMATCH"):
                gate.verify_resources(snapshot, ((server, True), (driver, False)))

    def test_server_classpath_rejects_trial_keys_entrypoint(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp);(root / "classes").mkdir();(root / "jars").mkdir()
            (root / "classes/CDirectDemoDriver.class").write_bytes(b"trial-only")
            with self.assertRaisesRegex(ValueError, "ROLE_CLASS_SEPARATION"):
                gate.check_role_tree(root, "server")

    def test_oracle_classpath_rejects_server_entrypoint(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp);(root / "classes").mkdir();(root / "jars").mkdir()
            (root / "classes/CDirectDemoApplication.class").write_bytes(b"server-only")
            with self.assertRaisesRegex(ValueError, "ROLE_CLASS_SEPARATION"):
                gate.check_role_tree(root, "oracle")

    def test_test_dependency_is_never_production(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp);(root / "classes").mkdir();(root / "jars").mkdir()
            (root / "classes/Production.class").write_bytes(b"production")
            (root / "jars/junit-jupiter-api-5.10.3.jar").write_bytes(b"test-only")
            with self.assertRaisesRegex(ValueError, "TEST_JARS"):
                gate.check_role_tree(root, "server")

    def test_web_requires_new_demo_entry_and_bounded_owned_tree(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            with self.assertRaisesRegex(ValueError, "WEB_ENTRY_MISSING"):
                gate.checked_web_tree(root)
            (root / "c-demo.html").write_text("<main>C demo</main>")
            self.assertEqual({"c-demo.html": gate.sha(root / "c-demo.html")}, gate.checked_web_tree(root))

    def test_all_roles_have_internal_only_owner_scoped_launch(self):
        for role in ("pg", "anvil", "oracle", "app", "bootstrap", "verify"):
            args = gate.launch_args(role, self.run_id, self.paths(), self.env_files())
            self.assertNotIn("-p", args)
            self.assertNotIn("--publish", args)
            self.assertIn("tms.c.owner=" + self.run_id, args)
            self.assertIn(self.run_id + "-network", args)
            self.assertIn("--pull=never", args)
            self.assertFalse(any("system" == a or "prune" == a or "--privileged" == a for a in args))

    def test_keyless_app_and_oracle_do_not_receive_driver_config_or_environment(self):
        paths = self.paths()
        app = gate.launch_args("app", self.run_id, paths, self.env_files())
        oracle = gate.launch_args("oracle", self.run_id, paths, self.env_files())
        self.assertNotIn("--env-file", app)
        self.assertNotIn("--env-file", oracle)
        self.assertFalse(any(str(paths["private"].resolve()) in a or "/driver-fixture" in a for a in app + oracle))
        self.assertFalse(any(str(paths["oracleConfig"].resolve()) in a for a in app))
        self.assertFalse(any(str(paths["appConfig"].resolve()) in a for a in oracle))
        self.assertIn("/server-fixture/classes:/server-fixture/jars/*", app)
        self.assertIn("/oracle-fixture/classes:/oracle-fixture/jars/*", oracle)

    def test_driver_transport_flags_are_present_once_and_not_in_app(self):
        for role in ("bootstrap", "verify"):
            args = gate.launch_args(role, self.run_id, self.paths(), self.env_files())
            for flag in gate.DRIVER_JVM:
                self.assertEqual(1, args.count(flag))
        app = gate.launch_args("app", self.run_id, self.paths(), self.env_files())
        self.assertTrue(all(flag not in app for flag in gate.DRIVER_JVM))

    def test_invalid_run_and_role_never_generate_launch(self):
        for invalid in ("old-run", "../cdemo-" + "a" * 16, "cdemo-" + "A" * 16):
            with self.assertRaisesRegex(ValueError, "RUN_ID"):
                gate.launch_args("app", invalid, self.paths(), self.env_files())
        with self.assertRaisesRegex(ValueError, "ROLE"):
            gate.launch_args("other-project", self.run_id, self.paths(), self.env_files())

    def test_actual_inspection_shape_and_capability_alias_are_strict(self):
        for role in ("pg", "anvil", "oracle", "app", "bootstrap", "verify"):
            gate.validate_container(self.inspection(role), role, self.run_id, self.paths(), self.env_files())
        pg = self.inspection("pg");pg["HostConfig"]["CapDrop"] = ["NET_RAW"]
        gate.validate_container(pg, "pg", self.run_id, self.paths(), self.env_files())
        pg["HostConfig"]["CapDrop"] = ["NET_RAW", "CAP_NET_RAW"]
        with self.assertRaisesRegex(ValueError, "PG_SCOPE"):
            gate.validate_container(pg, "pg", self.run_id, self.paths(), self.env_files())

    def test_server_extra_private_mount_or_driver_secret_environment_is_rejected(self):
        app = self.inspection("app")
        app["Mounts"].append({"Type": "bind", "Source": "C:/new-owned/private", "Destination": "/driver-private", "RW": False})
        with self.assertRaisesRegex(ValueError, "BIND_SEPARATION"):
            gate.validate_container(app, "app", self.run_id, self.paths(), self.env_files())
        app = self.inspection("app");app["Config"]["Env"] = ["C_DEMO_OWNER_PASSWORD=not-real-test-secret"]
        with self.assertRaisesRegex(ValueError, "ROLE_ENVIRONMENT"):
            gate.validate_container(app, "app", self.run_id, self.paths(), self.env_files())

    def test_bind_option_injection_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "BIND_FORMAT"):
            gate.bind(Path("C:/new-owned/private,readonly"), "/demo-config", True)


if __name__ == "__main__":
    unittest.main()
