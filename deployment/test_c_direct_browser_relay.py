"""No-socket/no-Docker policy regression. These do not claim live browser acceptance."""
import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from c_direct_browser_relay import BrowserRelay, JAVA_IMAGE, pipe_command, validate_plan, validate_target


class BrowserRelayPolicyTest(unittest.TestCase):
    def setUp(self):
        self.run = "cdemo-" + "a" * 16
        self.meta = {"scope": "C_BROWSER_NEW_OWNED_INTERNAL_RELAY", "runId": self.run,
                     "appId": "b" * 64, "networkId": "c" * 64, "sourceIdentity": "d" * 64,
                     "helperSha256": "e" * 64, "appPort": 32171, "rpcPort": 32172,
                     "seconds": 900, "origin": "http://127.0.0.1:32171"}
        self.app = {"Id": "b" * 64, "Name": "/" + self.run + "-app", "Image": JAVA_IMAGE,
                    "Config": {"User": "10001:10001", "Labels": {"tms.c.owner": self.run}},
                    "State": {"Running": True},
                    "HostConfig": {"NetworkMode": self.run + "-network", "ReadonlyRootfs": True,
                                   "CapDrop": ["ALL"], "SecurityOpt": ["no-new-privileges"]},
                    "NetworkSettings": {"Networks": {self.run + "-network": {"NetworkID": "c" * 64}}},
                    "Mounts": [{"Destination": "/server-fixture", "Type": "bind", "RW": False, "Source": "/new-owned/server"}]}
        self.network = {"Id": "c" * 64, "Internal": True, "Labels": {"tms.c.owner": self.run}}

    def test_valid_identity_has_fixed_fixture_helper(self):
        self.assertEqual(validate_target(self.meta, self.app, self.network), Path("/new-owned/server/classes/CDirectBrowserPipe.class"))

    def test_plan_scope_identity_ports_origin_and_lifetime_fail_closed(self):
        cases = {"scope": "OLD_FIXTURE", "runId": "old", "appId": "app-name", "networkId": "", "sourceIdentity": "0",
                 "helperSha256": "missing", "seconds": 3601, "origin": "http://0.0.0.0:32171", "appPort": True, "rpcPort": 32171}
        for key, value in cases.items():
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_plan(dict(self.meta, **{key: value}))

    def test_existing_renamed_stopped_or_other_image_app_refused(self):
        for key, value in (("Id", "f" * 64), ("Name", "/old-app"), ("Image", "untrusted"), ("State", {"Running": False})):
            app = copy.deepcopy(self.app);app[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): validate_target(self.meta, app, self.network)

    def test_noninternal_extra_network_and_wrong_owner_refused(self):
        network = dict(self.network, Internal=False)
        with self.assertRaises(ValueError): validate_target(self.meta, self.app, network)
        app = copy.deepcopy(self.app);app["NetworkSettings"]["Networks"]["bridge"] = {}
        with self.assertRaises(ValueError): validate_target(self.meta, app, self.network)
        app = copy.deepcopy(self.app);app["Config"]["Labels"]["tms.c.owner"] = "other"
        with self.assertRaises(ValueError): validate_target(self.meta, app, self.network)

    def test_privileged_writable_root_or_published_port_refused(self):
        for key, value in (("Privileged", True), ("CapAdd", ["NET_ADMIN"]), ("PortBindings", {"8080/tcp": [{}]}),
                           ("ReadonlyRootfs", False), ("CapDrop", []), ("SecurityOpt", [])):
            app = copy.deepcopy(self.app);app["HostConfig"][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): validate_target(self.meta, app, self.network)

    def test_helper_mount_must_be_readonly_bind(self):
        for key, value in (("RW", True), ("Type", "volume")):
            app = copy.deepcopy(self.app);app["Mounts"][0][key] = value
            with self.assertRaises(ValueError): validate_target(self.meta, app, self.network)

    def test_command_cannot_select_arbitrary_host_or_shell(self):
        for role in ("APP", "RPC"):
            command = pipe_command("b" * 64, role)
            self.assertEqual(command[:5], ["docker", "exec", "-i", "--user", "10001:10001"])
            self.assertEqual(command[-3:], ["CDirectBrowserPipe", role, "120"])
            self.assertNotIn("sh", command)
        for target, role in (("name", "APP"), ("b" * 64, "PG"), ("b" * 64, "outside:443")):
            with self.assertRaises(ValueError): pipe_command(target, role)

    def test_construction_opens_no_socket_process_or_network(self):
        with tempfile.TemporaryDirectory() as directory, patch("socket.socket", side_effect=AssertionError("socket")), patch("subprocess.Popen", side_effect=AssertionError("process")):
            relay = BrowserRelay(self.meta, directory)
            self.assertEqual(relay.accepted, 0)
            relay.close()

    def test_long_session_connection_budget_remains_finite_and_concurrency_unchanged(self):
        with tempfile.TemporaryDirectory() as directory:
            for seconds,cap in ((900,512),(1800,1024),(3600,2048)):
                relay=BrowserRelay(dict(self.meta,seconds=seconds),directory)
                self.assertEqual(relay.max_connections,cap)
                for role in ("APP","RPC"):
                    self.assertTrue(relay.slots[role].acquire(blocking=False));self.assertTrue(relay.slots[role].acquire(blocking=False))
                    self.assertFalse(relay.slots[role].acquire(blocking=False))
                    relay.slots[role].release();relay.slots[role].release()
                relay.close()

    def test_existing_stop_marker_blocks_new_listener(self):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, "STOP").write_text("PRESERVE\n", encoding="ascii")
            with self.assertRaises(ValueError): BrowserRelay(self.meta, directory)

    def test_shared_shutdown_deadline_not_three_seconds_per_thread(self):
        with tempfile.TemporaryDirectory() as directory:
            relay = BrowserRelay(self.meta, directory)
            joins = []
            class FakeThread:
                def join(self, seconds): joins.append(seconds)
            relay.threads = [FakeThread() for _ in range(10)]
            relay.end = 1000
            # First monotonic fixes the shared deadline; later joins have decreasing remaining budget.
            with patch.object(relay.done, "wait", return_value=True), patch("c_direct_browser_relay.time.monotonic", side_effect=[10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20]):
                relay.wait()
            self.assertEqual(joins, [2, 1, 0, 0, 0, 0, 0, 0, 0, 0])

    def test_unconfirmed_exec_stop_is_not_normal_success(self):
        with tempfile.TemporaryDirectory() as directory:
            relay = BrowserRelay(self.meta, directory)
            class Child:
                def poll(self): return None
                def terminate(self): pass
            relay.children = [Child()]
            relay.end = 1000
            with patch.object(relay.done, "wait", return_value=True), self.assertRaisesRegex(ValueError, "EXEC_STOP_UNCONFIRMED"):
                relay.wait()

    def test_rpc_saturation_cannot_consume_app_capacity_and_four_is_total_bound(self):
        with tempfile.TemporaryDirectory() as directory:
            relay = BrowserRelay(self.meta, directory)
            for role in ("RPC","APP"):
                self.assertTrue(relay.slots[role].acquire(blocking=False))
                self.assertTrue(relay.slots[role].acquire(blocking=False))
                self.assertFalse(relay.slots[role].acquire(blocking=False))
            for role in ("RPC","APP"):
                relay.slots[role].release();relay.slots[role].release()
            relay.close()


if __name__ == "__main__":
    unittest.main()
