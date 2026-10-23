"""Explicit pure C typed-data unit only: no Gradle, app classes, network or services."""
import datetime
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import uuid

BASE = "backend/src/main/java/com/pricetrack/exchange/custody/"
SOURCES = (BASE + "abi/CAbiModels.java", BASE + "proof/CEip712Verifier.java",
           BASE + "authorization/CUserIntentTypedData.java",
           "backend/src/test/java/com/pricetrack/exchange/custody/authorization/CUserIntentTypedDataTest.java")
FILES = (*SOURCES, "deployment/c-intent-typed-data-check.py")


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def quote(value):
    value = str(value).replace("\\", "/")
    if any(c in value for c in ('"', "\r", "\n", "\0")):
        raise ValueError("C_TYPED_ARGUMENT")
    return '"' + value + '"'


def main():
    if len(sys.argv) != 1:
        raise SystemExit("C_TYPED_NO_ARGUMENTS")
    root = Path(__file__).resolve().parents[1]
    cache = root.parents[2] / "deployment/runtime/dapp-audit-development-worktree/deployment/runtime/dapp-stage2-offline/java-classpath.args"
    lines = cache.read_text(encoding="utf-8-sig").splitlines()
    if len(lines) != 2 or lines[0] != "-cp":
        raise SystemExit("C_TYPED_CLASSPATH")
    jars = {}
    for entry in lines[1].strip('"').split(os.pathsep):
        path = Path(entry)
        if path.suffix.lower() != ".jar":
            continue
        if "/caches/modules-2/files-2.1/" not in path.as_posix() or not path.is_file():
            raise SystemExit("C_TYPED_DEPENDENCY_SCOPE")
        digest = sha(path)
        if path.name in jars and jars[path.name][1] != digest:
            raise SystemExit("C_TYPED_DEPENDENCY_COLLISION")
        jars[path.name] = (path, digest)
    if not any(name.startswith("apiguardian-api-") for name in jars):
        junit = [p for name, (p, _) in jars.items() if name.startswith("junit-jupiter-api-")]
        if len(junit) != 1:
            raise SystemExit("C_TYPED_JUNIT")
        extra = list((junit[0].parents[4] / "org.apiguardian/apiguardian-api/1.1.2").glob("*/apiguardian-api-1.1.2.jar"))
        if len(extra) != 1:
            raise SystemExit("C_TYPED_APIGUARDIAN")
        jars[extra[0].name] = (extra[0], sha(extra[0]))
    required = ("junit-jupiter-engine-", "junit-platform-launcher-", "jackson-databind-", "crypto-")
    if len(jars) > 512 or any(sum(name.startswith(p) for name in jars) != 1 for p in required):
        raise SystemExit("C_TYPED_DEPENDENCIES")
    jdk = Path("C:/Program Files/Java/jdk-21.0.10/bin")
    if not (jdk / "javac.exe").is_file():
        raise SystemExit("C_TYPED_JDK")
    before = {rel: sha(root / rel) for rel in FILES}
    run = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    evidence = root / "deployment/runtime/c-intent-typed-data" / run
    evidence.mkdir(parents=True, exist_ok=False)
    classes = evidence / "classes"
    classes.mkdir()
    for rel in FILES:
        target = evidence / "source-snapshot" / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open("xb") as out:
            out.write((root / rel).read_bytes())
    cp = os.pathsep.join(str(jars[name][0]) for name in sorted(jars))
    commands = {
        "compile": [str(jdk / "javac.exe"), "--release", "21", "-parameters", "-encoding", "UTF-8", "-proc:none",
                    "-cp", cp, "-d", str(classes), *[str(evidence / "source-snapshot" / rel) for rel in SOURCES]],
        "test": [str(jdk / "java.exe"), "-Xms16m", "-Xmx128m", "-XX:MaxMetaspaceSize=96m", "-Dfile.encoding=UTF-8",
                 "-cp", str(classes) + os.pathsep + cp, "com.pricetrack.exchange.custody.authorization.CUserIntentTypedDataTest"]}
    env = {k: v for k, v in os.environ.items() if k.upper() in {
        "PATH", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "USERPROFILE", "LOCALAPPDATA", "APPDATA", "PATHEXT", "COMSPEC"}}
    version = subprocess.run([str(jdk / "java.exe"), "-version"], env=env, capture_output=True, text=True, timeout=10)
    exits = {}
    for name, command in commands.items():
        if name == "test" and exits.get("compile") != 0:
            exits[name] = None
            continue
        argfile = evidence / (name + ".args")
        with argfile.open("x", encoding="utf-8") as out:
            out.write("\n".join(quote(a) for a in command[1:]))
        with (evidence / (name + ".stdout.log")).open("xb") as out, (evidence / (name + ".stderr.log")).open("xb") as err:
            try:
                # javac -J controls its JVM and is not accepted inside @argfiles.
                direct = [command[0], "-J-Xmx192m"] if name == "compile" else [command[0]]
                exits[name] = subprocess.run([*direct, "@" + str(argfile)], cwd=root, env=env, stdout=out, stderr=err, timeout=90).returncode
            except subprocess.TimeoutExpired:
                exits[name] = 124
    unchanged = before == {rel: sha(root / rel) for rel in FILES}
    dependencies_unchanged = all(sha(p) == h for p, h in jars.values())
    passed = exits == {"compile": 0, "test": 0} and unchanged and dependencies_unchanged and version.returncode == 0 and 'version "21.0.10"' in version.stderr
    record = {"run": run, "status": "PURE_FORMATTER_OFFLINE_PASS" if passed else "FAILED_OR_BLOCKED", "exits": exits,
              "sourceHash": hashlib.sha256(json.dumps(before, sort_keys=True).encode()).hexdigest(), "sources": before,
              "sourcesUnchanged": unchanged, "dependenciesUnchanged": dependencies_unchanged,
              "dependencies": {name: digest for name, (_, digest) in jars.items()}, "javaVersion": version.stderr,
              "classHashes": {p.relative_to(classes).as_posix(): sha(p) for p in sorted(classes.rglob("*.class"))},
              "scope": "PURE_JSON_AND_EIP712_DIGEST_ONLY_NOT_AUTHORIZATION_OR_SERVICE_GATE", "paidCalls": 0, "dockerCalls": 0}
    with (evidence / "result.json").open("x", encoding="utf-8") as out:
        json.dump(record, out, indent=2)
    print(json.dumps({k: record[k] for k in ("run", "status", "sourceHash", "exits")}))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
