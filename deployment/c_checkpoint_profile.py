"""Fixed, normally stopped post-user-trade checkpoint. Not the earlier zero-trade profile."""
import hashlib
import json
from pathlib import Path

PARENT = "cdemo-135ae9a88bb244c6"
DATA_PARENT = "cdemo-e0e5a24638b0416f"
SNAPSHOT_SHA = "eb9f99b9e305507d820f35b0e2c839b35877de91247c86ca98702b5e84fb50e6"
SNAPSHOT_BYTES = 14591003
SOURCE = "16c3d610c84c1c1687f43c4f65fef2acc6c9d354c18873a6ec4c539b4c0a0acd"
SUCCESS_HELPER_SHA = "592d23aa360eadd320fcd069e7d60ff9db2680240f12638b7d7785a6a26a93e6"
ANCHORS = {"result.json": "084c448324a41c5234d80556e6c315b3a7520a57b55bbf72c621e8969ccf3204",
           "recovery-lineage.json": "9f76266aa6d005349f8f64693f34f5eec1e422e5103bc7f91dd27d56f96a7c83",
           "manual-user-acceptance-2026-10-24.json": "6d4c5fede1d390f40f87eb2605ac796e665ac73dc313fed099716c736cc2e264"}
EXPECTED = {"eoaNonce": 8, "controllerNonce": 2, "wallet": 1000003066148919985598442,
            "free": 0, "locked": 0, "unpaid": 0, "house": 9999996933851080014401558,
            "positionStatus": 3, "positionVersion": 2, "drafts": 3, "claims": 3, "executions": 2, "consents": 0}


def require(ok, code):
    if not ok: raise ValueError("C_CHECKPOINT_" + code)


def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def read(path): return json.loads(path.read_text(encoding="utf-8"))
def quantity(value): return int(value, 16) if isinstance(value, str) and value.startswith("0x") else int(value)


def validate_parent(result, lineage, manual):
    require(result.get("runId") == PARENT and result.get("sourceIdentity") == SOURCE and result.get("status") == "READY_SAME_LOGICAL_STATE_USER_TRADES_NOT_ASSESSED" and result.get("safeError") is None and result.get("originalUnchanged") is True, "PARENT_RESULT")
    require(result.get("anvilState", {}).get("sha256") == SNAPSHOT_SHA and result["anvilState"].get("bytes") == SNAPSHOT_BYTES, "PARENT_SNAPSHOT")
    require(set(result.get("stopped", {})) == {"app", "oracle", "anvil", "pg", "copy"} and all(s.get("normalStop") is True and s.get("oomKilled") is False and s.get("exitCode") in {0, 143} for s in result["stopped"].values()) and set(result.get("preserved", {})) == {PARENT + suffix for suffix in ("-network", "-pg-data", "-state")} and all(result["preserved"].values()), "PARENT_NORMAL_STOP")
    require(lineage.get("child") == PARENT and lineage.get("originalDataParent") == DATA_PARENT and lineage.get("recoverySourceIdentity") == SOURCE and manual.get("runId") == PARENT and manual.get("recoverySourceIdentity") == SOURCE, "PARENT_LINEAGE")
    require(manual.get("status") == "PASS_SELECTED_LOCAL_DIRECT_APPROVE_DEPOSIT_OPEN_CLOSE_WITHDRAW" and manual.get("positionStatus") == "CLOSED" and manual.get("positionVersion") == "2" and manual.get("controllerUserNonce") == "2" and manual.get("eoaNonceAtLaterRead") == "8" and manual.get("pendingTransactionCountAtRead") == 0, "MANUAL_BOUNDARY")
    require(int(manual["finalWalletE18"]) == EXPECTED["wallet"] and int(manual["finalHouseAndActualVaultTokensE18"]) == EXPECTED["house"] and manual.get("finalFreeE18") == manual.get("finalLockedE18") == manual.get("unpaidProfitE18") == "0", "MANUAL_ASSETS")


def load(root, root_history):
    parent = root / "deployment/runtime/c-direct-resume" / PARENT
    require(all(sha(parent / rel) == digest for rel, digest in ANCHORS.items()), "ANCHORS")
    result, lineage, manual = (read(parent / rel) for rel in ANCHORS)
    validate_parent(result, lineage, manual)
    snapshot_path = parent / "anvil-state.private.json"
    require(snapshot_path.stat().st_size == SNAPSHOT_BYTES and sha(snapshot_path) == SNAPSHOT_SHA, "SNAPSHOT_BYTES")
    snapshot = read(snapshot_path)
    require(len(snapshot.get("transactions", [])) == 14 and quantity(snapshot["best_block_number"]) == 7333 and all(tx.get("receipt", {}).get("status") in {"0x0", "0x1"} for tx in snapshot["transactions"]), "SNAPSHOT_TERMINAL")
    history = {action + "_ROOT": tx for action, tx, _, _ in root_history}
    history.update(manual["transactions"])
    expected = {}
    for action, txhash in history.items():
        matches = [tx for tx in snapshot["transactions"] if tx["info"]["transaction_hash"] == txhash]
        require(len(matches) == 1 and quantity(matches[0]["receipt"]["status"]) == 1 and matches[0]["info"]["from"] == manual["owner"], "RECEIPT_ANCHOR")
        expected[action] = matches[0]
    require(len(expected) == 8, "EIGHT_USER_RECEIPTS")
    return parent, result, lineage, manual, snapshot_path, snapshot, expected
