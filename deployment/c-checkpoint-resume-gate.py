"""Normally stopped post-trade checkpoint restore Gate. No user window or transaction replay.

Uses frozen production artifacts and the successful resume unit's isolation primitives.
The old zero-trade/pre-public-failure admission is deliberately NOT used. New output,
parent claim, PG volume, Anvil state, control and evidence are preserved on all outcomes.
"""
import hashlib
from local_demo_credentials import local_demo_password
import importlib.util
import json
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.request

sys.dont_write_bytecode = True
import c_checkpoint_profile as profile
from c_direct_browser_relay import BrowserRelay


def module(root, filename, name):
    spec = importlib.util.spec_from_file_location(name, root / "deployment" / filename)
    result = importlib.util.module_from_spec(spec);spec.loader.exec_module(result)
    return result


def metadata_sql():
    tables = {"drafts": ("tms_c_execution.drafts", "operation_id"), "claims": ("tms_c_execution.direct_execution_claims", "operation_id"),
              "executions": ("tms_c_execution.direct_executions", "operation_id"), "consents": ("tms_c_execution.consents", "operation_id"),
              "users": ("public.users", "id"), "walletLinks": ("tms_c_identity.wallet_links", "user_id")}
    fields = []
    for key, (table, order) in tables.items():
        fields.extend(["'" + key + "',(SELECT count(*) FROM " + table + ")", "'" + key + "Hash',(SELECT encode(sha256(convert_to(coalesce(string_agg(to_jsonb(t)::text,'|' ORDER BY " + order + "),''),'UTF8')),'hex') FROM " + table + " t)"])
    return "SELECT json_build_object(" + ",".join(fields) + ");"


def verify_metadata(value):
    for key in ("drafts", "claims", "executions", "consents"):
        profile.require(value.get(key) == profile.EXPECTED[key], "FINANCIAL_METADATA_" + key.upper())
    profile.require(value.get("users") == 2 and value.get("walletLinks") == 1, "USER_METADATA")


def verify_terminal_lock(raw, owner):
    profile.require(bool(re.fullmatch(r"0x[0-9a-fA-F]{192}", raw)), "POSITION_LOCK_ABI")
    words = [int(raw[index:index + 64], 16) for index in range(2, len(raw), 64)]
    profile.require(words == [int(owner, 16), 0, 1], "TERMINAL_POSITION_LOCK")


def run(root):
    local_demo_password()
    base = module(root, "c-direct-demo-check.py", "checkpoint_base")
    browser = module(root, "c-direct-browser-run.py", "checkpoint_browser")
    probe = module(root, "c-anvil-snapshot-clone-check.py", "checkpoint_history")
    profile.require(base.sha(root / "deployment/c-direct-same-state-resume.py") == profile.SUCCESS_HELPER_SHA, "FROZEN_SUCCESS_HELPER")
    reuse = module(root, "c-direct-same-state-resume.py", "checkpoint_reuse")
    original, _, identity, owner, _, _, data_source = probe.load_original(root)
    parent, parent_result, lineage, manual, snapshot_path, snapshot, receipts = profile.load(root, probe.HISTORY)
    manifest = reuse.read_json(original / "driver-fixture/manifest.json")
    relay_template = reuse.read_json(parent / "relay.json")
    prepared = root / "deployment/runtime/c-direct-browser-prepare" / browser.PREPARED_ID
    prep = reuse.read_json(prepared / "result.json")
    profile.require(prep["sourceIdentity"] == reuse.read_json(original / "execution-identity.json")["preparedSourceIdentity"] and all(base.sha(root / p) == h for p, h in prep["sources"].items()) and all(base.tree_hashes(prepared / (r + "-fixture")) == h for r, h in prep["fixtureHashes"].items()) and base.checked_web_tree(prepared / "web-fixture") == prep["webHashes"], "FROZEN_ARTIFACTS")
    tracked = {str(parent / rel): digest for rel, digest in profile.ANCHORS.items()}
    tracked[str(snapshot_path)] = profile.SNAPSHOT_SHA
    for path in (parent / "appConfig/application.properties", parent / "appConfig/oracle-token.private", original / "pg.private.env", original / "oracleConfig/identity.json", original / "oracleConfig/oracle-key.private", original / "oracleConfig/auth-token.private", root / "deployment/c-checkpoint-resume-gate.py", root / "deployment/c_checkpoint_profile.py"):
        tracked[str(path)] = base.sha(path)
    start = time.monotonic();deadline = start + 240;total = deadline + 90
    commands = [];attempted = set();stopped = {};relay = None;clock_requests = 0
    def docker(args, timeout=10, stdin=None, check=True):
        remaining = int(deadline - time.monotonic());profile.require(remaining > 0, "DEADLINE")
        p = subprocess.run(["docker", *args], cwd=root, env=base.clean_env(), input=stdin, capture_output=True, text=True, timeout=min(timeout, remaining))
        commands.append({"args": args, "exitCode": p.returncode, "stdoutBytes": len(p.stdout), "stderrBytes": len(p.stderr)})
        profile.require(not check or p.returncode == 0, "DOCKER_EXIT")
        return p
    def inspect(name): return json.loads(docker(["container", "inspect", name]).stdout)[0]
    ram = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "(Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory"], env=base.clean_env(), capture_output=True, text=True, timeout=10)
    profile.require(ram.returncode == 0 and ram.stdout.strip().isdigit() and int(ram.stdout.strip()) * 1024 >= base.MIN_RAM and shutil.disk_usage(root).free >= base.MIN_DISK, "RESOURCES")
    old_infos = {}
    for role in ("copy", "pg", "anvil", "oracle", "app"):
        info = inspect(profile.PARENT + "-" + role);reuse.check_old_role(info, profile.PARENT, role, base.PG_IMAGE if role in {"pg", "copy"} else base.FOUNDRY_IMAGE if role == "anvil" else base.JAVA_IMAGE);old_infos[role] = info
    children = docker(["ps", "-a", "--filter", "label=tms.c.parent=" + profile.PARENT, "--format", "{{.ID}}"]).stdout.splitlines()
    reuse.no_live_parent_children([inspect(c) for c in children], profile.PARENT, profile.SNAPSHOT_SHA)
    claim = reuse.claim_path(root, profile.PARENT, profile.SNAPSHOT_SHA);profile.require(not claim.exists(), "CHECKPOINT_ALREADY_CLAIMED")
    # The previous parent claim must point at this exact latest checkpoint; never restart ancestors.
    previous = reuse.claim_path(root, lineage["parent"], lineage["parentState"])
    previous_value = reuse.read_json(previous)
    profile.require(previous_value.get("child") == profile.PARENT and previous_value.get("sourceIdentity") == profile.SOURCE, "LATEST_LINEAGE_CLAIM")
    for ancestor in sorted({profile.DATA_PARENT, lineage["parent"]}):
        profile.require(docker(["ps", "--filter", "label=tms.c.owner=" + ancestor, "--format", "{{.ID}}"]).stdout.strip() == "", "NO_LIVE_ANCESTOR")
    tracked[str(previous)] = base.sha(previous)
    source = hashlib.sha256(json.dumps(tracked, sort_keys=True).encode()).hexdigest()
    for image in (base.PG_IMAGE, base.JAVA_IMAGE, base.FOUNDRY_IMAGE): profile.require(docker(["image", "inspect", image, "--format", "{{.Id}}|{{.Os}}|{{.Architecture}}"]).stdout.strip() == image + "|linux|amd64", "IMAGE")
    for port in (32171, 32172):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
            if hasattr(socket, "SO_EXCLUSIVEADDRUSE"): listener.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
            listener.bind(("127.0.0.1", port))
    nets = docker(["network", "ls", "--format", "{{.ID}}"]).stdout.splitlines();profile.require(len(nets) <= 1024, "NETWORKS")
    occupied = []
    for index in range(0, len(nets), 64):
        for line in docker(["network", "inspect", *nets[index:index + 64], "--format", "{{json .IPAM.Config}}"]).stdout.splitlines(): occupied.extend(v["Subnet"] for v in json.loads(line) or [] if v.get("Subnet"))
    routes = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "@(Get-NetRoute -AddressFamily IPv4 -ErrorAction Stop | Select-Object -ExpandProperty DestinationPrefix) | ConvertTo-Json -Compress"], env=base.clean_env(), capture_output=True, text=True, timeout=10)
    profile.require(routes.returncode == 0, "ROUTES");r = json.loads(routes.stdout);subnet = base.select_subnet(occupied, [r] if isinstance(r, str) else r)
    mounts = [m for m in old_infos["pg"]["Mounts"] if m.get("Destination") == "/var/lib/postgresql/data"]
    profile.require(len(mounts) == 1 and mounts[0].get("Name") == profile.PARENT + "-pg-data" and mounts[0].get("Type") == "volume", "PARENT_PG_VOLUME")
    source_volume = mounts[0]["Name"]
    profile.require(json.loads(docker(["volume", "inspect", source_volume]).stdout)[0].get("Labels", {}).get("tms.c.owner") == profile.PARENT, "PARENT_VOLUME_OWNER")
    import uuid
    run_id = "cdemo-" + uuid.uuid4().hex[:16]
    out = root / "deployment/runtime/c-checkpoint-resume" / run_id;out.mkdir(parents=True, exist_ok=False)
    claim.parent.mkdir(parents=True, exist_ok=True)
    with claim.open("x", encoding="utf-8") as f: json.dump({"parent": profile.PARENT, "child": run_id, "snapshotSha256": profile.SNAPSHOT_SHA, "sourceIdentity": source, "failedClaimIsRetained": True}, f)
    paths = {"server": prepared / "server-fixture", "oracle": prepared / "oracle-fixture", "web": prepared / "web-fixture", "oracleConfig": original / "oracleConfig"}
    for key in ("appConfig", "appControl", "appEvidence", "oracleControl", "oracleEvidence", "relayControl"):
        paths[key] = out / key;paths[key].mkdir()
    for filename in ("application.properties", "oracle-token.private"): shutil.copyfile(parent / "appConfig" / filename, paths["appConfig"] / filename)
    env_files = {"pg": original / "pg.private.env"}
    base.new_json(out / "checkpoint-lineage.json", {"parent": profile.PARENT, "parentState": profile.SNAPSHOT_SHA, "child": run_id, "dataRun": profile.DATA_PARENT, "dataSource": data_source, "sourceIdentity": source, "profileSource": profile.SOURCE, "inputs": tracked, "expected": profile.EXPECTED, "receiptCount": 8, "mode": "VERIFY_AND_STOP_NOT_USER_WINDOW", "claim": str(claim), "nextResumeMustUseThisChild": True})
    stage = "COPY_PG";status = "BLOCKED";error = None;anvil_id = None;state_file = None;preserved = {}
    def args_for(role):
        args = browser.app_args(base, run_id, paths, env_files) if role == "app" else browser.oracle_args(base, run_id, paths, env_files, 3600) if role == "oracle" else base.launch_args(role, run_id, paths, env_files)
        if role == "anvil":
            at = args.index("--entrypoint");end = args.index(base.FOUNDRY_IMAGE) + 1
            args = args[:at] + ["--mount", base.bind(snapshot_path, "/snapshot/anvil-state.private.json", True)] + args[at:end] + ["--host", "0.0.0.0", "--port", "8545", "--chain-id", "31337", "--accounts", "0", "--block-time", "1", "--silent", "--load-state", "/snapshot/anvil-state.private.json", "--dump-state", "/state/anvil.json"]
        return args[:3] + ["--label", "tms.c.parent=" + profile.PARENT, "--label", "tms.c.parent-state=" + profile.SNAPSHOT_SHA] + args[3:]
    def launch(role):
        attempted.add(role);args = args_for(role);docker(args);info = inspect(run_id + "-" + role)
        profile.require(info["Config"]["Labels"].get("tms.c.owner") == run_id and info["Config"]["Labels"].get("tms.c.parent") == profile.PARENT and info["Config"]["Labels"].get("tms.c.parent-state") == profile.SNAPSHOT_SHA, "CHILD_LABEL")
        if role == "app": browser.validate_selected_java(base, info, role, run_id, paths, env_files, owner)
        elif role == "oracle": browser.validate_oracle(base, info, run_id, paths, env_files, 3600)
        elif role == "pg": base.validate_container(info, role, run_id, paths, env_files)
        else:
            c, h, n = info["Config"], info["HostConfig"], info["NetworkSettings"];at = args.index("--entrypoint")
            profile.require(info["Image"] == base.FOUNDRY_IMAGE and c.get("User") == "0:0" and h.get("NetworkMode") == run_id + "-network" and set(n.get("Networks", {})) == {run_id + "-network"} and not h.get("PortBindings") and not any((n.get("Ports") or {}).values()) and not h.get("Privileged") and not h.get("CapAdd") and h.get("CapDrop") == ["ALL"] and h.get("Memory") == 192 * 1024**2 and h.get("ReadonlyRootfs") is True and any(v in {"no-new-privileges", "no-new-privileges:true"} for v in h.get("SecurityOpt", [])), "ANVIL_ISOLATION")
            profile.require(c.get("Entrypoint") == [args[at + 1]] and c.get("Cmd") == args[at + 3:], "ANVIL_COMMAND")
            ms = {m["Destination"]: m for m in info["Mounts"] if m.get("Type") != "tmpfs"}
            profile.require(set(ms) == {"/state", "/snapshot/anvil-state.private.json"} and ms["/state"].get("Type") == "volume" and ms["/state"].get("Name") == run_id + "-state" and ms["/state"].get("RW") is True and ms["/snapshot/anvil-state.private.json"].get("Type") == "bind" and ms["/snapshot/anvil-state.private.json"].get("RW") is False and reuse.mount_path(ms["/snapshot/anvil-state.private.json"]["Source"]) == reuse.mount_path(snapshot_path), "ANVIL_MOUNTS")
        return info["Id"]
    def rpc(method, *params):
        nonlocal clock_requests
        profile.require(method in probe.READ_METHODS | {"eth_pendingTransactions", "evm_setNextBlockTimestamp"}, "READ_METHOD")
        if method == "evm_setNextBlockTimestamp":
            profile.require(clock_requests == 0 and len(params) == 1 and type(params[0]) is int and abs(params[0] - time.time()) <= 1.5, "ONCE_CURRENT_WALL")
            clock_requests = 1;base.new_json(out / "clock-sync-claim.json", {"requests": 1, "wall": params[0], "basis": "APPROVED_CHILD_ONLY_CURRENT_WALL_NO_TRANSACTION"})
        return json.loads(docker(["exec", anvil_id, "/usr/local/bin/cast", "rpc", "--rpc-url", "http://127.0.0.1:8545", method, *[p if isinstance(p, str) else json.dumps(p, separators=(",", ":")) for p in params]]).stdout)
    def keccak(value): return docker(["exec", "-i", anvil_id, "/usr/local/bin/cast", "keccak"], stdin=value).stdout.strip()
    def raw_call(target, signature, arg=""):
        selector = docker(["exec", anvil_id, "/usr/local/bin/cast", "sig", signature]).stdout.strip()
        return rpc("eth_call", {"to": target, "data": selector + arg}, "latest")
    def chain_check(label):
        q = profile.quantity;token, controller, vault = (identity[k]["address"] for k in ("token", "controller", "vault"))
        profile.require(q(rpc("eth_chainId")) == 31337 and rpc("eth_getBlockByNumber", "0x0", False)["hash"] == identity["genesisHash"], "CHAIN_GENESIS")
        for role in ("token", "controller", "rules", "vault"): profile.require(keccak(rpc("eth_getCode", identity[role]["address"], "latest")) == identity[role]["codeHash"], "FOUR_CODEHASHES")
        profile.require(q(rpc("eth_getTransactionCount", owner, "latest")) == q(rpc("eth_getTransactionCount", owner, "pending")) == 8 and rpc("eth_pendingTransactions") == [], "PENDING_NONCE")
        state = {"controllerNonce": q(raw_call(controller, "nonces(address)", probe.address_word(owner))), "wallet": q(raw_call(token, "balanceOf(address)", probe.address_word(owner))), "free": q(raw_call(vault, "freeBalance(address)", probe.address_word(owner))), "locked": q(raw_call(vault, "lockedBalance(address)", probe.address_word(owner))), "unpaid": q(raw_call(vault, "totalUnpaidProfit()")), "house": q(raw_call(vault, "houseBalance()"))}
        profile.require(all(state[k] == profile.EXPECTED[k] for k in state) and q(raw_call(vault, "totalLocked()")) == 0 and q(rpc("eth_getBalance", owner, "latest")) == q(snapshot["accounts"][owner]["balance"]), "FINAL_ASSETS")
        backing = {"actualVaultTokens": q(raw_call(token, "balanceOf(address)", probe.address_word(vault))), "recognizedAssets": q(raw_call(vault, "recognizedAssets()")), "unassignedAssets": q(raw_call(vault, "unassignedAssets()"))}
        profile.require(backing["actualVaultTokens"] == backing["recognizedAssets"] == profile.EXPECTED["house"] and backing["unassignedAssets"] == 0, "VAULT_BACKING")
        verify_terminal_lock(raw_call(vault, "positionLocks(bytes32)", manual["positionId"][2:]), owner)
        account = keccak(keccak("TMS_C_FUTURES_WALLET_ACCOUNT_V1") + probe.address_word(owner) + identity["symbolHash"][2:])
        profile.require(q(raw_call(identity["rules"]["address"], "activePosition(bytes32)", account[2:])) == 0, "NO_ACTIVE_POSITION")
        pos = raw_call(identity["rules"]["address"], "getPosition(bytes32)", manual["positionId"][2:]);words = [int(pos[i:i + 64], 16) for i in range(2, len(pos), 64)]
        profile.require(len(words) == 11 and words[6] == profile.EXPECTED["positionStatus"] and words[7] == profile.EXPECTED["positionVersion"], "CLOSED_POSITION_VERSION")
        for action, expected in receipts.items():
            txhash = expected["info"]["transaction_hash"];number = q(expected["block_number"]);blockhash = expected["block_hash"]
            receipt = rpc("eth_getTransactionReceipt", txhash);tx = rpc("eth_getTransactionByHash", txhash);block = rpc("eth_getBlockByNumber", hex(number), False)
            profile.require(receipt and tx and q(receipt["status"]) == 1 and receipt["transactionHash"] == txhash and receipt["blockHash"] == blockhash == block["hash"] and q(receipt["blockNumber"]) == number and txhash in block["transactions"] and tx["from"] == owner and tx["to"] == expected["info"]["to"] and q(tx["nonce"]) == q(expected["info"]["nonce"]) and probe.logs_projection(receipt["logs"]) == probe.logs_projection(expected["receipt"]["logs"]), "EIGHT_CANONICAL_RECEIPTS")
        base.new_json(out / (label + ".json"), {"eoaNonce": 8, "assets": {k: str(v) for k, v in state.items()}, "vaultBacking": {k: str(v) for k, v in backing.items()}, "positionLock": {"owner": owner, "amount": "0", "terminal": True}, "position": manual["positionId"], "status": "CLOSED", "version": 2, "receiptCount": 8, "restoredRpcPending": 0, "fullAudit": False})
    def sql(statement):
        return json.loads(docker(["exec", "-i", run_id + "-pg", "/bin/sh", "-ceu", 'PGPASSWORD="$POSTGRES_PASSWORD" exec psql -X -qAt -v ON_ERROR_STOP=1 -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'], stdin=statement).stdout)
    def ready(role, path):
        end = time.monotonic() + 90
        while time.monotonic() < end:
            data = browser.published_ready(path)
            if data is not None: return data
            profile.require(inspect(run_id + "-" + role)["State"]["Running"], "SERVICE_START")
            time.sleep(.5)
        raise ValueError("C_CHECKPOINT_SERVICE_TIMEOUT")
    try:
        for kind, name in (("network", run_id + "-network"), ("volume", run_id + "-pg-data"), ("volume", run_id + "-state"), *[("container", run_id + "-" + r) for r in ("copy", "pg", "anvil", "oracle", "app")]): profile.require(docker([kind, "inspect", name], check=False).returncode != 0, "COLLISION")
        docker(["network", "create", "--internal", "--subnet", subnet, "--label", "tms.c.owner=" + run_id, run_id + "-network"]);network = json.loads(docker(["network", "inspect", run_id + "-network"]).stdout)[0];profile.require(network.get("Internal") is True and network.get("Labels", {}).get("tms.c.owner") == run_id, "INTERNAL_NETWORK")
        for name in (run_id + "-pg-data", run_id + "-state"): docker(["volume", "create", "--label", "tms.c.owner=" + run_id, name])
        attempted.add("copy");docker(["run", "-d", "--pull=never", "--name", run_id + "-copy", "--label", "tms.c.owner=" + run_id, "--label", "tms.c.parent=" + profile.PARENT, "--label", "tms.c.parent-state=" + profile.SNAPSHOT_SHA, "--network", "none", "--read-only", "--memory=256m", "--pids-limit=64", "--cap-drop=ALL", "--cap-add=DAC_OVERRIDE", "--cap-add=CHOWN", "--cap-add=FOWNER", "--security-opt=no-new-privileges", "--tmpfs", "/var/lib/postgresql/data:rw,noexec,nosuid,size=1m", "--mount", "type=volume,source=" + source_volume + ",target=/from,readonly", "--mount", "type=volume,source=" + run_id + "-pg-data,target=/to", "--entrypoint", "/bin/bash", base.PG_IMAGE, "-ceu", reuse.COPY])
        reuse.validate_copy(inspect(run_id + "-copy"), run_id, profile.PARENT, profile.SNAPSHOT_SHA, source_volume, run_id + "-pg-data", base.PG_IMAGE)
        profile.require(docker(["wait", run_id + "-copy"], timeout=60).stdout.strip() == "0", "COPY_EXIT")
        text = docker(["logs", run_id + "-copy"]).stdout;ids = re.findall(r"Database system identifier:\s+(\d+)", text);profile.require(ids == ["7699878592856473638"] * 2 and "C_RESUME_PG_COLD_COPY_MATCH " in text, "PG_SYSTEM_ID")
        copy_hash = re.findall(r"C_RESUME_PG_COLD_COPY_MATCH ([0-9a-f]{64})", text);profile.require(len(copy_hash) == 1, "PG_COPY_HASH_EVIDENCE")
        base.new_json(out / "pg-copy.json", {"systemIdentifier": ids[0], "byteClone": True, "sourceReadOnly": True, "contentHash": copy_hash[0]})
        stage = "PG_ANVIL";launch("pg");anvil_id = launch("anvil")
        for _ in range(10):
            ping = docker(["exec", anvil_id, "/usr/local/bin/cast", "rpc", "--rpc-url", "http://127.0.0.1:8545", "eth_chainId"], timeout=3, check=False)
            if ping.returncode == 0: break
            time.sleep(.5)
        else: raise ValueError("C_CHECKPOINT_ANVIL_READY")
        for _ in range(30):
            if docker(["exec", run_id + "-pg", "pg_isready", "-h", "127.0.0.1"], check=False).returncode == 0: break
            time.sleep(.5)
        else: raise ValueError("C_CHECKPOINT_PG_READY")
        db = sql("SELECT json_build_object('systemId',(SELECT system_identifier::text FROM pg_control_system()),'database',current_database(),'runId',(SELECT run_id FROM public.c_direct_demo_identity),'marker',(SELECT marker FROM public.c_direct_demo_identity),'wallet',(SELECT trim(wallet) FROM tms_c_identity.wallet_links WHERE user_id=1));")
        profile.require(db["systemId"] == ids[0] and db["database"] == manifest["database"] and db["runId"] == profile.DATA_PARENT and db["marker"] == manifest["marker"] and db["wallet"] == owner, "DB_IDENTITY")
        before_metadata = sql(metadata_sql());verify_metadata(before_metadata);base.new_json(out / "metadata-before.json", before_metadata)
        stage = "PRE_SYNC_CHAIN";chain_check("before-clock")
        stage = "ONCE_WALL_SYNC";previous_block = rpc("eth_getBlockByNumber", "latest", False);requested_wall = int(time.time());rpc("evm_setNextBlockTimestamp", requested_wall)
        for _ in range(10):
            latest = rpc("eth_getBlockByNumber", "latest", False)
            if reuse.clock_block_applied(latest, previous_block, requested_wall): break
            time.sleep(.5)
        else: raise ValueError("C_CHECKPOINT_CLOCK_BLOCK")
        profile.require(abs(profile.quantity(latest["timestamp"]) - time.time()) <= 2, "CLOCK_OFFSET")
        stage = "POST_SYNC_CHAIN";chain_check("after-clock")
        profile.require(abs(profile.quantity(rpc("eth_getBlockByNumber", "latest", False)["timestamp"]) - time.time()) <= 2, "CLOCK_POST_CHECK")
        stage = "SERVICES";launch("oracle");oracle = ready("oracle", paths["oracleEvidence"] / "ready.json");app_id = launch("app");app = ready("app", paths["appEvidence"] / "server-ready.json")
        profile.require(app.get("runId") == profile.DATA_PARENT and app.get("sourceIdentity") == data_source and app.get("sessionSeconds") == app.get("jwtValidityMs", 0) // 1000 == 3600 and all(app.get(k) is False for k in ("userPrivateKeys", "oraclePrivateKeys", "relayerPrivateKeys")) and oracle.get("oracle") == identity["oracle"], "KEYLESS_ORIGINAL_SERVICES")
        stage = "HTTP_READS";meta = dict(relay_template, runId=run_id, appId=app_id, networkId=network["Id"], sourceIdentity=source, seconds=3600);base.new_json(out / "relay.json", meta);relay = BrowserRelay(meta, paths["relayControl"]).start()
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *unused): raise ValueError("C_CHECKPOINT_REDIRECT")
        http = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect());origin = relay_template["origin"]
        def request(path, data=None, jwt=None):
            headers = {"Origin": origin}
            if data is not None: headers["Content-Type"] = "application/json"
            if jwt: headers["Authorization"] = "Bearer " + jwt
            with http.open(urllib.request.Request(origin + path, data=None if data is None else json.dumps(data).encode(), headers=headers), timeout=20) as response:
                body = response.read(1024**2 + 1);profile.require(response.status == 200 and len(body) <= 1024**2, "HTTP_RESPONSE");return body
        profile.require(hashlib.sha256(request("/c-demo.html")).hexdigest() == base.sha(paths["web"] / "c-demo.html"), "STATIC_BYTES")
        jwt = json.loads(request("/api/auth/login", {"loginId": "c-demo-user", "password": local_demo_password()}))["accessToken"]
        profile.require(json.loads(request("/api/me", jwt=jwt)).get("id") == "1" and json.loads(request("/api/c-futures/wallet", jwt=jwt)).get("wallet") == owner, "JWT_WALLET")
        config = json.loads(request("/api/c-futures/config", jwt=jwt));account = json.loads(request("/api/c-futures/account", jwt=jwt))["value"];position = json.loads(request("/api/c-futures/positions/" + manual["positionId"], jwt=jwt))["value"];market = json.loads(request("/api/c-futures/direct/market", jwt=jwt))
        profile.require(config.get("deploymentId") == oracle.get("deploymentId") and account["walletBalance"] == str(profile.EXPECTED["wallet"]) and account["house"] == str(profile.EXPECTED["house"]) and account["free"] == account["locked"] == account["totalUnpaidProfit"] == "0" and account["userNonce"] == "2" and position["status"] == "CLOSED" and position["version"] == "2" and market.get("provider") == "SIMULATED", "HTTP_CURRENT_STATE")
        http_receipts = []
        for action, expected in receipts.items():
            txhash = expected["info"]["transaction_hash"];suffix = ""
            if action in {"OPEN", "CLOSE"}:
                event = [log for log in expected["receipt"]["logs"] if log["address"] == identity["controller"]["address"]]
                profile.require(len(event) == 1 and len(event[0]["topics"]) == 4, "EXECUTION_EVENT_LINK")
                intent = event[0]["topics"][1];suffix = "?intentId=" + intent
                json.loads(request("/api/c-futures/direct/intents/" + intent + "/execution", jwt=jwt))
            observed = json.loads(request("/api/c-futures/direct/transactions/" + txhash + suffix, jwt=jwt));profile.require(observed.get("status") == "CONFIRMED", "HTTP_CANONICAL_RECEIPT");http_receipts.append({"action": action, "txHash": txhash, "status": "CONFIRMED"})
        del jwt
        # Close public access before the final read-only checks; no presentation window is opened.
        relay.close();relay.wait();relay = None
        profile.require(profile.quantity(rpc("eth_getTransactionCount", owner, "latest")) == profile.quantity(rpc("eth_getTransactionCount", owner, "pending")) == 8 and rpc("eth_pendingTransactions") == [], "NO_EXTERNAL_TRANSACTION_DURING_GATE")
        after_metadata = sql(metadata_sql());profile.require(after_metadata == before_metadata, "READS_MUTATED_FINANCIAL_METADATA");base.new_json(out / "metadata-after.json", after_metadata)
        base.new_json(out / "http-read-gate.json", {"receiptCount": 8, "receipts": http_receipts, "jwtWallet": True, "position": "CLOSED_V2", "noNewFinancialMetadata": True, "fullAudit": False, "userWindowOpened": False})
        status = "PASS_READ_ONLY_CHECKPOINT_SERVICE_RECOVERY_NOT_NEW_USER_TRADE_OR_FULL_AUDIT";stage = "VERIFIED_NORMAL_STOP"
    except Exception as failure:
        error = str(failure) if isinstance(failure, ValueError) and str(failure).startswith(("C_CHECKPOINT_", "C_RESUME_")) else "C_CHECKPOINT_SAFE_" + type(failure).__name__
    finally:
        deadline = total
        if relay:
            try: relay.close();relay.wait()
            except Exception: status = "BLOCKED";error = error or "C_CHECKPOINT_RELAY_STOP"
        for role in ("app", "oracle"):
            try:
                with (paths[role + "Control"] / "STOP").open("x", encoding="ascii") as f: f.write("READ_ONLY_GATE_DONE\n")
            except FileExistsError: pass
            except Exception: status = "BLOCKED";error = error or "C_CHECKPOINT_STOP_FILE"
        for role in ("app", "oracle", "anvil", "pg", "copy"):
            if role not in attempted: continue
            try:
                info = inspect(run_id + "-" + role);profile.require(info["Config"].get("Labels", {}).get("tms.c.owner") == run_id, "STOP_OWNER")
                if info["State"]["Running"]: docker(["stop", "-t", "20", info["Id"]], timeout=25)
                state = inspect(info["Id"])["State"];ok = not state.get("Running") and not state.get("OOMKilled") and not state.get("Error") and state.get("ExitCode") in {0, 143};stopped[role] = {"normalStop": ok, "exitCode": state.get("ExitCode"), "oomKilled": state.get("OOMKilled")};profile.require(ok, "NORMAL_STOP")
            except Exception: status = "BLOCKED";error = error or "C_CHECKPOINT_STOP_UNCONFIRMED"
        for kind, name in (("network", run_id + "-network"), ("volume", run_id + "-pg-data"), ("volume", run_id + "-state")):
            try: preserved[name] = json.loads(docker([kind, "inspect", name]).stdout)[0].get("Labels", {}).get("tms.c.owner") == run_id
            except Exception: preserved[name] = False
        if stopped.get("anvil", {}).get("normalStop"):
            try:
                proc = subprocess.Popen(["docker", "cp", anvil_id + ":/state/anvil.json", "-"], cwd=root, env=base.clean_env(), stdout=subprocess.PIPE, stderr=subprocess.DEVNULL);data = []
                reader = threading.Thread(target=lambda: data.append(proc.stdout.read(16 * 1024**2 + 10241)), daemon=True);reader.start();reader.join(15)
                if reader.is_alive() or not data or len(data[0]) > 16 * 1024**2 + 10240: proc.kill();proc.wait(timeout=3);raise ValueError("C_CHECKPOINT_DUMP_BOUND")
                profile.require(proc.wait(timeout=3) == 0, "DUMP_COPY");proc.stdout.close();helper = module(root, "c-anvil-check.py", "checkpoint_tar");content = helper.extract_state_tar(data[0])
                with (out / "anvil-state.private.json").open("xb") as f: f.write(content)
                state_file = {"sha256": hashlib.sha256(content).hexdigest(), "bytes": len(content), "head": profile.quantity(json.loads(content)["best_block_number"])}
            except Exception: status = "BLOCKED";error = error or "C_CHECKPOINT_DUMP_UNCONFIRMED"
        try: unchanged = all(base.sha(Path(p)) == h for p, h in tracked.items())
        except Exception: unchanged = False
        if not unchanged or not all(preserved.values()) or (attempted and not state_file): status = "BLOCKED";error = error or "C_CHECKPOINT_PRESERVATION"
        base.new_json(out / "result.json", {"runId": run_id, "parent": profile.PARENT, "parentState": profile.SNAPSHOT_SHA, "status": status, "stage": stage, "safeError": error, "sourceIdentity": source, "clockSyncRequests": clock_requests,
                      "newTransactions": 0, "newKeys": 0, "newBootstrap": 0, "financialMetadataWrites": 0, "originalUnchanged": unchanged, "stopped": stopped, "preserved": preserved, "anvilState": state_file, "claimRetained": claim.is_file(),
                      "commands": commands, "elapsedSeconds": time.monotonic() - start, "fullAudit": False, "nextResumeMustUseChild": run_id, "originalManualReceiptCount": 8, "userWindow": False})
    print(json.dumps({"runId": run_id, "status": status, "safeError": error}), flush=True)
    return 0 if status.startswith("PASS_") else 1


if __name__ == "__main__":
    profile.require(len(sys.argv) == 1, "NO_MUTABLE_ARGS")
    raise SystemExit(run(Path(__file__).resolve().parent.parent))
