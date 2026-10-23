"""Compile one independent pure C ABI unit with cached JUnit, no services/sockets."""
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
    if len(sys.argv) != 2 or sys.argv[1] not in ("calls", "events"):
        raise SystemExit("C_ABI_TEST_SCOPE: calls|events")
    scope = sys.argv[1]
    root = Path(__file__).resolve().parents[1]
    original = root.parents[2]
    cache_args = original / "deployment/runtime/dapp-audit-development-worktree/deployment/runtime/dapp-stage2-offline/java-classpath.args"
    lines = cache_args.read_text(encoding="utf-8-sig").splitlines()
    if len(lines) != 2 or lines[0] != "-cp":
        raise SystemExit("CACHED_JUNIT_CLASSPATH_FORMAT")
    resolved = [Path(p) for p in lines[1].strip('"').split(os.pathsep)]
    jars = []
    for prefix in ("junit-jupiter-api", "junit-jupiter-engine", "junit-platform-commons", "junit-platform-engine",
                   "junit-platform-launcher", "apiguardian-api", "opentest4j"):
        matches = [p for p in resolved if re.fullmatch(re.escape(prefix) + r"-\d[^/\\]*\.jar", p.name)]
        if prefix == "apiguardian-api" and not matches and jars:
            matches = list((jars[0].parents[4] / "org.apiguardian/apiguardian-api/1.1.2").glob("*/apiguardian-api-1.1.2.jar"))
        if len(matches) != 1 or not matches[0].is_file():
            raise SystemExit("CACHED_JUNIT_DEPENDENCY_MISSING:" + prefix)
        jars.append(matches[0])
    main_dir = root / "backend/src/main/java/com/pricetrack/exchange/custody/abi"
    test_dir = root / "backend/src/test/java/com/pricetrack/exchange/custody/abi"
    sources = [main_dir / "CAbiModels.java"]
    if scope == "calls":
        sources += [main_dir / "CAbiCodec.java", test_dir / "CAbiCodecTest.java"]
    else:
        sources += [main_dir / "CAbiEventModels.java", main_dir / "CAbiEventCodec.java", test_dir / "CAbiEventCodecTest.java"]
    sources.append(root / "deployment/CAbiTests.java")
    evidence_sources = [*sources, Path(__file__).resolve()]
    # Bind the ABI unit to the actual reviewed Solidity definitions too.
    evidence_sources += [root / "contracts/src" / p for p in (
        "CCollateralizedFuturesController.sol", "CFuturesIntentTypes.sol", "FuturesReportTypes.sol",
        "FuturesEngine.sol", "CMarginVault.sol", "CMockCollateral.sol")]
    before = {p.relative_to(root).as_posix(): sha(p) for p in evidence_sources}
    run = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    evidence = root / "deployment/runtime/c-abi" / run
    classes = evidence / "classes"
    classes.mkdir(parents=True, exist_ok=False)
    for p in evidence_sources:
        dest = evidence / "source-snapshot" / p.relative_to(root)
        dest.parent.mkdir(parents=True, exist_ok=True)
        with dest.open("xb") as out:
            out.write(p.read_bytes())
    env = {k: v for k, v in os.environ.items() if k.upper() in {
        "PATH", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "USERPROFILE", "LOCALAPPDATA", "PATHEXT", "COMSPEC"}}
    jdk = Path("C:/Program Files/Java/jdk-21.0.10/bin")
    cp = os.pathsep.join(str(p) for p in jars)
    compile_cmd = [str(jdk / "javac.exe"), "-J-Xmx192m", "--release", "21", "-encoding", "UTF-8",
                   "-cp", cp, "-d", str(classes), *map(str, sources)]
    test_cmd = [str(jdk / "java.exe"), "-Xms32m", "-Xmx192m", "-XX:MaxMetaspaceSize=128m",
                "-Dfile.encoding=UTF-8", "-cp", str(classes) + os.pathsep + cp, "CAbiTests", scope]

    def execute(command, label):
        with (evidence / (label + ".stdout.log")).open("x", encoding="utf-8") as out, (evidence / (label + ".stderr.log")).open("x", encoding="utf-8") as err:
            try:
                return subprocess.run(command, cwd=root, env=env, stdout=out, stderr=err, timeout=120).returncode
            except subprocess.TimeoutExpired:
                return 124
    version = subprocess.run([str(jdk / "java.exe"), "-version"], env=env, capture_output=True, text=True, timeout=10)
    compile_exit = execute(compile_cmd, "compile")
    test_exit = execute(test_cmd, "test") if compile_exit == 0 else None
    unchanged = before == {p.relative_to(root).as_posix(): sha(p) for p in evidence_sources}
    record = {"run": run, "scope": "PURE_C_ABI_" + scope.upper(), "sources": before,
              "sourceHash": hashlib.sha256(json.dumps(before, sort_keys=True).encode()).hexdigest(),
              "sourcesUnchanged": unchanged, "dependencies": {p.name: sha(p) for p in jars}, "jdk": version.stderr,
              "compileCommand": compile_cmd, "testCommand": test_cmd, "compileExit": compile_exit,
              "testExit": test_exit, "rpcCalls": 0, "dbCalls": 0, "paidCalls": 0}
    with (evidence / "result.json").open("x", encoding="utf-8") as out:
        json.dump(record, out, indent=2)
    print(json.dumps({k: record[k] for k in ("run", "scope", "sourceHash", "compileExit", "testExit", "sourcesUnchanged")}))
    for p in sorted(evidence.glob("*.log")):
        print(p.name); print(p.read_text(encoding="utf-8", errors="replace")[-16000:])
    return 0 if compile_exit == 0 and test_exit == 0 and unchanged else 1


if __name__ == "__main__":
    raise SystemExit(main())
