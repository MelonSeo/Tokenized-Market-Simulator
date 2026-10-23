"""Small live-successor validation/preservation adapter; frozen Gate source stays unchanged."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import re
import tarfile
from types import SimpleNamespace

STATE_CAP = 32 * 1024**2  # Live-only cap; the old Gate/helper remains 16 MiB.
FROZEN_HELPER_PATHS = frozenset({"deployment/c-checkpoint-resume-gate.py", "deployment/c_checkpoint_profile.py",
                               "deployment/c-direct-same-state-resume.py", "deployment/c-direct-browser-run.py",
                               "deployment/c-anvil-snapshot-clone-check.py"})


def require(ok, code):
    if not ok: raise ValueError("C_LIVE_" + code)


def module(root, filename, name):
    spec = importlib.util.spec_from_file_location(name, root / "deployment" / filename)
    result = importlib.util.module_from_spec(spec);spec.loader.exec_module(result)
    return result


def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def read(path): return json.loads(path.read_text(encoding="utf-8"))
def q(value): return int(value, 16) if isinstance(value, str) and value.startswith("0x") else int(value)


def verify_frozen(root, p):
    require(set(p.get("frozenHelpers", {})) == FROZEN_HELPER_PATHS and all(sha(root / rel) == digest for rel, digest in p["frozenHelpers"].items()), "FROZEN_HELPERS")


def extract_live_state_tar(data):
    require(len(data) <= STATE_CAP + 10240, "STATE_ARCHIVE_CAP")
    try:
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:") as archive:
            item = archive.next()
            require(item is not None and item.isfile() and item.name == "anvil.json" and 0 < item.size <= STATE_CAP, "STATE_MEMBER")
            content = archive.extractfile(item).read(STATE_CAP + 1)
            require(len(content) == item.size and archive.next() is None, "STATE_ARCHIVE_SHAPE")
            return content
    except (tarfile.TarError, OSError):
        raise ValueError("C_LIVE_STATE_ARCHIVE_INVALID") from None


def load_context(root):
    p = read(root / "deployment/c-live-latest-checkpoint.json")
    require(p.get("scope") == "C_LOCAL_EXPLICIT_LIVE_SUCCESSOR_CHECKPOINT" and re.fullmatch(r"cdemo-[0-9a-f]{16}", p.get("parent", "")) and p.get("sessionSeconds") == 3600 and (p.get("appPort"), p.get("rpcPort")) == (32171, 32172), "PROFILE")
    verify_frozen(root, p)
    base = module(root, "c-direct-demo-check.py", "c_live_base")
    browser = module(root, "c-direct-browser-run.py", "c_live_browser")
    reuse = module(root, "c-direct-same-state-resume.py", "c_live_reuse")
    gate = module(root, "c-checkpoint-resume-gate.py", "c_live_gate")
    history = module(root, "c-anvil-snapshot-clone-check.py", "c_live_history")
    original, _, identity, owner, _, _, data_source = history.load_original(root)
    require(data_source == p["dataSource"] and p["dataRun"] == history.ORIGINAL, "DATA_IDENTITY")
    require(read(original / "execution-identity.json")["sources"]["deployment/c-direct-browser-run.py"] == p["frozenHelpers"]["deployment/c-direct-browser-run.py"], "ORIGINAL_BROWSER_HELPER_ANCHOR")
    parent = root / p["parentDirectory"]
    require(parent.name == p["parent"] and all(sha(parent / rel) == digest for rel, digest in p["anchors"].items()), "PARENT_ANCHORS")
    result, lineage = read(parent / "result.json"), read(parent / "checkpoint-lineage.json")
    require(result.get("runId") == p["parent"] and result.get("status") == "PASS_READ_ONLY_CHECKPOINT_SERVICE_RECOVERY_NOT_NEW_USER_TRADE_OR_FULL_AUDIT" and result.get("safeError") is None and result.get("sourceIdentity") == lineage.get("sourceIdentity") == p["parentSource"] and result.get("originalUnchanged") is True and result.get("newTransactions") == result.get("financialMetadataWrites") == 0, "PARENT_PASS")
    require(set(result.get("stopped", {})) == {"copy", "pg", "anvil", "oracle", "app"} and all(s.get("normalStop") is True and s.get("oomKilled") is False and s.get("exitCode") in {0, 143} for s in result["stopped"].values()) and set(result.get("preserved", {})) == {p["parent"] + s for s in ("-network", "-pg-data", "-state")} and all(result["preserved"].values()), "PARENT_STOPPED")
    snapshot_path = parent / "anvil-state.private.json"
    require(sha(snapshot_path) == result["anvilState"]["sha256"] == p["snapshotSha256"] and snapshot_path.stat().st_size == p["snapshotBytes"] == result["anvilState"]["bytes"], "LATEST_SNAPSHOT")
    snapshot = read(snapshot_path)
    require(q(snapshot["best_block_number"]) == p["snapshotHead"] and len(snapshot.get("transactions", [])) == p["snapshotTransactionCount"] and all(tx.get("receipt", {}).get("status") in {"0x0", "0x1"} for tx in snapshot["transactions"]), "STORED_TRANSACTIONS")
    manual_path = root / p["manualDirectory"] / "manual-user-acceptance-2026-10-24.json"
    require(sha(manual_path) == p["manualSha256"], "MANUAL_ANCHOR");manual = read(manual_path)
    require(manual["owner"] == owner and manual["positionStatus"] == "CLOSED" and manual["controllerUserNonce"] == "2" and manual["eoaNonceAtLaterRead"] == "8", "MANUAL_STATE")
    user_hashes = {action + "_ROOT": tx for action, tx, _, _ in history.HISTORY};user_hashes.update(manual["transactions"])
    receipts = {}
    for action, txhash in user_hashes.items():
        matches = [tx for tx in snapshot["transactions"] if tx["info"]["transaction_hash"] == txhash]
        require(len(matches) == 1 and matches[0]["info"]["from"] == owner and q(matches[0]["receipt"]["status"]) == 1, "EIGHT_RECEIPT_ANCHORS");receipts[action] = matches[0]
    require(len(receipts) == 8, "EIGHT_RECEIPTS")
    prepared = root / "deployment/runtime/c-direct-browser-prepare" / browser.PREPARED_ID;prep = read(prepared / "result.json")
    require(prep["sourceIdentity"] == read(original / "execution-identity.json")["preparedSourceIdentity"], "COMPILED_IDENTITY")
    c = SimpleNamespace(root=root, p=p, base=base, browser=browser, reuse=reuse, gate=gate, history=history, original=original, parent=parent,
                        identity=identity, owner=owner, data_source=data_source, snapshot_path=snapshot_path, snapshot=snapshot, receipts=receipts,
                        manual=manual, lineage=lineage, prepared=prepared, prep=prep)
    verify_artifacts(c)
    return c


def verify_artifacts(c):
    verify_frozen(c.root, c.p)  # Recheck dynamic helpers before claim and at shutdown as well as startup.
    b, prep = c.base, c.prep
    require(all(b.sha(c.root / rel) == digest for rel, digest in prep["sources"].items()) and all(b.tree_hashes(c.prepared / (r + "-fixture")) == hs for r, hs in prep["fixtureHashes"].items()) and b.checked_web_tree(c.prepared / "web-fixture") == prep["webHashes"], "SOURCE_ARTIFACT_HASH")
    recorded_server = {p for p in prep["sources"] if p.startswith(b.BASE + "custody/")} | set(b.APP_DEPENDENCIES) | {"deployment/CDirectDemoApplication.java"}
    recorded_web = {p for p in prep["sources"] if p.startswith(b.WEB)}
    require(set(b.server_sources(c.root)) == recorded_server and set(b.web_sources(c.root)) == recorded_web, "EXACT_SOURCE_INVENTORY")


def validate_anvil(c, info, args, run_id):
    co, h, n = info["Config"], info["HostConfig"], info["NetworkSettings"];at = args.index("--entrypoint")
    require(info["Image"] == c.base.FOUNDRY_IMAGE and co.get("User") == "0:0" and h.get("NetworkMode") == run_id + "-network" and set(n.get("Networks", {})) == {run_id + "-network"} and not h.get("PortBindings") and not any((n.get("Ports") or {}).values()) and not h.get("Privileged") and not h.get("CapAdd") and h.get("CapDrop") == ["ALL"] and h.get("Memory") == 192 * 1024**2 and h.get("ReadonlyRootfs") is True and any(v in {"no-new-privileges", "no-new-privileges:true"} for v in h.get("SecurityOpt", [])), "ANVIL_ISOLATION")
    require(co.get("Entrypoint") == [args[at + 1]] and co.get("Cmd") == args[at + 3:], "ANVIL_COMMAND")
    ms = {m["Destination"]: m for m in info["Mounts"] if m.get("Type") != "tmpfs"}
    require(set(ms) == {"/state", "/snapshot/anvil-state.private.json"} and ms["/state"].get("Type") == "volume" and ms["/state"].get("Name") == run_id + "-state" and ms["/state"].get("RW") is True and ms["/snapshot/anvil-state.private.json"].get("Type") == "bind" and ms["/snapshot/anvil-state.private.json"].get("RW") is False and c.reuse.mount_path(ms["/snapshot/anvil-state.private.json"]["Source"]) == c.reuse.mount_path(c.snapshot_path), "ANVIL_MOUNTS")


def chain_observation(c, rpc, keccak, raw_call, initial):
    i, owner = c.identity, c.owner;token, controller, vault = (i[k]["address"] for k in ("token", "controller", "vault"))
    require(q(rpc("eth_chainId")) == 31337 and rpc("eth_getBlockByNumber", "0x0", False)["hash"] == i["genesisHash"], "GENESIS")
    for role in ("token", "controller", "rules", "vault"): require(keccak(rpc("eth_getCode", i[role]["address"], "latest")) == i[role]["codeHash"], "FOUR_CODEHASHES")
    aw = c.history.address_word
    state = {"eoaNonce": q(rpc("eth_getTransactionCount", owner, "latest")), "pendingNonce": q(rpc("eth_getTransactionCount", owner, "pending")), "controllerNonce": q(raw_call(controller, "nonces(address)", aw(owner))),
             "wallet": q(raw_call(token, "balanceOf(address)", aw(owner))), "free": q(raw_call(vault, "freeBalance(address)", aw(owner))), "locked": q(raw_call(vault, "lockedBalance(address)", aw(owner))), "house": q(raw_call(vault, "houseBalance()")), "unpaid": q(raw_call(vault, "totalUnpaidProfit()")),
             "actualVaultTokens": q(raw_call(token, "balanceOf(address)", aw(vault))), "recognizedAssets": q(raw_call(vault, "recognizedAssets()")), "unassignedAssets": q(raw_call(vault, "unassignedAssets()")), "pendingCount": len(rpc("eth_pendingTransactions"))}
    if initial:
        require(all(state[k] == c.gate.profile.EXPECTED[k] for k in ("eoaNonce", "controllerNonce", "wallet", "free", "locked", "house", "unpaid")) and state["pendingNonce"] == 8 and state["pendingCount"] == 0 and state["actualVaultTokens"] == state["recognizedAssets"] == state["house"] and state["unassignedAssets"] == 0 and q(raw_call(vault, "totalLocked()")) == 0, "INITIAL_ASSETS_NONCE")
        account = keccak(keccak("TMS_C_FUTURES_WALLET_ACCOUNT_V1") + aw(owner) + i["symbolHash"][2:])
        require(q(raw_call(i["rules"]["address"], "activePosition(bytes32)", account[2:])) == 0 and q(rpc("eth_getBalance", owner, "latest")) == q(c.snapshot["accounts"][owner]["balance"]), "INITIAL_INACTIVE")
        c.gate.verify_terminal_lock(raw_call(vault, "positionLocks(bytes32)", c.manual["positionId"][2:]), owner)
        pos = raw_call(i["rules"]["address"], "getPosition(bytes32)", c.manual["positionId"][2:]);words = [int(pos[k:k + 64], 16) for k in range(2, len(pos), 64)]
        require(len(words) == 11 and words[6:8] == [3, 2], "CLOSED_V2")
    return {k: str(v) for k, v in state.items()}


def verify_receipts(c, rpc):
    for expected in c.receipts.values():
        txhash = expected["info"]["transaction_hash"];number = q(expected["block_number"]);blockhash = expected["block_hash"]
        receipt = rpc("eth_getTransactionReceipt", txhash);tx = rpc("eth_getTransactionByHash", txhash);block = rpc("eth_getBlockByNumber", hex(number), False)
        require(receipt and tx and q(receipt["status"]) == 1 and receipt["transactionHash"] == txhash and receipt["blockHash"] == blockhash == block["hash"] and q(receipt["blockNumber"]) == number and txhash in block["transactions"] and tx["from"] == c.owner and tx["to"] == expected["info"]["to"] and q(tx["nonce"]) == q(expected["info"]["nonce"]) and c.history.logs_projection(receipt["logs"]) == c.history.logs_projection(expected["receipt"]["logs"]), "EIGHT_CANONICAL_RECEIPTS")


def verify_http(c, request, jwt, deployment):
    require(json.loads(request("/api/me", jwt=jwt)).get("id") == "1" and json.loads(request("/api/c-futures/wallet", jwt=jwt)).get("wallet") == c.owner, "JWT_WALLET")
    config = json.loads(request("/api/c-futures/config", jwt=jwt));account = json.loads(request("/api/c-futures/account", jwt=jwt))["value"];position = json.loads(request("/api/c-futures/positions/" + c.manual["positionId"], jwt=jwt))["value"];market = json.loads(request("/api/c-futures/direct/market", jwt=jwt))
    require(config.get("deploymentId") == deployment and account["walletBalance"] == str(c.gate.profile.EXPECTED["wallet"]) and account["house"] == str(c.gate.profile.EXPECTED["house"]) and account["free"] == account["locked"] == account["totalUnpaidProfit"] == "0" and account["userNonce"] == "2" and position["status"] == "CLOSED" and position["version"] == "2" and market.get("provider") == "SIMULATED", "INITIAL_HTTP_STATE")
    for action, expected in c.receipts.items():
        suffix = "";txhash = expected["info"]["transaction_hash"]
        if action in {"OPEN", "CLOSE"}:
            events = [log for log in expected["receipt"]["logs"] if log["address"] == c.identity["controller"]["address"]]
            require(len(events) == 1 and len(events[0]["topics"]) == 4, "EXECUTION_EVENT_LINK")
            intent = events[0]["topics"][1];suffix = "?intentId=" + intent
            json.loads(request("/api/c-futures/direct/intents/" + intent + "/execution", jwt=jwt))
        require(json.loads(request("/api/c-futures/direct/transactions/" + txhash + suffix, jwt=jwt)).get("status") == "CONFIRMED", "INITIAL_HTTP_RECEIPT")
