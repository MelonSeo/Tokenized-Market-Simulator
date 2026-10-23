"""Offline, additive browser preparation from an unchanged successful direct Gate artifact.

Never modifies the base preparation (including its consumed RUN_CLAIM), builds the web,
starts Docker, creates an approval, connects to RPC, or generates an external owner's key.
"""
import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import uuid

sys.dont_write_bytecode = True
BASE_ID = "20261023T140536Z-654b77de"
SOURCE_PATHS = ("deployment/CDirectBrowserPipe.java", "deployment/CDirectBrowserBootstrap.java",
                "deployment/c_direct_browser_relay.py", "deployment/test_c_direct_browser_relay.py",
                "deployment/c-direct-browser-prepare.py", "deployment/test_c_direct_browser_prepare.py")


def load_base(root):
    spec = importlib.util.spec_from_file_location("c_direct_browser_base", root / "deployment/c-direct-demo-check.py")
    base = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(base)
    return base


def validate_base(root, base, prepared, record):
    if record.get("run") != BASE_ID or record.get("status") != "PREPARED_NOT_ACTUAL_PG_ANVIL_HTTP_OR_METAMASK" or not record.get("sourcesUnchanged") or not record.get("dependenciesUnchanged"):
        raise ValueError("C_BROWSER_BASE_RESULT")
    if not record.get("sources") or any(base.sha(root / p) != digest for p, digest in record["sources"].items()):
        raise ValueError("C_BROWSER_BASE_SOURCE_CHANGED")
    if set(record.get("fixtureHashes", {})) != {"server", "oracle", "driver"} or any(base.tree_hashes(prepared / (role + "-fixture")) != hashes for role, hashes in record["fixtureHashes"].items()):
        raise ValueError("C_BROWSER_BASE_FIXTURE_CHANGED")
    if base.checked_web_tree(prepared / "web-fixture") != record.get("webHashes"):
        raise ValueError("C_BROWSER_BASE_WEB_CHANGED")
    # A historical claim is deliberately neither removed nor treated as a reusable run claim.


def source_identity(base_sources, new_sources):
    if set(base_sources) & set(new_sources):
        raise ValueError("C_BROWSER_SOURCE_COLLISION")
    return hashlib.sha256(json.dumps({**base_sources, **new_sources}, sort_keys=True).encode()).hexdigest()


def prepare(root):
    base = load_base(root)
    old = root / "deployment/runtime/c-direct-demo-prepare" / BASE_ID
    record = json.loads((old / "result.json").read_text(encoding="utf-8"))
    validate_base(root, base, old, record)
    old_hashes = base.tree_hashes(old)
    sources = {p: base.sha(root / p) for p in SOURCE_PATHS}
    identity = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ-") + uuid.uuid4().hex[:8]
    out = root / "deployment/runtime/c-direct-browser-prepare" / identity
    out.mkdir(parents=True, exist_ok=False)
    result = {"run": identity, "status": "BLOCKED", "scope": "OFFLINE_NEW_BROWSER_PREPARATION_NOT_LIVE_OR_METAMASK",
              "baseRun": BASE_ID, "baseResultSha256": base.sha(old / "result.json"), "baseSourceIdentity": record["sourceIdentity"],
              "sourceIdentity": source_identity(record["sources"], sources), "sources": {**record["sources"], **sources},
              "sqlHashes": record["sqlHashes"], "webHashes": record["webHashes"], "dependencies": record["dependencies"],
              "compileExits": {}, "selfTestExits": {}, "dockerCalls": 0, "rpcCalls": 0, "paidCalls": 0}
    try:
        for name in ("server-fixture", "driver-fixture", "oracle-fixture", "web-fixture", "source-snapshot"):
            shutil.copytree(old / name, out / name)
        for rel in SOURCE_PATHS:
            target = out / "source-snapshot" / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            with target.open("xb") as dest: dest.write((root / rel).read_bytes())
        (out / "empty-sourcepath").mkdir()
        jdk = Path("C:/Program Files/Java/jdk-21.0.10/bin")
        env = base.clean_env()
        def process(name, command):
            with (out / (name + ".stdout.log")).open("xb") as stdout, (out / (name + ".stderr.log")).open("xb") as stderr:
                return subprocess.run(command, cwd=root, env=env, stdout=stdout, stderr=stderr, timeout=60).returncode
        version = subprocess.run([str(jdk / "java.exe"), "-version"], env=env, capture_output=True, text=True, timeout=10)
        if version.returncode or 'version "21.0.10"' not in version.stderr:
            raise ValueError("C_BROWSER_JDK_IDENTITY")
        result["javaVersion"] = version.stderr
        for role, name in (("server", "CDirectBrowserPipe"), ("driver", "CDirectBrowserBootstrap")):
            fixture = out / (role + "-fixture")
            if {p.name: base.sha(p) for p in (fixture / "jars").glob("*.jar")} != record["dependencies"]:
                raise ValueError("C_BROWSER_DEPENDENCY_IDENTITY")
            cp = os.pathsep.join([str(fixture / "classes"), *(str(p) for p in sorted((fixture / "jars").glob("*.jar")))])
            args = ["-proc:none", "-parameters", "--release", "21", "-implicit:none", "-sourcepath", str(out / "empty-sourcepath"),
                    "-cp", cp, "-d", str(fixture / "classes"), str(out / "source-snapshot/deployment" / (name + ".java"))]
            argfile = out / (role + ".browser.compile.args")
            with argfile.open("x", encoding="utf-8") as stream: stream.write("\n".join(base.quote(a) for a in args))
            code = process(role + ".browser.compile", [str(jdk / "javac.exe"), "-J-Xmx128m", "@" + str(argfile)])
            result["compileExits"][role] = code
            if code: raise ValueError("C_BROWSER_COMPILE_" + role.upper())
            base.check_role_tree(fixture, role)
            code = process(role + ".browser.self-test", [str(jdk / "java.exe"), "-Xmx64m", "-cp", cp, name, "--self-test"])
            result["selfTestExits"][role] = code
            if code or (out / (role + ".browser.self-test.stderr.log")).stat().st_size:
                raise ValueError("C_BROWSER_SELF_TEST_" + role.upper())
        result["pythonTestExit"] = process("browser.python-tests", [sys.executable, "-B", "-m", "unittest", "discover", "-s", "deployment", "-p", "test_c_direct_browser*.py"])
        if result["pythonTestExit"]: raise ValueError("C_BROWSER_PYTHON_TESTS")
        result["helperSha256"] = base.sha(out / "server-fixture/classes/CDirectBrowserPipe.class")
        result["status"] = "PREPARED_NOT_LIVE_BROWSER_OR_METAMASK"
    except Exception as safe:
        result["safeError"] = str(safe) if isinstance(safe, ValueError) and str(safe).startswith("C_BROWSER_") else "C_BROWSER_PREPARE_" + type(safe).__name__
    finally:
        result["baseUnchanged"] = base.tree_hashes(old) == old_hashes
        result["sourcesUnchanged"] = all(base.sha(root / p) == digest for p, digest in result["sources"].items())
        result["fixtureHashes"] = {role: base.tree_hashes(out / (role + "-fixture")) for role in ("server", "oracle", "driver")}
        if not result["baseUnchanged"] or not result["sourcesUnchanged"]:
            result["status"] = "BLOCKED";result["safeError"] = "C_BROWSER_END_IDENTITY_CHANGED"
        base.new_json(out / "result.json", result)
    print(json.dumps({key: result.get(key) for key in ("run", "status", "sourceIdentity", "safeError")}))
    return 0 if result["status"].startswith("PREPARED_") else 1


if __name__ == "__main__":
    raise SystemExit(prepare(Path(__file__).resolve().parent.parent))
