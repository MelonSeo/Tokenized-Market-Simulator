"""Offline C read/wallet unit only; no Gradle, Docker, real RPC, PG or paid APIs.

Compile an explicit source list against immutable, already-cached dependency jars.
Never load cached application classes or scan other project tests. New evidence
is append-only and old successful/failed runs are never reused or overwritten.
"""
import datetime
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import uuid


BASE = "backend/src/main/java/com/pricetrack/exchange/"
TEST_BASE = "backend/src/test/java/com/pricetrack/exchange/custody/"
SOURCES = (
    BASE + "custody/runtime/CCustodyFailure.java",
    BASE + "custody/runtime/CReadRpc.java",
    BASE + "custody/runtime/CCustodyIdentity.java",
    BASE + "custody/runtime/CReadAbi.java",
    BASE + "custody/runtime/CCustodyRpcClient.java",
    BASE + "custody/runtime/CCustodyRpcReader.java",
    BASE + "custody/runtime/CCustodyRuntimeConfig.java",
    BASE + "custody/auth/CWalletLinkStore.java",
    BASE + "custody/auth/CWalletProofService.java",
    BASE + "custody/api/CCustodyController.java",
    BASE + "custody/abi/CAbiModels.java",
    BASE + "futures/math/FuturesMath.java",
    BASE + "custody/proof/CEip712Verifier.java",
    BASE + "auth/AuthenticatedUser.java",
    BASE + "user/UserRole.java",
    TEST_BASE + "runtime/CCustodyIdentityTest.java",
    TEST_BASE + "runtime/CCustodyRpcReaderTest.java",
    TEST_BASE + "runtime/CCustodyRpcClientTest.java",
    TEST_BASE + "runtime/CCustodyRuntimeConfigTest.java",
    TEST_BASE + "auth/CWalletLinkStoreTest.java",
    TEST_BASE + "auth/CWalletProofServiceTest.java",
    TEST_BASE + "api/CCustodyControllerTest.java",
    "deployment/CReadWalletTests.java",
)
EVIDENCE_SOURCES = (*SOURCES, "deployment/c-service-read-check.py",
                    "backend/src/main/resources/c-wallet-schema.sql")


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_new(path, value):
    with path.open("x", encoding="utf-8") as out:
        json.dump(value, out, indent=2, ensure_ascii=False)


def clean_env():
    return {k: v for k, v in os.environ.items() if k.upper() in {
        "PATH", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "USERPROFILE",
        "LOCALAPPDATA", "APPDATA", "PATHEXT", "COMSPEC"}}


def quoted(value):
    # Java argument files avoid Windows command-line limits. Normalize path
    # separators before quoting; do not interpolate any command through a shell.
    text = str(value).replace("\\", "/")
    if any(c in text for c in ('"', "\r", "\n", "\0")):
        raise ValueError("C_READ_ARGUMENT_FORMAT")
    return '"' + text + '"'


def main():
    if len(sys.argv) != 1:
        raise SystemExit("C_READ_OFFLINE_NO_ARGUMENTS")
    root = Path(__file__).resolve().parents[1]
    cache = root.parents[2] / "deployment/runtime/dapp-audit-development-worktree/deployment/runtime/dapp-stage2-offline/java-classpath.args"
    lines = cache.read_text(encoding="utf-8-sig").splitlines()
    if len(lines) != 2 or lines[0] != "-cp":
        raise SystemExit("C_READ_CACHED_CLASSPATH_FORMAT")
    # Only third-party Gradle dependency JARs: exclude app classes/resources,
    # old app.jar, and any unrecognized path. Never resolve/download a dependency.
    jars = {}
    for item in lines[1].strip('"').split(os.pathsep):
        path = Path(item)
        if path.suffix.lower() != ".jar":
            continue
        if "/caches/modules-2/files-2.1/" not in path.as_posix() or not path.is_file():
            raise SystemExit("C_READ_DEPENDENCY_SCOPE")
        digest = sha(path)
        if path.name in jars and jars[path.name][1] != digest:
            raise SystemExit("C_READ_DEPENDENCY_COLLISION")
        jars[path.name] = (path, digest)
    if not any(name.startswith("apiguardian-api-") for name in jars):
        junit = [value[0] for name, value in jars.items() if name.startswith("junit-jupiter-api-")]
        if len(junit) != 1:
            raise SystemExit("C_READ_JUNIT_DEPENDENCY")
        extra = list((junit[0].parents[4] / "org.apiguardian/apiguardian-api/1.1.2").glob("*/apiguardian-api-1.1.2.jar"))
        if len(extra) != 1 or not extra[0].is_file():
            raise SystemExit("C_READ_APIGUARDIAN_DEPENDENCY")
        jars[extra[0].name] = (extra[0], sha(extra[0]))
    required = ("junit-jupiter-engine-", "junit-platform-launcher-", "h2-", "okhttp-", "jackson-databind-")
    if len(jars) > 512 or any(sum(name.startswith(prefix) for name in jars) != 1 for prefix in required):
        raise SystemExit("C_READ_DEPENDENCY_SET")
    jdk = Path("C:/Program Files/Java/jdk-21.0.10/bin")
    if not (jdk / "javac.exe").is_file():
        raise SystemExit("C_READ_JDK_MISSING")
    if any(not (root / rel).is_file() for rel in EVIDENCE_SOURCES):
        raise SystemExit("C_READ_SOURCE_MISSING")
    run = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    evidence = root / "deployment/runtime/c-service-read" / run
    evidence.mkdir(parents=True, exist_ok=False)
    classes = evidence / "classes"
    classes.mkdir()
    before = {rel: sha(root / rel) for rel in EVIDENCE_SOURCES}
    for rel in EVIDENCE_SOURCES:
        target = evidence / "source-snapshot" / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open("xb") as out:
            out.write((root / rel).read_bytes())
    cp = os.pathsep.join(str(jars[name][0]) for name in sorted(jars))
    compile_args = ["--release", "21", "-encoding", "UTF-8", "-proc:none", "-cp", cp,
                    "-d", str(classes), *[str(evidence / "source-snapshot" / rel) for rel in SOURCES]]
    test_args = ["-Xms32m", "-Xmx192m", "-XX:MaxMetaspaceSize=128m", "-Dfile.encoding=UTF-8",
                 "-cp", str(classes) + os.pathsep + cp, "CReadWalletTests"]
    for name, args in (("compile", compile_args), ("test", test_args)):
        with (evidence / (name + ".args")).open("x", encoding="utf-8") as out:
            out.write("\n".join(quoted(arg) for arg in args))
    env = clean_env()
    started = datetime.datetime.now(datetime.timezone.utc)

    def execute(name, command):
        with (evidence / (name + ".stdout.log")).open("xb") as out, (evidence / (name + ".stderr.log")).open("xb") as err:
            try:
                return subprocess.run(command, cwd=root, env=env, stdout=out, stderr=err, timeout=120).returncode
            except subprocess.TimeoutExpired:
                return 124

    compile_command = [str(jdk / "javac.exe"), "-J-Xmx256m", "@" + str(evidence / "compile.args")]
    test_command = [str(jdk / "java.exe"), "@" + str(evidence / "test.args")]
    version = subprocess.run([str(jdk / "java.exe"), "-version"], env=env, capture_output=True, text=True, timeout=10)
    compile_exit = execute("compile", compile_command)
    # SQL is a new test classpath resource, not a migration against any old DB.
    if compile_exit == 0:
        with (classes / "c-wallet-schema.sql").open("xb") as out:
            out.write((evidence / "source-snapshot/backend/src/main/resources/c-wallet-schema.sql").read_bytes())
    test_exit = execute("test", test_command) if compile_exit == 0 else None
    unchanged = before == {rel: sha(root / rel) for rel in EVIDENCE_SOURCES}
    dependencies_unchanged = all(sha(path) == digest for path, digest in jars.values())
    jdk_matches = version.returncode == 0 and 'version "21.0.10"' in version.stderr
    status = "OFFLINE_PASS_NOT_PG_RPC_OR_SPRING_E2E" if compile_exit == 0 and test_exit == 0 and unchanged and dependencies_unchanged and jdk_matches else "BLOCKED_OR_FAILED"
    record = {"run": run, "status": status, "sourceHash": hashlib.sha256(json.dumps(before, sort_keys=True).encode()).hexdigest(),
              "sources": before, "sourcesUnchanged": unchanged, "dependenciesUnchanged": dependencies_unchanged,
              "javaVersion": version.stderr.strip(), "expectedJdkMatched": jdk_matches,
              "dependencies": {name: digest for name, (_, digest) in jars.items()},
              "classHashes": {p.relative_to(classes).as_posix(): sha(p) for p in sorted(classes.rglob("*.class"))},
              "compileExit": compile_exit, "testExit": test_exit, "compileCommand": compile_command, "testCommand": test_command,
              "elapsedSeconds": (datetime.datetime.now(datetime.timezone.utc) - started).total_seconds(),
              "scope": "EXPLICIT_OFFLINE_C_READ_WALLET_UNIT_MOCK_TRANSPORT_H2_ONLY", "gradle": False,
              "realRpcCalls": 0, "realPostgresCalls": 0, "dockerCalls": 0, "paidCalls": 0}
    write_new(evidence / "result.json", record)
    print(json.dumps({key: record[key] for key in ("run", "status", "sourceHash", "compileExit", "testExit", "sourcesUnchanged", "elapsedSeconds")}))
    return 0 if status.startswith("OFFLINE_PASS") else 1


if __name__ == "__main__":
    raise SystemExit(main())
