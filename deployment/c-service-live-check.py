"""Prepare, then run ONCE a NEW owned internal C read/wallet HTTP-PG-Anvil Gate.

No old fixture restart, host port, pull, deletion, full application scan or paid call.
The server receives only production classes/JWT+API-DB config, never driver keys.
"""
import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import threading
import time
import uuid

sys.dont_write_bytecode = True
from c_gate_network import select_subnet

CORE_RUN = "20261023T075906Z-9a0a410e"
CORE_HASH = "34732172658d779342ccf5e8b8cfd78d135053d5238be6c65905a235f9dbb96e"
CONTRACT_RUN = "20261023T065808Z-9108ce4b"
CONTRACT_HASH = "6ffb45c5e07187ed647f8281e048893c7dba522363ff55fdfeaf126deccbf1cb"
FOUNDRY_IMAGE = "sha256:0c00cb0bda1ab1b91c9a6bf60f4c76c09c1a8870824b6d4718afbabacf6f9a17"
JAVA_IMAGE = "sha256:35be56d41389c9ad515c089dfded911ae9a8bdb362c8a303224666cd358bead3"
PG_IMAGE = "sha256:c1b3783309b6499c795eed7c20135a1a4d25cae1b575c3d52c6f536129a1b109"
SCOPE = "C_READ_WALLET_LIVE"
PASS = "C_READ_WALLET_ACTUAL_HTTP_PG_ANVIL_PASS_NOT_FULL_TMS_OR_BROWSER"
MIN_RAM = 1920 * 1024**2
MIN_DISK = 2 * 1024**3
BASE = "backend/src/main/java/com/pricetrack/exchange/"
SERVER_SOURCES = tuple(BASE + "custody/" + p + ".java" for p in (
    "runtime/CCustodyFailure", "runtime/CReadRpc", "runtime/CCustodyIdentity", "runtime/CReadAbi",
    "runtime/CCustodyRpcClient", "runtime/CCustodyRpcReader", "runtime/CCustodyRuntimeConfig",
    "auth/CWalletLinkStore", "auth/CWalletProofService", "api/CCustodyController",
    "abi/CAbiModels", "proof/CEip712Verifier")) + tuple(BASE + p + ".java" for p in (
    "auth/AuthenticatedUser", "auth/JwtTokenProvider", "auth/JwtAuthenticationFilter",
    "common/config/SecurityConfig", "common/exception/InvalidTokenException",
    "common/exception/ApiErrorResponse", "user/UserRole", "user/User", "user/UserRepository")) + (
    "deployment/CCustodyLiveApplication.java",)
DRIVER_SOURCES = ("deployment/CCustodyLiveDriver.java", "deployment/CAnvilContractGate.java",
    "deployment/CAnvilRiskClaimsScenario.java", BASE + "futures/math/FuturesMath.java",
    BASE + "custody/abi/CAbiCodec.java", BASE + "custody/abi/CAbiEventModels.java", BASE + "custody/abi/CAbiEventCodec.java")
EXTRA_SOURCES = ("deployment/c-service-live-check.py", "deployment/test_c_service_live_check.py",
    "deployment/c_gate_network.py", "deployment/c-anvil-check.py", "backend/src/main/resources/c-wallet-schema.sql")
FORBIDDEN_SERVER_PREFIXES = ("junit-", "junit-platform-", "apiguardian-", "opentest4j-", "mockito-",
    "byte-buddy-agent-", "hamcrest-", "assertj-", "spring-test-", "spring-security-test-", "spring-boot-test-",
    "h2-", "jsonassert-", "json-path-", "android-json-", "json-smart-", "accessors-smart-")
DRIVER_JVM_ARGS = ("-Djdk.httpclient.disableRetryConnect=true", "-Djdk.httpclient.enableAllMethodRetry=false",
    "-Djdk.httpclient.redirects.retrylimit=1", "--add-opens=java.net.http/jdk.internal.net.http=ALL-UNNAMED")


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_new(path, value):
    with path.open("x", encoding="utf-8") as out:
        json.dump(value, out, indent=2, ensure_ascii=False)


def clean_env():
    return {k: v for k, v in os.environ.items() if k.upper() in {
        "PATH", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "USERPROFILE", "LOCALAPPDATA", "APPDATA", "PATHEXT", "COMSPEC"}}


def safe_command(args, private_values=()):
    """Only secret-free argv may be retained. Values belong in new private files."""
    if not isinstance(args, list) or not args or any(not isinstance(value, str) or len(value) > 32768 for value in args):
        raise ValueError("C_SERVICE_COMMAND_FORMAT")
    if any(arg in {"-e", "--env"} or arg.startswith("--env=") for arg in args):
        raise ValueError("C_SERVICE_INLINE_ENV_FORBIDDEN")
    if any(secret and any(secret in arg for arg in args) for secret in private_values):
        raise ValueError("C_SERVICE_COMMAND_SECRET_FORBIDDEN")
    return ["docker", *args]


def command_observation(args, stdout, stderr):
    """Inspect JSON stays in memory. Never copy raw stdout/stderr or Env values to evidence.

    This applies to logs/unknown commands too: private container logs remain in
    their owned container, not duplicated into the Python result document.
    """
    out = {"stdoutBytes": len(stdout.encode("utf-8")), "stderrBytes": len(stderr.encode("utf-8")),
        "outputPolicy": "NO_RAW_DOCKER_STDOUT_OR_STDERR_RETAINED"}
    if "inspect" not in args:
        return out
    try:
        values = json.loads(stdout)
        if not isinstance(values, list) or len(values) > 1024 or any(not isinstance(item, dict) for item in values):
            raise ValueError()
        summaries = []
        for item in values:
            config, host, state = item.get("Config", {}), item.get("HostConfig", {}), item.get("State", {})
            if not all(isinstance(value, dict) for value in (config, host, state)):
                raise ValueError()
            names = []
            for entry in config.get("Env", []) or []:
                if isinstance(entry, str) and "=" in entry:
                    name = entry.partition("=")[0]
                    if re.fullmatch(r"[A-Z][A-Z0-9_]{0,80}", name):
                        names.append(name)
            ident = item.get("Id", "")
            image = item.get("Image", "")
            entrypoint = config.get("Entrypoint")
            summary = {"environmentNames": sorted(set(names)), "argumentCount": len(config.get("Cmd") or []),
                "entrypointKind": "JAVA" if entrypoint == ["java"] else "ANVIL" if entrypoint == ["/usr/local/bin/anvil"] else "OTHER_OR_IMAGE_DEFAULT",
                "userKind": "NONROOT_10001" if config.get("User") == "10001:10001" else "ROOT_OR_OTHER",
                "readonlyRootfs": host.get("ReadonlyRootfs") is True, "privileged": host.get("Privileged") is True,
                "hostPortBindingsPresent": bool(host.get("PortBindings")), "mountCount": len(item.get("Mounts") or []),
                "running": state.get("Running") is True, "oomKilled": state.get("OOMKilled") is True,
                "daemonErrorPresent": bool(state.get("Error"))}
            if ident in {PG_IMAGE, JAVA_IMAGE, FOUNDRY_IMAGE}:
                summary["approvedImageId"] = ident
            if image in {PG_IMAGE, JAVA_IMAGE, FOUNDRY_IMAGE}:
                summary["imageId"] = image
            if type(host.get("Memory")) is int:
                summary["memoryBytes"] = host["Memory"]
            if type(state.get("ExitCode")) is int:
                summary["exitCode"] = state["ExitCode"]
            summaries.append(summary)
        out["inspection"] = summaries
    except Exception:
        out["inspection"] = "UNPARSED_INSPECTION_NO_RAW_RETAINED"
    return out


def load_c5(root):
    spec = importlib.util.spec_from_file_location("c_service_c5_primitives", root / "deployment/c-anvil-check.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    if module.FOUNDRY_IMAGE != FOUNDRY_IMAGE or module.JAVA_IMAGE != JAVA_IMAGE:
        raise ValueError("C_SERVICE_PRIMITIVE_IMAGE_CHANGED")
    return module


def tree_hashes(path):
    return {p.relative_to(path).as_posix(): sha(p) for p in sorted(path.rglob("*")) if p.is_file()}


def checked_inputs(root):
    core = json.loads((root / "deployment/runtime/c-service-read" / CORE_RUN / "result.json").read_text(encoding="utf-8"))
    contracts = json.loads((root / "deployment/runtime/c-anvil-offline" / CONTRACT_RUN / "result.json").read_text(encoding="utf-8"))
    if core.get("sourceHash") != CORE_HASH or core.get("compileExit") != 0 or core.get("testExit") != 0 or not core.get("sourcesUnchanged") or not core.get("dependenciesUnchanged"):
        raise ValueError("C_SERVICE_CORE_BASELINE")
    if contracts.get("sourceIdentity") != CONTRACT_HASH or contracts.get("status") != "OFFLINE_PASS_NOT_ACTUAL_ANVIL" or not contracts.get("sourcesUnchanged"):
        raise ValueError("C_SERVICE_CONTRACT_BASELINE")
    for rel, digest in {**core["sources"], **contracts["sources"], **contracts["contractSources"]}.items():
        if sha(root / rel) != digest:
            raise ValueError("C_SERVICE_SOURCE_CHANGED")
    old_fixture = root / "deployment/runtime/c-anvil-offline" / CONTRACT_RUN / "fixture"
    if tree_hashes(old_fixture) != contracts.get("fixtureHashes"):
        raise ValueError("C_SERVICE_CONTRACT_ARTIFACT_CHANGED")
    for entry in contracts["sourceMap"]:
        source = root / "contracts" / entry["source"] if entry["source"].startswith("src/") else Path(entry["source"])
        if sha(source) != entry["sha256"]:
            raise ValueError("C_SERVICE_CONTRACT_DEPENDENCY_CHANGED")
    return core, contracts, old_fixture


def cached_jars(root, core):
    cache = root.parents[2] / "deployment/runtime/dapp-audit-development-worktree/deployment/runtime/dapp-stage2-offline/java-classpath.args"
    lines = cache.read_text(encoding="utf-8-sig").splitlines()
    if len(lines) != 2 or lines[0] != "-cp":
        raise ValueError("C_SERVICE_CLASSPATH_FORMAT")
    jars = {}
    for part in lines[1].strip('"').split(os.pathsep):
        p = Path(part)
        if p.suffix.lower() != ".jar":
            continue
        if "/caches/modules-2/files-2.1/" not in p.as_posix() or not p.is_file() or p.name in jars:
            raise ValueError("C_SERVICE_CACHED_DEPENDENCY_SCOPE")
        if sha(p) != core["dependencies"].get(p.name):
            raise ValueError("C_SERVICE_CACHED_DEPENDENCY_CHANGED")
        if not p.name.startswith(FORBIDDEN_SERVER_PREFIXES):
            jars[p.name] = p
    if not jars or len(jars) > 512:
        raise ValueError("C_SERVICE_DEPENDENCY_LIMIT")
    required = ("spring-boot-", "spring-data-jpa-", "hibernate-core-", "postgresql-", "jjwt-api-", "okhttp-", "tomcat-embed-core-")
    if any(not any(name.startswith(prefix) for name in jars) for prefix in required):
        raise ValueError("C_SERVICE_PRODUCTION_DEPENDENCY_MISSING")
    first = next(iter(jars.values()))
    modules = first.parents[4]
    lombok = list((modules / "org.projectlombok/lombok/1.18.34").glob("*/lombok-1.18.34.jar"))
    if len(lombok) != 1 or not lombok[0].is_file():
        raise ValueError("C_SERVICE_CACHED_LOMBOK_MISSING")
    return jars, lombok[0]


def quote(arg):
    arg = str(arg).replace("\\", "/")
    if any(c in arg for c in ('"', "\r", "\n", "\0")):
        raise ValueError("C_SERVICE_ARGUMENT_FORMAT")
    return '"' + arg + '"'


def assert_server_tree(path):
    files = list((path / "classes").rglob("*.class"))
    if not files or any(p.name.startswith(("CAnvil", "CCustodyLiveDriver", "CReadWalletTests")) or "Test" in p.name for p in files):
        raise ValueError("C_SERVICE_SERVER_CLASS_SEPARATION")
    if any(p.name.startswith(FORBIDDEN_SERVER_PREFIXES) for p in (path / "jars").glob("*.jar")):
        raise ValueError("C_SERVICE_SERVER_TEST_DEPENDENCY")


def javac_args(kind, classpath, output, snapshot, lombok):
    """The same argv builder is used by prepare and its no-process regression.

    Spring resolves unnamed @PathVariable parameters from MethodParameters;
    retain production-equivalent metadata on the selected server compile.
    """
    if kind == "server":
        selected = SERVER_SOURCES
        extra = ["-parameters", "-processorpath", str(lombok), "-processor", "lombok.launch.AnnotationProcessorHider$AnnotationProcessor"]
    elif kind == "driver":
        selected = DRIVER_SOURCES
        extra = ["-proc:none"]
    else:
        raise ValueError("C_SERVICE_COMPILE_KIND")
    return ["--release", "21", "-encoding", "UTF-8", *extra, "-cp", classpath, "-d", str(output),
        *[str(snapshot / rel) for rel in selected]]


def prepare(root):
    core, contracts, artifacts = checked_inputs(root)
    jars, lombok = cached_jars(root, core)
    sources = tuple(dict.fromkeys((*SERVER_SOURCES, *DRIVER_SOURCES, *EXTRA_SOURCES)))
    if any(not (root / p).is_file() for p in sources):
        raise ValueError("C_SERVICE_GATE_SOURCE_MISSING")
    identity = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    evidence = root / "deployment/runtime/c-service-live-prepare" / identity
    server, driver = evidence / "server-fixture", evidence / "driver-fixture"
    for p in (server / "classes", server / "jars", driver / "classes"):
        p.mkdir(parents=True, exist_ok=False)
    before = {p: sha(root / p) for p in sources}
    for rel in sources:
        target = evidence / "source-snapshot" / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open("xb") as out:
            out.write((root / rel).read_bytes())
    for name, p in jars.items():
        shutil.copyfile(p, server / "jars" / name)
    for name in ("CMockCollateral", "CCollateralizedFuturesController", "CMarginVault", "FuturesEngine"):
        shutil.copyfile(artifacts / (name + ".json"), driver / (name + ".json"))
    shutil.copyfile(artifacts / "source-map.json", driver / "source-map.json")
    for item in contracts["sourceMap"]:
        shutil.copyfile(artifacts / item["file"], driver / item["file"])
    sql = root / "backend/src/main/resources/c-wallet-schema.sql"
    shutil.copyfile(sql, server / "classes/c-wallet-schema.sql")
    shutil.copyfile(sql, driver / "c-wallet-schema.sql")
    cp = os.pathsep.join(str(server / "jars" / name) for name in sorted(jars))
    jdk = Path("C:/Program Files/Java/jdk-21.0.10/bin")
    if not (jdk / "javac.exe").is_file():
        raise ValueError("C_SERVICE_JDK_MISSING")
    record = {"run": identity, "scope": SCOPE, "sources": before, "coreSourceHash": CORE_HASH, "contractSourceIdentity": CONTRACT_HASH,
        "dependencies": {name: sha(p) for name, p in jars.items()}, "lombokHash": sha(lombok), "compileExits": {}, "paidCalls": 0,
        "dockerCalls": 0, "realRpcCalls": 0, "status": "BLOCKED"}
    try:
        for name in ("server", "driver"):
            out = server / "classes" if name == "server" else driver / "classes"
            classpath = cp + (os.pathsep + str(server / "classes") if name == "driver" else os.pathsep + str(lombok))
            args = javac_args(name, classpath, out, evidence / "source-snapshot", lombok)
            args_file = evidence / (name + ".args")
            with args_file.open("x", encoding="utf-8") as f:
                f.write("\n".join(quote(a) for a in args))
            with (evidence / (name + ".compile.stdout.log")).open("xb") as stdout, (evidence / (name + ".compile.stderr.log")).open("xb") as stderr:
                result = subprocess.run([str(jdk / "javac.exe"), "-J-Xmx256m", "@" + str(args_file)], cwd=root, env=clean_env(), stdout=stdout, stderr=stderr, timeout=120)
            record["compileExits"][name] = result.returncode
            if result.returncode:
                raise ValueError("C_SERVICE_" + name.upper() + "_COMPILE")
        assert_server_tree(server)
        # Only pure driver/App self-tests; neither starts Spring, JDBC, sockets or an RPC client.
        for name, main in (("server", "CCustodyLiveApplication"), ("driver", "CCustodyLiveDriver")):
            path = server / "classes" if name == "server" else driver / "classes"
            args = ["-Xmx192m", "-XX:MaxMetaspaceSize=128m", "-Dfile.encoding=UTF-8", *(DRIVER_JVM_ARGS if name == "driver" else ()),
                "-cp", str(path) + os.pathsep + str(server / "classes") + os.pathsep + cp, main, "--self-test"]
            args_file = evidence / (name + ".self-test.args")
            with args_file.open("x", encoding="utf-8") as f:
                f.write("\n".join(quote(a) for a in args))
            tested = subprocess.run([str(jdk / "java.exe"), "@" + str(args_file)], cwd=root, env=clean_env(), capture_output=True, timeout=30)
            (evidence / (name + ".self-test.stdout.log")).write_bytes(tested.stdout)
            (evidence / (name + ".self-test.stderr.log")).write_bytes(tested.stderr)
            record[name + "SelfTestExit"] = tested.returncode
            if tested.returncode or tested.stderr:
                raise ValueError("C_SERVICE_" + name.upper() + "_SELF_TEST")
        test = subprocess.run([sys.executable, "-B", "-m", "unittest", "discover", "-s", "deployment", "-p", "test_c_service_live_check.py"], cwd=root, env=clean_env(), capture_output=True, timeout=30)
        (evidence / "python.stdout.log").write_bytes(test.stdout); (evidence / "python.stderr.log").write_bytes(test.stderr)
        record["pythonTestExit"] = test.returncode
        if test.returncode:
            raise ValueError("C_SERVICE_PYTHON_TEST")
        record["status"] = "PREPARED_NOT_ACTUAL_HTTP_PG_ANVIL"
    except subprocess.TimeoutExpired:
        record["error"] = "C_SERVICE_OFFLINE_TIMEOUT"
    except Exception as error:
        record["error"] = str(error) if type(error) is ValueError and re.fullmatch(r"C_SERVICE_[A-Z0-9_]+", str(error)) else "C_SERVICE_OFFLINE_SAFE_FAILURE"
    finally:
        record["sourcesUnchanged"] = before == {p: sha(root / p) for p in sources}
        record["dependenciesUnchanged"] = all(sha(p) == record["dependencies"][name] for name, p in jars.items()) and sha(lombok) == record["lombokHash"]
        record["serverFixtureHashes"] = tree_hashes(server); record["driverFixtureHashes"] = tree_hashes(driver)
        record["contractSources"] = contracts["contractSources"]; record["sourceMap"] = contracts["sourceMap"]
        record["sourceIdentity"] = hashlib.sha256(json.dumps({"sources": before, "scope": SCOPE}, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        if not record["sourcesUnchanged"] or not record["dependenciesUnchanged"]:
            record["status"] = "BLOCKED"
        write_new(evidence / "result.json", record)
    print(json.dumps({k: record.get(k) for k in ("run", "status", "error", "sourceIdentity", "sourcesUnchanged")}))
    return 0 if record["status"].startswith("PREPARED") else 1


def valid_run(run):
    if not re.fullmatch(r"c-live-[0-9a-f]{16}", run):
        raise ValueError("C_SERVICE_RUN_ID")


def resource_guard(memory, disk):
    if type(memory) is not int or memory < MIN_RAM:
        raise ValueError("C_SERVICE_RAM_LOW")
    if type(disk) is not int or disk < MIN_DISK:
        raise ValueError("C_SERVICE_DISK_LOW")


def bind(source, target, readonly):
    return "type=bind,source=" + str(source) + ",target=" + target + (",readonly" if readonly else "")


def launch_args(kind, run, paths, driver_env, pg_env):
    valid_run(run)
    if kind not in {"pg", "anvil", "driver", "api"}:
        raise ValueError("C_SERVICE_LAUNCH_KIND")
    if kind == "pg":
        # Keep the official-image initialization boundary already exercised by C4b.
        # PG rootfs/default entrypoint is writable; ONLY its NEW named volume is mounted.
        return ["run", "-d", "--pull=never", "--name", run + "-pg", "--label", "tms.c.owner=" + run,
            "--network", run + "-network", "--network-alias", "pg", "--restart=no", "--memory=256m",
            "--cpus=1", "--pids-limit=128", "--shm-size=32m", "--cap-drop=NET_RAW", "--env-file", str(pg_env),
            "--mount", "type=volume,source=" + run + "-pg-data,target=/var/lib/postgresql/data", PG_IMAGE,
            "-c", "shared_buffers=16MB", "-c", "max_connections=12", "-c", "max_wal_size=64MB"]
    base = ["run", "-d", "--pull=never", "--name", run + "-" + kind, "--label", "tms.c.owner=" + run,
        "--network", run + "-network", "--restart=no", "--cpus=1", "--pids-limit=128", "--read-only", "--cap-drop=ALL",
        "--security-opt=no-new-privileges", "--tmpfs", "/tmp:rw,noexec,nosuid,size=32m", "--log-opt", "max-size=10m", "--log-opt", "max-file=1"]
    if kind == "anvil":
        return base + ["--network-alias", "anvil", "--memory=256m", "--user=0:0", "--mount", "type=volume,source=" + run + "-state,target=/state",
            "--entrypoint", "/usr/local/bin/anvil", FOUNDRY_IMAGE, "--host", "0.0.0.0", "--port", "8545", "--chain-id", "31337",
            "--accounts", "0", "--silent", "--state", "/state/anvil.json", "--state-interval", "1"]
    java = base + ["--memory=" + ("384m" if kind == "driver" else "512m"), "--user=10001:10001"]
    if kind == "driver":
        for source, target, ro in ((paths["driver"], "/fixture", True), (paths["server"], "/server-fixture", True),
                (paths["private"], "/driver-private", False), (paths["config"], "/server-config", False), (paths["serverEvidence"], "/server-evidence", True)):
            java += ["--mount", bind(source, target, ro)]
        return java + ["--env-file", str(driver_env), "--entrypoint", "java", JAVA_IMAGE, "-Xms32m", "-Xmx192m", "-XX:MaxMetaspaceSize=128m",
            "-Dfile.encoding=UTF-8", *DRIVER_JVM_ARGS, "-cp", "/fixture/classes:/server-fixture/classes:/server-fixture/jars/*", "CCustodyLiveDriver",
            "/fixture", "/driver-private", "/server-config", "/server-evidence"]
    for source, target, ro in ((paths["server"], "/server-fixture", True), (paths["config"], "/server-config", True),
            (paths["control"], "/server-control", True), (paths["serverEvidence"], "/server-evidence", False)):
        java += ["--mount", bind(source, target, ro)]
    return java + ["--network-alias", "api", "--entrypoint", "java", JAVA_IMAGE, "-Xms32m", "-Xmx256m", "-XX:MaxMetaspaceSize=128m",
        "-Dfile.encoding=UTF-8", "-cp", "/server-fixture/classes:/server-fixture/jars/*", "CCustodyLiveApplication",
        "/server-config/application.properties", "/server-control", "/server-evidence"]


def validate_container(info, kind, run, paths):
    valid_run(run)
    config, host, net = info.get("Config", {}), info.get("HostConfig", {}), info.get("NetworkSettings", {})
    if config.get("Labels", {}).get("tms.c.owner") != run or host.get("NetworkMode") != run + "-network" or set(net.get("Networks", {})) != {run + "-network"}:
        raise ValueError("C_SERVICE_CONTAINER_OWNER_NETWORK")
    if host.get("PortBindings") or host.get("Privileged") or any(net.get("Ports", {}).values()):
        raise ValueError("C_SERVICE_CONTAINER_ISOLATION")
    image = PG_IMAGE if kind == "pg" else FOUNDRY_IMAGE if kind == "anvil" else JAVA_IMAGE
    memory = (384 if kind == "driver" else 512 if kind == "api" else 256) * 1024**2
    if info.get("Image") != image or host.get("Memory") != memory:
        raise ValueError("C_SERVICE_CONTAINER_IMAGE_LIMITS")
    if kind == "pg":
        cap_drop = host.get("CapDrop")
        if host.get("ReadonlyRootfs") or not isinstance(cap_drop, list) or len(cap_drop) != 1 or cap_drop[0] not in {"NET_RAW", "CAP_NET_RAW"} or host.get("CapAdd") or config.get("User") not in {"", "0", "0:0"}:
            raise ValueError("C_SERVICE_PG_INITIALIZATION_SCOPE")
    elif not host.get("ReadonlyRootfs") or "ALL" not in host.get("CapDrop", []) or not any(x in {"no-new-privileges", "no-new-privileges:true"} for x in host.get("SecurityOpt", [])):
        raise ValueError("C_SERVICE_CONTAINER_IMAGE_LIMITS")
    if kind != "pg" and config.get("User") != ("0:0" if kind == "anvil" else "10001:10001"):
        raise ValueError("C_SERVICE_CONTAINER_USER")
    mounts = {m.get("Destination"): m for m in info.get("Mounts", []) if m.get("Type") != "tmpfs"}
    if kind in {"anvil", "pg"}:
        dest, volume = ("/state", run + "-state") if kind == "anvil" else ("/var/lib/postgresql/data", run + "-pg-data")
        if set(mounts) != {dest} or mounts[dest].get("Type") != "volume" or mounts[dest].get("Name") != volume or not mounts[dest].get("RW"):
            raise ValueError("C_SERVICE_VOLUME_SCOPE")
    else:
        expected = {"/server-fixture": (paths["server"], False), "/server-config": (paths["config"], kind == "driver"),
            "/server-evidence": (paths["serverEvidence"], kind == "api")}
        expected.update({"/fixture": (paths["driver"], False), "/driver-private": (paths["private"], True)} if kind == "driver" else {"/server-control": (paths["control"], False)})
        if set(mounts) != set(expected):
            raise ValueError("C_SERVICE_SERVER_DRIVER_MOUNT_SEPARATION")
        for destination, (path, rw) in expected.items():
            m = mounts[destination]
            if m.get("Type") != "bind" or m.get("RW") is not rw or str(m.get("Source", "")).replace("\\", "/").lower().rstrip("/") != str(path.resolve()).replace("\\", "/").lower().rstrip("/"):
                raise ValueError("C_SERVICE_BIND_SCOPE")
    if kind == "api" and (config.get("Entrypoint") != ["java"] or any(arg in config.get("Cmd", []) for arg in DRIVER_JVM_ARGS)):
        raise ValueError("C_SERVICE_SERVER_JVM_SEPARATION")
    if kind == "driver" and (config.get("Entrypoint") != ["java"] or any(config.get("Cmd", []).count(arg) != 1 for arg in DRIVER_JVM_ARGS)):
        raise ValueError("C_SERVICE_DRIVER_RETRY_POLICY")
    if kind in {"api", "driver"}:
        args = config.get("Cmd", [])
        expected_cp = "/server-fixture/classes:/server-fixture/jars/*" if kind == "api" else "/fixture/classes:/server-fixture/classes:/server-fixture/jars/*"
        main = "CCustodyLiveApplication" if kind == "api" else "CCustodyLiveDriver"
        if args.count("-cp") != 1 or args[args.index("-cp") + 1] != expected_cp or main not in args:
            raise ValueError("C_SERVICE_CONTAINER_CLASSPATH")
    if kind == "api":
        for entry in config.get("Env", []):
            name, _, value = entry.partition("=")
            if name.startswith("C_GATE_") or (value and any(word in name.upper() for word in ("PRIVATE_KEY", "MNEMONIC", "SEED_PHRASE", "API_KEY"))):
                raise ValueError("C_SERVICE_SERVER_SECRET_SCOPE")


def preserve_anvil(root, name, target, commands, helper):
    if target.exists() or not re.fullmatch(r"c-live-[0-9a-f]{16}-anvil", name):
        raise ValueError("C_SERVICE_STATE_SCOPE")
    command = ["docker", "cp", name + ":/state/anvil.json", "-"]
    record = {"command": command, "scope": "OWNED_STOPPED_STATE_COPY"}; commands.append(record)
    process = subprocess.Popen(command, cwd=root, env=clean_env(), stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    read = []
    worker = threading.Thread(target=lambda: read.append(process.stdout.read(16 * 1024**2 + 10241)), daemon=True)
    worker.start(); worker.join(15)
    if worker.is_alive() or not read or len(read[0]) > 16 * 1024**2 + 10240:
        process.kill(); process.wait(timeout=5)
        raise ValueError("C_SERVICE_STATE_COPY_TIMEOUT_OR_CAP")
    code = process.wait(timeout=5); record["exitCode"] = code
    if code:
        raise ValueError("C_SERVICE_STATE_COPY_FAILED")
    content = helper.extract_state_tar(read[0])
    with target.open("xb") as out:
        out.write(content)
    record.update(bytes=len(content), sha256=sha(target))
    return {"bytes": len(content), "sha256": sha(target), "scope": "STOPPED_STATE_FILE_PRESERVATION_NOT_RESTART_PROOF"}


def run_live(root, prep):
    if not re.fullmatch(r"\d{8}T\d{6}Z-[0-9a-f]{8}", prep):
        raise ValueError("C_SERVICE_PREP_ID")
    prepared = root / "deployment/runtime/c-service-live-prepare" / prep
    prior = json.loads((prepared / "result.json").read_text(encoding="utf-8"))
    if prior.get("status") != "PREPARED_NOT_ACTUAL_HTTP_PG_ANVIL" or not prior.get("sourcesUnchanged") or not prior.get("dependenciesUnchanged"):
        raise ValueError("C_SERVICE_PREP_FAILED")
    checked_inputs(root)
    if any(sha(root / p) != digest for p, digest in prior["sources"].items()) or tree_hashes(prepared / "server-fixture") != prior["serverFixtureHashes"] or tree_hashes(prepared / "driver-fixture") != prior["driverFixtureHashes"]:
        raise ValueError("C_SERVICE_PREP_CHANGED")
    assert_server_tree(prepared / "server-fixture")
    run = "c-live-" + uuid.uuid4().hex[:16]
    evidence = root / "deployment/runtime/c-service-live" / run
    evidence.mkdir(parents=True, exist_ok=False)
    paths = {name: evidence / dirname for name, dirname in (("server", "server-fixture"), ("driver", "driver-fixture"),
        ("private", "driver-private"), ("config", "server-config"), ("control", "server-control"), ("serverEvidence", "server-evidence"))}
    for key in ("private", "config", "control", "serverEvidence"):
        paths[key].mkdir()
    shutil.copytree(prepared / "server-fixture", paths["server"])
    shutil.copytree(prepared / "driver-fixture", paths["driver"])
    suffix = run[7:]; database, owner, api = "c_live_" + suffix, "c_owner_" + suffix, "c_api_" + suffix
    marker, owner_pw, api_pw, jwt = (secrets.token_hex(32) for _ in range(4))
    manifest = {"scope": SCOPE, "runId": run, "sourceIdentity": prior["sourceIdentity"], "database": database,
        "databaseOwner": owner, "apiUser": api, "marker": marker}
    with (paths["driver"] / "manifest.properties").open("x", encoding="ascii") as out:
        out.write("\n".join(k + "=" + v for k, v in manifest.items()) + "\n")
    driver_env, pg_env = evidence / "driver.private.env", evidence / "pg.private.env"
    with driver_env.open("x", encoding="ascii") as out:
        out.write("C_GATE_OWNER_PASSWORD=" + owner_pw + "\nC_GATE_API_PASSWORD=" + api_pw + "\nC_GATE_JWT_SECRET=" + jwt + "\n")
    with pg_env.open("x", encoding="ascii") as out:
        out.write("POSTGRES_DB=" + database + "\nPOSTGRES_USER=" + owner + "\nPOSTGRES_PASSWORD=" + owner_pw + "\nPOSTGRES_INITDB_ARGS=--auth-host=scram-sha-256\n")
    commands, stopped, stop_failures, state = [], {}, {}, None
    status, failure = "BLOCKED", None
    active_end = time.monotonic() + 300
    helper = load_c5(root)

    def execute(args, timeout=15, check=True, cleanup=False):
        remaining = active_end - time.monotonic()
        if not cleanup and remaining <= 0:
            raise ValueError("C_SERVICE_OVERALL_TIMEOUT")
        item = {"command": safe_command(args, (owner_pw, api_pw, jwt))}; commands.append(item)
        try:
            result = subprocess.run(item["command"], cwd=root, env=clean_env(), capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout if cleanup else min(timeout, max(1, remaining)))
            item.update(exitCode=result.returncode, **command_observation(args, result.stdout, result.stderr))
            if check and result.returncode:
                raise ValueError("C_SERVICE_DOCKER_COMMAND_FAILED")
            return result
        except subprocess.TimeoutExpired:
            item["timeout"] = True
            raise ValueError("C_SERVICE_DOCKER_TIMEOUT_NO_RETRY") from None

    def info(kind, cleanup=False):
        r = execute(["container", "inspect", run + "-" + kind], check=False, cleanup=cleanup)
        return json.loads(r.stdout)[0] if r.returncode == 0 else None

    def owned(kind, cleanup=False):
        value = info(kind, cleanup)
        return value if value and value.get("Config", {}).get("Labels", {}).get("tms.c.owner") == run else None

    try:
        ram = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "(Get-CimInstance Win32_OperatingSystem -ErrorAction Stop).FreePhysicalMemory"], env=clean_env(), capture_output=True, text=True, timeout=10)
        memory = int(ram.stdout.strip()) * 1024 if ram.returncode == 0 and ram.stdout.strip().isdigit() else 0
        disk = shutil.disk_usage(root).free
        write_new(evidence / "resources.json", {"hostFreeMemoryBytes": memory, "hostFreeDiskBytes": disk, "minimumMemoryBytes": MIN_RAM})
        resource_guard(memory, disk)
        execute(["version", "--format", "{{.Server.Version}}"])
        execute(["ps", "-a", "--format", "{{.ID}}|{{.Names}}|{{.Image}}|{{.Status}}"])
        execute(["volume", "ls", "--format", "{{.Name}}"])
        for image in (PG_IMAGE, JAVA_IMAGE, FOUNDRY_IMAGE):
            inspected = json.loads(execute(["image", "inspect", image]).stdout)
            if len(inspected) != 1 or inspected[0].get("Id") != image or inspected[0].get("Os") != "linux" or inspected[0].get("Architecture") != "amd64":
                raise ValueError("C_SERVICE_IMAGE_IDENTITY")
        network_ids = execute(["network", "ls", "-q"]).stdout.splitlines()
        if len(network_ids) > 1024 or any(not re.fullmatch(r"[0-9a-f]{12,64}", x) for x in network_ids):
            raise ValueError("C_SERVICE_NETWORK_INVENTORY")
        occupied = []
        for first in range(0, len(network_ids), 64):
            configs = execute(["network", "inspect", *network_ids[first:first + 64], "--format", "{{json .IPAM.Config}}"])
            for line in configs.stdout.splitlines():
                occupied.extend(c["Subnet"] for c in json.loads(line) or [] if c.get("Subnet"))
        routes = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "@(Get-NetRoute -AddressFamily IPv4 -ErrorAction Stop | Select-Object -ExpandProperty DestinationPrefix) | ConvertTo-Json -Compress"], env=clean_env(), capture_output=True, text=True, timeout=10)
        if routes.returncode:
            raise ValueError("C_SERVICE_ROUTE_INVENTORY")
        values = json.loads(routes.stdout)
        subnet = select_subnet(occupied, [values] if isinstance(values, str) else values)
        for kind, name in (("network", run + "-network"), ("volume", run + "-pg-data"), ("volume", run + "-state"),
                *(("container", run + "-" + k) for k in ("pg", "anvil", "driver", "api"))):
            if execute([kind, "inspect", name], check=False).returncode == 0:
                raise ValueError("C_SERVICE_RESOURCE_EXISTS")
        execute(["network", "create", "--internal", "--subnet", subnet, "--label", "tms.c.owner=" + run, run + "-network"])
        net = json.loads(execute(["network", "inspect", run + "-network"]).stdout)[0]
        if net.get("Internal") is not True or net.get("Labels", {}).get("tms.c.owner") != run or [c.get("Subnet") for c in net.get("IPAM", {}).get("Config", [])] != [subnet]:
            raise ValueError("C_SERVICE_CREATED_NETWORK")
        for name in (run + "-pg-data", run + "-state"):
            execute(["volume", "create", "--label", "tms.c.owner=" + run, name])
            v = json.loads(execute(["volume", "inspect", name]).stdout)[0]
            if v.get("Labels", {}).get("tms.c.owner") != run:
                raise ValueError("C_SERVICE_CREATED_VOLUME")
        for kind in ("pg", "anvil"):
            execute(launch_args(kind, run, paths, driver_env, pg_env), timeout=30)
            value = owned(kind)
            if not value:
                raise ValueError("C_SERVICE_STARTED_OWNER")
            validate_container(value, kind, run, paths)
        ready = False
        for _ in range(20):
            if execute(["exec", run + "-pg", "pg_isready", "-h", "127.0.0.1", "-p", "5432", "-U", owner, "-d", database], timeout=5, check=False).returncode == 0:
                ready = True; break
            time.sleep(0.5)
        if not ready:
            raise ValueError("C_SERVICE_PG_NOT_READY")
        execute(["exec", run + "-anvil", "/bin/sh", "-c", "test \"$(id -u)\" = 0 && test -w /state && umask 077 && printf '%s' C_SERVICE_NEW_STATE_WRITE_PROBE > /state/c-service-write-probe"])
        execute(launch_args("driver", run, paths, driver_env, pg_env), timeout=30)
        value = owned("driver")
        if not value:
            raise ValueError("C_SERVICE_DRIVER_OWNER")
        validate_container(value, "driver", run, paths)
        bootstrap = paths["private"] / "bootstrap-ready.json"
        bootstrap_end = min(active_end, time.monotonic() + 100)
        while not bootstrap.is_file():
            value = owned("driver")
            if not value or value.get("State", {}).get("Running") is not True:
                raise ValueError("C_SERVICE_BOOTSTRAP_DRIVER_STOPPED")
            if time.monotonic() >= bootstrap_end:
                raise ValueError("C_SERVICE_BOOTSTRAP_TIMEOUT")
            time.sleep(0.5)
        if bootstrap.stat().st_size > 1024**2:
            raise ValueError("C_SERVICE_BOOTSTRAP_RESULT_CAP")
        boot = json.loads(bootstrap.read_text(encoding="utf-8"))
        if boot.get("runId") != run or boot.get("sourceIdentity") != prior["sourceIdentity"]:
            raise ValueError("C_SERVICE_BOOTSTRAP_IDENTITY")
        if not (paths["config"] / "application.properties").is_file():
            raise ValueError("C_SERVICE_SERVER_CONFIG_MISSING")
        server_config_hash = sha(paths["config"] / "application.properties")
        execute(launch_args("api", run, paths, driver_env, pg_env), timeout=30)
        value = owned("api")
        if not value:
            raise ValueError("C_SERVICE_API_OWNER")
        validate_container(value, "api", run, paths)
        waited = execute(["wait", run + "-driver"], timeout=max(1, active_end - time.monotonic()))
        if waited.stdout.strip() != "0":
            raise ValueError("C_SERVICE_DRIVER_GATE_FAILED")
        result_path = paths["private"] / "live-result.json"
        if not result_path.is_file() or result_path.stat().st_size > 1024**2:
            raise ValueError("C_SERVICE_DRIVER_RESULT_MISSING")
        result = json.loads(result_path.read_text(encoding="utf-8"))
        if result.get("result") != "PASS" or result.get("sourceIdentity") != prior["sourceIdentity"]:
            raise ValueError("C_SERVICE_DRIVER_RESULT_IDENTITY")
        ready_path = paths["serverEvidence"] / "server-ready.json"
        if not ready_path.is_file() or ready_path.stat().st_size > 1024**2:
            raise ValueError("C_SERVICE_SERVER_READY_MISSING")
        ready = json.loads(ready_path.read_text(encoding="utf-8"))
        if ready.get("runId") != run or ready.get("sourceIdentity") != prior["sourceIdentity"] or ready.get("dbName") != database or ready.get("dbUser") != api or ready.get("markerSha256") != hashlib.sha256(marker.encode()).hexdigest():
            raise ValueError("C_SERVICE_SERVER_READY_IDENTITY")
        if sha(paths["config"] / "application.properties") != server_config_hash:
            raise ValueError("C_SERVICE_SERVER_CONFIG_CHANGED")
        status = PASS
    except Exception as error:
        failure = str(error) if type(error) is ValueError and re.fullmatch(r"C_SERVICE_[A-Z0-9_]+", str(error)) else "C_SERVICE_SAFE_FAILURE"
    finally:
        # A normal API exit is requested by a fixture-local file, not a privileged HTTP endpoint.
        try:
            if owned("api", True):
                with (paths["control"] / "STOP").open("x", encoding="ascii") as out:
                    out.write("C_SERVICE_OWNED_STOP\n")
                execute(["wait", run + "-api"], timeout=20, check=False, cleanup=True)
        except Exception:
            stop_failures[run + "-api"] = "C_SERVICE_API_NORMAL_STOP_UNCONFIRMED"
        for kind in ("driver", "api", "pg", "anvil"):
            name = run + "-" + kind
            try:
                value = owned(kind, True)
                if value:
                    execute(["logs", "--tail", "100", name], check=False, cleanup=True)
                    stopped_cli = execute(["stop", "--time", "10", name], timeout=20, check=False, cleanup=True)
                    value = owned(kind, True)
                    stopped[name] = stopped_cli.returncode == 0 and value is not None and helper.normal_stop_state(value)
                    if not stopped[name]:
                        stop_failures[name] = "C_SERVICE_NONZERO_OR_UNSAFE_EXIT"
                    if kind == "anvil" and stopped[name]:
                        state = preserve_anvil(root, name, paths["private"] / "anvil-stopped-state.private.json", commands, helper)
            except Exception:
                stopped[name] = False; stop_failures[name] = "C_SERVICE_STOP_OR_PRESERVATION_FAILED"
        unchanged = all(sha(root / p) == digest for p, digest in prior["sources"].items())
        if status == PASS and (not unchanged or set(stopped) != {run + "-" + k for k in ("driver", "api", "pg", "anvil")} or not all(stopped.values()) or stop_failures or state is None):
            status = "BLOCKED"
        record = {"run": run, "scope": SCOPE, "status": status, "failure": failure, "sourceIdentity": prior["sourceIdentity"],
            "coreSourceHash": CORE_HASH, "contractSourceIdentity": CONTRACT_HASH, "sourcesUnchanged": unchanged,
            "ownedContainersStopped": stopped, "stopOrPreservationFailures": stop_failures, "anvilStatePreserved": state,
            "commands": commands, "paidCalls": 0, "realMetaMask": False, "fullTmsApplication": False,
            "completeCoverage": False, "productSynthetic": False, "oldResourceMutationsRequested": 0}
        write_new(evidence / "result.private.json", record)
        print(json.dumps({k: record[k] for k in ("run", "status", "failure", "sourceIdentity")}))
    return 0 if status == PASS else 1


def main():
    root = Path(__file__).resolve().parents[1]
    if sys.argv[1:] == ["prepare"]:
        return prepare(root)
    if len(sys.argv) == 3 and sys.argv[1] == "run":
        return run_live(root, sys.argv[2])
    raise SystemExit("USAGE: c-service-live-check.py prepare | run <prepared-id>; live requires completed review")


if __name__ == "__main__":
    raise SystemExit(main())
