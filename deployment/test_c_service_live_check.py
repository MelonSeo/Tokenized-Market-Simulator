"""Pure preparation/argv tests. No Docker, process, socket, PG or RPC execution."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("c_service_live_check", Path(__file__).with_name("c-service-live-check.py"))
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)


class CServiceLivePreparationTest(unittest.TestCase):
    def setUp(self):
        self.run = "c-live-" + "1" * 16
        self.paths = {key: Path("C:/new-owned/" + key) for key in ("server", "driver", "private", "config", "control", "serverEvidence")}

    def argv(self, kind):
        return gate.launch_args(kind, self.run, self.paths, Path("C:/new-owned/driver.private.env"), Path("C:/new-owned/pg.private.env"))

    def info(self, kind):
        args = self.argv(kind)
        image = gate.PG_IMAGE if kind == "pg" else gate.FOUNDRY_IMAGE if kind == "anvil" else gate.JAVA_IMAGE
        index = args.index(image)
        mounts = []
        for i, arg in enumerate(args):
            if arg != "--mount":
                continue
            values = dict(pair.split("=", 1) for pair in args[i + 1].split(",") if "=" in pair)
            m = {"Destination": values["target"], "Type": values["type"], "RW": "readonly" not in args[i + 1].split(",")}
            if m["Type"] == "volume":
                m["Name"] = values["source"]
            else:
                m["Source"] = str(Path(values["source"]).resolve())
            mounts.append(m)
        return {"Image": image, "Config": {"Labels": {"tms.c.owner": self.run}, "Entrypoint": ["java"] if kind in {"api", "driver"} else ["/usr/local/bin/anvil"] if kind == "anvil" else ["docker-entrypoint.sh"],
            "User": "10001:10001" if kind in {"api", "driver"} else "" if kind == "pg" else "0:0", "Cmd": args[index + 1:], "Env": []},
            "HostConfig": {"NetworkMode": self.run + "-network", "PortBindings": {}, "Privileged": False, "ReadonlyRootfs": kind != "pg",
                "Memory": (384 if kind == "driver" else 512 if kind == "api" else 256) * 1024**2, "CapDrop": ["NET_RAW"] if kind == "pg" else ["ALL"], "SecurityOpt": [] if kind == "pg" else ["no-new-privileges"]},
            "NetworkSettings": {"Networks": {self.run + "-network": {}}, "Ports": {}}, "Mounts": mounts}

    def test_minimum_ram_not_lowered_and_disk_is_required(self):
        self.assertEqual(gate.MIN_RAM, 1920 * 1024**2)
        gate.resource_guard(gate.MIN_RAM, gate.MIN_DISK)
        with self.assertRaisesRegex(ValueError, "C_SERVICE_RAM_LOW"):
            gate.resource_guard(gate.MIN_RAM - 1, gate.MIN_DISK)
        with self.assertRaisesRegex(ValueError, "C_SERVICE_DISK_LOW"):
            gate.resource_guard(gate.MIN_RAM, gate.MIN_DISK - 1)

    def test_run_identity_cannot_target_existing_generic_names(self):
        for name in ("exchange", "canvil-" + "1" * 16, "../c-live-" + "1" * 16, "c-live-" + "1" * 15):
            with self.assertRaises(ValueError):
                gate.launch_args("api", name, self.paths, Path("d"), Path("p"))

    def test_every_container_is_owned_internal_unpublished_and_pull_never(self):
        for kind in ("pg", "anvil", "driver", "api"):
            args = self.argv(kind)
            self.assertIn("--pull=never", args)
            if kind == "pg":
                self.assertNotIn("--read-only", args)
                self.assertIn("--cap-drop=NET_RAW", args)
                self.assertFalse(any(arg.startswith("--cap-add") for arg in args))
            else:
                self.assertIn("--read-only", args)
                self.assertIn("--cap-drop=ALL", args)
            self.assertNotIn("-p", args)
            self.assertNotIn("--network=host", args)
            gate.validate_container(self.info(kind), kind, self.run, self.paths)

    def test_pg_actual_inspect_capability_spelling_preserves_exact_single_drop(self):
        for spelling in ("NET_RAW", "CAP_NET_RAW"):
            info = self.info("pg")
            info["HostConfig"]["CapDrop"] = [spelling]
            gate.validate_container(info, "pg", self.run, self.paths)
        for invalid in (None, [], "NET_RAW", ["ALL"], ["CAP_NET_ADMIN"], ["NET_RAW", "CAP_NET_RAW"],
                        ["NET_RAW", "NET_RAW"], ["CAP_NET_RAW", "CAP_NET_RAW"], ["CAP_NET_RAW", "ALL"]):
            info = self.info("pg")
            info["HostConfig"]["CapDrop"] = invalid
            with self.subTest(cap_drop=invalid), self.assertRaisesRegex(ValueError, "PG_INITIALIZATION_SCOPE"):
                gate.validate_container(info, "pg", self.run, self.paths)
        for mutate in (
            lambda i: i["HostConfig"].update(CapAdd=["NET_ADMIN"]),
            lambda i: i["HostConfig"].update(ReadonlyRootfs=True),
            lambda i: i["HostConfig"].update(Privileged=True),
            lambda i: i["Config"].update(User="10001:10001"),
        ):
            info = self.info("pg")
            info["HostConfig"]["CapDrop"] = ["CAP_NET_RAW"]
            mutate(info)
            with self.assertRaises(ValueError):
                gate.validate_container(info, "pg", self.run, self.paths)

    def test_driver_keys_and_c5_classpath_cannot_be_mounted_in_server(self):
        args = self.argv("api")
        self.assertNotIn("--env-file", args)
        text = "|".join(args)
        self.assertNotIn("driver-private", text)
        self.assertNotIn("/fixture/classes", text)
        self.assertNotIn("CAnvil", text)
        for value in gate.DRIVER_JVM_ARGS:
            self.assertNotIn(value, args)
        bad = self.info("api")
        bad["Mounts"].append({"Type": "bind", "Source": str(self.paths["private"]), "Destination": "/driver-private", "RW": False})
        with self.assertRaisesRegex(ValueError, "SEPARATION"):
            gate.validate_container(bad, "api", self.run, self.paths)

    def test_server_environment_cannot_inherit_driver_or_external_keys(self):
        for entry in ("C_GATE_OWNER_PASSWORD=private", "USER_PRIVATE_KEY=private", "OPENAI_API_KEY=private"):
            bad = self.info("api")
            bad["Config"]["Env"] = [entry]
            with self.assertRaisesRegex(ValueError, "SERVER_SECRET_SCOPE"):
                gate.validate_container(bad, "api", self.run, self.paths)

    def test_runtime_inspect_rejects_ports_image_uid_owner_and_memory_changes(self):
        for mutate in (
            lambda i: i["HostConfig"].update(PortBindings={"8080/tcp": [{"HostPort": "8080"}]}),
            lambda i: i.update(Image="sha256:" + "0" * 64),
            lambda i: i["Config"].update(User="0:0"),
            lambda i: i["Config"]["Labels"].update({"tms.c.owner": "other"}),
            lambda i: i["HostConfig"].update(Memory=0),
            lambda i: i["HostConfig"].update(ReadonlyRootfs=False),
        ):
            info = self.info("api"); mutate(info)
            with self.assertRaises(ValueError):
                gate.validate_container(info, "api", self.run, self.paths)

    def test_driver_retry_settings_are_process_local_and_compiled_scope_is_separate(self):
        args = self.argv("driver")
        for value in gate.DRIVER_JVM_ARGS:
            self.assertEqual(1, args.count(value))
        self.assertIn("/fixture/classes:/server-fixture/classes:/server-fixture/jars/*", args)
        self.assertNotIn("deployment/CAnvilContractGate.java", gate.SERVER_SOURCES)
        self.assertNotIn("deployment/CCustodyLiveDriver.java", gate.SERVER_SOURCES)
        self.assertTrue(all("/src/test/" not in p for p in gate.SERVER_SOURCES))

    def test_prepared_server_tree_rejects_driver_and_test_artifacts(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); (root / "classes").mkdir(); (root / "jars").mkdir()
            (root / "classes/CCustodyLiveApplication.class").write_bytes(b"compile fixture only")
            gate.assert_server_tree(root)
            (root / "classes/CAnvilContractGate.class").write_bytes(b"must reject")
            with self.assertRaisesRegex(ValueError, "CLASS_SEPARATION"):
                gate.assert_server_tree(root)

    def test_argument_file_quoting_cannot_add_another_command_or_switch(self):
        self.assertEqual('"C:/safe/new file"', gate.quote(Path("C:/safe/new file")))
        for value in ('unsafe"quote', "unsafe\nnew-arg", "unsafe\0"):
            with self.assertRaises(ValueError):
                gate.quote(value)

    def test_inspect_password_jwt_and_raw_log_values_are_never_copied_to_evidence(self):
        password, token = "PRIVATE_PASSWORD_SENTINEL_849ab", "PRIVATE_JWT_SENTINEL_551cd"
        observed = self.info("api")
        observed["Config"]["Env"] = ["POSTGRES_PASSWORD=" + password, "C_GATE_JWT_SECRET=" + token, token]
        observed["Config"]["Cmd"] = [password, token]
        observed["Config"]["Labels"]["unrelated"] = password
        observed["State"] = {"Running": False, "OOMKilled": False, "ExitCode": 1, "Error": token}
        record = {"command": gate.safe_command(["container", "inspect", self.run + "-api"], (password, token)),
            **gate.command_observation(["container", "inspect", self.run + "-api"], json.dumps([observed]), password + token)}
        encoded = json.dumps(record)
        self.assertNotIn(password, encoded); self.assertNotIn(token, encoded)
        self.assertIn("POSTGRES_PASSWORD", encoded); self.assertIn("C_GATE_JWT_SECRET", encoded)
        self.assertNotIn("stdout\"", encoded); self.assertNotIn("stderr\"", encoded)
        logs = gate.command_observation(["logs", self.run + "-api"], password + token, token)
        self.assertNotIn(password, json.dumps(logs)); self.assertNotIn(token, json.dumps(logs))

    def test_malformed_inspection_never_preserves_raw_input_or_parse_exception(self):
        secret = "PRIVATE_MALFORMED_INSPECT_SENTINEL"
        record = gate.command_observation(["image", "inspect", gate.JAVA_IMAGE], "{bad:" + secret, secret)
        self.assertEqual(record["inspection"], "UNPARSED_INSPECTION_NO_RAW_RETAINED")
        self.assertNotIn(secret, json.dumps(record))

    def test_private_values_or_inline_environment_in_command_are_rejected_before_logging(self):
        secret = "PRIVATE_ARG_SENTINEL"
        for args in (["run", "--env", "SECRET=" + secret], ["run", "-e", "SECRET=" + secret], ["run", "--env=SECRET=" + secret], ["exec", self.run + "-driver", "echo", secret]):
            with self.assertRaises(ValueError):
                gate.safe_command(args, (secret,))

    def test_actual_server_compile_builder_retains_path_variable_parameter_metadata(self):
        snapshot, output, lombok = Path("C:/new-owned/source-snapshot"), Path("C:/new-owned/server/classes"), Path("C:/cached/lombok.jar")
        args = gate.javac_args("server", "fixed-jar-classpath", output, snapshot, lombok)
        self.assertEqual(1, args.count("-parameters"))
        self.assertIn(str(snapshot / gate.BASE / "custody/api/CCustodyController.java"), args)
        self.assertEqual(str(lombok), args[args.index("-processorpath") + 1])
        self.assertIn("lombok.launch.AnnotationProcessorHider$AnnotationProcessor", args)
        self.assertEqual("fixed-jar-classpath", args[args.index("-cp") + 1])
        driver = gate.javac_args("driver", "fixed-driver-classpath", output, snapshot, lombok)
        self.assertNotIn("-parameters", driver)
        self.assertIn("-proc:none", driver)
        with self.assertRaises(ValueError):
            gate.javac_args("unknown", "fixed", output, snapshot, lombok)


if __name__ == "__main__":
    unittest.main()
