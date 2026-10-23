"""Compile only the pure C projection and JUnit tests, without Gradle sockets.

Resolved JUnit jars are read-only cached dependencies; old app classes, Spring,
provider credentials, DB/RPC opt-in environment variables are never loaded.
"""
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import uuid


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="backslashreplace")
    root = Path(__file__).resolve().parents[1]
    original = root.parents[2]
    cache_args = original / "deployment/runtime/dapp-audit-development-worktree/deployment/runtime/dapp-stage2-offline/java-classpath.args"
    lines = cache_args.read_text(encoding="utf-8-sig").splitlines()
    if len(lines) != 2 or lines[0] != "-cp":
        raise SystemExit("CACHED_JUNIT_CLASSPATH_FORMAT")
    resolved = [Path(x) for x in lines[1].strip('"').split(os.pathsep)]
    prefixes = ["junit-jupiter-api", "junit-jupiter-engine", "junit-platform-commons",
                "junit-platform-engine", "junit-platform-launcher", "apiguardian-api", "opentest4j"]
    jars = []
    for prefix in prefixes:
        candidates = [p for p in resolved if re.fullmatch(re.escape(prefix) + r"-\d[^/\\]*\.jar", p.name)]
        # Gradle's runtime classpath omits this compile-time annotation artifact.
        # Use only its already-cached, exact version; never download or use old
        # application classes to compensate for a missing dependency.
        if prefix == "apiguardian-api" and not candidates and jars:
            module_cache = jars[0].parents[4]
            candidates = list((module_cache / "org.apiguardian/apiguardian-api/1.1.2").glob("*/apiguardian-api-1.1.2.jar"))
        if len(candidates) != 1 or not candidates[0].is_file():
            raise SystemExit("CACHED_JUNIT_DEPENDENCY_MISSING_OR_AMBIGUOUS:" + prefix)
        jars.append(candidates[0])
    jdk = Path("C:/Program Files/Java/jdk-21.0.10/bin")
    if not (jdk / "javac.exe").is_file():
        raise SystemExit("APPROVED_LOCAL_JDK_MISSING")
    # The accounting/storage Gate must not silently absorb later ABI/crypto/RPC
    # units or require their unrelated dependency/class-loading environments.
    sources = [root / p for p in (
        "backend/src/main/java/com/pricetrack/exchange/custody/model/CCustodyModels.java",
        "backend/src/main/java/com/pricetrack/exchange/custody/projection/CCustodyReducer.java",
        "backend/src/main/java/com/pricetrack/exchange/custody/store/CCustodyStateCodec.java",
        "backend/src/main/java/com/pricetrack/exchange/custody/store/CCustodyProjectionStore.java",
        "backend/src/test/java/com/pricetrack/exchange/custody/projection/CCustodyReducerTest.java",
        "backend/src/test/java/com/pricetrack/exchange/custody/store/CCustodyStateCodecTest.java",
        "backend/src/test/java/com/pricetrack/exchange/custody/store/CCustodyProjectionStorePostgresTest.java")]
    sources.append(root / "deployment/CProjectionTests.java")
    postgres_launcher = root / "deployment/CPostgresTests.java"
    if postgres_launcher.is_file():
        sources.append(postgres_launcher)
    if len(sources) < 4:
        raise SystemExit("C_PROJECTION_SOURCE_MISSING")
    identity = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    evidence = root / "deployment/runtime/c-projection" / identity
    evidence.mkdir(parents=True, exist_ok=False)
    classes = evidence / "classes"
    classes.mkdir()
    evidence_sources = [*sources, root / "deployment/c-projection-check.py", root / "deployment/c-postgres-check.py",
                        root / "deployment/c_gate_network.py", root / "deployment/test_c_gate_network.py"]
    schema = root / "backend/src/main/resources/c-custody-schema.sql"
    if schema.is_file():
        evidence_sources.append(schema)
    before = {p.relative_to(root).as_posix(): sha(p) for p in evidence_sources}
    for p in evidence_sources:
        target = evidence / "source-snapshot" / p.relative_to(root)
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open("xb") as snapshot:
            snapshot.write(p.read_bytes())
    env = {k: v for k, v in os.environ.items() if k.upper() in {
        "PATH", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "USERPROFILE", "LOCALAPPDATA", "PATHEXT", "COMSPEC"}}
    jar_cp = os.pathsep.join(str(p) for p in jars)
    compile_command = [str(jdk / "javac.exe"), "-J-Xmx192m", "--release", "21", "-encoding", "UTF-8",
                       "-cp", jar_cp, "-d", str(classes), *map(str, sources)]
    test_command = [str(jdk / "java.exe"), "-Xms32m", "-Xmx192m", "-XX:MaxMetaspaceSize=128m",
                    "-Dfile.encoding=UTF-8", "-cp", str(classes) + os.pathsep + jar_cp, "CProjectionTests"]
    started = datetime.datetime.now(datetime.timezone.utc)

    def execute(command, label):
        with (evidence / (label + ".stdout.log")).open("x", encoding="utf-8") as out, (evidence / (label + ".stderr.log")).open("x", encoding="utf-8") as err:
            try:
                return subprocess.run(command, cwd=root, env=env, stdout=out, stderr=err, timeout=120).returncode
            except subprocess.TimeoutExpired:
                return 124

    version = subprocess.run([str(jdk / "java.exe"), "-version"], env=env, capture_output=True, text=True, timeout=10)
    compile_exit = execute(compile_command, "compile")
    test_exit = execute(test_command, "test") if compile_exit == 0 else None
    unchanged = before == {p.relative_to(root).as_posix(): sha(p) for p in evidence_sources}
    class_hashes = {p.relative_to(classes).as_posix(): sha(p) for p in sorted(classes.rglob("*.class"))}
    record = {"run": identity, "java": version.stderr.strip(), "sourceHash": hashlib.sha256(json.dumps(before, sort_keys=True).encode()).hexdigest(),
              "sources": before, "classHashes": class_hashes, "dependencies": {p.name: sha(p) for p in jars}, "compileExit": compile_exit,
              "testExit": test_exit, "sourcesUnchanged": unchanged, "compileCommand": compile_command, "testCommand": test_command,
              "elapsedSeconds": (datetime.datetime.now(datetime.timezone.utc) - started).total_seconds(),
              "scope": "PURE_C_UNIT_ONLY", "gradle": False, "rpcCalls": 0, "dbCalls": 0, "paidCalls": 0}
    with (evidence / "result.json").open("x", encoding="utf-8") as output:
        json.dump(record, output, indent=2)
    print(json.dumps({k: record[k] for k in ("run", "sourceHash", "compileExit", "testExit", "sourcesUnchanged", "elapsedSeconds")}))
    for file in sorted(evidence.glob("*.log")):
        print(file.name)
        print(file.read_text(encoding="utf-8", errors="replace")[-14000:])
    return 0 if compile_exit == 0 and test_exit == 0 and unchanged else 1


if __name__ == "__main__":
    raise SystemExit(main())
