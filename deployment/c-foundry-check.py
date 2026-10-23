"""Offline C candidate Foundry check; new append-only logs, no RPC or deployment.

Uses installed compiler/dependency sources read-only, with cache/out exclusively in
this worktree. Never starts Docker/Anvil, sends transactions or reads credentials.
"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import uuid


def hashes(root):
    paths = [root / "contracts/foundry.toml"]
    for folder in ("contracts/src", "contracts/test", "contracts/script"):
        paths.extend(p for p in (root / folder).rglob("*") if p.is_file())
    return {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(paths)}


def main():
    # Foundry prints Unicode gas/time units; Windows redirected cp949 must not
    # turn an already-recorded successful run into a reporting exception.
    sys.stdout.reconfigure(encoding="utf-8", errors="backslashreplace")
    parser = argparse.ArgumentParser()
    parser.add_argument("scope", choices=("c1", "all", "build", "c2"))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    # This worktree is nested under the original repository's deployment/runtime.
    original = root.parents[2]
    libs = original / "contracts/lib"
    for dependency in ("openzeppelin-contracts/contracts/token/ERC20/ERC20.sol", "forge-std/src/Test.sol"):
        if not (libs / dependency).is_file():
            raise SystemExit("OFFLINE_DEPENDENCY_MISSING")
    forge = shutil.which("forge")
    if not forge:
        raise SystemExit("FOUNDRY_MISSING")
    identity = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    evidence = root / "deployment/runtime/c-foundry" / identity
    evidence.mkdir(parents=True, exist_ok=False)
    before = hashes(root)
    # Preserve the exact pre-C2 candidate, not merely a hash of files later edited.
    for relative, expected in before.items():
        content = (root / relative).read_bytes()
        if hashlib.sha256(content).hexdigest() != expected:
            raise SystemExit("SOURCE_CHANGED_DURING_SNAPSHOT")
        destination = evidence / "source-snapshot" / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        with destination.open("xb") as snapshot:
            snapshot.write(content)
    env = {k: v for k, v in os.environ.items()
           if k.upper() in {"PATH", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "USERPROFILE", "LOCALAPPDATA", "APPDATA", "PATHEXT", "COMSPEC"}}
    env["FOUNDRY_REMAPPINGS"] = (
        "@openzeppelin/=" + (libs / "openzeppelin-contracts").as_posix() + "/\n"
        "forge-std/=" + (libs / "forge-std/src").as_posix() + "/"
    )
    command = [forge, "build" if args.scope == "build" else "test", "--offline", "-j", "1",
               "--out", str(root / "deployment/runtime/c-foundry-out"),
               "--cache-path", str(root / "deployment/runtime/c-foundry-cache")]
    if args.scope in ("c1", "c2"):
        command += ["--match-path", "test/CCustody.t.sol" if args.scope == "c1" else "test/C*.t.sol"]
    if args.scope != "build":
        command += ["-vv"]
    version = subprocess.run([forge, "--version"], capture_output=True, text=True, timeout=10, env=env)
    started = datetime.datetime.now(datetime.timezone.utc)
    with (evidence / "stdout.log").open("x", encoding="utf-8") as stdout, (evidence / "stderr.log").open("x", encoding="utf-8") as stderr:
        try:
            result = subprocess.run(command, cwd=root / "contracts", env=env,
                                    stdout=stdout, stderr=stderr, timeout=180)
            code = result.returncode
        except subprocess.TimeoutExpired:
            code = 124
    after = hashes(root)
    record = {"run": identity, "scope": args.scope, "command": command,
              "forge": version.stdout.strip(), "startedAt": started.isoformat(),
              "elapsedSeconds": (datetime.datetime.now(datetime.timezone.utc) - started).total_seconds(),
              "exitCode": code, "sources": before,
              "sourceHash": hashlib.sha256(json.dumps(before, sort_keys=True).encode()).hexdigest(),
              "sourcesUnchanged": before == after, "offline": True,
              "rpcCalls": 0, "deployments": 0}
    with (evidence / "result.json").open("x", encoding="utf-8") as output:
        json.dump(record, output, indent=2)
    print(json.dumps({k: record[k] for k in ("run", "scope", "exitCode", "elapsedSeconds", "sourceHash", "sourcesUnchanged")}))
    print((evidence / "stdout.log").read_text(encoding="utf-8")[-14000:])
    print((evidence / "stderr.log").read_text(encoding="utf-8")[-5000:])
    return code if before == after else 125


if __name__ == "__main__":
    raise SystemExit(main())
