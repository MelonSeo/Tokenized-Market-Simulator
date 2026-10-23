"""New local C owner-direct demo preparation. Prepare is offline; no old fixture is changed."""
import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import secrets
import subprocess
import sys
import threading
import time
import uuid

from c_gate_network import select_subnet

sys.dont_write_bytecode = True
CORE_RUN = "20261023T115200Z-d5586600"
CORE_HASH = "0bce4c2a3978c142cfe126fd753e8fa708d875bbbb7614114d3b4075248aebce"
CONTRACT_RUN = "20261023T065808Z-9108ce4b"
CONTRACT_HASH = "6ffb45c5e07187ed647f8281e048893c7dba522363ff55fdfeaf126deccbf1cb"
LOCK_HASH = "cfc3eeae5203cf105f8999f5048c7649e97bf96001f8c2d4a15051d11d8a256b"
PG_IMAGE = "sha256:c1b3783309b6499c795eed7c20135a1a4d25cae1b575c3d52c6f536129a1b109"
JAVA_IMAGE = "sha256:35be56d41389c9ad515c089dfded911ae9a8bdb362c8a303224666cd358bead3"
FOUNDRY_IMAGE = "sha256:0c00cb0bda1ab1b91c9a6bf60f4c76c09c1a8870824b6d4718afbabacf6f9a17"
MIN_RAM, MIN_DISK = 1920 * 1024**2, 2 * 1024**3
WORK_SECONDS, TOTAL_SECONDS = 330, 390
BASE = "backend/src/main/java/com/pricetrack/exchange/"
WEB = "tools/websocket-test-client/"
SQL = ("backend/src/main/resources/c-wallet-schema.sql", "backend/src/main/resources/c-consent-schema.sql",
       "backend/src/main/resources/c-direct-execution-schema.sql", "deployment/c-direct-demo-synthetic-schema.sql")
APP_DEPENDENCIES = tuple(BASE + p + ".java" for p in (
    "auth/AuthenticatedUser", "auth/JwtTokenProvider", "auth/JwtAuthenticationFilter", "user/UserRole", "user/User", "user/UserRepository",
    "common/config/SecurityConfig", "common/exception/ApiErrorResponse", "common/exception/InvalidTokenException",
    "futures/math/FuturesMath", "futures/runtime/FuturesMarketGate", "futures/ledger/FuturesLedger", "futures/ledger/FuturesRecords", "blockchain/support/TokenUnits",
    "market/MarketPriceService", "market/TradingPriceContext", "market/MarketDataEligibility", "market/MarketClosedException", "market/StalePriceException",
    "market/MarketCandleEntity", "market/MarketCandleRepository", "market/MarketCandleAggregator",
    "market/model/MarketPriceSnapshot", "market/model/MarketStatus", "market/model/PriceStatus", "market/model/CandleInterval", "market/model/MarketCandle", "market/model/MarketCandlePage",
    "market/provider/MarketPriceProvider", "market/provider/MarketCandleProvider",
    "market/provider/simulated/SimulatedPriceProvider", "market/provider/simulated/SimulatedCandleProvider", "market/provider/simulated/SyntheticMarketEngine", "market/provider/simulated/SyntheticMarketState", "market/provider/simulated/SyntheticMarketStateRepository",
    "websocket/publisher/MarketWebSocketPublisher", "websocket/publisher/WebSocketEventPublisher",
    "websocket/event/WebSocketDestinations", "websocket/event/WebSocketEventType", "websocket/event/WebSocketEvent", "websocket/event/WebSocketDelivery", "websocket/event/PriceEventPayload", "websocket/event/TradeEventPayload",
    "trade/Trade", "order/OrderSide", "order/ExecutionMode"))
ORACLE_SOURCES = ("deployment/CDirectOracleServer.java", BASE + "custody/abi/CAbiModels.java", BASE + "custody/proof/CEip712Verifier.java",
                  BASE + "custody/runtime/CCustodyIdentity.java", BASE + "custody/runtime/CCustodyFailure.java")
DRIVER_SOURCES = ("deployment/CDirectDemoDriver.java", "deployment/CAnvilContractGate.java", "deployment/CAnvilRiskClaimsScenario.java",
    BASE + "custody/abi/CAbiModels.java", BASE + "custody/abi/CAbiCodec.java", BASE + "custody/abi/CAbiEventModels.java", BASE + "custody/abi/CAbiEventCodec.java",
    BASE + "custody/proof/CEip712Verifier.java", BASE + "custody/runtime/CCustodyIdentity.java", BASE + "custody/runtime/CCustodyFailure.java", BASE + "futures/math/FuturesMath.java")
ORACLE_JARS = ("crypto-4.12.2.jar", "utils-4.12.2.jar", "rlp-4.12.2.jar", "bcprov-jdk18on-1.78.1.jar",
               "jackson-databind-2.17.2.jar", "jackson-core-2.17.2.jar", "jackson-annotations-2.17.2.jar")
FORBIDDEN_JARS = ("junit-", "junit-platform-", "apiguardian-", "opentest4j-", "mockito-", "byte-buddy-agent-", "hamcrest-", "assertj-",
    "spring-test-", "spring-security-test-", "spring-boot-test-", "h2-", "jsonassert-", "json-path-", "android-json-", "json-smart-", "accessors-smart-")
DRIVER_JVM = ("-Djdk.httpclient.disableRetryConnect=true", "-Djdk.httpclient.enableAllMethodRetry=false", "-Djdk.httpclient.redirects.retrylimit=1",
              "--add-opens=java.net.http/jdk.internal.net.http=ALL-UNNAMED")


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def new_json(path, value):
    with path.open("x", encoding="utf-8") as out:
        json.dump(value, out, indent=2, ensure_ascii=False)


def clean_env():
    return {k: v for k, v in os.environ.items() if k.upper() in {"PATH", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "USERPROFILE", "LOCALAPPDATA", "APPDATA", "PATHEXT", "COMSPEC"}}


def quote(value):
    value = str(value).replace("\\", "/")
    if any(c in value for c in ('"', '\r', '\n', '\0')):
        raise ValueError("C_DIRECT_DEMO_ARGUMENT")
    return '"' + value + '"'


def tree_hashes(path):
    return {p.relative_to(path).as_posix(): sha(p) for p in sorted(path.rglob("*")) if p.is_file()}


def server_sources(root):
    custody = tuple(p.relative_to(root).as_posix() for p in sorted((root / (BASE + "custody")).rglob("*.java")))
    if not custody or len(custody) > 192 or any("/src/test/" in p for p in custody):
        raise ValueError("C_DIRECT_DEMO_CUSTODY_SOURCE_SCOPE")
    return tuple(dict.fromkeys((*custody, *APP_DEPENDENCIES, "deployment/CDirectDemoApplication.java")))


def web_sources(root):
    paths = [root / (WEB + p) for p in ("index.html", "c-demo.html", "package.json", "package-lock.json", "vite.config.js")]
    paths.extend(p for p in sorted((root / (WEB + "src")).rglob("*")) if p.is_file())
    if len(paths) > 256 or any(not p.is_file() or p.is_symlink() or not p.resolve().is_relative_to((root / WEB).resolve()) for p in paths):
        raise ValueError("C_DIRECT_DEMO_WEB_SOURCE_SCOPE")
    return tuple(p.relative_to(root).as_posix() for p in paths)


def verify_resources(snapshot, fixtures):
    expected = {Path(rel).name: sha(snapshot / rel) for rel in SQL}
    for fixture, use_classpath in fixtures:
        for name, digest in expected.items():
            paths = [fixture / name]
            if use_classpath:
                paths.append(fixture / "classes" / name)
            if any(not p.is_file() or sha(p) != digest for p in paths):
                raise ValueError("C_DIRECT_DEMO_SQL_RESOURCE_MISMATCH")
    return expected


def copy_resources(snapshot, server, driver):
    for rel in SQL:
        source = snapshot / rel
        for path in (server / Path(rel).name, server / "classes" / Path(rel).name, driver / Path(rel).name):
            with path.open("xb") as out:
                out.write(source.read_bytes())
    return verify_resources(snapshot, ((server, True), (driver, False)))


def check_role_tree(path, role):
    files = list((path / "classes").rglob("*.class"))
    if not files:
        raise ValueError("C_DIRECT_DEMO_EMPTY_CLASSES")
    forbidden = {"server": ("CAnvil", "CDirectDemoDriver", "CDirectOracleServer", "CCustodyLiveDriver"),
                 "oracle": ("CAnvil", "CDirectDemoDriver", "CDirectDemoApplication", "CCustodyLiveDriver"),
                 "driver": ("CDirectDemoApplication", "CDirectOracleServer")}[role]
    if any("Test" in p.name or p.name.startswith(forbidden) for p in files):
        raise ValueError("C_DIRECT_DEMO_ROLE_CLASS_SEPARATION")
    names = [p.name for p in (path / "jars").glob("*.jar")]
    if any(name.startswith(FORBIDDEN_JARS) for name in names):
        raise ValueError("C_DIRECT_DEMO_TEST_JARS")
    if role == "oracle" and set(names) != set(ORACLE_JARS):
        raise ValueError("C_DIRECT_DEMO_ORACLE_JAR_SCOPE")


def javac_args(role, sources, classpath, output, snapshot, lombok):
    if role not in {"server", "oracle", "driver"}:
        raise ValueError("C_DIRECT_DEMO_COMPILE_ROLE")
    processor = ["-processorpath", str(lombok), "-processor", "lombok.launch.AnnotationProcessorHider$AnnotationProcessor"] if role == "server" else ["-proc:none"]
    # Processor discovery and source annotation resolution are separate javac classpaths.
    compile_classpath = classpath + os.pathsep + str(lombok) if role == "server" else classpath
    return ["--release", "21", "-parameters", "-encoding", "UTF-8", *processor, "-cp", compile_classpath, "-d", str(output), *[str(snapshot / p) for p in sources]]


def load_inputs(root):
    core_path = root / "deployment/runtime/c-service-read" / CORE_RUN / "result.json"
    core = json.loads(core_path.read_text(encoding="utf-8"))
    if core.get("sourceHash") != CORE_HASH or core.get("compileExit") != 0 or core.get("testExit") != 0 or not core.get("sourcesUnchanged") or not core.get("dependenciesUnchanged"):
        raise ValueError("C_DIRECT_DEMO_CORE_BASELINE")
    if any(sha(root / p) != digest for p, digest in core["sources"].items()):
        raise ValueError("C_DIRECT_DEMO_CORE_CHANGED")
    old = root / "deployment/runtime/c-anvil-offline" / CONTRACT_RUN
    contract = json.loads((old / "result.json").read_text(encoding="utf-8"))
    if contract.get("sourceIdentity") != CONTRACT_HASH or contract.get("status") != "OFFLINE_PASS_NOT_ACTUAL_ANVIL" or not contract.get("sourcesUnchanged"):
        raise ValueError("C_DIRECT_DEMO_CONTRACT_BASELINE")
    if any(sha(root / p) != h for p, h in {**contract["sources"], **contract["contractSources"]}.items()) or tree_hashes(old / "fixture") != contract.get("fixtureHashes"):
        raise ValueError("C_DIRECT_DEMO_CONTRACT_CHANGED")
    for item in contract["sourceMap"]:
        p = (root / "contracts" / item["source"]).resolve() if item["source"].startswith("src/") else Path(item["source"]).resolve()
        allowed = (root / "contracts/src").resolve() if item["source"].startswith("src/") else (root.parents[2] / "contracts/lib").resolve()
        if not p.is_relative_to(allowed) or sha(p) != item["sha256"]:
            raise ValueError("C_DIRECT_DEMO_CONTRACT_DEPENDENCY_CHANGED")
    return core, contract, old / "fixture"


def cached_jars(root, core):
    cache = root.parents[2] / "deployment/runtime/dapp-audit-development-worktree/deployment/runtime/dapp-stage2-offline/java-classpath.args"
    lines = cache.read_text(encoding="utf-8-sig").splitlines()
    if len(lines) != 2 or lines[0] != "-cp":
        raise ValueError("C_DIRECT_DEMO_CLASSPATH")
    jars = {}
    for entry in lines[1].strip('"').split(os.pathsep):
        p = Path(entry)
        if p.suffix.lower() != ".jar":
            continue
        if not p.is_file() or "/caches/modules-2/files-2.1/" not in p.as_posix() or p.name in jars or sha(p) != core["dependencies"].get(p.name):
            raise ValueError("C_DIRECT_DEMO_CACHED_DEPENDENCY")
        if not p.name.startswith(FORBIDDEN_JARS):
            jars[p.name] = p
    if len(jars) > 512 or any(name not in jars for name in ORACLE_JARS):
        raise ValueError("C_DIRECT_DEMO_DEPENDENCY_PROFILE")
    first = next(iter(jars.values()))
    candidates = list((first.parents[4] / "org.projectlombok/lombok/1.18.34").glob("*/lombok-1.18.34.jar"))
    if len(candidates) != 1:
        raise ValueError("C_DIRECT_DEMO_LOMBOK")
    return jars, candidates[0]


def checked_web_tree(path):
    if not (path / "c-demo.html").is_file():
        raise ValueError("C_DIRECT_DEMO_WEB_ENTRY_MISSING")
    files = [p for p in path.rglob("*") if p.is_file()]
    if not files or len(files) > 512 or sum(p.stat().st_size for p in files) > 16 * 1024**2:
        raise ValueError("C_DIRECT_DEMO_WEB_LIMIT")
    if any(p.is_symlink() or not p.resolve().is_relative_to(path.resolve()) for p in files):
        raise ValueError("C_DIRECT_DEMO_WEB_OUTSIDE_ROOT")
    return tree_hashes(path)


def prepare(root):
    core, contract, artifact_fixture = load_inputs(root)
    jars, lombok = cached_jars(root, core)
    selected = server_sources(root)
    inputs = tuple(dict.fromkeys((*selected, *ORACLE_SOURCES, *DRIVER_SOURCES, *SQL, *web_sources(root),
        "deployment/c-direct-demo-check.py", "deployment/test_c_direct_demo_check.py", "deployment/c_gate_network.py", "deployment/c-anvil-check.py")))
    if any(not (root / p).is_file() for p in inputs):
        raise ValueError("C_DIRECT_DEMO_INPUT_MISSING")
    if sha(root / (WEB + "package-lock.json")) != LOCK_HASH or json.loads((root / (WEB + "package.json")).read_text(encoding="utf-8"))["scripts"].get("build") != "vite build":
        raise ValueError("C_DIRECT_DEMO_WEB_BUILD_IDENTITY")
    identity = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ-") + uuid.uuid4().hex[:8]
    out = root / "deployment/runtime/c-direct-demo-prepare" / identity
    out.mkdir(parents=True, exist_ok=False)
    paths = {name: out / (name + "-fixture") for name in ("server", "oracle", "driver")}
    for path in paths.values():
        (path / "classes").mkdir(parents=True, exist_ok=False);(path / "jars").mkdir()
    before = {p: sha(root / p) for p in inputs}
    for rel in inputs:
        dest = out / "source-snapshot" / rel;dest.parent.mkdir(parents=True, exist_ok=True)
        with dest.open("xb") as f:
            f.write((root / rel).read_bytes())
    for name, p in jars.items():
        for role in ("server", "driver"):
            shutil.copyfile(p, paths[role] / "jars" / name)
        if name in ORACLE_JARS:
            shutil.copyfile(p, paths["oracle"] / "jars" / name)
    sql_hashes = copy_resources(out / "source-snapshot", paths["server"], paths["driver"])
    for name in ("CMockCollateral", "CCollateralizedFuturesController", "CMarginVault", "FuturesEngine"):
        shutil.copyfile(artifact_fixture / (name + ".json"), paths["driver"] / (name + ".json"))
    shutil.copyfile(artifact_fixture / "source-map.json", paths["driver"] / "source-map.json")
    for item in contract["sourceMap"]:
        shutil.copyfile(artifact_fixture / item["file"], paths["driver"] / item["file"])
    jdk = Path("C:/Program Files/Java/jdk-21.0.10/bin")
    if not (jdk / "java.exe").is_file() or not (jdk / "javac.exe").is_file():
        raise ValueError("C_DIRECT_DEMO_JDK")
    env = clean_env()
    record = {"run": identity, "status": "BLOCKED", "scope": "OFFLINE_C_DIRECT_LOCAL_DEMO_PREPARATION_NOT_ACTUAL_HTTP_OR_WALLET",
        "sources": before, "sourceIdentity": hashlib.sha256(json.dumps(before, sort_keys=True).encode()).hexdigest(),
        "coreSourceHash": CORE_HASH, "coreRun": CORE_RUN, "contractSourceIdentity": CONTRACT_HASH, "contractRun": CONTRACT_RUN,
        "dependencies": {name: sha(p) for name, p in jars.items()}, "lombokHash": sha(lombok), "sqlHashes": sql_hashes,
        "compileExits": {}, "dockerCalls": 0, "realRpcCalls": 0, "paidCalls": 0}
    def process(name, command, timeout=120, child_env=None):
        with (out / (name + ".stdout.log")).open("xb") as stdout, (out / (name + ".stderr.log")).open("xb") as stderr:
            return subprocess.run(command, cwd=root if not name.startswith("web") else root / WEB, env=env if child_env is None else child_env,
                                  stdout=stdout, stderr=stderr, timeout=timeout).returncode
    try:
        version = subprocess.run([str(jdk / "java.exe"), "-version"], env=env, capture_output=True, text=True, timeout=10)
        record["javaVersion"] = version.stderr
        if version.returncode or 'version "21.0.10"' not in version.stderr:
            raise ValueError("C_DIRECT_DEMO_JDK_IDENTITY")
        source_sets = {"server": selected, "oracle": ORACLE_SOURCES, "driver": DRIVER_SOURCES}
        mains = {"server": "CDirectDemoApplication", "oracle": "CDirectOracleServer", "driver": "CDirectDemoDriver"}
        for role in ("server", "oracle", "driver"):
            path = paths[role];cp = os.pathsep.join(str(p) for p in sorted((path / "jars").glob("*.jar")))
            args = javac_args(role, source_sets[role], cp, path / "classes", out / "source-snapshot", lombok)
            argfile = out / (role + ".compile.args")
            with argfile.open("x", encoding="utf-8") as f:
                f.write("\n".join(quote(a) for a in args))
            code = process(role + ".compile", [str(jdk / "javac.exe"), "-J-Xmx256m", "@" + str(argfile)])
            record["compileExits"][role] = code
            if code:
                raise ValueError("C_DIRECT_DEMO_" + role.upper() + "_COMPILE")
            check_role_tree(path, role)
            args = ["-Xms16m", "-Xmx128m", "-XX:MaxMetaspaceSize=128m", "-Dfile.encoding=UTF-8", *(DRIVER_JVM if role == "driver" else ()),
                    "-cp", str(path / "classes") + os.pathsep + cp, mains[role], "--self-test", *([str(path)] if role == "driver" else ())]
            argfile = out / (role + ".self-test.args")
            with argfile.open("x", encoding="utf-8") as f:
                f.write("\n".join(quote(a) for a in args))
            code = process(role + ".self-test", [str(jdk / "java.exe"), "@" + str(argfile)], timeout=30)
            record[role + "SelfTestExit"] = code
            if code or (out / (role + ".self-test.stderr.log")).stat().st_size:
                raise ValueError("C_DIRECT_DEMO_" + role.upper() + "_SELF_TEST")
        text = (out / "server.self-test.stdout.log").read_text(encoding="utf-8")
        for name, digest in sql_hashes.items():
            if "C_DIRECT_DEMO_RESOURCE " + name + " " + digest not in text:
                raise ValueError("C_DIRECT_DEMO_SQL_CLASSLOADER_SELF_TEST")
        web = out / "web-fixture"
        if web.exists() or not web.resolve().is_relative_to(out.resolve()):
            raise ValueError("C_DIRECT_DEMO_NEW_WEB_OUTPUT")
        npm = shutil.which("npm.cmd") or shutil.which("npm")
        if npm is None:
            raise ValueError("C_DIRECT_DEMO_NPM")
        web_env = dict(env);web_env["npm_config_offline"] = "true";web_env["npm_config_audit"] = "false";web_env["npm_config_fund"] = "false"
        record["webBuildExit"] = process("web.build", [npm, "run", "build", "--", "--outDir", str(web)], timeout=90, child_env=web_env)
        if record["webBuildExit"]:
            raise ValueError("C_DIRECT_DEMO_WEB_BUILD")
        record["webHashes"] = checked_web_tree(web)
        record["pythonTestExit"] = process("python.tests", [sys.executable, "-B", "-m", "unittest", "discover", "-s", "deployment", "-p", "test_c_direct_demo_check.py"], timeout=30)
        if record["pythonTestExit"]:
            raise ValueError("C_DIRECT_DEMO_PYTHON_TESTS")
        record["status"] = "PREPARED_NOT_ACTUAL_PG_ANVIL_HTTP_OR_METAMASK"
    except subprocess.TimeoutExpired:
        record["error"] = "C_DIRECT_DEMO_OFFLINE_TIMEOUT"
    except Exception as ex:
        record["error"] = str(ex) if isinstance(ex, ValueError) and re.fullmatch(r"C_DIRECT_DEMO_[A-Z0-9_]+", str(ex)) else "C_DIRECT_DEMO_OFFLINE_SAFE_FAILURE_" + type(ex).__name__
    finally:
        try:
            record["sourcesUnchanged"] = before == {p: sha(root / p) for p in inputs} and selected == server_sources(root) and set(web_sources(root)).issubset(before)
            record["dependenciesUnchanged"] = all(sha(p) == record["dependencies"][name] for name, p in jars.items()) and sha(lombok) == record["lombokHash"]
        except Exception:
            record["sourcesUnchanged"] = record["dependenciesUnchanged"] = False
        record["fixtureHashes"] = {role: tree_hashes(path) for role, path in paths.items()}
        if not record["sourcesUnchanged"] or not record["dependenciesUnchanged"]:
            record["status"] = "BLOCKED";record["error"] = "C_DIRECT_DEMO_END_IDENTITY_CHANGED"
        new_json(out / "result.json", record)
    print(json.dumps({key: record.get(key) for key in ("run", "status", "sourceIdentity", "error")}))
    return 0 if record["status"].startswith("PREPARED_") else 1


def valid_run(run):
    if not re.fullmatch(r"cdemo-[0-9a-f]{16}", run):
        raise ValueError("C_DIRECT_DEMO_RUN_ID")


def bind(source, destination, readonly):
    resolved = str(source.resolve())
    if any(c in resolved + destination for c in (",", "\n", "\r", "\0")):
        raise ValueError("C_DIRECT_DEMO_BIND_FORMAT")
    return "type=bind,source=" + resolved + ",target=" + destination + (",readonly" if readonly else "")


def launch_args(role, run, paths, env_files):
    valid_run(run)
    if role not in {"pg", "anvil", "oracle", "app", "bootstrap", "verify"}:
        raise ValueError("C_DIRECT_DEMO_ROLE")
    common = ["run", "-d", "--pull=never", "--name", run + "-" + role, "--label", "tms.c.owner=" + run,
        "--network", run + "-network", "--restart=no", "--cpus=1", "--pids-limit=128", "--log-opt", "max-size=10m", "--log-opt", "max-file=1"]
    if role == "pg":
        return common + ["--network-alias", "pg", "--memory=256m", "--cap-drop=NET_RAW", "--mount", "type=volume,source=" + run + "-pg-data,target=/var/lib/postgresql/data", "--env-file", str(env_files["pg"]), PG_IMAGE]
    common += ["--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges", "--tmpfs", "/tmp:rw,noexec,nosuid,size=32m"]
    if role == "anvil":
        return common + ["--network-alias", "anvil", "--memory=192m", "--user=0:0", "--mount", "type=volume,source=" + run + "-state,target=/state",
            "--entrypoint", "/usr/local/bin/anvil", FOUNDRY_IMAGE, "--host", "0.0.0.0", "--port", "8545", "--chain-id", "31337", "--accounts", "0", "--silent", "--block-time", "1", "--state", "/state/anvil.json", "--state-interval", "1"]
    common += ["--user=10001:10001"]
    if role in {"bootstrap", "verify"}:
        mappings = ((paths["driver"], "/driver-fixture", True), (paths["private"], "/driver-private", False),
            (paths["appConfig"], "/app-config", role != "bootstrap"), (paths["oracleConfig"], "/oracle-config", role != "bootstrap"),
            (paths["driverControl"], "/driver-control", True))
        # Only bootstrap gets write access to generated keyless/signer configuration.
        for source, target, ro in mappings:
            common += ["--mount", bind(source, target, ro)]
        return common + ["--memory=256m", "--env-file", str(env_files["driver"]), "--entrypoint", "java", JAVA_IMAGE,
            "-Xms16m", "-Xmx128m", "-XX:MaxMetaspaceSize=96m", "-Dfile.encoding=UTF-8", *DRIVER_JVM,
            "-cp", "/driver-fixture/classes:/driver-fixture/jars/*", "CDirectDemoDriver", role,
            "/driver-fixture", "/driver-private", "/app-config", "/oracle-config", "/driver-control"]
    if role == "oracle":
        for source, target, ro in ((paths["oracle"], "/oracle-fixture", True), (paths["oracleConfig"], "/oracle-config", True),
            (paths["oracleControl"], "/oracle-control", True), (paths["oracleEvidence"], "/oracle-evidence", False)):
            common += ["--mount", bind(source, target, ro)]
        return common + ["--network-alias", "oracle", "--memory=192m", "--entrypoint", "java", JAVA_IMAGE,
            "-Xms16m", "-Xmx64m", "-XX:MaxMetaspaceSize=64m", "-Dfile.encoding=UTF-8", "-cp", "/oracle-fixture/classes:/oracle-fixture/jars/*",
            "CDirectOracleServer", "/oracle-config/identity.json", "/oracle-config/oracle-key.private", "/oracle-config/auth-token.private", "/oracle-control", "/oracle-evidence", "900"]
    for source, target, ro in ((paths["server"], "/server-fixture", True), (paths["web"], "/demo-web", True), (paths["appConfig"], "/demo-config", True),
        (paths["appControl"], "/demo-control", True), (paths["appEvidence"], "/demo-evidence", False)):
        common += ["--mount", bind(source, target, ro)]
    return common + ["--network-alias", "app", "--memory=512m", "--entrypoint", "java", JAVA_IMAGE,
        "-Xms32m", "-Xmx256m", "-XX:MaxMetaspaceSize=128m", "-Dfile.encoding=UTF-8", "-cp", "/server-fixture/classes:/server-fixture/jars/*",
        "CDirectDemoApplication", "/demo-config/application.properties", "/demo-control", "/demo-evidence"]


def validate_container(info, role, run, paths, env_files):
    valid_run(run)
    c, h, n = info.get("Config", {}), info.get("HostConfig", {}), info.get("NetworkSettings", {})
    if c.get("Labels", {}).get("tms.c.owner") != run or h.get("NetworkMode") != run + "-network" or set(n.get("Networks", {})) != {run + "-network"}:
        raise ValueError("C_DIRECT_DEMO_CONTAINER_OWNER_NETWORK")
    if h.get("Privileged") or h.get("CapAdd") or h.get("PortBindings") or any((n.get("Ports") or {}).values()):
        raise ValueError("C_DIRECT_DEMO_CONTAINER_ISOLATION")
    expected_image = PG_IMAGE if role == "pg" else FOUNDRY_IMAGE if role == "anvil" else JAVA_IMAGE
    expected_memory = {"pg": 256, "anvil": 192, "oracle": 192, "app": 512, "bootstrap": 256, "verify": 256}[role] * 1024**2
    if info.get("Image") != expected_image or h.get("Memory") != expected_memory:
        raise ValueError("C_DIRECT_DEMO_IMAGE_MEMORY")
    cap = h.get("CapDrop")
    if role == "pg":
        if h.get("ReadonlyRootfs") or not isinstance(cap, list) or len(cap) != 1 or cap[0] not in {"NET_RAW", "CAP_NET_RAW"} or c.get("User") not in {"", "0", "0:0"}:
            raise ValueError("C_DIRECT_DEMO_PG_SCOPE")
        if c.get("Entrypoint") != ["docker-entrypoint.sh"] or c.get("Cmd") != ["postgres"]:
            raise ValueError("C_DIRECT_DEMO_PG_ENTRYPOINT")
    elif h.get("ReadonlyRootfs") is not True or cap != ["ALL"] or not any(s in {"no-new-privileges", "no-new-privileges:true"} for s in h.get("SecurityOpt", [])) or c.get("User") != ("0:0" if role == "anvil" else "10001:10001"):
        raise ValueError("C_DIRECT_DEMO_JAVA_ANVIL_SCOPE")
    mounts = {m.get("Destination"): m for m in info.get("Mounts", []) if m.get("Type") != "tmpfs"}
    if role in {"pg", "anvil"}:
        target = "/var/lib/postgresql/data" if role == "pg" else "/state"
        name = run + ("-pg-data" if role == "pg" else "-state")
        if set(mounts) != {target} or mounts[target].get("Type") != "volume" or mounts[target].get("Name") != name or mounts[target].get("RW") is not True:
            raise ValueError("C_DIRECT_DEMO_VOLUME_SCOPE")
    else:
        if role in {"bootstrap", "verify"}:
            expected = {"/driver-fixture": (paths["driver"], False), "/driver-private": (paths["private"], True),
                "/app-config": (paths["appConfig"], role == "bootstrap"), "/oracle-config": (paths["oracleConfig"], role == "bootstrap"), "/driver-control": (paths["driverControl"], False)}
        elif role == "oracle":
            expected = {"/oracle-fixture": (paths["oracle"], False), "/oracle-config": (paths["oracleConfig"], False),
                "/oracle-control": (paths["oracleControl"], False), "/oracle-evidence": (paths["oracleEvidence"], True)}
        else:
            expected = {"/server-fixture": (paths["server"], False), "/demo-web": (paths["web"], False), "/demo-config": (paths["appConfig"], False),
                "/demo-control": (paths["appControl"], False), "/demo-evidence": (paths["appEvidence"], True)}
        if set(mounts) != set(expected):
            raise ValueError("C_DIRECT_DEMO_BIND_SEPARATION")
        for target, (source, rw) in expected.items():
            m = mounts[target]
            if m.get("Type") != "bind" or m.get("RW") is not rw or str(m.get("Source", "")).replace("\\", "/").lower().rstrip("/") != str(source.resolve()).replace("\\", "/").lower().rstrip("/"):
                raise ValueError("C_DIRECT_DEMO_BIND_SCOPE")
    expected_args = launch_args(role, run, paths, env_files)
    at = expected_args.index("--entrypoint") if "--entrypoint" in expected_args else -1
    if at >= 0 and (c.get("Entrypoint") != [expected_args[at + 1]] or c.get("Cmd") != expected_args[at + 3:]):
        raise ValueError("C_DIRECT_DEMO_PROCESS_ARGS")
    if role in {"app", "oracle"}:
        for entry in c.get("Env", []):
            key, _, value = entry.partition("=")
            if key.startswith("C_DEMO_") or value and any(s in key.upper() for s in ("PRIVATE_KEY", "MNEMONIC", "SEED_PHRASE", "API_KEY")):
                raise ValueError("C_DIRECT_DEMO_ROLE_ENVIRONMENT")


def run_gate(root, prep_id):
    if not re.fullmatch(r"\d{8}T\d{6}Z-[0-9a-f]{8}", prep_id):
        raise ValueError("C_DIRECT_DEMO_PREPARE_ID")
    prepared = root / "deployment/runtime/c-direct-demo-prepare" / prep_id
    r = json.loads((prepared / "result.json").read_text(encoding="utf-8"))
    if r.get("status") != "PREPARED_NOT_ACTUAL_PG_ANVIL_HTTP_OR_METAMASK" or any(sha(root / p) != v for p, v in r["sources"].items()):
        raise ValueError("C_DIRECT_DEMO_PREPARED_SOURCE_CHANGED")
    for role, hashes in r["fixtureHashes"].items():
        if tree_hashes(prepared / (role + "-fixture")) != hashes:
            raise ValueError("C_DIRECT_DEMO_PREPARED_FIXTURE_CHANGED")
    if checked_web_tree(prepared / "web-fixture") != r["webHashes"] or (prepared / "RUN_CLAIM").exists():
        raise ValueError("C_DIRECT_DEMO_WEB_OR_RUN_CLAIM")
    began = time.monotonic();deadline = began + WORK_SECONDS;total_deadline = began + TOTAL_SECONDS
    commands = [];secret_values = ()
    def docker(args, timeout=10, check=True):
        if any(secret in str(args) for secret in secret_values):
            raise ValueError("C_DIRECT_DEMO_SECRET_ARGV")
        remaining = int(deadline - time.monotonic())
        if remaining < 1:
            raise ValueError("C_DIRECT_DEMO_DEADLINE")
        result = subprocess.run(["docker", *args], cwd=root, env=clean_env(), capture_output=True, text=True, timeout=min(timeout, remaining))
        commands.append({"args": args, "exitCode": result.returncode, "stdoutBytes": len(result.stdout), "stderrBytes": len(result.stderr)})
        if check and result.returncode:
            raise ValueError("C_DIRECT_DEMO_DOCKER_FAILURE")
        return result
    # Read-only prerequisites precede the once-only claim and every new Docker resource.
    ram = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "(Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory"], env=clean_env(), capture_output=True, text=True, timeout=10)
    free = int(ram.stdout.strip()) * 1024 if ram.returncode == 0 and ram.stdout.strip().isdigit() else 0
    if free < MIN_RAM or shutil.disk_usage(root).free < MIN_DISK:
        raise ValueError("C_DIRECT_DEMO_RESOURCES_LOW")
    docker(["version", "--format", "{{.Server.Version}}"])
    for image in (PG_IMAGE, JAVA_IMAGE, FOUNDRY_IMAGE):
        if docker(["image", "inspect", image, "--format", "{{.Id}}|{{.Os}}|{{.Architecture}}"]).stdout.strip() != image + "|linux|amd64":
            raise ValueError("C_DIRECT_DEMO_IMAGE_IDENTITY")
    nets = docker(["network", "ls", "--format", "{{.ID}}"]).stdout.splitlines()
    if len(nets) > 1024 or any(not re.fullmatch(r"[0-9a-f]{12,64}", n) for n in nets):
        raise ValueError("C_DIRECT_DEMO_NETWORK_LIST")
    occupied = []
    for i in range(0, len(nets), 64):
        for line in docker(["network", "inspect", *nets[i:i + 64], "--format", "{{json .IPAM.Config}}"]).stdout.splitlines():
            occupied.extend(item["Subnet"] for item in json.loads(line) or [] if item.get("Subnet"))
    routes = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "@(Get-NetRoute -AddressFamily IPv4 -ErrorAction Stop | Select-Object -ExpandProperty DestinationPrefix) | ConvertTo-Json -Compress"], env=clean_env(), capture_output=True, text=True, timeout=10)
    if routes.returncode:
        raise ValueError("C_DIRECT_DEMO_ROUTES")
    parsed = json.loads(routes.stdout);subnet = select_subnet(occupied, [parsed] if isinstance(parsed, str) else parsed)
    with (prepared / "RUN_CLAIM").open("x", encoding="ascii") as f:
        f.write("ONE_NEW_INTERNAL_ACTUAL_GATE_NO_AUTOMATIC_RETRY\n")
    run = "cdemo-" + uuid.uuid4().hex[:16]
    out = root / "deployment/runtime/c-direct-demo" / run;out.mkdir(parents=True, exist_ok=False)
    paths = {"server": prepared / "server-fixture", "oracle": prepared / "oracle-fixture", "web": prepared / "web-fixture", "driver": out / "driver-fixture"}
    shutil.copytree(prepared / "driver-fixture", paths["driver"])
    for name in ("private", "appConfig", "oracleConfig", "driverControl", "appControl", "oracleControl", "appEvidence", "oracleEvidence"):
        paths[name] = out / name;paths[name].mkdir()
    suffix = run[6:];database, owner, api = "cdemo_" + suffix, "cdowner_" + suffix, "cdapi_" + suffix
    secrets_map = {key: secrets.token_hex(32) for key in ("OWNER_PASSWORD", "API_PASSWORD", "JWT_SECRET", "LOGIN_PASSWORD", "ORACLE_TOKEN")}
    secret_values = tuple(secrets_map.values())
    if len(set(secrets_map.values())) != 5:
        raise ValueError("C_DIRECT_DEMO_SECRET_COLLISION")
    env_files = {role: out / (role + ".private.env") for role in ("pg", "driver")}
    with env_files["pg"].open("x", encoding="ascii") as f:
        f.write("POSTGRES_DB=" + database + "\nPOSTGRES_USER=" + owner + "\nPOSTGRES_PASSWORD=" + secrets_map["OWNER_PASSWORD"] + "\nPOSTGRES_INITDB_ARGS=--auth-host=scram-sha-256\n")
    with env_files["driver"].open("x", encoding="ascii") as f:
        f.write("\n".join("C_DEMO_" + key + "=" + value for key, value in secrets_map.items()) + "\n")
    manifest = {"scope": "C_DIRECT_DEMO_NEW_OWNED_FIXTURE", "runId": run, "sourceIdentity": r["sourceIdentity"], "marker": secrets.token_hex(32),
        "origin": "http://127.0.0.1:1", "database": database, "databaseOwner": owner, "apiUser": api, "lifetimeSeconds": 900,
        "presentationLocalOnly": True, "syntheticTickMs": 3000, "sqlHashes": r["sqlHashes"]}
    new_json(paths["driver"] / "manifest.json", manifest)
    driver_hashes = tree_hashes(paths["driver"]);status, error, states, preservation = "BLOCKED", None, {}, {};attempted = set();stage = "NEW_RESOURCE_COLLISION_CHECK"
    def inspect(role):
        values = json.loads(docker(["container", "inspect", run + "-" + role]).stdout)
        if not isinstance(values, list) or len(values) != 1:
            raise ValueError("C_DIRECT_DEMO_INSPECT")
        validate_container(values[0], role, run, paths, env_files)
        return values[0]
    def wait_stopped(role):
        while time.monotonic() < deadline:
            state = inspect(role).get("State", {})
            if state.get("Running") is False:
                if state.get("ExitCode") != 0 or state.get("OOMKilled") or state.get("Error"):
                    raise ValueError("C_DIRECT_DEMO_" + role.upper() + "_FAILED")
                return
            time.sleep(1)
        raise ValueError("C_DIRECT_DEMO_DEADLINE")
    def wait_file(role, file):
        while time.monotonic() < deadline:
            if file.is_file():
                return json.loads(file.read_text(encoding="utf-8"))
            if inspect(role).get("State", {}).get("Running") is not True:
                raise ValueError("C_DIRECT_DEMO_" + role.upper() + "_NOT_READY")
            time.sleep(1)
        raise ValueError("C_DIRECT_DEMO_DEADLINE")
    def launch(role):
        # Mark BEFORE docker run: a timed-out CLI may still have created the exact new container.
        attempted.add(role);docker(launch_args(role, run, paths, env_files));inspect(role)
    try:
        new_json(out / "resources.json", {"freeMemoryBytes": free, "freeDiskBytes": shutil.disk_usage(root).free, "minimumMemoryBytes": MIN_RAM, "minimumDiskBytes": MIN_DISK})
        for kind, name in (("network", run + "-network"), ("volume", run + "-pg-data"), ("volume", run + "-state"), *[("container", run + "-" + role) for role in ("pg", "anvil", "bootstrap", "oracle", "app", "verify")]):
            if docker([kind, "inspect", name], check=False).returncode == 0:
                raise ValueError("C_DIRECT_DEMO_EXISTING_RESOURCE_COLLISION")
        docker(["network", "create", "--internal", "--subnet", subnet, "--label", "tms.c.owner=" + run, run + "-network"])
        network = json.loads(docker(["network", "inspect", run + "-network"]).stdout)[0]
        if network.get("Labels", {}).get("tms.c.owner") != run or network.get("Internal") is not True:
            raise ValueError("C_DIRECT_DEMO_NETWORK_SCOPE")
        for name in (run + "-pg-data", run + "-state"):
            docker(["volume", "create", "--label", "tms.c.owner=" + run, name])
            volume = json.loads(docker(["volume", "inspect", name]).stdout)
            if len(volume) != 1 or volume[0].get("Labels", {}).get("tms.c.owner") != run:
                raise ValueError("C_DIRECT_DEMO_VOLUME_OWNER")
        for role in ("pg", "anvil"):
            stage = "START_" + role.upper()
            launch(role)
        stage = "POSTGRES_TCP_READINESS"
        for _ in range(30):
            if docker(["exec", run + "-pg", "pg_isready", "-h", "127.0.0.1", "-p", "5432", "-U", owner, "-d", database], check=False).returncode == 0:
                break
            time.sleep(1)
        else:
            raise ValueError("C_DIRECT_DEMO_PG_NOT_READY")
        stage = "ANVIL_RPC_READINESS"
        for _ in range(12):
            if docker(["exec", run + "-anvil", "/usr/local/bin/cast", "chain-id", "--rpc-url", "http://127.0.0.1:8545"], timeout=3, check=False).stdout.strip() == "31337":
                break
            time.sleep(0.25)
        else:
            raise ValueError("C_DIRECT_DEMO_ANVIL_NOT_READY")
        stage = "DRIVER_BOOTSTRAP"
        launch("bootstrap");wait_stopped("bootstrap")
        boot = json.loads((paths["private"] / "bootstrap/phase-result.json").read_text(encoding="utf-8"))
        if boot.get("result") != "PASS" or boot.get("sourceIdentity") != r["sourceIdentity"] or boot.get("runId") != run:
            raise ValueError("C_DIRECT_DEMO_BOOTSTRAP_IDENTITY")
        public_identity = json.loads((paths["private"] / "identity.json").read_text(encoding="utf-8"))
        boot_ready = json.loads((paths["private"] / "bootstrap/bootstrap-ready.json").read_text(encoding="utf-8"))
        for role, ready_file in (("oracle", paths["oracleEvidence"] / "ready.json"), ("app", paths["appEvidence"] / "server-ready.json")):
            stage = "START_AND_WAIT_" + role.upper()
            launch(role);ready = wait_file(role, ready_file)
            if role == "app" and (ready.get("runId") != run or ready.get("sourceIdentity") != r["sourceIdentity"] or any(ready.get(k) is not False for k in ("userPrivateKeys", "oraclePrivateKeys", "relayerPrivateKeys"))):
                raise ValueError("C_DIRECT_DEMO_SERVER_READY_IDENTITY")
            if role == "app" and ready.get("syntheticTickMs") != manifest["syntheticTickMs"]:
                raise ValueError("C_DIRECT_DEMO_SERVER_CADENCE_IDENTITY")
            if role == "oracle" and (ready.get("deploymentId") != boot_ready.get("deploymentId") or ready.get("oracle") != public_identity["oracle"] or ready.get("scope") != "INTERNAL_FIXED_REPORT_SIGNER_NOT_USER_OR_RELAYER"):
                raise ValueError("C_DIRECT_DEMO_ORACLE_READY_IDENTITY")
        stage = "DRIVER_ACTUAL_HTTP_VERIFY";launch("verify");wait_stopped("verify")
        actual = json.loads((paths["private"] / "verify/direct-actual-result.json").read_text(encoding="utf-8"))
        if actual.get("runId") != run or actual.get("sourceIdentity") != r["sourceIdentity"] or actual.get("result") != "PASS" or not all(actual.get(k) is True for k in ("approveDeposit", "open", "close", "freeWithdraw")) or actual.get("realMetaMask") is not False:
            raise ValueError("C_DIRECT_DEMO_RESULT")
        status = "C_DIRECT_ACTUAL_HTTP_SYNTHETIC_PG_ANVIL_PASS_NOT_METAMASK_OR_FULL_AUDIT"
    except Exception as ex:
        error = str(ex) if isinstance(ex, ValueError) and re.fullmatch(r"C_DIRECT_DEMO_[A-Z0-9_]+", str(ex)) else "C_DIRECT_DEMO_ACTUAL_SAFE_FAILURE_" + type(ex).__name__
    finally:
        deadline = total_deadline
        for role in ("app", "oracle"):
            try:
                with (paths[role + "Control"] / "STOP").open("x", encoding="ascii") as f:
                    f.write("OWNED_NEW_FIXTURE_NORMAL_STOP\n")
            except FileExistsError:
                pass
            except Exception:
                status = "BLOCKED";error = error or "C_DIRECT_DEMO_STOP_MARKER_FAILED"
        for role in ("verify", "app", "oracle", "bootstrap", "anvil", "pg"):
            if role not in attempted:
                states[role] = {"attempted": False};continue
            name = run + "-" + role
            try:
                probe = docker(["container", "inspect", name], timeout=3, check=False)
                if probe.returncode and any(s in probe.stderr for s in ("No such object:", "No such container:")):
                    states[role] = {"present": False};continue
                info = json.loads(probe.stdout)
                if len(info) != 1 or info[0].get("Config", {}).get("Labels", {}).get("tms.c.owner") != run:
                    raise ValueError("C_DIRECT_DEMO_STOP_OWNER")
                if info[0].get("State", {}).get("Running"):
                    if role in {"app", "oracle"}:
                        for _ in range(30):
                            if json.loads(docker(["container", "inspect", name], timeout=2).stdout)[0].get("State", {}).get("Running") is False:
                                break
                            time.sleep(0.1)
                    if json.loads(docker(["container", "inspect", name], timeout=2).stdout)[0].get("State", {}).get("Running"):
                        docker(["stop", "-t", "10", name], timeout=15)
                stopped = json.loads(docker(["container", "inspect", name], timeout=2).stdout)[0]
                st = stopped.get("State", {});ok = st.get("Running") is False and st.get("ExitCode") in {0, 143} and not st.get("OOMKilled") and not st.get("Error")
                states[role] = {"normalStop": ok, "exitCode": st.get("ExitCode"), "oomKilled": st.get("OOMKilled")}
                if not ok:
                    status = "BLOCKED";error = error or "C_DIRECT_DEMO_NORMAL_STOP_FAILED"
            except Exception:
                states[role] = {"normalStop": False};status = "BLOCKED";error = error or "C_DIRECT_DEMO_STOP_UNCONFIRMED"
        for kind, name in (("network", run + "-network"), ("volume", run + "-pg-data"), ("volume", run + "-state")):
            try:
                found = json.loads(docker([kind, "inspect", name], timeout=2).stdout)
                preservation[name] = len(found) == 1 and found[0].get("Labels", {}).get("tms.c.owner") == run
            except Exception:
                preservation[name] = False
        state_preserved = None
        if states.get("anvil", {}).get("normalStop"):
            try:
                # Read only a bounded archive from this exact stopped, owner-checked new Anvil.
                command = ["docker", "cp", run + "-anvil:/state/anvil.json", "-"]
                proc = subprocess.Popen(command, cwd=root, env=clean_env(), stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
                data = []
                reader = threading.Thread(target=lambda: data.append(proc.stdout.read(16 * 1024**2 + 10241)), daemon=True)
                reader.start();reader.join(min(15, max(1, int(total_deadline - time.monotonic()))))
                if reader.is_alive() or not data or len(data[0]) > 16 * 1024**2 + 10240:
                    proc.kill();proc.wait(timeout=3);raise ValueError("C_DIRECT_DEMO_STATE_COPY_BOUND")
                if proc.wait(timeout=3):
                    raise ValueError("C_DIRECT_DEMO_STATE_COPY_FAILED")
                spec = importlib.util.spec_from_file_location("c_direct_demo_c5_state", root / "deployment/c-anvil-check.py")
                helper = importlib.util.module_from_spec(spec);spec.loader.exec_module(helper)
                content = helper.extract_state_tar(data[0])
                target = out / "anvil-state.private.json"
                with target.open("xb") as f:
                    f.write(content)
                state_preserved = {"bytes": len(content), "sha256": sha(target), "scope": "STOPPED_STATE_FILE_ONLY_NOT_RESTART_PROOF"}
                commands.append({"args": command[1:], "exitCode": 0, "bytes": len(content)})
            except Exception:
                status = "BLOCKED";error = error or "C_DIRECT_DEMO_STATE_PRESERVATION_FAILED"
        try:
            sources_ok = all(sha(root / p) == v for p, v in r["sources"].items())
            prepared_ok = all(tree_hashes(prepared / (role + "-fixture")) == hashes for role, hashes in r["fixtureHashes"].items()) and checked_web_tree(prepared / "web-fixture") == r["webHashes"]
            runtime_ok = tree_hashes(paths["driver"]) == driver_hashes
        except Exception:
            sources_ok = prepared_ok = runtime_ok = False
        if not sources_ok or not prepared_ok or not runtime_ok:
            status = "BLOCKED";error = error or "C_DIRECT_DEMO_END_IDENTITY_CHANGED"
        if status.startswith("C_DIRECT_ACTUAL_") and (not all(states.get(role, {}).get("normalStop") for role in ("pg", "anvil", "bootstrap", "oracle", "app", "verify")) or not all(preservation.values())):
            status = "BLOCKED";error = error or "C_DIRECT_DEMO_END_PRESERVATION_UNCONFIRMED"
        if time.monotonic() > total_deadline:
            status = "BLOCKED";error = error or "C_DIRECT_DEMO_TOTAL_DEADLINE"
        new_json(out / "result.json", {"run": run, "status": status, "safeError": error, "stage": stage, "sourceIdentity": r["sourceIdentity"], "commands": commands,
            "stopped": states, "resourcesPreserved": preservation, "sourcesUnchanged": sources_ok, "preparedFixtureUnchanged": prepared_ok,
            "runtimeFixtureUnchanged": runtime_ok, "anvilState": state_preserved, "elapsedSeconds": time.monotonic() - began, "totalBoundSeconds": TOTAL_SECONDS,
            "scope": "NEW_INTERNAL_HTTP_TRIAL_OWNER_C_DIRECT_NOT_BROWSER_OR_FULL_AUDIT", "paidCalls": 0, "deleteRequests": 0})
    print(json.dumps({"run": run, "status": status, "safeError": error}))
    return 0 if status.startswith("C_DIRECT_ACTUAL_") else 1


def main():
    root = Path(__file__).resolve().parents[1]
    if sys.argv[1:] == ["prepare"]:
        return prepare(root)
    if len(sys.argv) == 3 and sys.argv[1] == "run":
        return run_gate(root, sys.argv[2])
    raise SystemExit("USAGE: c-direct-demo-check.py prepare | run <prepare-id> (run only after independent review)")


if __name__ == "__main__":
    raise SystemExit(main())
