"""Pure checkpoint profile/policy regressions, no Docker, RPC, original data or sockets."""
import copy
import importlib.util
from pathlib import Path
import unittest

import c_checkpoint_profile as profile

spec = importlib.util.spec_from_file_location("c_checkpoint_gate", Path(__file__).with_name("c-checkpoint-resume-gate.py"))
gate = importlib.util.module_from_spec(spec);spec.loader.exec_module(gate)


class PostTradeCheckpointTest(unittest.TestCase):
    def records(self):
        result = {"runId": profile.PARENT, "sourceIdentity": profile.SOURCE, "status": "READY_SAME_LOGICAL_STATE_USER_TRADES_NOT_ASSESSED", "safeError": None, "originalUnchanged": True,
                  "anvilState": {"sha256": profile.SNAPSHOT_SHA, "bytes": profile.SNAPSHOT_BYTES},
                  "stopped": {key: {"normalStop": True, "oomKilled": False, "exitCode": 143 if key == "app" else 0} for key in ("app", "oracle", "anvil", "pg", "copy")},
                  "preserved": {profile.PARENT + suffix: True for suffix in ("-network", "-pg-data", "-state")}}
        lineage = {"child": profile.PARENT, "originalDataParent": profile.DATA_PARENT, "recoverySourceIdentity": profile.SOURCE}
        manual = {"runId": profile.PARENT, "recoverySourceIdentity": profile.SOURCE, "status": "PASS_SELECTED_LOCAL_DIRECT_APPROVE_DEPOSIT_OPEN_CLOSE_WITHDRAW", "positionStatus": "CLOSED", "positionVersion": "2", "controllerUserNonce": "2", "eoaNonceAtLaterRead": "8", "pendingTransactionCountAtRead": 0,
                  "finalWalletE18": str(profile.EXPECTED["wallet"]), "finalHouseAndActualVaultTokensE18": str(profile.EXPECTED["house"]), "finalFreeE18": "0", "finalLockedE18": "0", "unpaidProfitE18": "0"}
        return result, lineage, manual

    def test_successful_post_trade_checkpoint_is_not_zero_trade_profile(self):
        profile.validate_parent(*self.records())
        self.assertEqual(profile.EXPECTED["eoaNonce"], 8);self.assertEqual(profile.EXPECTED["controllerNonce"], 2)
        self.assertEqual(profile.EXPECTED["drafts"], 3);self.assertEqual(profile.EXPECTED["executions"], 2)
        self.assertNotEqual(profile.PARENT, profile.DATA_PARENT)

    def test_missing_preservation_abnormal_stop_or_changed_snapshot_fail_closed(self):
        for mutation in (lambda r: r["preserved"].clear(), lambda r: r["stopped"]["anvil"].update(exitCode=137), lambda r: r["stopped"]["app"].update(normalStop=False), lambda r: r["anvilState"].update(sha256="0" * 64)):
            result, lineage, manual = self.records();mutation(result)
            with self.assertRaises(ValueError): profile.validate_parent(result, lineage, manual)

    def test_nonce_rollback_open_position_and_missing_profit_rejected(self):
        for key, value in (("controllerUserNonce", "0"), ("eoaNonceAtLaterRead", "3"), ("positionStatus", "ACTIVE"), ("finalWalletE18", "1000000000000000000000000"), ("pendingTransactionCountAtRead", 1)):
            result, lineage, manual = self.records();manual[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): profile.validate_parent(result, lineage, manual)

    def test_metadata_expected_counts_keep_unfinished_draft(self):
        value = {key: profile.EXPECTED[key] for key in ("drafts", "claims", "executions", "consents")};value.update(users=2, walletLinks=1)
        gate.verify_metadata(value)
        for key in ("drafts", "claims", "executions", "consents"):
            wrong = dict(value);wrong[key] += 1
            with self.subTest(key=key), self.assertRaises(ValueError): gate.verify_metadata(wrong)

    def test_metadata_digest_excludes_changing_synthetic_tables(self):
        query = gate.metadata_sql()
        self.assertIn("sha256", query);self.assertIn("to_jsonb", query)
        for table in ("drafts", "direct_execution_claims", "direct_executions", "consents", "wallet_links", "public.users"):
            self.assertIn(table, query)
        self.assertNotIn("synthetic_market_state", query);self.assertNotIn("UPDATE", query);self.assertNotIn("INSERT", query)

    def test_frozen_helper_and_no_automatic_window_or_user_execution(self):
        text = Path(gate.__file__).read_text(encoding="utf-8")
        self.assertIn('profile.SUCCESS_HELPER_SHA', text)
        self.assertNotIn('launch("bootstrap")', text);self.assertNotIn('rpc("eth_send', text)
        self.assertNotIn('rpc("evm_mine"', text)
        self.assertIn('reuse.clock_block_applied', text)
        self.assertIn('clock_requests == 0', text)
        self.assertIn('VERIFY_AND_STOP_NOT_USER_WINDOW', text)
        self.assertIn('after_metadata == before_metadata', text)
        self.assertIn('NO_LIVE_ANCESTOR', text)
        self.assertIn('proc.stdout.read(16 * 1024**2 + 10241)', text)
        self.assertNotIn('relay.wait()\n        status =', text)

    def test_terminal_economic_checkpoint_conserves_owner_profit_against_house(self):
        initial_wallet, initial_house = 10**24, 10**25
        self.assertEqual(profile.EXPECTED["wallet"] - initial_wallet, initial_house - profile.EXPECTED["house"])
        self.assertEqual(profile.EXPECTED["locked"] + profile.EXPECTED["free"] + profile.EXPECTED["unpaid"], 0)
        owner = "0x" + "a" * 40
        raw = "0x" + format(int(owner, 16), "064x") + "0" * 64 + format(1, "064x")
        gate.verify_terminal_lock(raw, owner)
        for wrong in (raw[:-64] + "0" * 64, raw[:66] + format(1, "064x") + raw[-64:], "0x1234"):
            with self.assertRaises(ValueError): gate.verify_terminal_lock(wrong, owner)
        with self.assertRaises(ValueError): gate.verify_terminal_lock(raw, "0x" + "b" * 40)


if __name__ == "__main__":
    unittest.main()
