"""One new network-none Anvil restore proof; never resumes or mounts an old writable volume.

Read-only cast RPC only. No key file, signature, transaction, host port, time manipulation,
PG, App, Oracle, or full-audit claim. Failed clone and original evidence are preserved.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import uuid

ORIGINAL = "cdemo-e0e5a24638b0416f"
SNAPSHOT_SHA = "e015e7c508653f2d47b0d5e1c4bfcada7b5acbef1f360ab5ce6e6ebc92a7a9e8"
IMAGE = "sha256:0c00cb0bda1ab1b91c9a6bf60f4c76c09c1a8870824b6d4718afbabacf6f9a17"
ANCHORS = {"result.json": "5d59a48d410c4dc38bf1b40860ee2c95264dfa875e978b0078bcb296c6464936",
           "execution-identity.json": "303d4d3b5a901f6e29ad6d531e5c6d03f039565929cbe0ec292b32998e30bcf5",
           "private/identity.json": "3ec05c3261c6f17ba0b8d3747926093d346cf63e279ece34e81107c1d70aaa5d",
           "browser-ready.json": "de5370efa8c4f94d1edd724577de6284c1cab246507146b9503a33793cd52d98"}
HISTORY = (
    ("APPROVE", "0x7e81bf8d8205c9721b879e34c50987f2aa5756dfe3a6d00fa10168ed196ac8a8", 2554, "0x426a51a641b162e55948ef11fbdc5134954a246691ac981b39e297b6a6145d31"),
    ("DEPOSIT", "0xcf0695f0a12f2f379e9541891e802715220e79f74c2eb239ba52f42b9f2d744b", 3026, "0x964de8f562604e69d44ea1917ede7059b2530fb53effe20e8de1b75ba2388356"),
    ("WITHDRAW", "0xce1ea49c2f8d87f9f61f62c35a167f7be21375b0fd7d9d1b315d0ca18adaa07e", 3654, "0x5a1a8062d7517bbdd9b7c78f94ac2af33dbdc39336dfb606d36600adc34e3601"))
READ_METHODS = {"eth_chainId", "eth_getBlockByNumber", "eth_getCode", "eth_getBalance", "eth_getTransactionCount", "eth_getTransactionReceipt", "eth_getTransactionByHash", "eth_call"}


def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def quantity(value): return int(value, 16) if isinstance(value, str) and value.startswith("0x") else int(value)
def require(condition, code):
    if not condition: raise ValueError("C_CLONE_" + code)
def word(value): return format(value, "064x")
def address_word(value):
    require(bool(re.fullmatch(r"0x[0-9a-f]{40}", value)), "ADDRESS")
    return "0" * 24 + value[2:]
def logs_projection(logs): return [{"address": item["address"].lower(), "topics": [t.lower() for t in item["topics"]], "data": item["data"].lower()} for item in logs]
def clean_env(): return {key: value for key, value in os.environ.items() if key.upper() in {"PATH", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "PATHEXT", "COMSPEC", "USERPROFILE", "LOCALAPPDATA"}}


def load_original(root):
    old = root / "deployment/runtime/c-direct-browser" / ORIGINAL
    require(all(sha(old / rel) == digest for rel, digest in ANCHORS.items()), "PROVENANCE_ANCHOR")
    snapshot_path = old / "anvil-state.private.json"
    require(snapshot_path.stat().st_size == 7794973 and sha(snapshot_path) == SNAPSHOT_SHA, "SNAPSHOT_HASH")
    result = json.loads((old / "result.json").read_text(encoding="utf-8"))
    execution = json.loads((old / "execution-identity.json").read_text(encoding="utf-8"))
    ready = json.loads((old / "browser-ready.json").read_text(encoding="utf-8"))
    identity = json.loads((old / "private/identity.json").read_text(encoding="utf-8"))
    source_hash = hashlib.sha256(json.dumps(execution["sources"], sort_keys=True).encode()).hexdigest()
    require(execution["runId"] == result["runId"] == ready["runId"] == ORIGINAL and execution["sourceIdentity"] == result["sourceIdentity"] == ready["sourceIdentity"] == source_hash, "SOURCE_IDENTITY")
    require(result["anvilState"]["sha256"] == SNAPSHOT_SHA and result["anvilState"]["bytes"] == 7794973 and result["stopped"]["anvil"]["normalStop"] and result["resourcesPreserved"][ORIGINAL + "-state"], "STOPPED_ORIGINAL")
    require(identity["chainId"] == 31337 and ready["chainId"] == 31337, "ORIGINAL_CHAIN")
    owner = ready["owner"];address_word(owner)
    snapshot = json.loads(snapshot_path.read_text(encoding="utf-8"))
    expected = {}
    for action, txhash, number, blockhash in HISTORY:
        found = [tx for tx in snapshot["transactions"] if tx["info"]["transaction_hash"] == txhash]
        require(len(found) == 1, "SNAPSHOT_TRANSACTION")
        tx = found[0]
        require(tx["block_hash"] == blockhash and quantity(tx["block_number"]) == number and quantity(tx["receipt"]["status"]) == 1 and tx["info"]["from"] == owner, "SNAPSHOT_HISTORY")
        expected[txhash] = tx
    return old, snapshot_path, identity, owner, snapshot, expected, source_hash


def validate_container(info, name, snapshot_path, output):
    c, h, n = info.get("Config", {}), info.get("HostConfig", {}), info.get("NetworkSettings", {})
    require(info.get("Name") == "/" + name and info.get("Image") == IMAGE and c.get("Labels", {}).get("tms.c.clone-owner") == name, "CONTAINER_OWNER")
    require(h.get("NetworkMode") == "none" and set(n.get("Networks", {})) <= {"none"} and not h.get("PortBindings") and not any((n.get("Ports") or {}).values()), "NETWORK_NONE")
    require(not h.get("Privileged") and not h.get("CapAdd") and h.get("CapDrop") == ["ALL"] and h.get("ReadonlyRootfs") is True and h.get("Memory") == 192 * 1024**2 and c.get("User") == "0:0", "ISOLATION")
    require(any(s in {"no-new-privileges", "no-new-privileges:true"} for s in h.get("SecurityOpt", [])), "NO_NEW_PRIVILEGES")
    mounts = {m["Destination"]: m for m in info.get("Mounts", []) if m.get("Type") != "tmpfs"}
    require(set(mounts) == {"/snapshot/anvil-state.private.json", "/output"}, "MOUNT_SET")
    for dest, source, writable in (("/snapshot/anvil-state.private.json", snapshot_path, False), ("/output", output, True)):
        m = mounts[dest]
        require(m.get("Type") == "bind" and m.get("RW") is writable and str(m.get("Source", "")).replace("\\", "/").lower().rstrip("/") == str(source.resolve()).replace("\\", "/").lower().rstrip("/"), "MOUNT_BOUNDARY")
    require(c.get("Entrypoint") == ["/usr/local/bin/anvil"] and c.get("Cmd") == anvil_args(), "EXACT_COMMAND")


def anvil_args():
    return ["--host", "127.0.0.1", "--port", "8545", "--chain-id", "31337", "--accounts", "0", "--block-time", "1", "--silent", "--load-state", "/snapshot/anvil-state.private.json", "--dump-state", "/output/anvil.json"]


def expected_logs(action, identity, owner, topics):
    token, vault, amount = identity["token"]["address"], identity["vault"]["address"], "0x" + word(3000 * 10**18)
    who, vault_word = "0x" + address_word(owner), "0x" + address_word(vault)
    if action == "APPROVE": return [{"address": token, "topics": [topics["Approval"], who, vault_word], "data": amount}]
    transfer = {"address": token, "topics": [topics["Transfer"], who if action == "DEPOSIT" else vault_word, vault_word if action == "DEPOSIT" else who], "data": amount}
    return [transfer, {"address": vault, "topics": [topics["Deposited" if action == "DEPOSIT" else "Withdrawn"], who], "data": amount}]


def run(root):
    old, snapshot_path, identity, owner, snapshot, expected, source_hash = load_original(root)
    provenance = {rel: sha(old / rel) for rel in (*ANCHORS, "anvil-state.private.json")}
    # Preflight is read-only and occurs before any new resource or claim.
    ram = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "(Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory"], env=clean_env(), capture_output=True, text=True, timeout=10)
    require(ram.returncode == 0 and ram.stdout.strip().isdigit() and int(ram.stdout.strip()) * 1024 >= 1920 * 1024**2 and shutil.disk_usage(root).free >= 2 * 1024**3, "RESOURCE_FLOOR")
    image = subprocess.run(["docker", "image", "inspect", IMAGE, "--format", "{{.Id}}|{{.Os}}|{{.Architecture}}"], env=clean_env(), capture_output=True, text=True, timeout=10)
    require(image.returncode == 0 and image.stdout.strip() == IMAGE + "|linux|amd64", "IMAGE")
    name = "cclone-" + uuid.uuid4().hex[:16]
    out = root / "deployment/runtime/c-anvil-snapshot-clone" / name;out.mkdir(parents=True, exist_ok=False)
    output = out / "anvil-output";output.mkdir()
    (out / "RUN_CLAIM").write_text("ONE_NEW_SNAPSHOT_CLONE_ONLY\n", encoding="ascii")
    (out / "provenance.json").write_text(json.dumps({"originalRun": ORIGINAL, "originalSourceIdentity": source_hash, "originalHashes": provenance, "scriptSha256": sha(Path(__file__)), "image": IMAGE, "snapshotSha256": SNAPSHOT_SHA, "owner": owner}, indent=2), encoding="utf-8")
    start = time.monotonic();deadline = start + 150;commands = [];attempted = False;container_id = None;result = {"runId": name, "status": "BLOCKED", "readOnlyRpc": True, "newSignatures": 0, "newTransactions": 0, "fullAudit": False, "pythonVersion": sys.version};stage = "NEW_NAME_CHECK"
    def docker(args, timeout=10, stdin=None, check=True):
        remaining = int(deadline - time.monotonic());require(remaining >= 1, "DEADLINE")
        p = subprocess.run(["docker", *args], env=clean_env(), capture_output=True, text=True, input=stdin, timeout=min(timeout, remaining))
        commands.append({"args": args, "exitCode": p.returncode, "stdoutBytes": len(p.stdout), "stderrBytes": len(p.stderr)})
        require(not check or p.returncode == 0, "DOCKER_EXIT")
        return p
    def rpc(method, *params):
        require(method in READ_METHODS, "READ_ONLY_METHOD")
        args = [json.dumps(p, separators=(",", ":")) if not isinstance(p, str) else p for p in params]
        p = docker(["exec", container_id, "/usr/local/bin/cast", "rpc", "--rpc-url", "http://127.0.0.1:8545", method, *args])
        return json.loads(p.stdout)
    def cast_hash(value):
        return docker(["exec", "-i", container_id, "/usr/local/bin/cast", "keccak"], stdin=value).stdout.strip()
    try:
        require(docker(["container", "inspect", name], check=False).returncode != 0, "NAME_COLLISION")
        stage = "NEW_ANVIL_LOAD";attempted = True
        docker(["run", "-d", "--pull=never", "--name", name, "--label", "tms.c.clone-owner=" + name, "--network", "none", "--restart=no", "--memory=192m", "--cpus=1", "--pids-limit=128", "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges", "--user=0:0", "--tmpfs", "/tmp:rw,noexec,nosuid,size=16m", "--mount", "type=bind,source=" + str(snapshot_path.resolve()) + ",target=/snapshot/anvil-state.private.json,readonly", "--mount", "type=bind,source=" + str(output.resolve()) + ",target=/output", "--entrypoint", "/usr/local/bin/anvil", IMAGE, *anvil_args()])
        info = json.loads(docker(["container", "inspect", name]).stdout)[0];validate_container(info, name, snapshot_path, output);container_id = info["Id"]
        version = docker(["exec", container_id, "/usr/local/bin/anvil", "--version"]).stdout.strip();require("1.8.1" in version, "ANVIL_VERSION");result["anvilVersion"] = version
        result["castVersion"] = docker(["exec", container_id, "/usr/local/bin/cast", "--version"]).stdout.strip()
        # Bounded startup read probes only; no re-create, transaction, mining, or timestamp RPC.
        stage = "READINESS"
        for _ in range(10):
            probe = docker(["exec", container_id, "/usr/local/bin/cast", "rpc", "--rpc-url", "http://127.0.0.1:8545", "eth_chainId"], timeout=3, check=False)
            if probe.returncode == 0: break
            time.sleep(.5)
        else: raise ValueError("C_CLONE_NOT_READY")
        require(quantity(json.loads(probe.stdout)) == 31337, "CHAIN_ID")
        stage = "GENESIS_CODE"
        require(rpc("eth_getBlockByNumber", "0x0", False)["hash"] == identity["genesisHash"], "GENESIS")
        for role in ("token", "controller", "rules", "vault"):
            require(cast_hash(rpc("eth_getCode", identity[role]["address"], "latest")) == identity[role]["codeHash"], "CODE_" + role.upper())
        stage = "OWNER_ASSETS"
        def call(target, signature, argument=""):
            selector = docker(["exec", container_id, "/usr/local/bin/cast", "sig", signature]).stdout.strip()
            return quantity(rpc("eth_call", {"to": target, "data": selector + argument}, "latest"))
        token, controller, vault = (identity[k]["address"] for k in ("token", "controller", "vault"))
        actual = {"eoaNonce": quantity(rpc("eth_getTransactionCount", owner, "latest")), "controllerNonce": call(controller, "nonces(address)", address_word(owner)), "token": call(token, "balanceOf(address)", address_word(owner)), "free": call(vault, "freeBalance(address)", address_word(owner)), "locked": call(vault, "lockedBalance(address)", address_word(owner)), "totalLocked": call(vault, "totalLocked()"), "house": call(vault, "houseBalance()"), "ethBalance": quantity(rpc("eth_getBalance", owner, "latest"))}
        require(actual == {"eoaNonce": 3, "controllerNonce": 0, "token": 1_000_000 * 10**18, "free": 0, "locked": 0, "totalLocked": 0, "house": 10_000_000 * 10**18, "ethBalance": quantity(snapshot["accounts"][owner]["balance"])}, "OWNER_ASSETS")
        result["ownerAssets"] = {k: str(v) for k, v in actual.items()}
        stage = "HISTORICAL_RECEIPTS"
        topics = {event: cast_hash(event + "(address,address,uint256)" if event in {"Transfer", "Approval"} else event + "(address,uint256)") for event in ("Transfer", "Approval", "Deposited", "Withdrawn")}
        receipts = []
        for action, txhash, number, blockhash in HISTORY:
            receipt = rpc("eth_getTransactionReceipt", txhash);tx = rpc("eth_getTransactionByHash", txhash);block = rpc("eth_getBlockByNumber", hex(number), False)
            require(isinstance(receipt, dict) and isinstance(tx, dict) and quantity(receipt["status"]) == 1 and receipt["transactionHash"] == txhash and quantity(receipt["blockNumber"]) == number and receipt["blockHash"] == blockhash == block["hash"] and txhash in block["transactions"], "CANONICAL_RECEIPT")
            require(tx["from"] == owner and tx["to"] == (token if action == "APPROVE" else vault) and tx["blockHash"] == blockhash and quantity(tx["value"]) == 0 and quantity(tx["nonce"]) == quantity(expected[txhash]["info"]["nonce"]), "TRANSACTION_OWNER")
            logs = logs_projection(receipt["logs"])
            require(logs == logs_projection(expected[txhash]["receipt"]["logs"]) == expected_logs(action, identity, owner, topics), "RECEIPT_LOGS")
            receipts.append({"action": action, "txHash": txhash, "blockNumber": number, "blockHash": blockhash, "status": "MATCH", "logCount": len(logs)})
        result["receipts"] = receipts
        stage = "NATURAL_WALL_CLOCK"
        latest = rpc("eth_getBlockByNumber", "latest", False);offset = abs(quantity(latest["timestamp"]) - time.time())
        result["latestTimestamp"] = quantity(latest["timestamp"]);result["wallClockOffsetSeconds"] = offset
        require(offset <= 2, "WALL_CLOCK_OFFSET")
        result["status"] = "PASS_CLONED_ANVIL_STATE_CURRENT_READS_AND_THREE_RECEIPTS_NOT_SERVICE_RESUME_OR_FULL_AUDIT"
    except Exception as failure:
        result["safeError"] = str(failure) if isinstance(failure, ValueError) and str(failure).startswith("C_CLONE_") else "C_CLONE_SAFE_" + type(failure).__name__
    finally:
        deadline = start + 195
        if attempted:
            try:
                probe = docker(["container", "inspect", container_id or name], check=False)
                require(probe.returncode == 0, "STOP_INSPECT")
                info = json.loads(probe.stdout)[0];require(info["Config"].get("Labels", {}).get("tms.c.clone-owner") == name and info["Name"] == "/" + name, "STOP_OWNER")
                if info["State"].get("Running"): docker(["stop", "-t", "10", info["Id"]], timeout=15)
                state = json.loads(docker(["container", "inspect", info["Id"]]).stdout)[0]["State"]
                result["normalStop"] = state.get("Running") is False and state.get("ExitCode") == 0 and not state.get("OOMKilled") and not state.get("Error")
                require(result["normalStop"], "NORMAL_STOP")
            except Exception:
                result["normalStop"] = False;result["safeError"] = result.get("safeError", "C_CLONE_STOP_UNCONFIRMED");result["status"] = "BLOCKED"
        try: unchanged = all(sha(old / rel) == digest for rel, digest in provenance.items())
        except Exception: unchanged = False
        result["originalUnchanged"] = unchanged;result["stage"] = stage;result["commands"] = commands;result["elapsedSeconds"] = time.monotonic() - start
        if not unchanged: result["status"] = "BLOCKED";result["safeError"] = "C_CLONE_ORIGINAL_CHANGED"
        if (output / "anvil.json").is_file(): result["newDump"] = {"sha256": sha(output / "anvil.json"), "bytes": (output / "anvil.json").stat().st_size}
        elif attempted:
            result["status"] = "BLOCKED";result["safeError"] = result.get("safeError", "C_CLONE_NEW_DUMP_MISSING")
        (out / "result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps({"runId": name, "status": result["status"], "safeError": result.get("safeError")}))
    return 0 if result["status"].startswith("PASS_") else 1


if __name__ == "__main__":
    require(len(sys.argv) == 1, "NO_MUTABLE_ARGUMENTS")
    raise SystemExit(run(Path(__file__).resolve().parent.parent))
