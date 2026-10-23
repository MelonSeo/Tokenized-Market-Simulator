"""Owned PostgreSQL consent Gate. Prepare is offline; run is a single Linux Gate."""
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import time
import uuid
from c_gate_network import select_subnet

PG_IMAGE = "sha256:c1b3783309b6499c795eed7c20135a1a4d25cae1b575c3d52c6f536129a1b109"
JAVA_IMAGE = "sha256:35be56d41389c9ad515c089dfded911ae9a8bdb362c8a303224666cd358bead3"
CORE_HASH = "d094ad7622ace598cd72e0430ffe4b8f167d636005449ad767f14126bc692d18"
BASE = "backend/src/main/java/com/pricetrack/exchange/custody/"
SOURCES = (BASE + "abi/CAbiModels.java", BASE + "proof/CEip712Verifier.java",
           BASE + "runtime/CCustodyIdentity.java", BASE + "runtime/CCustodyFailure.java",
           "backend/src/main/java/com/pricetrack/exchange/futures/math/FuturesMath.java",
           BASE + "authorization/CConsentModels.java", BASE + "authorization/CConsentStore.java",
           "deployment/CConsentPostgresTests.java")
RESOURCES = ("backend/src/main/resources/c-wallet-schema.sql", "backend/src/main/resources/c-consent-schema.sql")
FILES = (*SOURCES, *RESOURCES, "deployment/c-consent-postgres-check.py", "deployment/test_c_consent_postgres_check.py", "deployment/c_gate_network.py")
MIN_RAM, MIN_DISK = 1920 * 1024**2, 2 * 1024**3
TOTAL_SECONDS, WORK_SECONDS = 180, 150


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def new_json(p, value):
    with p.open("x", encoding="utf-8") as f:
        json.dump(value, f, indent=2)


def quote(v):
    v = str(v).replace("\\", "/")
    if any(c in v for c in ('"', "\n", "\r", "\0")):
        raise ValueError("C_CONSENT_ARGS")
    return '"' + v + '"'


def valid_run(run):
    if not re.fullmatch(r"ccpg-[0-9a-f]{16}", run):
        raise ValueError("C_CONSENT_RUN_ID")


def resources(ram, disk):
    if type(ram) is not int or ram < MIN_RAM or disk < MIN_DISK:
        raise ValueError("C_CONSENT_RESOURCE_LOW")


def tree_hashes(directory):
    return {p.relative_to(directory).as_posix(): sha(p) for p in sorted(directory.rglob("*")) if p.is_file()}


def copy_resources(snapshot, fixture):
    for rel in RESOURCES:
        source = snapshot / rel
        for dest in (fixture / Path(rel).name, fixture / "classes" / Path(rel).name):
            with dest.open("xb") as out:
                out.write(source.read_bytes())
    return verify_resources(snapshot, fixture)


def verify_resources(snapshot, fixture):
    hashes = {}
    for rel in RESOURCES:
        expected = sha(snapshot / rel)
        for dest in (fixture / Path(rel).name, fixture / "classes" / Path(rel).name):
            if not dest.is_file() or sha(dest) != expected:
                raise ValueError("C_CONSENT_RESOURCE_CLASSPATH_MISMATCH")
        hashes[Path(rel).name] = expected
    return hashes


def cleanup_targets(run):
    valid_run(run)
    return [(kind, run + "-" + kind) for kind in ("java", "pg")]


def missing_object(result):
    return result.returncode != 0 and any(s in result.stderr for s in ("No such object:", "No such container:"))


def args(kind, run, fixture, evidence, envfile):
    valid_run(run)
    if kind not in {"pg", "java"}:
        raise ValueError("C_CONSENT_KIND")
    common = ["run", "-d", "--pull=never", "--name", run + "-" + kind, "--label", "tms.c.owner=" + run,
              "--network", run + "-network", "--restart=no", "--cpus=1", "--pids-limit=128", "--env-file", str(envfile)]
    if kind == "pg":
        return common + ["--network-alias", "pg", "--memory=256m", "--cap-drop=NET_RAW",
                         "--mount", "type=volume,source=" + run + "-data,target=/var/lib/postgresql/data", PG_IMAGE]
    return common + ["--read-only", "--user=10001:10001", "--memory=384m", "--cap-drop=ALL", "--security-opt=no-new-privileges",
        "--tmpfs", "/tmp:rw,noexec,nosuid,size=32m", "--mount", "type=bind,source=" + str(fixture) + ",target=/fixture,readonly",
        "--mount", "type=bind,source=" + str(evidence) + ",target=/evidence", "--entrypoint", "java", JAVA_IMAGE,
        "-Xms32m", "-Xmx192m", "-XX:MaxMetaspaceSize=128m", "-Dfile.encoding=UTF-8",
        "-cp", "/fixture/classes:/fixture/jars/*", "CConsentPostgresTests", "/fixture", "/evidence"]


def validate(info, kind, run, fixture, evidence):
    valid_run(run)
    c, h, n = info.get("Config", {}), info.get("HostConfig", {}), info.get("NetworkSettings", {})
    if c.get("Labels", {}).get("tms.c.owner") != run or h.get("NetworkMode") != run + "-network" or set(n.get("Networks", {})) != {run + "-network"}:
        raise ValueError("C_CONSENT_OWNER_NETWORK")
    if h.get("Privileged") or h.get("PortBindings") or any(n.get("Ports", {}).values()) or h.get("CapAdd"):
        raise ValueError("C_CONSENT_ISOLATION")
    if info.get("Image") != (PG_IMAGE if kind == "pg" else JAVA_IMAGE) or h.get("Memory") != (256 if kind == "pg" else 384) * 1024**2:
        raise ValueError("C_CONSENT_IMAGE_MEMORY")
    mounts = {m.get("Destination"): m for m in info.get("Mounts", []) if m.get("Type") != "tmpfs"}
    if kind == "pg":
        cap = h.get("CapDrop")
        if h.get("ReadonlyRootfs") or not isinstance(cap, list) or len(cap) != 1 or cap[0] not in {"NET_RAW", "CAP_NET_RAW"} or c.get("User") not in {"", "0", "0:0"}:
            raise ValueError("C_CONSENT_PG_SCOPE")
        if c.get("Entrypoint") != ["docker-entrypoint.sh"] or c.get("Cmd") != ["postgres"]:
            raise ValueError("C_CONSENT_PG_COMMAND")
        if set(mounts) != {"/var/lib/postgresql/data"} or mounts["/var/lib/postgresql/data"].get("Name") != run + "-data" or mounts["/var/lib/postgresql/data"].get("Type") != "volume" or not mounts["/var/lib/postgresql/data"].get("RW"):
            raise ValueError("C_CONSENT_PG_VOLUME")
    else:
        if not h.get("ReadonlyRootfs") or c.get("User") != "10001:10001" or h.get("CapDrop") != ["ALL"] or not any(s in {"no-new-privileges", "no-new-privileges:true"} for s in h.get("SecurityOpt", [])) or c.get("Entrypoint") != ["java"]:
            raise ValueError("C_CONSENT_JAVA_SCOPE")
        if set(mounts) != {"/fixture", "/evidence"}:
            raise ValueError("C_CONSENT_JAVA_MOUNT")
        for dest, path, rw in (("/fixture", fixture, False), ("/evidence", evidence, True)):
            m = mounts[dest]
            if m.get("Type") != "bind" or Path(m.get("Source", "")).resolve() != path.resolve() or m.get("RW") != rw:
                raise ValueError("C_CONSENT_JAVA_MOUNT")
        if c.get("Cmd") != args("java", run, fixture, evidence, Path("unused"))[args("java", run, fixture, evidence, Path("unused")).index(JAVA_IMAGE)+1:]:
            raise ValueError("C_CONSENT_JAVA_COMMAND")


def observation(command, result):
    # Inspection, SQL failures and secrets remain memory-only: never duplicate output.
    if any(x in {"-e", "--env"} or x.startswith("--env=") for x in command):
        raise ValueError("C_CONSENT_INLINE_ENV")
    return {"command": command, "exitCode": result.returncode,
            "stdoutBytes": len(result.stdout.encode("utf-8")), "stderrBytes": len(result.stderr.encode("utf-8")),
            "outputPolicy": "NO_RAW_STDOUT_STDERR_RECORDED"}


def clean_env():
    return {k: v for k, v in os.environ.items() if k.upper() in {"PATH", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "USERPROFILE", "LOCALAPPDATA", "APPDATA", "PATHEXT", "COMSPEC"}}


def prepare(root, offline):
    if not re.fullmatch(r"\d{8}T\d{6}Z-[0-9a-f]{8}", offline):
        raise ValueError("C_CONSENT_OFFLINE_ID")
    prior_dir = root / "deployment/runtime/c-consent-store" / offline
    prior = json.loads((prior_dir / "result.json").read_text(encoding="utf-8"))
    if prior.get("sourceHash") != CORE_HASH or prior.get("status") != "C_CONSENT_H2_OFFLINE_PASS_NOT_POSTGRES" or prior.get("exits") != {"compile": 0, "test": 0} or not all(prior.get(k) for k in ("sourcesUnchanged", "dependenciesUnchanged", "schemaResourceUnchanged")):
        raise ValueError("C_CONSENT_CORE_PRECONDITION")
    if len(prior["sources"]) != 11 or any(sha(root / p) != h for p, h in prior["sources"].items()):
        raise ValueError("C_CONSENT_CORE_CHANGED")
    classes = {p.relative_to(prior_dir / "classes").as_posix(): sha(p) for p in (prior_dir / "classes").rglob("*.class")}
    if len(classes) != 37 or classes != prior.get("classHashes"):
        raise ValueError("C_CONSENT_CORE_CLASSES")
    cache = root.parents[2] / "deployment/runtime/dapp-audit-development-worktree/deployment/runtime/dapp-stage2-offline/java-classpath.args"
    lines = cache.read_text(encoding="utf-8-sig").splitlines()
    if len(lines) != 2 or lines[0] != "-cp":
        raise ValueError("C_CONSENT_CACHE")
    jars = {}
    for item in lines[1].strip('"').split(os.pathsep):
        p = Path(item)
        if p.suffix.lower() != ".jar":
            continue
        if "/caches/modules-2/files-2.1/" not in p.as_posix() or not p.is_file():
            raise ValueError("C_CONSENT_CACHE_SCOPE")
        if p.name in jars and sha(p) != sha(jars[p.name]):
            raise ValueError("C_CONSENT_JAR_COLLISION")
        jars[p.name] = p
    # Include only a missing API Guardian already verified by the accepted core run.
    for name, digest in prior["dependencies"].items():
        if name not in jars and name == "apiguardian-api-1.1.2.jar":
            api = next(p for n, p in jars.items() if n.startswith("junit-jupiter-api-"))
            matches = list((api.parents[4] / "org.apiguardian/apiguardian-api/1.1.2").glob("*/" + name))
            if len(matches) != 1:
                raise ValueError("C_CONSENT_APIGUARDIAN")
            jars[name] = matches[0]
        if name not in jars or sha(jars[name]) != digest:
            raise ValueError("C_CONSENT_DEPENDENCY_CHANGED")
    if len(jars) > 512 or not any(n.startswith("postgresql-") for n in jars):
        raise ValueError("C_CONSENT_JDBC")
    deps = {n: sha(p) for n, p in jars.items()}
    identity = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    out = root / "deployment/runtime/c-consent-pg-prepare" / identity
    (out / "fixture/classes").mkdir(parents=True, exist_ok=False)
    (out / "fixture/jars").mkdir()
    before = {p: sha(root / p) for p in FILES}
    for rel in FILES:
        target = out / "source-snapshot" / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open("xb") as f:
            f.write((root / rel).read_bytes())
    resource_hashes = copy_resources(out / "source-snapshot", out / "fixture")
    for name, path in jars.items():
        shutil.copyfile(path, out / "fixture/jars" / name)
    jdk = Path("C:/Program Files/Java/jdk-21.0.10/bin")
    cp = os.pathsep.join(str(p) for p in jars.values())
    compile_args = ["--release", "21", "-parameters", "-encoding", "UTF-8", "-proc:none", "-cp", cp,
                    "-d", str(out / "fixture/classes"), *[str(out / "source-snapshot" / p) for p in SOURCES]]
    with (out / "compile.args").open("x", encoding="utf-8") as f:
        f.write("\n".join(quote(a) for a in compile_args))
    version = subprocess.run([str(jdk / "java.exe"), "-version"], env=clean_env(), capture_output=True, text=True, timeout=10)
    with (out / "compile.stdout.log").open("xb") as stdout, (out / "compile.stderr.log").open("xb") as stderr:
        try:
            code = subprocess.run([str(jdk / "javac.exe"), "-J-Xmx192m", "@" + str(out / "compile.args")], env=clean_env(), cwd=root, stdout=stdout, stderr=stderr, timeout=90).returncode
        except subprocess.TimeoutExpired:
            code = 124
    self_exit, self_valid, self_stderr_bytes = None, False, None
    if code == 0:
        command = [str(jdk / "java.exe"), "-Xms16m", "-Xmx128m", "-XX:MaxMetaspaceSize=96m", "-Dfile.encoding=UTF-8",
                   "-cp", str(out / "fixture/classes") + os.pathsep + cp, "CConsentPostgresTests",
                   "--resource-self-test", str(out / "fixture")]
        with (out / "resource-self-test.args").open("x", encoding="utf-8") as f:
            f.write("\n".join(quote(a) for a in command[1:]))
        try:
            probe = subprocess.run([command[0], "@" + str(out / "resource-self-test.args")], cwd=root, env=clean_env(), capture_output=True, text=True, timeout=30)
            self_exit, self_stderr_bytes = probe.returncode, len(probe.stderr.encode("utf-8"))
            payload = json.loads(probe.stdout)
            expected = {"result": "RESOURCE_SELF_TEST_PASS", "walletSchemaSha256": resource_hashes["c-wallet-schema.sql"],
                        "consentSchemaSha256": resource_hashes["c-consent-schema.sql"],
                        "scope": "CLASSPATH_SQL_RESOURCES_ONLY_NO_JDBC_SOCKET_OR_ENV"}
            self_valid = self_exit == 0 and self_stderr_bytes == 0 and payload == expected
            if self_valid:
                new_json(out / "resource-self-test.json", payload)
        except subprocess.TimeoutExpired:
            self_exit = 124
        except (ValueError, UnicodeError):
            self_valid = False
    resource_ok = resource_hashes == verify_resources(out / "source-snapshot", out / "fixture")
    dep_ok = all(sha(out / "fixture/jars" / n) == h and sha(jars[n]) == h for n, h in deps.items())
    with (out / "python.stdout.log").open("xb") as stdout, (out / "python.stderr.log").open("xb") as stderr:
        try:
            python_exit = subprocess.run([sys.executable, "-B", "-m", "unittest", "discover", "-s", "deployment", "-p", "test_c_consent_postgres_check.py"], cwd=root, env=clean_env(), stdout=stdout, stderr=stderr, timeout=30).returncode
        except subprocess.TimeoutExpired:
            python_exit = 124
    unchanged = before == {p: sha(root / p) for p in FILES}
    source_id = hashlib.sha256(json.dumps(before, sort_keys=True).encode()).hexdigest()
    passed = code == 0 and python_exit == 0 and self_valid and resource_ok and unchanged and dep_ok and version.returncode == 0 and 'version "21.0.10"' in version.stderr
    record = {"run": identity, "status": "PREPARED_NOT_POSTGRES" if passed else "FAILED_OR_BLOCKED", "compileExit": code,
        "pythonExit": python_exit, "resourceSelfTestExit": self_exit, "resourceSelfTestValid": self_valid,
        "resourceSelfTestStderrBytes": self_stderr_bytes, "resourceHashes": resource_hashes, "resourcesClasspathEqual": resource_ok,
        "coreSourceHash": CORE_HASH, "coreRun": offline, "sourceIdentity": source_id, "sources": before,
        "sourcesUnchanged": unchanged, "dependencies": deps, "dependenciesUnchanged": dep_ok,
        "fixtureHashes": {p.relative_to(out / "fixture").as_posix(): sha(p) for p in sorted((out / "fixture").rglob("*")) if p.is_file()},
        "javaVersion": version.stderr, "dockerCalls": 0, "paidCalls": 0, "scope": "COMPILE_SQL_RESOURCE_SELF_TEST_AND_PURE_PYTHON_ONLY_NO_PG_NO_JUNIT_EXECUTION"}
    new_json(out / "result.json", record)
    print(json.dumps({k: record[k] for k in ("run", "status", "sourceIdentity", "compileExit")}))
    return 0 if passed else 1


def run_gate(root, prepared):
    began = time.monotonic()
    total_deadline = began + TOTAL_SECONDS
    if not re.fullmatch(r"\d{8}T\d{6}Z-[0-9a-f]{8}", prepared):
        raise ValueError("C_CONSENT_PREPARE_ID")
    prep = root / "deployment/runtime/c-consent-pg-prepare" / prepared
    r = json.loads((prep / "result.json").read_text(encoding="utf-8"))
    if r.get("status") != "PREPARED_NOT_POSTGRES" or any(sha(root / p) != h for p, h in r["sources"].items()):
        raise ValueError("C_CONSENT_PREPARE_CHANGED")
    actual = {p.relative_to(prep / "fixture").as_posix(): sha(p) for p in (prep / "fixture").rglob("*") if p.is_file()}
    if actual != r["fixtureHashes"]:
        raise ValueError("C_CONSENT_FIXTURE_CHANGED")
    # Consume this preparation exactly once, including failed runs.
    with (prep / "RUN_CLAIM").open("x", encoding="ascii") as f:
        f.write("ONE_ACTUAL_ATTEMPT_NO_RETRY\n")
    run = "ccpg-" + uuid.uuid4().hex[:16]
    out = root / "deployment/runtime/c-consent-pg" / run
    out.mkdir(parents=True, exist_ok=False)
    fixture, evidence = out / "fixture", out / "java-evidence"
    shutil.copytree(prep / "fixture", fixture)
    evidence.mkdir()
    suffix = run[5:]
    db, owner, api = "ccpg_" + suffix, "ccowner_" + suffix, "ccapi_" + suffix
    ownerpw, apipw, marker = secrets.token_hex(32), secrets.token_hex(32), secrets.token_hex(32)
    props = {"scope": "C_CONSENT_PG_ONLY", "runId": run, "database": db, "databaseOwner": owner, "apiUser": api, "marker": marker,
        "jdbcUrl": "jdbc:postgresql://pg:5432/" + db + "?sslmode=disable&connectTimeout=3&socketTimeout=8",
        "walletSchemaSha256": sha(fixture / "c-wallet-schema.sql"), "consentSchemaSha256": sha(fixture / "c-consent-schema.sql"),
        "coreSourceHash": CORE_HASH, "fixtureSourceHash": r["sourceIdentity"], "sourceIdentity": r["sourceIdentity"]}
    with (fixture / "manifest.properties").open("x", encoding="ascii") as f:
        f.write("\n".join(k + "=" + v for k, v in props.items()) + "\n")
    runtime_hashes = tree_hashes(fixture)
    pgenv, javaenv = out / "pg.private.env", out / "java.private.env"
    with pgenv.open("x", encoding="ascii") as f:
        f.write("POSTGRES_DB=" + db + "\nPOSTGRES_USER=" + owner + "\nPOSTGRES_PASSWORD=" + ownerpw + "\nPOSTGRES_INITDB_ARGS=--auth-host=scram-sha-256\n")
    with javaenv.open("x", encoding="ascii") as f:
        f.write("C_CONSENT_PG_TESTS=true\nC_CONSENT_PG_OWNER_PASSWORD=" + ownerpw + "\nC_CONSENT_PG_API_PASSWORD=" + apipw + "\n")
    commands, states, preservation = [], {}, {}
    status, error, deadline = "BLOCKED", None, began + WORK_SECONDS

    def execute(command, timeout=10, check=True):
        if any(secret in str(command) for secret in (ownerpw, apipw)):
            raise ValueError("C_CONSENT_SECRET_ARGV")
        remaining = int(deadline - time.monotonic())
        if remaining < 1:
            raise ValueError("C_CONSENT_DEADLINE")
        left = min(timeout, remaining)
        result = subprocess.run(["docker", *command], cwd=root, env=clean_env(), capture_output=True, text=True, timeout=left)
        commands.append(observation(["docker", *command], result))
        if check and result.returncode:
            raise RuntimeError("C_CONSENT_DOCKER_FAILED")
        return result

    def inspect(kind):
        data = json.loads(execute(["container", "inspect", run + "-" + kind]).stdout)
        if not isinstance(data, list) or len(data) != 1:
            raise ValueError("C_CONSENT_INSPECT")
        validate(data[0], kind, run, fixture, evidence)
        return data[0]

    try:
        ram = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "(Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory"], env=clean_env(), capture_output=True, text=True, timeout=10)
        free = int(ram.stdout.strip()) * 1024 if ram.returncode == 0 and ram.stdout.strip().isdigit() else 0
        resources(free, shutil.disk_usage(root).free)
        new_json(out / "resources.json", {"freeMemoryBytes": free, "freeDiskBytes": shutil.disk_usage(root).free, "minimumMemoryBytes": MIN_RAM})
        execute(["version", "--format", "{{.Server.Version}}"])
        for image in (PG_IMAGE, JAVA_IMAGE):
            if execute(["image", "inspect", image, "--format", "{{.Id}}|{{.Os}}|{{.Architecture}} "]).stdout.strip() != image + "|linux|amd64":
                raise ValueError("C_CONSENT_IMAGE_IDENTITY")
        nets = execute(["network", "ls", "--format", "{{.ID}}"]).stdout.splitlines()
        if len(nets) > 1024 or any(not re.fullmatch(r"[0-9a-f]{12,64}", n) for n in nets):
            raise ValueError("C_CONSENT_NETWORK_LIST")
        occupied = []
        for i in range(0, len(nets), 64):
            for line in execute(["network", "inspect", *nets[i:i+64], "--format", "{{json .IPAM.Config}}"]).stdout.splitlines():
                occupied.extend(x["Subnet"] for x in json.loads(line) or [] if x.get("Subnet"))
        routes = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "@(Get-NetRoute -AddressFamily IPv4 -ErrorAction Stop | Select-Object -ExpandProperty DestinationPrefix) | ConvertTo-Json -Compress"], env=clean_env(), capture_output=True, text=True, timeout=10)
        if routes.returncode:
            raise ValueError("C_CONSENT_ROUTES")
        rv = json.loads(routes.stdout)
        subnet = select_subnet(occupied, [rv] if isinstance(rv, str) else rv)
        for kind, name in (("network", run + "-network"), ("volume", run + "-data"), ("container", run + "-pg"), ("container", run + "-java")):
            if execute([kind, "inspect", name], check=False).returncode == 0:
                raise ValueError("C_CONSENT_RESOURCE_COLLISION")
        execute(["network", "create", "--internal", "--subnet", subnet, "--label", "tms.c.owner=" + run, run + "-network"])
        network = json.loads(execute(["network", "inspect", run + "-network"]).stdout)[0]
        if network.get("Labels", {}).get("tms.c.owner") != run or network.get("Internal") is not True:
            raise ValueError("C_CONSENT_NETWORK_SCOPE")
        execute(["volume", "create", "--label", "tms.c.owner=" + run, run + "-data"])
        volume = json.loads(execute(["volume", "inspect", run + "-data"]).stdout)[0]
        if volume.get("Labels", {}).get("tms.c.owner") != run:
            raise ValueError("C_CONSENT_VOLUME_OWNER")
        execute(args("pg", run, fixture, evidence, pgenv)); inspect("pg")
        for attempt in range(30):
            if execute(["exec", run + "-pg", "pg_isready", "-h", "127.0.0.1", "-p", "5432", "-U", owner, "-d", db], check=False).returncode == 0:
                break
            if time.monotonic() >= deadline:
                raise ValueError("C_CONSENT_DEADLINE")
            time.sleep(1)
        else:
            raise ValueError("C_CONSENT_PG_NOT_READY")
        execute(args("java", run, fixture, evidence, javaenv)); inspect("java")
        while time.monotonic() < deadline:
            state = inspect("java").get("State", {})
            if not state.get("Running"):
                if state.get("ExitCode") != 0 or state.get("OOMKilled") or state.get("Error"):
                    raise ValueError("C_CONSENT_JAVA_FAILED")
                break
            time.sleep(1)
        else:
            raise ValueError("C_CONSENT_DEADLINE")
        result = json.loads((evidence / "result.json").read_text(encoding="utf-8"))
        if result.get("result") != "PASS" or result.get("sourceIdentity") != r["sourceIdentity"] or result.get("runId") != run or any(result.get(k) != v for k, v in {"testsFound": 6, "testsSucceeded": 6, "testsFailed": 0, "testsSkipped": 0, "testsAborted": 0}.items()):
            raise ValueError("C_CONSENT_JUNIT_RESULT")
        status = "C_CONSENT_ACTUAL_POSTGRES_6_PASS_NOT_SERVICE_OR_CHAIN"
    except Exception as ex:
        error = str(ex) if re.fullmatch(r"C_CONSENT_[A-Z0-9_]+", str(ex)) else "C_CONSENT_GATE_EXCEPTION_" + type(ex).__name__
    finally:
        # Reserve the final thirty seconds inside the single 180-second bound.
        # A timed-out docker run can still have created a container.
        deadline = total_deadline
        for kind, name in cleanup_targets(run):
            try:
                probe = execute(["container", "inspect", name], timeout=2, check=False)
                if missing_object(probe):
                    states[kind] = {"present": False, "normalStop": False}
                    if status.startswith("C_CONSENT_ACTUAL_"):
                        status, error = "BLOCKED", "C_CONSENT_PASS_CONTAINER_MISSING"
                    continue
                if probe.returncode:
                    raise ValueError("C_CONSENT_STOP_INSPECT_UNKNOWN")
                owned = json.loads(probe.stdout)
                if not isinstance(owned, list) or len(owned) != 1 or owned[0].get("Config", {}).get("Labels", {}).get("tms.c.owner") != run:
                    raise ValueError("C_CONSENT_STOP_OWNER")
                if owned[0].get("State", {}).get("Running"):
                    execute(["stop", "-t", "10", name], timeout=15)
                stopped = json.loads(execute(["container", "inspect", name], timeout=2).stdout)
                if len(stopped) != 1 or stopped[0].get("Config", {}).get("Labels", {}).get("tms.c.owner") != run:
                    raise ValueError("C_CONSENT_STOP_OWNER")
                state = stopped[0].get("State", {})
                ok = state.get("Running") is False and state.get("ExitCode") in {0, 143} and not state.get("OOMKilled") and not state.get("Error")
                states[kind] = {"normalStop": ok, "exitCode": state.get("ExitCode"), "oomKilled": state.get("OOMKilled")}
                if not ok:
                    status, error = "BLOCKED", "C_CONSENT_NORMAL_STOP_FAILED"
            except Exception:
                states[kind] = {"normalStop": False}
                status, error = "BLOCKED", "C_CONSENT_STOP_NOT_CONFIRMED"
        try:
            unchanged = r["sources"] == {p: sha(root / p) for p in r["sources"]}
            prep_unchanged = tree_hashes(prep / "fixture") == r["fixtureHashes"]
            runtime_unchanged = tree_hashes(fixture) == runtime_hashes
            if not (unchanged and prep_unchanged and runtime_unchanged):
                status, error = "BLOCKED", "C_CONSENT_END_IDENTITY_CHANGED"
        except Exception:
            unchanged = prep_unchanged = runtime_unchanged = False
            status, error = "BLOCKED", "C_CONSENT_END_IDENTITY_UNCONFIRMED"
        for kind, name in (("network", run + "-network"), ("volume", run + "-data")):
            try:
                probe = execute([kind, "inspect", name], timeout=2, check=False)
                found = json.loads(probe.stdout) if probe.returncode == 0 else []
                preservation[kind] = {"ownedExists": len(found) == 1 and found[0].get("Labels", {}).get("tms.c.owner") == run}
            except Exception:
                preservation[kind] = {"ownedExists": False, "observation": "UNCONFIRMED"}
        if status.startswith("C_CONSENT_ACTUAL_") and (not all(states.get(k, {}).get("normalStop") for k in ("java", "pg")) or not all(preservation[k]["ownedExists"] for k in ("network", "volume"))):
            status, error = "BLOCKED", "C_CONSENT_FINAL_PRESERVATION_UNCONFIRMED"
        if time.monotonic() > total_deadline:
            status, error = "BLOCKED", "C_CONSENT_TOTAL_DEADLINE"
        new_json(out / "result.json", {"run": run, "status": status, "safeError": error, "sourceIdentity": r["sourceIdentity"], "coreSourceHash": CORE_HASH,
            "commands": commands, "stopped": states, "scope": "ACTUAL_POSTGRES_ONLY_NOT_ANVIL_SERVICE_OR_BROWSER",
            "preservationIntent": "NO_DELETE_RESOURCES_OR_EVIDENCE", "resourceObservation": preservation,
            "sourcesUnchanged": unchanged, "preparedFixtureUnchanged": prep_unchanged, "runtimeFixtureUnchanged": runtime_unchanged,
            "runtimeFixtureHashes": runtime_hashes, "elapsedSeconds": time.monotonic()-began, "totalBoundSeconds": TOTAL_SECONDS, "paidCalls": 0})
    print(json.dumps({"run": run, "status": status, "safeError": error}))
    return 0 if status.startswith("C_CONSENT_ACTUAL_") else 1


def main():
    if len(sys.argv) != 3 or sys.argv[1] not in {"prepare", "run"}:
        raise SystemExit("USAGE: c-consent-postgres-check.py prepare <offline-id> | run <prepare-id>")
    root = Path(__file__).resolve().parents[1]
    return prepare(root, sys.argv[2]) if sys.argv[1] == "prepare" else run_gate(root, sys.argv[2])


if __name__ == "__main__":
    raise SystemExit(main())
