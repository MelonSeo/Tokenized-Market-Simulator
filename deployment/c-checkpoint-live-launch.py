"""Explicit one-hour local successor. Default is offline plan only: zero Docker/claim calls."""
import argparse
from local_demo_credentials import local_demo_password
import hashlib
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
import uuid

sys.dont_write_bytecode = True
import c_live_resume_support as support
from c_direct_browser_relay import BrowserRelay


def plan(c):
    claim = c.reuse.claim_path(c.root, c.p["parent"], c.p["snapshotSha256"])
    support.require(not claim.exists(), "PARENT_ALREADY_CONSUMED_USE_LATEST_CHILD")
    return {"status": "OFFLINE_PLAN_ONLY_NOT_STARTED", "parent": c.p["parent"], "snapshotSha256": c.p["snapshotSha256"],
            "dataRun": c.p["dataRun"], "seconds": 3600, "dockerCalls": 0, "claimsCreated": 0, "paidCalls": 0,
            "command": "python -B deployment/c-checkpoint-live-launch.py --live-window", "nextResume": "MUST_USE_NEW_CHILD_AFTER_ANY_LIVE_ATTEMPT_NOT_THIS_ANCESTOR"}


def live(c):
    local_demo_password()  # Fail before Docker/admission if local credentials were not supplied.
    b, reuse, browser, root = c.base, c.reuse, c.browser, c.root
    p = c.p;parent_id, parent_sha = p["parent"], p["snapshotSha256"]
    plan(c)
    start = time.monotonic();deadline = start + 3600 + 300;total = deadline + 120
    commands = [];attempted = set();stopped = {};preserved = {};relay = None;clock_requests = 0;anvil_id = None;state_file = None;window_opened = False
    tracked = {str(c.parent / rel): digest for rel, digest in p["anchors"].items()};tracked[str(c.snapshot_path)] = parent_sha
    tracked.update({str(root / rel): digest for rel, digest in p["frozenHelpers"].items()})
    for path in (c.parent / "appConfig/application.properties", c.parent / "appConfig/oracle-token.private", c.original / "pg.private.env", c.original / "oracleConfig/identity.json", c.original / "oracleConfig/oracle-key.private", c.original / "oracleConfig/auth-token.private", root / "deployment/c-live-latest-checkpoint.json", root / "deployment/c_live_resume_support.py", Path(__file__)):
        tracked[str(path)] = b.sha(path)  # No key parsing/logging; no user-key file access.
    def docker(args, timeout=10, stdin=None, check=True):
        remaining = int(deadline - time.monotonic());support.require(remaining > 0, "DEADLINE")
        result = subprocess.run(["docker", *args], cwd=root, env=b.clean_env(), capture_output=True, text=True, input=stdin, timeout=min(timeout, remaining))
        commands.append({"args": args, "exitCode": result.returncode, "stdoutBytes": len(result.stdout), "stderrBytes": len(result.stderr)})
        support.require(not check or result.returncode == 0, "DOCKER_EXIT")
        return result
    def inspect(name): return json.loads(docker(["container", "inspect", name]).stdout)[0]
    ram = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "(Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory"], env=b.clean_env(), capture_output=True, text=True, timeout=10)
    support.require(ram.returncode == 0 and ram.stdout.strip().isdigit() and int(ram.stdout.strip()) * 1024 >= b.MIN_RAM and shutil.disk_usage(root).free >= b.MIN_DISK, "RESOURCES")
    old = {}
    for role in ("copy", "pg", "anvil", "oracle", "app"):
        old[role] = inspect(parent_id + "-" + role);reuse.check_old_role(old[role], parent_id, role, b.PG_IMAGE if role in {"pg", "copy"} else b.FOUNDRY_IMAGE if role == "anvil" else b.JAVA_IMAGE)
    manual_lineage = support.read(root / p["manualDirectory"] / "recovery-lineage.json")
    for ancestor in sorted({p["dataRun"], c.lineage["parent"], manual_lineage["parent"]}): support.require(docker(["ps", "--filter", "label=tms.c.owner=" + ancestor, "--format", "{{.ID}}"]).stdout.strip() == "", "NO_LIVE_ANCESTOR")
    children = docker(["ps", "-a", "--filter", "label=tms.c.parent=" + parent_id, "--format", "{{.ID}}"]).stdout.splitlines();reuse.no_live_parent_children([inspect(n) for n in children], parent_id, parent_sha)
    ancestor_claim = reuse.claim_path(root, c.lineage["parent"], c.lineage["parentState"]);previous = support.read(ancestor_claim)
    support.require(previous.get("child") == parent_id and previous.get("sourceIdentity") == p["parentSource"], "ANCESTOR_CLAIM");tracked[str(ancestor_claim)] = b.sha(ancestor_claim)
    for image in (b.PG_IMAGE, b.JAVA_IMAGE, b.FOUNDRY_IMAGE): support.require(docker(["image", "inspect", image, "--format", "{{.Id}}|{{.Os}}|{{.Architecture}}"]).stdout.strip() == image + "|linux|amd64", "IMAGE")
    for port in (32171, 32172):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
            if hasattr(socket, "SO_EXCLUSIVEADDRUSE"): listener.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
            listener.bind(("127.0.0.1", port))
    nets = docker(["network", "ls", "--format", "{{.ID}}"]).stdout.splitlines();support.require(len(nets) <= 1024, "NETWORKS")
    occupied = []
    for index in range(0, len(nets), 64):
        for line in docker(["network", "inspect", *nets[index:index + 64], "--format", "{{json .IPAM.Config}}"]).stdout.splitlines(): occupied.extend(v["Subnet"] for v in json.loads(line) or [] if v.get("Subnet"))
    routes = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "@(Get-NetRoute -AddressFamily IPv4 -ErrorAction Stop | Select-Object -ExpandProperty DestinationPrefix) | ConvertTo-Json -Compress"], env=b.clean_env(), capture_output=True, text=True, timeout=10)
    support.require(routes.returncode == 0, "ROUTES");r = json.loads(routes.stdout);subnet = b.select_subnet(occupied, [r] if isinstance(r, str) else r)
    mounts = [m for m in old["pg"]["Mounts"] if m.get("Destination") == "/var/lib/postgresql/data"]
    support.require(len(mounts) == 1 and mounts[0].get("Type") == "volume" and mounts[0].get("Name") == parent_id + "-pg-data", "PARENT_VOLUME")
    source_volume = mounts[0]["Name"];support.require(json.loads(docker(["volume", "inspect", source_volume]).stdout)[0].get("Labels", {}).get("tms.c.owner") == parent_id, "PARENT_VOLUME_OWNER")
    support.verify_artifacts(c)  # Exact source inventory and bytes again immediately before admission.
    source = hashlib.sha256(json.dumps(tracked, sort_keys=True).encode()).hexdigest();run_id = "cdemo-" + uuid.uuid4().hex[:16]
    out = root / "deployment/runtime/c-checkpoint-live" / run_id;out.mkdir(parents=True, exist_ok=False)
    claim = reuse.claim_path(root, parent_id, parent_sha);claim.parent.mkdir(parents=True, exist_ok=True)
    with claim.open("x", encoding="utf-8") as f: json.dump({"parent": parent_id, "child": run_id, "snapshotSha256": parent_sha, "sourceIdentity": source, "failedClaimIsRetained": True}, f)
    try:
        paths = {"server": c.prepared / "server-fixture", "oracle": c.prepared / "oracle-fixture", "web": c.prepared / "web-fixture", "oracleConfig": c.original / "oracleConfig"}
        for key in ("appConfig", "appControl", "appEvidence", "oracleControl", "oracleEvidence", "relayControl"):
            paths[key] = out / key;paths[key].mkdir()
        for name in ("application.properties", "oracle-token.private"): shutil.copyfile(c.parent / "appConfig" / name, paths["appConfig"] / name)
        env_files = {"pg": c.original / "pg.private.env"};origin = "http://127.0.0.1:32171"
        b.new_json(out / "live-lineage.json", {"parent": parent_id, "parentState": parent_sha, "child": run_id, "dataRun": p["dataRun"], "dataSource": p["dataSource"], "sourceIdentity": source, "inputs": tracked, "claim": str(claim), "ancestorClaim": str(ancestor_claim), "nextResumeMustUseThisChild": True, "sessionSeconds": 3600, "stateArchiveCapBytes": support.STATE_CAP})
    except Exception as initial_failure:
        # Admission remains spent; no new network/volume/container has yet been requested.
        try: b.new_json(out / "result.json", {"runId": run_id, "parent": parent_id, "status": "BLOCKED", "stage": "POST_CLAIM_PRE_RESOURCE_PREPARATION", "safeExceptionType": type(initial_failure).__name__, "claimRetained": claim.is_file(), "ownedResourceCreates": 0, "userWindowOpened": False})
        except Exception: pass  # Storage failure can leave only the permanent claim; never reset it.
        raise ValueError("C_LIVE_POST_CLAIM_PREPARATION") from None
    status = "BLOCKED";error = None;stage = "NEW_RESOURCES"
    def args_for(role):
        args = browser.app_args(b, run_id, paths, env_files) if role == "app" else browser.oracle_args(b, run_id, paths, env_files, 3600) if role == "oracle" else b.launch_args(role, run_id, paths, env_files)
        if role == "anvil":
            at = args.index("--entrypoint");end = args.index(b.FOUNDRY_IMAGE) + 1
            args = args[:at] + ["--mount", b.bind(c.snapshot_path, "/snapshot/anvil-state.private.json", True)] + args[at:end] + ["--host", "0.0.0.0", "--port", "8545", "--chain-id", "31337", "--accounts", "0", "--block-time", "1", "--silent", "--load-state", "/snapshot/anvil-state.private.json", "--dump-state", "/state/anvil.json"]
        return args[:3] + ["--label", "tms.c.parent=" + parent_id, "--label", "tms.c.parent-state=" + parent_sha] + args[3:]
    def launch(role):
        attempted.add(role);args = args_for(role);docker(args);info = inspect(run_id + "-" + role)
        support.require(info["Config"]["Labels"].get("tms.c.owner") == run_id and info["Config"]["Labels"].get("tms.c.parent") == parent_id and info["Config"]["Labels"].get("tms.c.parent-state") == parent_sha, "CHILD_LABELS")
        if role == "app": browser.validate_selected_java(b, info, role, run_id, paths, env_files, c.owner)
        elif role == "oracle": browser.validate_oracle(b, info, run_id, paths, env_files, 3600)
        elif role == "pg": b.validate_container(info, role, run_id, paths, env_files)
        else: support.validate_anvil(c, info, args, run_id)
        return info["Id"]
    def rpc(method, *params):
        nonlocal clock_requests
        support.require(method in c.history.READ_METHODS | {"eth_pendingTransactions", "evm_setNextBlockTimestamp"}, "RPC_METHOD")
        if method == "evm_setNextBlockTimestamp":
            support.require(clock_requests == 0 and len(params) == 1 and type(params[0]) is int and abs(params[0] - time.time()) <= 1.5, "ONCE_CURRENT_WALL")
            clock_requests = 1;b.new_json(out / "clock-sync-claim.json", {"requests": 1, "timestamp": params[0]})
        return json.loads(docker(["exec", anvil_id, "/usr/local/bin/cast", "rpc", "--rpc-url", "http://127.0.0.1:8545", method, *[v if isinstance(v, str) else json.dumps(v, separators=(",", ":")) for v in params]]).stdout)
    def keccak(value): return docker(["exec", "-i", anvil_id, "/usr/local/bin/cast", "keccak"], stdin=value).stdout.strip()
    def raw_call(target, signature, arg=""):
        selector = docker(["exec", anvil_id, "/usr/local/bin/cast", "sig", signature]).stdout.strip();return rpc("eth_call", {"to": target, "data": selector + arg}, "latest")
    def sql(query): return json.loads(docker(["exec", "-i", run_id + "-pg", "/bin/sh", "-ceu", 'PGPASSWORD="$POSTGRES_PASSWORD" exec psql -X -qAt -v ON_ERROR_STOP=1 -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'], stdin=query).stdout)
    def ready(role, path):
        end = time.monotonic() + 90
        while time.monotonic() < end:
            value = browser.published_ready(path)
            if value is not None: return value
            support.require(inspect(run_id + "-" + role)["State"]["Running"], "SERVICE_START");time.sleep(.5)
        raise ValueError("C_LIVE_READY_TIMEOUT")
    try:
        for kind, name in (("network", run_id + "-network"), ("volume", run_id + "-pg-data"), ("volume", run_id + "-state"), *[("container", run_id + "-" + r) for r in ("copy", "pg", "anvil", "oracle", "app")]): support.require(docker([kind, "inspect", name], check=False).returncode != 0, "COLLISION")
        docker(["network", "create", "--internal", "--subnet", subnet, "--label", "tms.c.owner=" + run_id, run_id + "-network"]);network = json.loads(docker(["network", "inspect", run_id + "-network"]).stdout)[0];support.require(network.get("Internal") is True and network.get("Labels", {}).get("tms.c.owner") == run_id, "INTERNAL_NETWORK")
        for name in (run_id + "-pg-data", run_id + "-state"): docker(["volume", "create", "--label", "tms.c.owner=" + run_id, name])
        stage = "READONLY_PG_CLONE";attempted.add("copy")
        docker(["run", "-d", "--pull=never", "--name", run_id + "-copy", "--label", "tms.c.owner=" + run_id, "--label", "tms.c.parent=" + parent_id, "--label", "tms.c.parent-state=" + parent_sha, "--network", "none", "--read-only", "--memory=256m", "--pids-limit=64", "--cap-drop=ALL", "--cap-add=DAC_OVERRIDE", "--cap-add=CHOWN", "--cap-add=FOWNER", "--security-opt=no-new-privileges", "--tmpfs", "/var/lib/postgresql/data:rw,noexec,nosuid,size=1m", "--mount", "type=volume,source=" + source_volume + ",target=/from,readonly", "--mount", "type=volume,source=" + run_id + "-pg-data,target=/to", "--entrypoint", "/bin/bash", b.PG_IMAGE, "-ceu", reuse.COPY])
        reuse.validate_copy(inspect(run_id + "-copy"), run_id, parent_id, parent_sha, source_volume, run_id + "-pg-data", b.PG_IMAGE)
        support.require(docker(["wait", run_id + "-copy"], timeout=60).stdout.strip() == "0", "COPY_EXIT")
        copied = docker(["logs", run_id + "-copy"]).stdout;ids = re.findall(r"Database system identifier:\s+(\d+)", copied);copy_hash = re.findall(r"C_RESUME_PG_COLD_COPY_MATCH ([0-9a-f]{64})", copied)
        support.require(ids == ["7699878592856473638"] * 2 and len(copy_hash) == 1, "PG_COPY_PROOF");b.new_json(out / "pg-copy.json", {"systemIdentifier": ids[0], "contentHash": copy_hash[0], "byteClone": True, "sourceReadOnly": True})
        stage = "PG_ANVIL";launch("pg");anvil_id = launch("anvil")
        for _ in range(10):
            if docker(["exec", anvil_id, "/usr/local/bin/cast", "rpc", "--rpc-url", "http://127.0.0.1:8545", "eth_chainId"], timeout=3, check=False).returncode == 0: break
            time.sleep(.5)
        else: raise ValueError("C_LIVE_ANVIL_START")
        for _ in range(30):
            if docker(["exec", run_id + "-pg", "pg_isready", "-h", "127.0.0.1"], check=False).returncode == 0: break
            time.sleep(.5)
        else: raise ValueError("C_LIVE_PG_START")
        original_manifest = support.read(c.original / "driver-fixture/manifest.json")
        db = sql("SELECT json_build_object('systemId',(SELECT system_identifier::text FROM pg_control_system()),'database',current_database(),'runId',(SELECT run_id FROM public.c_direct_demo_identity),'marker',(SELECT marker FROM public.c_direct_demo_identity),'wallet',(SELECT trim(wallet) FROM tms_c_identity.wallet_links WHERE user_id=1));")
        support.require(db["systemId"] == ids[0] and db["database"] == original_manifest["database"] and db["runId"] == p["dataRun"] and db["marker"] == original_manifest["marker"] and db["wallet"] == c.owner, "DB_IDENTITY")
        before = sql(c.gate.metadata_sql());c.gate.verify_metadata(before);b.new_json(out / "metadata-before-window.json", before)
        stage = "INITIAL_CHAIN";b.new_json(out / "before-clock.json", support.chain_observation(c, rpc, keccak, raw_call, True));support.verify_receipts(c, rpc)
        stage = "ONCE_CLOCK_SYNC";prior_block = rpc("eth_getBlockByNumber", "latest", False);wall = int(time.time());rpc("evm_setNextBlockTimestamp", wall)
        for _ in range(10):
            block = rpc("eth_getBlockByNumber", "latest", False)
            if reuse.clock_block_applied(block, prior_block, wall): break
            time.sleep(.5)
        else: raise ValueError("C_LIVE_CLOCK_BLOCK")
        support.require(abs(support.q(block["timestamp"]) - time.time()) <= 2, "CLOCK_OFFSET")
        b.new_json(out / "after-clock.json", support.chain_observation(c, rpc, keccak, raw_call, True));support.verify_receipts(c, rpc)
        stage = "SERVICES";earliest_service_start = time.monotonic();launch("oracle");oracle = ready("oracle", paths["oracleEvidence"] / "ready.json");app_id = launch("app");app = ready("app", paths["appEvidence"] / "server-ready.json")
        support.require(app.get("runId") == p["dataRun"] and app.get("sourceIdentity") == p["dataSource"] and app.get("sessionSeconds") == app.get("jwtValidityMs", 0) // 1000 == 3600 and all(app.get(k) is False for k in ("userPrivateKeys", "oraclePrivateKeys", "relayerPrivateKeys")) and oracle.get("oracle") == c.identity["oracle"], "KEYLESS_SERVICES")
        stage = "INITIAL_HTTP";meta = dict(support.read(c.parent / "relay.json"), runId=run_id, appId=app_id, networkId=network["Id"], sourceIdentity=source, seconds=3600);b.new_json(out / "relay.json", meta);relay = BrowserRelay(meta, paths["relayControl"]).start()
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *unused): raise ValueError("C_LIVE_REDIRECT")
        http = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        def request(path, data=None, jwt=None):
            support.require(data is None or path == "/api/auth/login", "SCRIPT_LOGIN_POST_ONLY")
            headers = {"Origin": origin}
            if data is not None: headers["Content-Type"] = "application/json"
            if jwt: headers["Authorization"] = "Bearer " + jwt
            with http.open(urllib.request.Request(origin + path, data=None if data is None else json.dumps(data).encode(), headers=headers), timeout=20) as response:
                body = response.read(1024**2 + 1);support.require(response.status == 200 and len(body) <= 1024**2, "HTTP_RESPONSE");return body
        support.require(hashlib.sha256(request("/c-demo.html")).hexdigest() == b.sha(paths["web"] / "c-demo.html"), "STATIC_BYTES")
        jwt = json.loads(request("/api/auth/login", {"loginId": "c-demo-user", "password": local_demo_password()}))["accessToken"];support.verify_http(c, request, jwt, oracle.get("deploymentId"));del jwt
        initial_after = sql(c.gate.metadata_sql());support.require(initial_after == before, "INITIAL_READS_MUTATED_METADATA")
        support.verify_artifacts(c)
        support.require(abs(support.q(rpc("eth_getBlockByNumber", "latest", False)["timestamp"]) - time.time()) <= 2, "PRE_WINDOW_CLOCK")
        b.new_json(out / "initial-ready-proof.json", {"canonicalReceiptCount": 8, "eoaNonce": 8, "controllerNonce": 2, "initialMetadataUnchanged": True, "keyless": True, "fullAudit": False})
        window_end = earliest_service_start + 3600 - 5
        support.require(window_end - time.monotonic() >= 300, "INSUFFICIENT_USER_WINDOW")
        b.new_json(out / "browser-ready.json", {"runId": run_id, "parent": parent_id, "sourceIdentity": source, "web": origin + "/c-demo.html", "rpc": "http://127.0.0.1:32172", "chainId": 31337, "owner": c.owner, "loginId": "c-demo-user", "loginPassword": local_demo_password(), "expiresAtEpoch": time.time() + window_end - time.monotonic(), "stopFile": str(paths["relayControl"] / "STOP"), "initialProof": "EIGHT_RECEIPTS_CURRENT_STATE_QUERY_ONLY", "userTrades": "NOT_ASSESSED", "notice": "Close positions and withdraw before expiry; no automatic close or retransmission."})
        print(json.dumps({"status": "READY_BOUNDED_USER_WINDOW", "readyFile": str(out / "browser-ready.json")}), flush=True)
        stage = "USER_WINDOW";window_opened = True;status = "LIVE_WINDOW_OPENED_USER_RESULTS_REQUIRE_EVIDENCE"
        while time.monotonic() < window_end and not (paths["relayControl"] / "STOP").exists() and not relay.done.is_set():
            # Bounded, read-only liveness; no retrying or replaying a transaction.
            if not inspect(run_id + "-app")["State"]["Running"] or not inspect(run_id + "-oracle")["State"]["Running"]: raise ValueError("C_LIVE_SERVICE_EARLY_STOP")
            time.sleep(5)
        relay.close();relay.wait();relay = None
        stage = "FINAL_OBSERVATION_NOT_BASELINE_ASSERTION"
        final = support.chain_observation(c, rpc, keccak, raw_call, False);b.new_json(out / "final-chain-observation.json", final)
        b.new_json(out / "metadata-after-user-window.json", sql(c.gate.metadata_sql()))
        pending = rpc("eth_pendingTransactions");b.new_json(out / "pending-observed.private.json", {"scope": "FINAL_RPC_OBSERVATION_NOT_PERSISTED_MEMPOOL", "transactions": [{k: tx.get(k) for k in ("hash", "from", "to", "nonce")} for tx in pending]})
        if pending or int(final["pendingCount"]) or final["pendingNonce"] != final["eoaNonce"]:
            status = "BLOCKED";error = "C_LIVE_PENDING_PRESERVED_NO_RETRANSMISSION"
        else: status = "STOPPED_USER_WINDOW_STATE_PRESERVED_RESULTS_NOT_AUTOMATICALLY_ACCEPTED"
    except Exception as failure:
        status = "BLOCKED";error = str(failure) if isinstance(failure, ValueError) and str(failure).startswith(("C_LIVE_", "C_RESUME_")) else "C_LIVE_SAFE_" + type(failure).__name__
    finally:
        deadline = total
        if relay:
            try: relay.close();relay.wait()
            except Exception: status = "BLOCKED";error = error or "C_LIVE_RELAY_STOP"
        for role in ("app", "oracle"):
            try:
                with (paths[role + "Control"] / "STOP").open("x", encoding="ascii") as f: f.write("OWNED_LIVE_SESSION_STOP\n")
            except FileExistsError: pass
            except Exception: status = "BLOCKED";error = error or "C_LIVE_STOP_FILE"
        for role in ("app", "oracle", "anvil", "pg", "copy"):
            if role not in attempted: continue
            try:
                info = inspect(run_id + "-" + role);support.require(info["Config"].get("Labels", {}).get("tms.c.owner") == run_id, "STOP_OWNER")
                if info["State"]["Running"]: docker(["stop", "-t", "20", info["Id"]], timeout=25)
                st = inspect(info["Id"])["State"];ok = not st.get("Running") and not st.get("OOMKilled") and not st.get("Error") and st.get("ExitCode") in {0, 143};stopped[role] = {"normalStop": ok, "exitCode": st.get("ExitCode"), "oomKilled": st.get("OOMKilled")};support.require(ok, "NORMAL_STOP")
            except Exception: status = "BLOCKED";error = error or "C_LIVE_STOP_UNCONFIRMED"
        for kind, name in (("network", run_id + "-network"), ("volume", run_id + "-pg-data"), ("volume", run_id + "-state")):
            try: preserved[name] = json.loads(docker([kind, "inspect", name]).stdout)[0].get("Labels", {}).get("tms.c.owner") == run_id
            except Exception: preserved[name] = False
        if stopped.get("anvil", {}).get("normalStop"):
            try:
                proc = subprocess.Popen(["docker", "cp", anvil_id + ":/state/anvil.json", "-"], cwd=root, env=b.clean_env(), stdout=subprocess.PIPE, stderr=subprocess.DEVNULL);data = []
                reader = threading.Thread(target=lambda: data.append(proc.stdout.read(support.STATE_CAP + 10241)), daemon=True);reader.start();reader.join(20)
                if reader.is_alive() or not data or len(data[0]) > support.STATE_CAP + 10240: proc.kill();proc.wait(timeout=3);raise ValueError("C_LIVE_DUMP_CAP")
                support.require(proc.wait(timeout=3) == 0, "DUMP_COPY");proc.stdout.close();content = support.extract_live_state_tar(data[0])
                with (out / "anvil-state.private.json").open("xb") as f: f.write(content)
                state_file = {"sha256": hashlib.sha256(content).hexdigest(), "bytes": len(content), "head": support.q(json.loads(content)["best_block_number"])}
            except Exception: status = "BLOCKED";error = error or "C_LIVE_DUMP_UNCONFIRMED"
        try: unchanged = all(b.sha(Path(path)) == digest for path, digest in tracked.items());support.verify_artifacts(c)
        except Exception: unchanged = False
        if not unchanged or not all(preserved.values()) or (attempted and not state_file): status = "BLOCKED";error = error or "C_LIVE_PRESERVATION"
        b.new_json(out / "result.json", {"runId": run_id, "parent": parent_id, "parentState": parent_sha, "status": status, "stage": stage, "safeError": error, "sourceIdentity": source, "clockSyncRequests": clock_requests, "agentUserSignatures": 0, "agentTransactions": 0, "bootstrap": 0, "newKeys": 0,
                      "userWindowOpened": window_opened, "initialProofDoesNotCertifyLaterUserTrades": True, "parentAndSourcesUnchanged": unchanged, "stopped": stopped, "preserved": preserved, "anvilState": state_file, "claimRetained": claim.is_file(), "nextResumeMustUseChild": run_id, "commands": commands, "elapsedSeconds": time.monotonic() - start, "fullAudit": False})
    print(json.dumps({"runId": run_id, "status": status, "safeError": error}), flush=True)
    return 0 if not error and state_file else 1


def main(argv=None, root=None):
    parser = argparse.ArgumentParser();parser.add_argument("--live-window", action="store_true", help="Explicitly start the one-hour owned successor; consumes its permanent parent claim")
    args = parser.parse_args(argv);root = root or Path(__file__).resolve().parent.parent
    c = support.load_context(root)
    if not args.live_window:
        print(json.dumps(plan(c)));return 0
    return live(c)


if __name__ == "__main__":
    try: raise SystemExit(main())
    except Exception as safe:
        print(json.dumps({"status": "BLOCKED_BEFORE_OR_DURING_PREPARATION", "exceptionType": type(safe).__name__}), flush=True)
        raise SystemExit(1)
