"""Pure clone policy tests. No Docker, RPC, socket, original key or snapshot access."""
import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("c_snapshot_clone", Path(__file__).with_name("c-anvil-snapshot-clone-check.py"))
gate = importlib.util.module_from_spec(spec);spec.loader.exec_module(gate)


class AnvilSnapshotClonePolicyTest(unittest.TestCase):
    def setUp(self):
        self.name = "cclone-" + "a" * 16
        self.snapshot, self.output = Path("fresh/snapshot.json").resolve(), Path("fresh/output").resolve()
        self.info = {"Name": "/" + self.name, "Image": gate.IMAGE,
                     "Config": {"Labels": {"tms.c.clone-owner": self.name}, "User": "0:0", "Entrypoint": ["/usr/local/bin/anvil"], "Cmd": gate.anvil_args()},
                     "HostConfig": {"NetworkMode": "none", "ReadonlyRootfs": True, "Memory": 192 * 1024**2, "CapDrop": ["ALL"], "SecurityOpt": ["no-new-privileges"]},
                     "NetworkSettings": {"Networks": {"none": {}}},
                     "Mounts": [{"Destination": "/snapshot/anvil-state.private.json", "Source": str(self.snapshot), "Type": "bind", "RW": False}, {"Destination": "/output", "Source": str(self.output), "Type": "bind", "RW": True}]}

    def test_valid_network_none_readonly_snapshot(self):
        gate.validate_container(self.info, self.name, self.snapshot, self.output)

    def test_no_egress_or_host_ports(self):
        for key, value in (("NetworkMode", "bridge"), ("PortBindings", {"8545/tcp": [{}]}), ("Privileged", True), ("CapAdd", ["NET_ADMIN"]), ("ReadonlyRootfs", False)):
            info = copy.deepcopy(self.info);info["HostConfig"][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): gate.validate_container(info, self.name, self.snapshot, self.output)

    def test_old_volume_or_writable_original_refused(self):
        for key, value in (("RW", True), ("Type", "volume"), ("Source", "old-volume")):
            info = copy.deepcopy(self.info);info["Mounts"][0][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): gate.validate_container(info, self.name, self.snapshot, self.output)

    def test_wrong_owner_image_and_mutated_command_refused(self):
        for field in ("owner", "image", "command"):
            info = copy.deepcopy(self.info)
            if field == "owner": info["Config"]["Labels"]["tms.c.clone-owner"] = "old"
            if field == "image": info["Image"] = "host-anvil-1.5.1"
            if field == "command": info["Config"]["Cmd"] += ["--timestamp", "1"]
            with self.subTest(field=field), self.assertRaises(ValueError): gate.validate_container(info, self.name, self.snapshot, self.output)

    def test_time_mutations_and_transaction_methods_not_allowed(self):
        for method in ("evm_increaseTime", "evm_setNextBlockTimestamp", "anvil_setBlockTimestampInterval", "anvil_setBalance", "anvil_mine", "eth_sendRawTransaction", "eth_sendTransaction", "personal_sign"):
            self.assertNotIn(method, gate.READ_METHODS)
        args = gate.anvil_args()
        self.assertIn("--load-state", args);self.assertIn("--dump-state", args)
        self.assertNotIn("--timestamp", args)
        self.assertEqual(args[args.index("--accounts") + 1], "0")

    def test_independent_expected_cash_events(self):
        identity = {"token": {"address": "0x" + "1" * 40}, "vault": {"address": "0x" + "2" * 40}}
        owner = "0x" + "3" * 40
        topics = {key: "0x" + value * 64 for key, value in (("Approval", "a"), ("Transfer", "b"), ("Deposited", "c"), ("Withdrawn", "d"))}
        approve = gate.expected_logs("APPROVE", identity, owner, topics)
        self.assertEqual(len(approve), 1);self.assertEqual(approve[0]["topics"][1], "0x" + gate.address_word(owner))
        deposit, withdraw = (gate.expected_logs(action, identity, owner, topics) for action in ("DEPOSIT", "WITHDRAW"))
        self.assertEqual(deposit[0]["topics"][1:], list(reversed(withdraw[0]["topics"][1:])))
        self.assertEqual(deposit[1]["topics"][0], topics["Deposited"])
        self.assertEqual(withdraw[1]["topics"][0], topics["Withdrawn"])
        self.assertEqual(gate.quantity(withdraw[1]["data"]), 3000 * 10**18)

    def test_history_is_fixed_three_successful_user_cash_transactions(self):
        self.assertEqual([item[0] for item in gate.HISTORY], ["APPROVE", "DEPOSIT", "WITHDRAW"])
        self.assertEqual(len({item[1] for item in gate.HISTORY}), 3)
        self.assertEqual([item[2] for item in gate.HISTORY], [2554, 3026, 3654])
        self.assertEqual(gate.quantity("0x3"), 3)

    def test_exact_decimal_scaling_and_address(self):
        self.assertEqual(len(gate.word(10**24)), 64)
        self.assertEqual(len(gate.address_word("0x" + "a" * 40)), 64)
        with self.assertRaises(ValueError): gate.address_word("0x1234")


if __name__ == "__main__":
    unittest.main()
