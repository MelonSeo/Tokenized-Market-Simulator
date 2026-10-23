"""One new bounded local browser fixture. No automatic owner transaction or wallet signature.

Reuses the reviewed demo launch/isolation helpers and prepared byte artifacts. Existing
fixtures are never restarted. Host access is opaque loopback exec relay, not Docker -p.
"""
import argparse
from local_demo_credentials import local_demo_password
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import secrets
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

PREPARED_ID = "20261023T145120Z-58f159a7"
ROLES = ("pg", "anvil", "bootstrap", "oracle", "app")
def fixture_passwords(login_password=None):
    values={key:secrets.token_hex(32) for key in ("OWNER_PASSWORD","API_PASSWORD","JWT_SECRET","ORACLE_TOKEN")}
    values["LOGIN_PASSWORD"]=local_demo_password() if login_password is None else login_password
    if len(set(values.values()))!=5: raise ValueError("C_BROWSER_SECRETS")
    return values


def published_ready(path):
    """A created file is not necessarily a completely published JSON document.
    Only the existing bounded readiness loop may wait for completion; no HTTP,
    signing, transmission, or fixture creation is retried here.
    """
    try:
        with path.open("rb") as stream: data=stream.read(16385)
    except FileNotFoundError:
        return None
    if len(data)>16384: raise ValueError("C_BROWSER_READY_FILE_SIZE")
    try: value=json.loads(data)
    except (json.JSONDecodeError,UnicodeDecodeError): return None
    if not isinstance(value,dict): raise ValueError("C_BROWSER_READY_FILE_SHAPE")
    return value


def public_owner(value):
    if not re.fullmatch(r"0x[0-9a-fA-F]{40}", value) or int(value[2:], 16) == 0:
        raise ValueError("C_BROWSER_OWNER")
    return value.lower()


def limits(app_port, rpc_port, seconds):
    if any(type(p) is not int or not 1024 <= p <= 65535 for p in (app_port, rpc_port)) or app_port == rpc_port or type(seconds) is not int or not 30 <= seconds <= 3600:
        raise ValueError("C_BROWSER_LIMITS")


def bootstrap_args(base, run, paths, env_files, external):
    args = base.launch_args("bootstrap", run, paths, env_files)
    at = args.index("CDirectDemoDriver")
    if args[at + 1] != "bootstrap":
        raise ValueError("C_BROWSER_BOOTSTRAP_ARGUMENTS")
    return args[:at] + ["CDirectBrowserBootstrap"] + args[at + 2:] + [public_owner(external)]


def app_args(base, run, paths, env_files):
    args = base.launch_args("app", run, paths, env_files)
    if args.count("--memory=512m") != 1:
        raise ValueError("C_BROWSER_APP_BASE_MEMORY")
    args[args.index("--memory=512m")] = "--memory=768m"
    return args


def oracle_args(base, run, paths, env_files, seconds):
    limits(1024,1025,seconds)
    args=base.launch_args("oracle",run,paths,env_files)
    if args[-1]!="900": raise ValueError("C_BROWSER_ORACLE_BASE_SESSION")
    return [*args[:-1],str(seconds)]


def validate_oracle(base, info, run, paths, env_files, seconds):
    expected=oracle_args(base,run,paths,env_files,seconds);at=expected.index("--entrypoint")
    if info.get("Config",{}).get("Entrypoint")!=[expected[at+1]] or info.get("Config",{}).get("Cmd")!=expected[at+3:]:
        raise ValueError("C_BROWSER_ORACLE_SESSION_COMMAND")
    # Exact extended argv was checked above. Reuse every unchanged base isolation
    # check with its historical default duration; do not change the inspected object.
    historical=copy.deepcopy(info);historical["Config"]["Cmd"][-1]="900"
    base.validate_container(historical,"oracle",run,paths,env_files)


def validate_selected_java(base, info, role, run, paths, env_files, external):
    if role not in {"app", "bootstrap"}: raise ValueError("C_BROWSER_JAVA_ROLE")
    expected = bootstrap_args(base, run, paths, env_files, external) if role == "bootstrap" else app_args(base, run, paths, env_files)
    c, h, n = info.get("Config", {}), info.get("HostConfig", {}), info.get("NetworkSettings", {})
    if c.get("Labels", {}).get("tms.c.owner") != run or info.get("Image") != base.JAVA_IMAGE or c.get("User") != "10001:10001":
        raise ValueError("C_BROWSER_BOOTSTRAP_OWNER")
    if h.get("NetworkMode") != run + "-network" or set(n.get("Networks", {})) != {run + "-network"} or h.get("Privileged") or h.get("CapAdd") or h.get("PortBindings") or any((n.get("Ports") or {}).values()):
        raise ValueError("C_BROWSER_BOOTSTRAP_NETWORK")
    if h.get("Memory") != (256 if role == "bootstrap" else 768) * 1024**2 or h.get("ReadonlyRootfs") is not True or h.get("CapDrop") != ["ALL"] or not any(v in {"no-new-privileges", "no-new-privileges:true"} for v in h.get("SecurityOpt", [])):
        raise ValueError("C_BROWSER_BOOTSTRAP_ISOLATION")
    at = expected.index("--entrypoint")
    if c.get("Entrypoint") != [expected[at + 1]] or c.get("Cmd") != expected[at + 3:]:
        raise ValueError("C_BROWSER_BOOTSTRAP_COMMAND")
    mounts = {m.get("Destination"): m for m in info.get("Mounts", []) if m.get("Type") != "tmpfs"}
    expected_mounts = ({"/driver-fixture": (paths["driver"], False), "/driver-private": (paths["private"], True), "/app-config": (paths["appConfig"], True), "/oracle-config": (paths["oracleConfig"], True), "/driver-control": (paths["driverControl"], False)} if role == "bootstrap" else
                       {"/server-fixture": (paths["server"], False), "/demo-web": (paths["web"], False), "/demo-config": (paths["appConfig"], False), "/demo-control": (paths["appControl"], False), "/demo-evidence": (paths["appEvidence"], True)})
    if set(mounts) != set(expected_mounts):
        raise ValueError("C_BROWSER_BOOTSTRAP_MOUNTS")
    for target, (path, writable) in expected_mounts.items():
        mount = mounts[target]
        if mount.get("Type") != "bind" or mount.get("RW") is not writable or str(mount.get("Source", "")).replace("\\", "/").lower().rstrip("/") != str(path.resolve()).replace("\\", "/").lower().rstrip("/"):
            raise ValueError("C_BROWSER_BOOTSTRAP_MOUNT_SCOPE")
    if role == "app":
        for entry in c.get("Env", []):
            key, _, value = entry.partition("=")
            if key.startswith("C_DEMO_") or value and any(s in key.upper() for s in ("PRIVATE_KEY", "MNEMONIC", "SEED_PHRASE", "API_KEY")):
                raise ValueError("C_BROWSER_APP_KEYLESS_ENVIRONMENT")


def self_test():
    first,second=fixture_passwords('unit-fixture-not-live'),fixture_passwords('unit-fixture-not-live')
    assert first["LOGIN_PASSWORD"]==second["LOGIN_PASSWORD"]=='unit-fixture-not-live'
    for key in ("OWNER_PASSWORD","API_PASSWORD","JWT_SECRET","ORACLE_TOKEN"):
        assert re.fullmatch(r"[0-9a-f]{64}",first[key]) and first[key]!=second[key]
    assert public_owner("0x" + "A" * 40) == "0x" + "a" * 40
    for bad in ("", "0x" + "0" * 40, "0x1234", "http://owner"):
        try: public_owner(bad)
        except ValueError: pass
        else: raise AssertionError("OWNER")
    for args in ((0, 32002, 900), (32001, 32001, 900), (32001, 32002, 3601), (True, 32002, 900)):
        try: limits(*args)
        except ValueError: pass
        else: raise AssertionError("LIMITS")
    limits(32001, 32002, 900)
    limits(32001, 32002, 3600)
    class FakeBase:
        JAVA_IMAGE = "image"
        @staticmethod
        def launch_args(role, *unused):
            return (["java", "CDirectDemoDriver", "bootstrap", "fixture", "private", "app", "oracle", "control"] if role == "bootstrap" else
                    ["run", "--memory=512m", "--entrypoint", "java", "image", "CDirectDemoApplication", "args"])
    assert bootstrap_args(FakeBase(), "unused", {}, {}, "0x" + "a" * 40) == ["java", "CDirectBrowserBootstrap", "fixture", "private", "app", "oracle", "control", "0x" + "a" * 40]
    assert "--memory=768m" in app_args(FakeBase(), "unused", {}, {})
    fake_paths = {key: Path("owned") / key for key in ("server", "web", "appConfig", "appControl", "appEvidence")}
    fake_run = "cdemo-" + "a" * 16
    info = {"Image": "image", "Config": {"Labels": {"tms.c.owner": fake_run}, "User": "10001:10001", "Entrypoint": ["java"], "Cmd": ["CDirectDemoApplication", "args"]},
            "HostConfig": {"NetworkMode": fake_run + "-network", "Memory": 768 * 1024**2, "ReadonlyRootfs": True, "CapDrop": ["ALL"], "SecurityOpt": ["no-new-privileges"]},
            "NetworkSettings": {"Networks": {fake_run + "-network": {}}},
            "Mounts": [{"Destination": target, "Type": "bind", "RW": writable, "Source": str(fake_paths[key].resolve())} for target, key, writable in
                       (("/server-fixture", "server", False), ("/demo-web", "web", False), ("/demo-config", "appConfig", False), ("/demo-control", "appControl", False), ("/demo-evidence", "appEvidence", True))]}
    validate_selected_java(FakeBase(), info, "app", fake_run, fake_paths, {}, "0x" + "a" * 40)
    info["HostConfig"]["Memory"] = 512 * 1024**2
    try: validate_selected_java(FakeBase(), info, "app", fake_run, fake_paths, {}, "0x" + "a" * 40)
    except ValueError: pass
    else: raise AssertionError("APP_MEMORY")
    print("C_BROWSER_RUN_NO_SOCKET_POLICY_SELF_TEST_PASS")


def run(root, prepared_id, external, app_port, rpc_port, seconds):
    local_demo_password()  # Required before any once-only claim or resource creation.
    limits(app_port, rpc_port, seconds);external = public_owner(external)
    if prepared_id != PREPARED_ID:
        raise ValueError("C_BROWSER_PREPARED_ID")
    spec = importlib.util.spec_from_file_location("c_browser_run_base", root / "deployment/c-direct-demo-check.py")
    base = importlib.util.module_from_spec(spec);spec.loader.exec_module(base)
    prepared = root / "deployment/runtime/c-direct-browser-prepare" / prepared_id
    record = json.loads((prepared / "result.json").read_text(encoding="utf-8"))
    if record.get("status") != "PREPARED_NOT_LIVE_BROWSER_OR_METAMASK" or not record.get("baseUnchanged") or not record.get("sourcesUnchanged") or (prepared / "RUN_CLAIM").exists():
        raise ValueError("C_BROWSER_PREPARED_OR_CLAIM")
    if any(base.sha(root / p) != digest for p, digest in record["sources"].items()) or any(base.tree_hashes(prepared / (role + "-fixture")) != hashes for role, hashes in record["fixtureHashes"].items()) or base.checked_web_tree(prepared / "web-fixture") != record["webHashes"]:
        raise ValueError("C_BROWSER_SOURCE_OR_ARTIFACT_CHANGED")
    wrapper_hash = base.sha(Path(__file__))
    source_map = dict(record["sources"]);source_map["deployment/c-direct-browser-run.py"] = wrapper_hash
    source = hashlib.sha256(json.dumps(source_map, sort_keys=True).encode()).hexdigest()
    start = time.monotonic();deadline = start + seconds + 180;total_deadline = deadline + 60
    commands = [];secret_values = ();attempted = set();states = {};relay = None
    def docker(args, timeout=10, check=True):
        if any(secret in str(args) for secret in secret_values): raise ValueError("C_BROWSER_SECRET_ARGUMENT")
        remaining = int(deadline - time.monotonic())
        if remaining < 1: raise ValueError("C_BROWSER_DEADLINE")
        p = subprocess.run(["docker", *args], cwd=root, env=base.clean_env(), capture_output=True, text=True, timeout=min(remaining, timeout))
        commands.append({"args": args, "exitCode": p.returncode, "stdoutBytes": len(p.stdout), "stderrBytes": len(p.stderr)})
        if check and p.returncode: raise ValueError("C_BROWSER_DOCKER")
        return p
    # Read-only and one fixed-port probe before consuming the once-only browser claim.
    ram = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "(Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory"], env=base.clean_env(), capture_output=True, text=True, timeout=10)
    free = int(ram.stdout.strip()) * 1024 if ram.returncode == 0 and ram.stdout.strip().isdigit() else 0
    if free < base.MIN_RAM or shutil.disk_usage(root).free < base.MIN_DISK: raise ValueError("C_BROWSER_RESOURCES")
    probes = []
    try:
        for port in (app_port, rpc_port):
            probe = socket.socket(socket.AF_INET, socket.SOCK_STREAM);probes.append(probe)
            if hasattr(socket, "SO_EXCLUSIVEADDRUSE"): probe.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
            probe.bind(("127.0.0.1", port))
    finally:
        for probe in probes: probe.close()
    docker(["version", "--format", "{{.Server.Version}}"])
    for image in (base.PG_IMAGE, base.JAVA_IMAGE, base.FOUNDRY_IMAGE):
        if docker(["image", "inspect", image, "--format", "{{.Id}}|{{.Os}}|{{.Architecture}}"]).stdout.strip() != image + "|linux|amd64": raise ValueError("C_BROWSER_IMAGE")
    networks = docker(["network", "ls", "--format", "{{.ID}}"]).stdout.splitlines()
    if len(networks) > 1024 or any(not re.fullmatch(r"[0-9a-f]{12,64}", n) for n in networks): raise ValueError("C_BROWSER_NETWORK_LIST")
    occupied = []
    for index in range(0, len(networks), 64):
        for line in docker(["network", "inspect", *networks[index:index + 64], "--format", "{{json .IPAM.Config}}"]).stdout.splitlines(): occupied.extend(v["Subnet"] for v in json.loads(line) or [] if v.get("Subnet"))
    routes = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "@(Get-NetRoute -AddressFamily IPv4 -ErrorAction Stop | Select-Object -ExpandProperty DestinationPrefix) | ConvertTo-Json -Compress"], env=base.clean_env(), capture_output=True, text=True, timeout=10)
    if routes.returncode: raise ValueError("C_BROWSER_ROUTES")
    parsed = json.loads(routes.stdout);subnet = base.select_subnet(occupied, [parsed] if isinstance(parsed, str) else parsed)
    with (prepared / "RUN_CLAIM").open("x", encoding="ascii") as file: file.write("ONE_NEW_BROWSER_FIXTURE_NO_AUTOMATIC_RETRY\n")
    run_id = "cdemo-" + uuid.uuid4().hex[:16]
    out = root / "deployment/runtime/c-direct-browser" / run_id;out.mkdir(parents=True, exist_ok=False)
    paths = {"server": prepared / "server-fixture", "oracle": prepared / "oracle-fixture", "web": prepared / "web-fixture", "driver": out / "driver-fixture"}
    shutil.copytree(prepared / "driver-fixture", paths["driver"])
    for name in ("private", "appConfig", "oracleConfig", "driverControl", "appControl", "oracleControl", "appEvidence", "oracleEvidence", "relayControl"):
        paths[name] = out / name;paths[name].mkdir()
    passwords = fixture_passwords();secret_values = tuple(passwords.values())
    if len(set(secret_values)) != 5: raise ValueError("C_BROWSER_SECRETS")
    suffix = run_id[6:];database, owner, api = "cdemo_" + suffix, "cdowner_" + suffix, "cdapi_" + suffix
    env_files = {role: out / (role + ".private.env") for role in ("pg", "driver")}
    with env_files["pg"].open("x", encoding="ascii") as file: file.write("POSTGRES_DB=" + database + "\nPOSTGRES_USER=" + owner + "\nPOSTGRES_PASSWORD=" + passwords["OWNER_PASSWORD"] + "\nPOSTGRES_INITDB_ARGS=--auth-host=scram-sha-256\n")
    with env_files["driver"].open("x", encoding="ascii") as file: file.write("\n".join("C_DEMO_" + key + "=" + value for key, value in passwords.items()) + "\n")
    origin = "http://127.0.0.1:" + str(app_port)
    manifest = {"scope": "C_DIRECT_DEMO_NEW_OWNED_FIXTURE", "runId": run_id, "sourceIdentity": source, "marker": secrets.token_hex(32), "origin": origin,
                "database": database, "databaseOwner": owner, "apiUser": api, "lifetimeSeconds": seconds, "presentationLocalOnly": True, "syntheticTickMs": 3000,
                "sqlHashes": record["sqlHashes"], "browserOwner": external, "browserExternalOwnerOnly": True}
    base.new_json(paths["driver"] / "manifest.json", manifest)
    base.new_json(out / "browser-login.private.json", {"loginId": "c-demo-user", "password": passwords["LOGIN_PASSWORD"]})
    base.new_json(out / "execution-identity.json", {"runId": run_id, "sourceIdentity": source, "preparedSourceIdentity": record["sourceIdentity"], "wrapperSha256": wrapper_hash, "sources": source_map, "browserOwner": external})
    driver_hashes = base.tree_hashes(paths["driver"]);stage = "COLLISION_CHECK";status = "BLOCKED";error = None
    def inspect(role):
        raw=docker(["container", "inspect", run_id + "-" + role]).stdout
        try: values=json.loads(raw)
        except json.JSONDecodeError: raise ValueError("C_BROWSER_INSPECT_JSON_"+role.upper()) from None
        if not isinstance(values,list) or len(values)!=1 or not isinstance(values[0],dict):
            raise ValueError("C_BROWSER_INSPECT_SHAPE_"+role.upper())
        info=values[0]
        if role in {"bootstrap", "app"}: validate_selected_java(base, info, role, run_id, paths, env_files, external)
        elif role=="oracle": validate_oracle(base,info,run_id,paths,env_files,seconds)
        else: base.validate_container(info, role, run_id, paths, env_files)
        return info
    def launch(role):
        attempted.add(role)
        args = bootstrap_args(base, run_id, paths, env_files, external) if role == "bootstrap" else app_args(base, run_id, paths, env_files) if role == "app" else oracle_args(base,run_id,paths,env_files,seconds) if role=="oracle" else base.launch_args(role, run_id, paths, env_files)
        docker(args)
        return inspect(role)
    def ready(role, path):
        stop = min(deadline, time.monotonic() + 90)
        incomplete=False
        while time.monotonic() < stop:
            if path.is_file():
                value=published_ready(path)
                if value is not None:return value
                incomplete=True
            if inspect(role).get("State", {}).get("Running") is not True: raise ValueError("C_BROWSER_NOT_READY")
            time.sleep(.5)
        raise ValueError("C_BROWSER_READY_FILE_INCOMPLETE" if incomplete else "C_BROWSER_READINESS_DEADLINE")
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *unused): raise ValueError("C_BROWSER_HTTP_REDIRECT")
    http = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    def request(url, data=None, jwt=None):
        headers = {"Origin": origin}
        if data is not None: headers["Content-Type"] = "application/json"
        if jwt: headers["Authorization"] = "Bearer " + jwt
        with http.open(urllib.request.Request(url, data=None if data is None else json.dumps(data).encode(), headers=headers), timeout=15) as response:
            payload = response.read(1024**2 + 1)
            if response.status != 200 or len(payload) > 1024**2: raise ValueError("C_BROWSER_HTTP_PROBE")
            return payload
    try:
        for kind, name in (("network", run_id + "-network"), ("volume", run_id + "-pg-data"), ("volume", run_id + "-state"), *[("container", run_id + "-" + role) for role in ROLES]):
            if docker([kind, "inspect", name], check=False).returncode == 0: raise ValueError("C_BROWSER_EXISTING_RESOURCE")
        docker(["network", "create", "--internal", "--subnet", subnet, "--label", "tms.c.owner=" + run_id, run_id + "-network"])
        network = json.loads(docker(["network", "inspect", run_id + "-network"]).stdout)[0]
        if network.get("Internal") is not True or network.get("Labels", {}).get("tms.c.owner") != run_id: raise ValueError("C_BROWSER_INTERNAL_NETWORK")
        for name in (run_id + "-pg-data", run_id + "-state"):
            docker(["volume", "create", "--label", "tms.c.owner=" + run_id, name])
            if json.loads(docker(["volume", "inspect", name]).stdout)[0].get("Labels", {}).get("tms.c.owner") != run_id: raise ValueError("C_BROWSER_VOLUME_OWNER")
        stage = "START_PG_ANVIL";launch("pg");launch("anvil")
        for _ in range(30):
            if docker(["exec", run_id + "-pg", "pg_isready", "-h", "127.0.0.1", "-U", owner, "-d", database], check=False).returncode == 0: break
            time.sleep(.5)
        else: raise ValueError("C_BROWSER_PG_READINESS")
        if docker(["exec", run_id + "-anvil", "/usr/local/bin/cast", "chain-id", "--rpc-url", "http://127.0.0.1:8545"]).stdout.strip() != "31337": raise ValueError("C_BROWSER_CHAIN_ID")
        stage = "PUBLIC_OWNER_BOOTSTRAP";launch("bootstrap")
        while inspect("bootstrap").get("State", {}).get("Running") is True:
            if time.monotonic() > min(start + 150, deadline): raise ValueError("C_BROWSER_BOOTSTRAP_TIMEOUT")
            time.sleep(.5)
        boot = json.loads((paths["private"] / "bootstrap/phase-result.json").read_text(encoding="utf-8"))
        if boot.get("result") != "PASS" or boot.get("externalOwner") != external or boot.get("externalOwnerPrivateKeyGenerated") is not False or boot.get("sourceIdentity") != source: raise ValueError("C_BROWSER_BOOTSTRAP_PROOF")
        identity = json.loads((paths["private"] / "identity.json").read_text(encoding="utf-8"))
        stage = "START_ORACLE_APP";launch("oracle");oracle = ready("oracle", paths["oracleEvidence"] / "ready.json")
        app = launch("app");app_ready = ready("app", paths["appEvidence"] / "server-ready.json")
        if oracle.get("oracle") != identity["oracle"] or app_ready.get("runId") != run_id or app_ready.get("sourceIdentity") != source or any(app_ready.get(key) is not False for key in ("userPrivateKeys", "oraclePrivateKeys", "relayerPrivateKeys")): raise ValueError("C_BROWSER_READY_IDENTITY")
        if oracle.get("sessionSeconds")!=seconds or app_ready.get("sessionSeconds")!=seconds or app_ready.get("jwtValidityMs")!=seconds*1000:
            raise ValueError("C_BROWSER_SESSION_IDENTITY")
        stage = "LOOPBACK_RELAY"
        meta = {"scope": "C_BROWSER_NEW_OWNED_INTERNAL_RELAY", "runId": run_id, "appId": app["Id"], "networkId": network["Id"], "sourceIdentity": source,
                "helperSha256": record["helperSha256"], "appPort": app_port, "rpcPort": rpc_port, "origin": origin, "seconds": seconds}
        base.new_json(out / "relay.json", meta);relay = BrowserRelay(meta, paths["relayControl"]).start()
        stage = "ACTUAL_LOOPBACK_PROBES"
        if base.sha(prepared / "web-fixture/c-demo.html") != hashlib.sha256(request(origin + "/c-demo.html")).hexdigest(): raise ValueError("C_BROWSER_STATIC_BYTES")
        rpc_url = "http://127.0.0.1:" + str(rpc_port)
        def rpc(method, params):
            result = json.loads(request(rpc_url, {"jsonrpc": "2.0", "id": 1, "method": method, "params": params}))
            if result.get("error") or result.get("id") != 1: raise ValueError("C_BROWSER_RPC_PROBE")
            return result["result"]
        if rpc("eth_chainId", []) != "0x7a69" or rpc("eth_getBlockByNumber", ["0x0", False]).get("hash") != identity["genesisHash"] or rpc("eth_getCode", [external, "latest"]) != "0x": raise ValueError("C_BROWSER_RPC_IDENTITY")
        jwt = json.loads(request(origin + "/api/auth/login", {"loginId": "c-demo-user", "password": passwords["LOGIN_PASSWORD"]}))["accessToken"]
        if json.loads(request(origin + "/api/me", jwt=jwt)).get("id") != "1": raise ValueError("C_BROWSER_JWT_OWNER")
        config = json.loads(request(origin + "/api/c-futures/config", jwt=jwt))
        if config.get("deploymentId") != oracle.get("deploymentId"): raise ValueError("C_BROWSER_SERVER_RPC_CONFIG")
        market = json.loads(request(origin + "/api/c-futures/direct/market", jwt=jwt))
        if market.get("provider") != "SIMULATED" or not isinstance(market.get("price"), str): raise ValueError("C_BROWSER_PRODUCT_MARKET")
        del jwt
        base.new_json(out / "browser-ready.json", {"runId": run_id, "sourceIdentity": source, "owner": external, "web": origin + "/c-demo.html", "rpc": rpc_url, "chainId": 31337,
                      "loginPrivateFile": str(out / "browser-login.private.json"), "stopFile": str(paths["relayControl"] / "STOP"), "realMetaMask": False, "ownerProofPending": True, "seconds": seconds})
        print(json.dumps({"status": "READY_AWAITING_USER_WALLET_NOT_ACCEPTED", "readyFile": str(out / "browser-ready.json")}), flush=True)
        status = "READY_USER_ACCEPTANCE_NOT_RUN";stage = "BOUNDED_USER_WINDOW";relay.wait()
    except Exception as failure:
        error = str(failure) if isinstance(failure, ValueError) and str(failure).startswith("C_BROWSER_") else "C_BROWSER_SAFE_" + type(failure).__name__
        status = "BLOCKED"
    finally:
        deadline = total_deadline
        if relay:
            try: relay.close();relay.wait()
            except Exception: status = "BLOCKED";error = error or "C_BROWSER_RELAY_STOP_UNCONFIRMED"
        for role in ("app", "oracle"):
            try:
                with (paths[role + "Control"] / "STOP").open("x", encoding="ascii") as file: file.write("NEW_BROWSER_FIXTURE_NORMAL_STOP\n")
            except FileExistsError: pass
            except Exception: status = "BLOCKED";error = error or "C_BROWSER_STOP_MARKER"
        for role in reversed(ROLES):
            if role not in attempted: continue
            try:
                probe = docker(["container", "inspect", run_id + "-" + role], timeout=3, check=False)
                if probe.returncode and any(v in probe.stderr for v in ("No such object:", "No such container:")): states[role] = {"present": False};continue
                info = json.loads(probe.stdout)[0]
                if info.get("Config", {}).get("Labels", {}).get("tms.c.owner") != run_id: raise ValueError("C_BROWSER_STOP_OWNER")
                if info.get("State", {}).get("Running"): docker(["stop", "-t", "10", info["Id"]], timeout=15)
                state = json.loads(docker(["container", "inspect", info["Id"]], timeout=3).stdout)[0]["State"]
                normal = state.get("Running") is False and not state.get("OOMKilled") and state.get("ExitCode") in {0, 143} and not state.get("Error")
                states[role] = {"normalStop": normal, "exitCode": state.get("ExitCode"), "oomKilled": state.get("OOMKilled")}
                if not normal: raise ValueError("C_BROWSER_STOP")
            except Exception: status = "BLOCKED";error = error or "C_BROWSER_STOP_UNCONFIRMED"
        preserved = {}
        for kind, name in (("network", run_id + "-network"), ("volume", run_id + "-pg-data"), ("volume", run_id + "-state")):
            try: preserved[name] = json.loads(docker([kind, "inspect", name], timeout=3).stdout)[0].get("Labels", {}).get("tms.c.owner") == run_id
            except Exception: preserved[name] = False
        state_file = None
        if states.get("anvil", {}).get("normalStop"):
            try:
                # Bounded stopped-chain snapshot, no restart/import and no payload logging.
                proc = subprocess.Popen(["docker", "cp", run_id + "-anvil:/state/anvil.json", "-"], cwd=root, env=base.clean_env(), stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
                data = []
                reader = threading.Thread(target=lambda: data.append(proc.stdout.read(16 * 1024**2 + 10241)), daemon=True)
                reader.start();reader.join(min(15, max(1, int(total_deadline - time.monotonic()))))
                if reader.is_alive() or not data or len(data[0]) > 16 * 1024**2 + 10240:
                    proc.kill();proc.wait(timeout=3);raise ValueError("C_BROWSER_STATE_COPY_BOUND")
                if proc.wait(timeout=3): raise ValueError("C_BROWSER_STATE_COPY_EXIT")
                proc.stdout.close()
                spec = importlib.util.spec_from_file_location("c_browser_chain_state", root / "deployment/c-anvil-check.py")
                state_helper = importlib.util.module_from_spec(spec);spec.loader.exec_module(state_helper)
                content = state_helper.extract_state_tar(data[0])
                with (out / "anvil-state.private.json").open("xb") as file: file.write(content)
                state_file = {"sha256": hashlib.sha256(content).hexdigest(), "bytes": len(content), "scope": "STOPPED_SNAPSHOT_NOT_RESTART_PROOF"}
            except Exception: status = "BLOCKED";error = error or "C_BROWSER_STATE_COPY_UNCONFIRMED"
        if not all(preserved.values()): status = "BLOCKED";error = error or "C_BROWSER_PRESERVATION_UNCONFIRMED"
        try:
            unchanged = all(base.sha(root / path) == digest for path, digest in source_map.items()) and base.tree_hashes(paths["driver"]) == driver_hashes and all(base.tree_hashes(prepared / (role + "-fixture")) == hashes for role, hashes in record["fixtureHashes"].items()) and base.checked_web_tree(prepared / "web-fixture") == record["webHashes"]
        except Exception:
            unchanged = False
        if not unchanged: status = "BLOCKED";error = error or "C_BROWSER_END_IDENTITY"
        base.new_json(out / "result.json", {"runId": run_id, "sourceIdentity": source, "status": status, "stage": stage, "safeError": error, "commands": commands,
                      "stopped": states, "resourcesPreserved": preserved, "sourcesAndArtifactsUnchanged": unchanged, "elapsedSeconds": time.monotonic() - start,
                      "realMetaMaskAcceptance": "NOT_ASSESSED_BY_LAUNCHER", "automaticUserTransactions": 0, "paidCalls": 0, "deleteRequests": 0, "anvilState": state_file,
                      "anvilPreservationScope": "OWNED_PERSISTENT_VOLUME_AND_NORMAL_STOP_NOT_RESTART_PROOF"})
    print(json.dumps({"runId": run_id, "status": status, "safeError": error}), flush=True)
    return 0 if status.startswith("READY_") else 1


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        self_test();raise SystemExit(0)
    parser = argparse.ArgumentParser()
    parser.add_argument("prepared", choices=[PREPARED_ID]);parser.add_argument("owner")
    parser.add_argument("--app-port", required=True, type=int);parser.add_argument("--rpc-port", required=True, type=int)
    parser.add_argument("--seconds", type=int, default=3600)
    args = parser.parse_args()
    raise SystemExit(run(Path(__file__).resolve().parent.parent, args.prepared, args.owner, args.app_port, args.rpc_port, args.seconds))
