"""One approved local continuation: cold PG clone + immutable Anvil snapshot + original App.

Original containers/volumes/config/STOP are read-only. A permanent parent-state claim
prevents a second live descendant and is retained on failure. No bootstrap/funding/key
generation/user signing/broadcast/repricing is performed. Clock sync is one exec-only
current-wall-time request, before public relay and only with no position/pending/lock.
"""
import hashlib
from local_demo_credentials import local_demo_password
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.request
import uuid

sys.dont_write_bytecode = True
from c_direct_browser_relay import BrowserRelay

SECONDS = 3600
FAILED_PARENT = "cdemo-5e4bd3b1140f4e63"
FAILED_ANCHORS = {"result.json": "4c51f90029f1b75b43da2a0ac4cf72c5693f4528895152179aef062b7f6c6a27",
                  "recovery-lineage.json": "958c6e8014dcdc20169f73be10378fbcd4f3b766dccecb0aceba81730244efa3",
                  "pg-copy.json": "2c89b96cbbc8d2d9f24986dda1f63976707c6a0c55bb304fbce98681376b4deb"}
COPY = r'''set -euo pipefail
test -s /from/PG_VERSION
test ! -e /from/postmaster.pid
test -z "$(find /from -type l -print -quit)"
test -z "$(find /to -mindepth 1 -print -quit)"
before=$(tar --sort=name -C /from -cf - . | sha256sum | cut -d' ' -f1)
pg_controldata /from | grep 'Database system identifier'
pg_controldata /from | grep 'Database cluster state:.*shut down'
tar --sort=name -C /from -cf - . | tar -C /to -xpf -
after=$(tar --sort=name -C /from -cf - . | sha256sum | cut -d' ' -f1)
copied=$(tar --sort=name -C /to -cf - . | sha256sum | cut -d' ' -f1)
test "$before" = "$after"
test "$before" = "$copied"
pg_controldata /to | grep 'Database system identifier'
printf 'C_RESUME_PG_COLD_COPY_MATCH %s\n' "$before"
'''


def load_module(root, filename, name):
    spec = importlib.util.spec_from_file_location(name, root / "deployment" / filename)
    module = importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    return module


def ensure(ok, code):
    if not ok: raise ValueError("C_RESUME_" + code)


def claim_path(root, parent, snapshot_sha):
    ensure(bool(re.fullmatch(r"cdemo-[0-9a-f]{16}", parent) and re.fullmatch(r"[0-9a-f]{64}", snapshot_sha)), "CLAIM_SCOPE")
    return root / "deployment/runtime/c-direct-resume-claims" / (parent + "-" + snapshot_sha + ".claim")


def no_live_parent_children(infos, parent, snapshot_sha):
    for item in infos:
        labels = item.get("Config", {}).get("Labels", {})
        if labels.get("tms.c.parent") == parent and labels.get("tms.c.parent-state") == snapshot_sha:
            # Even a stopped prior live child prevents replaying the ancestor snapshot.
            raise ValueError("C_RESUME_EXISTING_DESCENDANT")


def check_old_role(info, parent, role, image):
    ensure(info.get("Name") == "/" + parent + "-" + role and info.get("Image") == image and info.get("Config", {}).get("Labels", {}).get("tms.c.owner") == parent, "OLD_ROLE_IDENTITY")
    state = info.get("State", {})
    ensure(state.get("Running") is False and not state.get("OOMKilled") and not state.get("Error") and state.get("ExitCode") in {0, 143}, "OLD_ROLE_NOT_STOPPED")


def read_json(path): return json.loads(path.read_text(encoding="utf-8"))


def clock_block_applied(block, previous, requested_wall):
    def q(value): return int(value, 16) if isinstance(value, str) else int(value)
    return q(block["number"]) > q(previous["number"]) and q(block["timestamp"]) >= requested_wall


def mount_path(value):
    # Same Docker Desktop alias normalization as reviewed deployment/ops.py:mount_path.
    if os.name == "nt":
        match = re.fullmatch(r"/(?:run/desktop/mnt/host|host_mnt)/([a-zA-Z])/(.*)", str(value))
        if match: value = match[1] + ":/" + match[2]
    return os.path.normcase(str(Path(value).resolve()))


def validate_failed_parent(result, lineage, pg_copy, root_parent, snapshot_sha):
    ensure(result.get("runId") == FAILED_PARENT and result.get("parent") == root_parent and result.get("parentState") == snapshot_sha and result.get("status") == "BLOCKED" and result.get("stage") == "NEW_PG_ANVIL" and result.get("safeError") == "C_RESUME_ANVIL_MOUNTS", "FAILED_PARENT_BOUNDARY")
    ensure(result.get("clockSyncRequests") == result.get("automaticTransactions") == result.get("bootstrapCalls") == result.get("newKeys") == 0 and result.get("originalUnchanged") is True and result.get("anvilState") is None, "FAILED_PARENT_NO_SIDE_EFFECTS")
    ensure(set(result.get("stopped", {})) == {"copy", "pg", "anvil"} and result["stopped"]["copy"]["normalStop"] is True and result["stopped"]["pg"]["normalStop"] is True and result["stopped"]["anvil"]["exitCode"] == 137 and result["stopped"]["anvil"]["oomKilled"] is False, "FAILED_PARENT_STOP_PROOF")
    ensure(lineage.get("child") == FAILED_PARENT and lineage.get("parent") == root_parent and lineage.get("parentState") == snapshot_sha and lineage.get("recoverySourceIdentity") == result["sourceIdentity"], "FAILED_PARENT_LINEAGE")
    ensure(pg_copy.get("systemIdentifier") == "7699878592856473638" and pg_copy.get("byteClone") is True and pg_copy.get("sourceReadOnly") is True, "FAILED_PARENT_PG_COPY")


def validate_copy(info, run_id, parent, snapshot_sha, old_volume, new_volume, pg_image):
    c, h, n = info.get("Config", {}), info.get("HostConfig", {}), info.get("NetworkSettings", {})
    ensure(info.get("Image") == pg_image and info.get("Name") == "/" + run_id + "-copy" and c.get("Labels", {}).get("tms.c.owner") == run_id and c.get("Labels", {}).get("tms.c.parent") == parent and c.get("Labels", {}).get("tms.c.parent-state") == snapshot_sha, "COPY_IDENTITY")
    ensure(h.get("NetworkMode") == "none" and set(n.get("Networks", {})) <= {"none"} and not h.get("PortBindings") and not any((n.get("Ports") or {}).values()) and not h.get("Privileged") and h.get("ReadonlyRootfs") is True and h.get("Memory") == 256 * 1024**2, "COPY_ISOLATION")
    caps = {cap.removeprefix("CAP_") for cap in h.get("CapAdd", [])}
    ensure(h.get("CapDrop") == ["ALL"] and caps == {"DAC_OVERRIDE", "CHOWN", "FOWNER"} and any(s in {"no-new-privileges", "no-new-privileges:true"} for s in h.get("SecurityOpt", [])), "COPY_CAPABILITIES")
    ensure(c.get("Entrypoint") == ["/bin/bash"] and c.get("Cmd") == ["-ceu", COPY], "COPY_COMMAND")
    ensure(h.get("Tmpfs") == {"/var/lib/postgresql/data": "rw,noexec,nosuid,size=1m"}, "COPY_IMAGE_VOLUME_MASK")
    mounts = {m.get("Destination"): m for m in info.get("Mounts", []) if m.get("Type") != "tmpfs"}
    ensure(set(mounts) == {"/from", "/to"}, "COPY_MOUNT_SET")
    for target, volume, writable in (("/from", old_volume, False), ("/to", new_volume, True)):
        m = mounts[target];ensure(m.get("Type") == "volume" and m.get("Name") == volume and m.get("RW") is writable, "COPY_MOUNT_BOUNDARY")


def run(root):
    local_demo_password()
    base = load_module(root, "c-direct-demo-check.py", "c_resume_base")
    browser = load_module(root, "c-direct-browser-run.py", "c_resume_browser")
    probe = load_module(root, "c-anvil-snapshot-clone-check.py", "c_resume_snapshot")
    old, snapshot_path, identity, owner, snapshot, expected_receipts, original_source = probe.load_original(root)
    ensure(len(snapshot.get("transactions", [])) == 9 and all(tx.get("receipt", {}).get("status") in {"0x0", "0x1"} and tx.get("block_hash") for tx in snapshot["transactions"]), "SNAPSHOT_ALL_TRANSACTIONS_TERMINAL")
    original_manifest = read_json(old / "driver-fixture/manifest.json")
    original_relay = read_json(old / "relay.json")
    data_parent, parent, snapshot_sha = probe.ORIGINAL, FAILED_PARENT, probe.SNAPSHOT_SHA
    failed = root / "deployment/runtime/c-direct-resume" / FAILED_PARENT
    ensure(all(base.sha(failed / p) == h for p, h in FAILED_ANCHORS.items()), "FAILED_PARENT_ANCHORS")
    failed_result, failed_lineage, failed_copy = (read_json(failed / name) for name in ("result.json", "recovery-lineage.json", "pg-copy.json"))
    validate_failed_parent(failed_result, failed_lineage, failed_copy, data_parent, snapshot_sha)
    ancestor_claim = claim_path(root, data_parent, snapshot_sha);ancestor_claim_hash = base.sha(ancestor_claim);ancestor = read_json(ancestor_claim)
    ensure(ancestor.get("child") == FAILED_PARENT and ancestor.get("parent") == data_parent and ancestor.get("snapshotSha256") == snapshot_sha and ancestor.get("sourceIdentity") == failed_result["sourceIdentity"], "ANCESTOR_CLAIM_MUST_REMAIN")
    hashes = {rel: base.sha(old / rel) for rel in (*probe.ANCHORS, "anvil-state.private.json", "appConfig/application.properties", "appConfig/oracle-token.private", "oracleConfig/identity.json", "oracleConfig/oracle-key.private", "oracleConfig/auth-token.private", "pg.private.env")}
    # Hash only: never parse/log/inject the Oracle signer key into App. User key files are never read.
    prepared = root / "deployment/runtime/c-direct-browser-prepare" / browser.PREPARED_ID
    prep = read_json(prepared / "result.json")
    ensure(prep["sourceIdentity"] == read_json(old / "execution-identity.json")["preparedSourceIdentity"], "PREPARED_SOURCE")
    ensure(all(base.sha(root / p) == h for p, h in prep["sources"].items()) and all(base.tree_hashes(prepared / (r + "-fixture")) == h for r, h in prep["fixtureHashes"].items()) and base.checked_web_tree(prepared / "web-fixture") == prep["webHashes"], "SOURCE_ARTIFACT_IDENTITY")
    supervisor_hash = base.sha(Path(__file__))
    recovery_source = hashlib.sha256(json.dumps({"parentSource": failed_result["sourceIdentity"], "originalSource": original_source, "supervisor": supervisor_hash, "snapshot": snapshot_sha}, sort_keys=True).encode()).hexdigest()
    began = time.monotonic();deadline = began + SECONDS + 240;total_deadline = deadline + 90
    commands = [];attempted = set();states = {};relay = None;clock_requests = 0
    def docker(args, timeout=10, stdin=None, check=True):
        remaining = int(deadline - time.monotonic());ensure(remaining >= 1, "DEADLINE")
        p = subprocess.run(["docker", *args], cwd=root, env=base.clean_env(), input=stdin, capture_output=True, text=True, timeout=min(timeout, remaining))
        commands.append({"args": args, "exitCode": p.returncode, "stdoutBytes": len(p.stdout), "stderrBytes": len(p.stderr)})
        ensure(not check or p.returncode == 0, "DOCKER_EXIT")
        return p
    def inspect(name):
        values = json.loads(docker(["container", "inspect", name]).stdout);ensure(len(values) == 1, "INSPECT")
        return values[0]
    ram = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "(Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory"], env=base.clean_env(), capture_output=True, text=True, timeout=10)
    ensure(ram.returncode == 0 and ram.stdout.strip().isdigit() and int(ram.stdout.strip()) * 1024 >= base.MIN_RAM and shutil.disk_usage(root).free >= base.MIN_DISK, "RESOURCES")
    old_infos = {}
    for role in ("pg", "anvil", "bootstrap", "oracle", "app"):
        info = inspect(data_parent + "-" + role);check_old_role(info, data_parent, role, base.PG_IMAGE if role == "pg" else base.FOUNDRY_IMAGE if role == "anvil" else base.JAVA_IMAGE);old_infos[role] = info
    failed_infos = {}
    for role in ("copy", "pg", "anvil"):
        info = inspect(parent + "-" + role)
        ensure(info["Config"].get("Labels", {}).get("tms.c.owner") == parent and info["Config"]["Labels"].get("tms.c.parent") == data_parent and info["Config"]["Labels"].get("tms.c.parent-state") == snapshot_sha and not info["State"].get("Running") and not info["State"].get("OOMKilled") and not info["State"].get("Error") and info["Image"] == (base.FOUNDRY_IMAGE if role == "anvil" else base.PG_IMAGE) and info["State"].get("ExitCode") == (137 if role == "anvil" else 0), "FAILED_PARENT_ALL_STOPPED")
        failed_infos[role] = info
    for role in ("app", "oracle"):
        ensure(docker(["container", "inspect", parent + "-" + role], check=False).returncode != 0, "FAILED_PARENT_NEVER_EXPOSED")
    root_children = docker(["ps", "-a", "--filter", "label=tms.c.parent=" + data_parent, "--format", "{{.ID}}"]).stdout.splitlines()
    for child in root_children:
        info = inspect(child);ensure(info["Config"].get("Labels", {}).get("tms.c.owner") == parent and not info["State"].get("Running"), "NO_OTHER_ANCESTOR_FORK")
    children = docker(["ps", "-a", "--filter", "label=tms.c.parent=" + parent, "--format", "{{.ID}}"]).stdout.splitlines()
    no_live_parent_children([inspect(child) for child in children], parent, snapshot_sha)
    ensure(not claim_path(root, parent, snapshot_sha).exists(), "PARENT_ALREADY_CLAIMED")
    for image in (base.PG_IMAGE, base.JAVA_IMAGE, base.FOUNDRY_IMAGE):
        ensure(docker(["image", "inspect", image, "--format", "{{.Id}}|{{.Os}}|{{.Architecture}}"]).stdout.strip() == image + "|linux|amd64", "IMAGE")
    for port in (32171, 32172):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
            if hasattr(socket, "SO_EXCLUSIVEADDRUSE"): listener.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
            listener.bind(("127.0.0.1", port))
    nets = docker(["network", "ls", "--format", "{{.ID}}"]).stdout.splitlines();ensure(len(nets) <= 1024, "NETWORK_COUNT")
    occupied = []
    for offset in range(0, len(nets), 64):
        for line in docker(["network", "inspect", *nets[offset:offset + 64], "--format", "{{json .IPAM.Config}}"]).stdout.splitlines(): occupied.extend(c["Subnet"] for c in json.loads(line) or [] if c.get("Subnet"))
    routes = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "@(Get-NetRoute -AddressFamily IPv4 -ErrorAction Stop | Select-Object -ExpandProperty DestinationPrefix) | ConvertTo-Json -Compress"], env=base.clean_env(), capture_output=True, text=True, timeout=10)
    ensure(routes.returncode == 0, "ROUTES");parsed = json.loads(routes.stdout);subnet = base.select_subnet(occupied, [parsed] if isinstance(parsed, str) else parsed)
    old_mount = [m for m in failed_infos["pg"]["Mounts"] if m.get("Destination") == "/var/lib/postgresql/data"]
    ensure(len(old_mount) == 1 and old_mount[0].get("Name") == parent + "-pg-data" and old_mount[0].get("Type") == "volume", "ORIGINAL_PG_VOLUME")
    ensure(json.loads(docker(["volume", "inspect", old_mount[0]["Name"]]).stdout)[0].get("Labels", {}).get("tms.c.owner") == parent, "ORIGINAL_VOLUME_OWNER")
    run_id = "cdemo-" + uuid.uuid4().hex[:16]
    out = root / "deployment/runtime/c-direct-resume" / run_id;out.mkdir(parents=True, exist_ok=False)
    claim = claim_path(root, parent, snapshot_sha);claim.parent.mkdir(parents=True, exist_ok=True)
    with claim.open("x", encoding="utf-8") as f: json.dump({"child": run_id, "parent": parent, "snapshotSha256": snapshot_sha, "sourceIdentity": recovery_source, "failedClaimIsRetained": True}, f)
    paths = {"server": prepared / "server-fixture", "oracle": prepared / "oracle-fixture", "web": prepared / "web-fixture"}
    for key in ("appConfig", "appControl", "appEvidence", "oracleControl", "oracleEvidence", "relayControl"):
        paths[key] = out / key;paths[key].mkdir()
    # Original configuration values are byte-identical. Only control/evidence mounts change.
    for file in ("application.properties", "oracle-token.private"): shutil.copyfile(old / "appConfig" / file, paths["appConfig"] / file)
    paths["oracleConfig"] = old / "oracleConfig"  # RO, mounted only by the Oracle role.
    env_files = {"pg": old / "pg.private.env"}
    original_pg_volume = old_mount[0]["Name"]
    base.new_json(out / "recovery-lineage.json", {"parent": parent, "parentState": snapshot_sha, "child": run_id, "originalDataParent": data_parent, "originalSourceIdentity": original_source, "recoverySourceIdentity": recovery_source, "originalHashes": hashes, "failedParentHashes": FAILED_ANCHORS, "claim": str(claim), "ancestorClaimRetained": str(ancestor_claim), "nextResumeMustUseThisChild": True, "snapshotInheritanceException": "PREPUBLIC_ZERO_TX_ZERO_CLOCK_FAILED_PARENT_NO_DUMP_INHERITS_IMMUTABLE_ROOT_SNAPSHOT_NOT_UNPUBLISHED_EMPTY_BLOCKS", "bootstrap": False, "newKeys": False})
    stage = "NEW_RESOURCES";status = "BLOCKED";error = None;app_id = None;anvil_id = None;preserved = {}
    def decorated(args): return args[:3] + ["--label", "tms.c.parent=" + parent, "--label", "tms.c.parent-state=" + snapshot_sha] + args[3:]
    def args_for(role):
        if role == "app": return decorated(browser.app_args(base, run_id, paths, env_files))
        if role == "oracle": return decorated(browser.oracle_args(base, run_id, paths, env_files, SECONDS))
        if role == "pg": return decorated(base.launch_args(role, run_id, paths, env_files))
        args = base.launch_args("anvil", run_id, paths, env_files)
        at = args.index("--entrypoint");tail = args.index(base.FOUNDRY_IMAGE) + 1
        args = args[:at] + ["--mount", base.bind(snapshot_path, "/snapshot/anvil-state.private.json", True)] + args[at:tail]
        return decorated(args + ["--host", "0.0.0.0", "--port", "8545", "--chain-id", "31337", "--accounts", "0", "--block-time", "1", "--silent", "--load-state", "/snapshot/anvil-state.private.json", "--dump-state", "/state/anvil.json"])
    def validate_anvil(info):
        c, h, n = info["Config"], info["HostConfig"], info["NetworkSettings"]
        ensure(info["Image"] == base.FOUNDRY_IMAGE and c.get("Labels", {}).get("tms.c.owner") == run_id and h.get("NetworkMode") == run_id + "-network" and set(n.get("Networks", {})) == {run_id + "-network"}, "ANVIL_IDENTITY")
        ensure(not h.get("Privileged") and not h.get("CapAdd") and not h.get("PortBindings") and not any((n.get("Ports") or {}).values()) and h.get("ReadonlyRootfs") is True and h.get("Memory") == 192 * 1024**2 and h.get("CapDrop") == ["ALL"] and c.get("User") == "0:0", "ANVIL_ISOLATION")
        ensure(any(v in {"no-new-privileges", "no-new-privileges:true"} for v in h.get("SecurityOpt", [])), "ANVIL_SECURITY")
        expected_args = args_for("anvil");at = expected_args.index("--entrypoint");ensure(c.get("Entrypoint") == [expected_args[at + 1]] and c.get("Cmd") == expected_args[at + 3:], "ANVIL_COMMAND")
        mounts = {m["Destination"]: m for m in info["Mounts"] if m.get("Type") != "tmpfs"}
        ensure(set(mounts) == {"/state", "/snapshot/anvil-state.private.json"} and mounts["/state"].get("Type") == "volume" and mounts["/state"].get("Name") == run_id + "-state" and mounts["/state"].get("RW") is True and mounts["/snapshot/anvil-state.private.json"].get("Type") == "bind" and mounts["/snapshot/anvil-state.private.json"].get("RW") is False and mount_path(mounts["/snapshot/anvil-state.private.json"]["Source"]) == mount_path(snapshot_path), "ANVIL_MOUNTS")
    def launch(role):
        attempted.add(role);docker(args_for(role));info = inspect(run_id + "-" + role)
        ensure(info["Config"]["Labels"].get("tms.c.parent") == parent and info["Config"]["Labels"].get("tms.c.parent-state") == snapshot_sha, "PARENT_LABEL")
        if role == "anvil": validate_anvil(info)
        elif role == "app": browser.validate_selected_java(base, info, role, run_id, paths, env_files, owner)
        elif role == "oracle": browser.validate_oracle(base, info, run_id, paths, env_files, SECONDS)
        else: base.validate_container(info, role, run_id, paths, env_files)
        return info["Id"]
    def rpc(method, *params):
        ensure(method in probe.READ_METHODS | {"eth_pendingTransactions", "evm_setNextBlockTimestamp"}, "RPC_ALLOWLIST")
        if method == "evm_setNextBlockTimestamp":
            nonlocal clock_requests
            ensure(clock_requests == 0 and len(params) == 1 and type(params[0]) is int and abs(params[0] - time.time()) <= 1.5, "ONE_CURRENT_TIME_ONLY")
            clock_requests += 1  # Persist before uncertain transmission; never retry.
            base.new_json(out / "clock-sync-claim.json", {"timestamp": params[0], "method": method, "requests": clock_requests, "wallBefore": time.time()})
        encoded = [p if isinstance(p, str) else json.dumps(p, separators=(",", ":")) for p in params]
        return json.loads(docker(["exec", anvil_id, "/usr/local/bin/cast", "rpc", "--rpc-url", "http://127.0.0.1:8545", method, *encoded]).stdout)
    def keccak(value): return docker(["exec", "-i", anvil_id, "/usr/local/bin/cast", "keccak"], stdin=value).stdout.strip()
    def call(target, signature, argument=""):
        selector = docker(["exec", anvil_id, "/usr/local/bin/cast", "sig", signature]).stdout.strip()
        return probe.quantity(rpc("eth_call", {"to": target, "data": selector + argument}, "latest"))
    def chain_check(label):
        ensure(probe.quantity(rpc("eth_chainId")) == 31337 and rpc("eth_getBlockByNumber", "0x0", False)["hash"] == identity["genesisHash"], "CHAIN_GENESIS")
        for role in ("token", "controller", "rules", "vault"): ensure(keccak(rpc("eth_getCode", identity[role]["address"], "latest")) == identity[role]["codeHash"], "CODEHASH")
        token, controller, vault = (identity[k]["address"] for k in ("token", "controller", "vault"))
        ensure(probe.quantity(rpc("eth_getTransactionCount", owner, "latest")) == probe.quantity(rpc("eth_getTransactionCount", owner, "pending")) == 3 and rpc("eth_pendingTransactions") == [], "NO_PENDING")
        account = keccak(keccak("TMS_C_FUTURES_WALLET_ACCOUNT_V1") + probe.address_word(owner) + identity["symbolHash"][2:])
        ensure(call(identity["rules"]["address"], "activePosition(bytes32)", account[2:]) == 0 and call(vault, "totalLocked()") == 0 and call(vault, "lockedBalance(address)", probe.address_word(owner)) == 0, "NO_ACTIVE_POSITION")
        ensure(call(controller, "nonces(address)", probe.address_word(owner)) == 0 and call(token, "balanceOf(address)", probe.address_word(owner)) == 1_000_000 * 10**18 and call(vault, "freeBalance(address)", probe.address_word(owner)) == 0 and call(vault, "houseBalance()") == 10_000_000 * 10**18 and probe.quantity(rpc("eth_getBalance", owner, "latest")) == probe.quantity(snapshot["accounts"][owner]["balance"]), "ASSET_NONCE_CONTINUITY")
        topics = {e: keccak(e + ("(address,address,uint256)" if e in {"Approval", "Transfer"} else "(address,uint256)")) for e in ("Approval", "Transfer", "Deposited", "Withdrawn")}
        for action, txhash, number, blockhash in probe.HISTORY:
            receipt = rpc("eth_getTransactionReceipt", txhash);block = rpc("eth_getBlockByNumber", hex(number), False)
            ensure(receipt and probe.quantity(receipt["status"]) == 1 and receipt["blockHash"] == blockhash == block["hash"] and probe.quantity(receipt["blockNumber"]) == number and txhash in block["transactions"] and probe.logs_projection(receipt["logs"]) == probe.expected_logs(action, identity, owner, topics) == probe.logs_projection(expected_receipts[txhash]["receipt"]["logs"]), "THREE_RECEIPTS")
        base.new_json(out / (label + ".json"), {"chainId": 31337, "genesisHash": identity["genesisHash"], "owner": owner, "eoaNonce": 3, "controllerNonce": 0, "activePosition": False, "pending": 0, "token": "1000000", "free": "0", "locked": "0", "house": "10000000", "receiptCount": 3, "fullAudit": False})
    def ready(role, path):
        end = min(deadline, time.monotonic() + 90)
        while time.monotonic() < end:
            value = browser.published_ready(path)
            if value is not None: return value
            ensure(inspect(run_id + "-" + role)["State"]["Running"], "SERVICE_READINESS")
            time.sleep(.5)
        raise ValueError("C_RESUME_READY_TIMEOUT")
    try:
        # Fresh resources only; no compose, old start, delete, or overwrite.
        for kind, name in (("network", run_id + "-network"), ("volume", run_id + "-pg-data"), ("volume", run_id + "-state"), *[("container", run_id + "-" + r) for r in ("copy", "pg", "anvil", "oracle", "app")]): ensure(docker([kind, "inspect", name], check=False).returncode != 0, "RESOURCE_COLLISION")
        docker(["network", "create", "--internal", "--subnet", subnet, "--label", "tms.c.owner=" + run_id, run_id + "-network"])
        network = json.loads(docker(["network", "inspect", run_id + "-network"]).stdout)[0];ensure(network.get("Internal") is True and network.get("Labels", {}).get("tms.c.owner") == run_id, "INTERNAL_NETWORK")
        for name in (run_id + "-pg-data", run_id + "-state"): docker(["volume", "create", "--label", "tms.c.owner=" + run_id, name])
        stage = "PG_READONLY_COLD_COPY";attempted.add("copy")
        docker(["run", "-d", "--pull=never", "--name", run_id + "-copy", "--label", "tms.c.owner=" + run_id, "--label", "tms.c.parent=" + parent, "--label", "tms.c.parent-state=" + snapshot_sha, "--network", "none", "--read-only", "--memory=256m", "--pids-limit=64", "--cap-drop=ALL", "--cap-add=DAC_OVERRIDE", "--cap-add=CHOWN", "--cap-add=FOWNER", "--security-opt=no-new-privileges", "--tmpfs", "/var/lib/postgresql/data:rw,noexec,nosuid,size=1m", "--mount", "type=volume,source=" + original_pg_volume + ",target=/from,readonly", "--mount", "type=volume,source=" + run_id + "-pg-data,target=/to", "--entrypoint", "/bin/bash", base.PG_IMAGE, "-ceu", COPY])
        validate_copy(inspect(run_id + "-copy"), run_id, parent, snapshot_sha, original_pg_volume, run_id + "-pg-data", base.PG_IMAGE)
        copied = docker(["wait", run_id + "-copy"], timeout=60).stdout.strip();ensure(copied == "0", "PG_COPY_EXIT")
        copy_info = inspect(run_id + "-copy");ensure(copy_info["Config"]["Labels"].get("tms.c.owner") == run_id and copy_info["HostConfig"]["NetworkMode"] == "none", "COPY_OWNER")
        copy_result = docker(["logs", run_id + "-copy"]).stdout;ids = re.findall(r"Database system identifier:\s+(\d+)", copy_result)
        ensure(len(ids) == 2 and ids[0] == ids[1] and "C_RESUME_PG_COLD_COPY_MATCH " in copy_result, "SYSTEM_ID_COPY")
        base.new_json(out / "pg-copy.json", {"systemIdentifier": ids[0], "byteClone": True, "sourceReadOnly": True})
        stage = "NEW_PG_ANVIL";pg_id = launch("pg");anvil_id = launch("anvil")
        for _ in range(10):
            ping = docker(["exec", anvil_id, "/usr/local/bin/cast", "rpc", "--rpc-url", "http://127.0.0.1:8545", "eth_chainId"], timeout=3, check=False)
            if ping.returncode == 0:
                ensure(probe.quantity(json.loads(ping.stdout)) == 31337, "ANVIL_CHAIN_READINESS");break
            time.sleep(.5)
        else: raise ValueError("C_RESUME_ANVIL_READINESS")
        for _ in range(30):
            if docker(["exec", pg_id, "pg_isready", "-h", "127.0.0.1"], check=False).returncode == 0: break
            time.sleep(.5)
        else: raise ValueError("C_RESUME_PG_READINESS")
        # The cloned PG environment supplies its own password; no secret appears in argv/output.
        sql = "SELECT json_build_object('systemId',(SELECT system_identifier::text FROM pg_control_system()),'database',current_database(),'marker',(SELECT marker FROM public.c_direct_demo_identity),'runId',(SELECT run_id FROM public.c_direct_demo_identity),'users',(SELECT count(*) FROM public.users),'wallet',(SELECT trim(wallet) FROM tms_c_identity.wallet_links WHERE user_id=1),'roleCount',(SELECT count(*) FROM pg_roles WHERE rolname IN ('" + original_manifest["databaseOwner"] + "','" + original_manifest["apiUser"] + "')),'drafts',(SELECT count(*) FROM tms_c_execution.drafts),'consents',(SELECT count(*) FROM tms_c_execution.consents),'executions',(SELECT count(*) FROM tms_c_execution.direct_executions),'claims',(SELECT count(*) FROM tms_c_execution.direct_execution_claims));"
        psql = ["exec", "-i", pg_id, "/bin/sh", "-ceu", 'PGPASSWORD="$POSTGRES_PASSWORD" exec psql -X -qAt -v ON_ERROR_STOP=1 -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"']
        db = json.loads(docker(psql, stdin=sql).stdout)
        ensure(db["systemId"] == ids[0] == failed_copy["systemIdentifier"] and db["database"] == original_manifest["database"] and db["runId"] == data_parent and db["marker"] == original_manifest["marker"] and db["users"] == 2 and db["wallet"] == owner and db["roleCount"] == 2 and db["drafts"] == db["consents"] == db["executions"] == db["claims"] == 0, "CLONED_DB_IDENTITY")
        base.new_json(out / "pg-observation.private.json", db)
        stage = "PRE_SYNC_CHAIN";chain_check("before-clock-sync")
        stage = "ONCE_CURRENT_WALL_SYNC";before = rpc("eth_getBlockByNumber", "latest", False);requested_wall = int(time.time());rpc("evm_setNextBlockTimestamp", requested_wall)
        for _ in range(10):
            latest = rpc("eth_getBlockByNumber", "latest", False)
            if clock_block_applied(latest, before, requested_wall): break
            time.sleep(.5)
        else: raise ValueError("C_RESUME_NATURAL_BLOCK_TIMEOUT")
        ensure(abs(probe.quantity(latest["timestamp"]) - time.time()) <= 2, "CLOCK_OFFSET")
        stage = "POST_SYNC_CHAIN";chain_check("after-clock-sync")
        ensure(abs(probe.quantity(rpc("eth_getBlockByNumber", "latest", False)["timestamp"]) - time.time()) <= 2, "CLOCK_POST_CHECK")
        stage = "START_ORIGINAL_SERVICES";launch("oracle");oracle = ready("oracle", paths["oracleEvidence"] / "ready.json");app_id = launch("app");app = ready("app", paths["appEvidence"] / "server-ready.json")
        ensure(app.get("runId") == data_parent and app.get("sourceIdentity") == original_source and app.get("sessionSeconds") == SECONDS and app.get("jwtValidityMs") == SECONDS * 1000 and all(app.get(k) is False for k in ("userPrivateKeys", "oraclePrivateKeys", "relayerPrivateKeys")) and oracle.get("oracle") == identity["oracle"], "ORIGINAL_SERVICE_BINDING")
        stage = "LOOPBACK_RELAY";meta = dict(original_relay, runId=run_id, appId=app_id, networkId=network["Id"], sourceIdentity=recovery_source, seconds=SECONDS)
        base.new_json(out / "relay.json", meta);relay = BrowserRelay(meta, paths["relayControl"]).start()
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *unused): raise ValueError("C_RESUME_REDIRECT")
        http = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect());origin = original_relay["origin"]
        def request(path, data=None, jwt=None):
            headers = {"Origin": origin}
            if data is not None: headers["Content-Type"] = "application/json"
            if jwt: headers["Authorization"] = "Bearer " + jwt
            with http.open(urllib.request.Request(origin + path, data=None if data is None else json.dumps(data).encode(), headers=headers), timeout=20) as response:
                payload = response.read(1024**2 + 1);ensure(response.status == 200 and len(payload) <= 1024**2, "HTTP_PROBE")
                return payload
        stage = "REAL_HTTP_PROBES"
        ensure(hashlib.sha256(request("/c-demo.html")).hexdigest() == base.sha(paths["web"] / "c-demo.html"), "STATIC_BYTES")
        jwt = json.loads(request("/api/auth/login", {"loginId": "c-demo-user", "password": local_demo_password()}))["accessToken"]
        ensure(json.loads(request("/api/me", jwt=jwt)).get("id") == "1" and json.loads(request("/api/c-futures/wallet", jwt=jwt)).get("wallet") == owner, "JWT_WALLET")
        config = json.loads(request("/api/c-futures/config", jwt=jwt));account = json.loads(request("/api/c-futures/account", jwt=jwt));market = json.loads(request("/api/c-futures/direct/market", jwt=jwt))
        ensure(config.get("deploymentId") == oracle.get("deploymentId") and account["value"]["owner"] == owner and account["value"]["walletBalance"] == str(1_000_000 * 10**18) and account["value"]["free"] == "0" and account["value"]["locked"] == "0" and market.get("provider") == "SIMULATED" and isinstance(market.get("price"), str), "FRESH_ACCOUNT_MARKET")
        for _, txhash, _, _ in probe.HISTORY: ensure(json.loads(request("/api/c-futures/direct/transactions/" + txhash, jwt=jwt)).get("status") == "CONFIRMED", "FRESH_RECEIPT_API")
        del jwt
        base.new_json(out / "browser-ready.json", {"parent": parent, "runId": run_id, "originalSourceIdentity": original_source, "recoverySourceIdentity": recovery_source, "web": origin + "/c-demo.html", "rpc": "http://127.0.0.1:32172", "chainId": 31337, "owner": owner, "loginId": "c-demo-user", "loginPassword": local_demo_password(), "seconds": SECONDS, "stopFile": str(paths["relayControl"] / "STOP"), "clockSyncRequests": clock_requests, "fullAudit": False})
        print(json.dumps({"status": "READY_SAME_LOGICAL_STATE_USER_TRADES_NOT_ASSESSED", "readyFile": str(out / "browser-ready.json")}), flush=True)
        stage = "BOUNDED_USER_WINDOW";status = "READY_SAME_LOGICAL_STATE_USER_TRADES_NOT_ASSESSED";relay.wait()
    except Exception as failure:
        error = str(failure) if isinstance(failure, ValueError) and str(failure).startswith("C_RESUME_") else "C_RESUME_SAFE_" + type(failure).__name__
        status = "BLOCKED"
    finally:
        deadline = total_deadline
        if relay:
            try: relay.close();relay.wait()
            except Exception: status = "BLOCKED";error = error or "C_RESUME_RELAY_STOP"
        for role in ("app", "oracle"):
            try:
                with (paths[role + "Control"] / "STOP").open("x", encoding="ascii") as f: f.write("OWNED_RESUME_STOP\n")
            except FileExistsError: pass
            except Exception: status = "BLOCKED";error = error or "C_RESUME_STOP_FILE"
        for role in ("app", "oracle", "anvil", "pg", "copy"):
            if role not in attempted: continue
            try:
                info = inspect(run_id + "-" + role);ensure(info["Config"].get("Labels", {}).get("tms.c.owner") == run_id, "STOP_OWNER")
                if info["State"]["Running"]: docker(["stop", "-t", "20", info["Id"]], timeout=25)
                state = inspect(info["Id"])["State"];ok = not state.get("Running") and not state.get("OOMKilled") and not state.get("Error") and state.get("ExitCode") in {0, 143}
                states[role] = {"normalStop": ok, "exitCode": state.get("ExitCode"), "oomKilled": state.get("OOMKilled")};ensure(ok, "NORMAL_STOP")
            except Exception: status = "BLOCKED";error = error or "C_RESUME_STOP_UNCONFIRMED"
        for kind, name in (("network", run_id + "-network"), ("volume", run_id + "-pg-data"), ("volume", run_id + "-state")):
            try: preserved[name] = json.loads(docker([kind, "inspect", name]).stdout)[0].get("Labels", {}).get("tms.c.owner") == run_id
            except Exception: preserved[name] = False
        state_file = None
        if states.get("anvil", {}).get("normalStop"):
            try:
                proc = subprocess.Popen(["docker", "cp", anvil_id + ":/state/anvil.json", "-"], cwd=root, env=base.clean_env(), stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
                data = [];reader = threading.Thread(target=lambda: data.append(proc.stdout.read(16 * 1024**2 + 10241)), daemon=True);reader.start();reader.join(15)
                if reader.is_alive() or not data or len(data[0]) > 16 * 1024**2 + 10240: proc.kill();proc.wait(timeout=3);raise ValueError("C_RESUME_STATE_BOUND")
                ensure(proc.wait(timeout=3) == 0, "STATE_COPY");proc.stdout.close();helper = load_module(root, "c-anvil-check.py", "c_resume_state_reader");content = helper.extract_state_tar(data[0])
                with (out / "anvil-state.private.json").open("xb") as f: f.write(content)
                state_file = {"sha256": hashlib.sha256(content).hexdigest(), "bytes": len(content)}
            except Exception: status = "BLOCKED";error = error or "C_RESUME_STATE_UNCONFIRMED"
        try: unchanged = all(base.sha(old / p) == h for p, h in hashes.items()) and all(base.sha(failed / p) == h for p, h in FAILED_ANCHORS.items()) and base.sha(ancestor_claim) == ancestor_claim_hash and base.sha(Path(__file__)) == supervisor_hash
        except Exception: unchanged = False
        if not unchanged or not all(preserved.values()): status = "BLOCKED";error = error or "C_RESUME_PRESERVATION"
        base.new_json(out / "result.json", {"runId": run_id, "parent": parent, "parentState": snapshot_sha, "status": status, "stage": stage, "safeError": error, "sourceIdentity": recovery_source,
                      "clockSyncRequests": clock_requests, "newKeys": 0, "bootstrapCalls": 0, "automaticTransactions": 0, "originalUnchanged": unchanged, "stopped": states, "preserved": preserved, "anvilState": state_file,
                      "commands": commands, "elapsedSeconds": time.monotonic() - began, "nextResumeParentMustBe": run_id, "claimRetained": claim.is_file(), "fullAudit": False, "userTrades": "NOT_ASSESSED"})
    print(json.dumps({"runId": run_id, "status": status, "safeError": error}), flush=True)
    return 0 if status.startswith("READY_") else 1


if __name__ == "__main__":
    ensure(len(sys.argv) == 1, "NO_MUTABLE_ARGUMENTS")
    raise SystemExit(run(Path(__file__).resolve().parent.parent))
