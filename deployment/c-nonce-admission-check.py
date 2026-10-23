"""C nonce admission explicit direct-JDK unit gate; no Docker, PG, RPC socket or keys."""
import datetime
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import uuid

BASE = "backend/src/main/java/com/pricetrack/exchange/custody/"
TEST = "backend/src/test/java/com/pricetrack/exchange/custody/"
SOURCES = (
    BASE + "abi/CAbiModels.java", BASE + "proof/CEip712Verifier.java",
    "backend/src/main/java/com/pricetrack/exchange/futures/math/FuturesMath.java",
    BASE + "runtime/CCustodyIdentity.java", BASE + "runtime/CCustodyFailure.java",
    BASE + "runtime/CReadRpc.java", BASE + "runtime/CReadAbi.java", BASE + "runtime/CCustodyRpcReader.java",
    BASE + "authorization/CConsentModels.java", BASE + "authorization/CConsentStore.java",
    BASE + "authorization/CNonceAdmissionModels.java", BASE + "authorization/CNonceAdmissionStore.java",
    TEST + "authorization/CConsentModelsTest.java", TEST + "authorization/CConsentStoreTest.java",
    TEST + "runtime/CCustodyIdentityTest.java", TEST + "runtime/CCustodyRpcReaderTest.java",
    TEST + "runtime/CAdmissionPrestateTest.java", TEST + "authorization/CNonceAdmissionStoreTest.java",
    "deployment/CNonceAdmissionTests.java",
)
RESOURCES = ("backend/src/main/resources/c-consent-schema.sql", "backend/src/main/resources/c-nonce-admission-schema.sql")
FILES = (*SOURCES, *RESOURCES, "deployment/c-nonce-admission-check.py", "deployment/c-consent-store-check.py")
CANDIDATE_SOURCES = (
    BASE + "authorization/CReportCandidateBuilder.java",
    TEST + "authorization/CReportCandidateBuilderTest.java",
    BASE + "abi/CAbiEventModels.java", BASE + "proof/CRulesTransitionModels.java", BASE + "proof/CRulesTransitionVerifier.java",
    "backend/src/main/java/com/pricetrack/exchange/futures/runtime/FuturesMarketGate.java",
    "backend/src/main/java/com/pricetrack/exchange/futures/ledger/FuturesLedger.java",
    "backend/src/main/java/com/pricetrack/exchange/futures/ledger/FuturesRecords.java",
    "backend/src/main/java/com/pricetrack/exchange/blockchain/support/TokenUnits.java",
    *tuple("backend/src/main/java/com/pricetrack/exchange/market/" + p + ".java" for p in (
        "MarketPriceService", "TradingPriceContext", "MarketDataEligibility", "MarketClosedException", "StalePriceException",
        "provider/MarketPriceProvider", "model/MarketPriceSnapshot", "model/MarketStatus", "model/PriceStatus")),
    "deployment/CReportCandidateTests.java",
)
DIRECT_SOURCES = (
    BASE + "authorization/CUserIntentTypedData.java", BASE + "runtime/CCustodyRpcClient.java",
    BASE + "auth/CWalletLinkStore.java", BASE + "auth/CWalletProofService.java",
    BASE + "abi/CAbiCodec.java", BASE + "abi/CAbiEventCodec.java",
    *tuple(BASE + "direct/" + p + ".java" for p in (
        "CDirectController", "CDirectExecutionStore", "CDirectModels", "CDirectOraclePort", "CDirectRuntimeConfig", "CDirectService",
        "CDirectOracleClient", "CDirectOracleConfig", "CDirectReceiptReader", "CDirectReceiptController")),
    "backend/src/main/java/com/pricetrack/exchange/auth/AuthenticatedUser.java",
    "backend/src/main/java/com/pricetrack/exchange/user/UserRole.java",
    *tuple(TEST + "direct/" + p + ".java" for p in ("CDirectServiceTest", "CDirectControllerTest", "CDirectOracleClientTest", "CDirectReceiptReaderTest")),
    "deployment/CDirectOracleServer.java", "deployment/CDirectOracleServerTest.java", "deployment/CDirectDemoTests.java",
    TEST + "runtime/CCustodyRpcClientTest.java",
)


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def quote(value):
    value = str(value).replace("\\", "/")
    if any(c in value for c in ('"', '\r', '\n', '\0')):
        raise ValueError("C_ADMISSION_ARGUMENT")
    return '"' + value + '"'


def main():
    if sys.argv[1:] not in ([], ["--candidate"], ["--direct"]):
        raise SystemExit("C_ADMISSION_SCOPE_ARGUMENTS")
    direct = sys.argv[1:] == ["--direct"]
    candidate = direct or sys.argv[1:] == ["--candidate"]
    extra = (*CANDIDATE_SOURCES, *DIRECT_SOURCES) if direct else CANDIDATE_SOURCES if candidate else ()
    sources = tuple(dict.fromkeys((*SOURCES, *extra)))
    resource_paths = (*RESOURCES, "backend/src/main/resources/c-direct-execution-schema.sql") if direct else RESOURCES
    files = tuple(dict.fromkeys((*FILES, *extra, *resource_paths)))
    root = Path(__file__).resolve().parents[1]
    cache = root.parents[2] / "deployment/runtime/dapp-audit-development-worktree/deployment/runtime/dapp-stage2-offline/java-classpath.args"
    lines = cache.read_text(encoding="utf-8-sig").splitlines()
    if len(lines) != 2 or lines[0] != "-cp":
        raise SystemExit("C_ADMISSION_CLASSPATH")
    jars = {}
    for entry in lines[1].strip('"').split(os.pathsep):
        p = Path(entry)
        if p.suffix.lower() != ".jar":
            continue
        if "/caches/modules-2/files-2.1/" not in p.as_posix() or not p.is_file():
            raise SystemExit("C_ADMISSION_DEPENDENCY_SCOPE")
        digest = sha(p)
        if p.name in jars and jars[p.name][1] != digest:
            raise SystemExit("C_ADMISSION_DEPENDENCY_COLLISION")
        jars[p.name] = (p, digest)
    if not any(n.startswith("apiguardian-api-") for n in jars):
        junit = [p for n, (p, _) in jars.items() if n.startswith("junit-jupiter-api-")]
        if len(junit) != 1:
            raise SystemExit("C_ADMISSION_JUNIT")
        extra = list((junit[0].parents[4] / "org.apiguardian/apiguardian-api/1.1.2").glob("*/apiguardian-api-1.1.2.jar"))
        if len(extra) != 1:
            raise SystemExit("C_ADMISSION_APIGUARDIAN")
        jars[extra[0].name] = (extra[0], sha(extra[0]))
    if len(jars) > 512 or any(sum(n.startswith(prefix) for n in jars) != 1 for prefix in (
        "junit-jupiter-engine-", "junit-platform-launcher-", "jackson-databind-", "crypto-", "h2-")):
        raise SystemExit("C_ADMISSION_DEPENDENCIES")
    jdk = Path("C:/Program Files/Java/jdk-21.0.10/bin")
    before = {p: sha(root / p) for p in files}
    run = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    category = "c-direct-offline" if direct else "c-report-candidate" if candidate else "c-nonce-admission"
    out = root / "deployment/runtime" / category / run
    out.mkdir(parents=True, exist_ok=False)
    classes = out / "classes"
    classes.mkdir()
    for rel in files:
        dest = out / "source-snapshot" / rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        with dest.open("xb") as f:
            f.write((root / rel).read_bytes())
    for rel in resource_paths:
        with (classes / Path(rel).name).open("xb") as f:
            f.write((out / "source-snapshot" / rel).read_bytes())
    cp = os.pathsep.join(str(jars[n][0]) for n in sorted(jars))
    commands = {
        "compile": [str(jdk / "javac.exe"), "--release", "21", "-parameters", "-encoding", "UTF-8", "-proc:none",
                    "-cp", cp, "-d", str(classes), *[str(out / "source-snapshot" / p) for p in sources]],
        "test": [str(jdk / "java.exe"), "-Xms16m", "-Xmx128m", "-XX:MaxMetaspaceSize=96m", "-Dfile.encoding=UTF-8",
                 "-cp", str(classes) + os.pathsep + cp, "CDirectDemoTests" if direct else "CReportCandidateTests" if candidate else "CNonceAdmissionTests", str(classes)],
    }
    env = {k: v for k, v in os.environ.items() if k.upper() in {
        "PATH", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "USERPROFILE", "LOCALAPPDATA", "APPDATA", "PATHEXT", "COMSPEC"}}
    version = subprocess.run([str(jdk / "java.exe"), "-version"], env=env, capture_output=True, text=True, timeout=10)
    exits = {}
    for name, command in commands.items():
        if name == "test" and exits.get("compile") != 0:
            exits[name] = None
            continue
        argfile = out / (name + ".args")
        with argfile.open("x", encoding="utf-8") as f:
            f.write("\n".join(quote(a) for a in command[1:]))
        with (out / (name + ".stdout.log")).open("xb") as stdout, (out / (name + ".stderr.log")).open("xb") as stderr:
            try:
                prefix = [command[0], "-J-Xmx192m"] if name == "compile" else [command[0]]
                exits[name] = subprocess.run([*prefix, "@" + str(argfile)], cwd=root, env=env, stdout=stdout, stderr=stderr, timeout=90).returncode
            except subprocess.TimeoutExpired:
                exits[name] = 124
    unchanged = before == {p: sha(root / p) for p in files}
    deps = all(sha(p) == h for p, h in jars.values())
    resource_ok = all(sha(classes / Path(p).name) == before[p] for p in resource_paths)
    passed = exits == {"compile": 0, "test": 0} and unchanged and deps and resource_ok and version.returncode == 0 and 'version "21.0.10"' in version.stderr
    success_status = "C_DIRECT_OFFLINE_PASS_NOT_HTTP_PG_OR_WALLET" if direct else "C_REPORT_CANDIDATE_OFFLINE_PASS_NOT_SIGNED_OR_EXECUTED" if candidate else "C_NONCE_ADMISSION_OFFLINE_PASS_NOT_PG_OR_EXECUTION"
    record = {"run": run, "status": success_status if passed else "FAILED_OR_BLOCKED",
              "sourceHash": hashlib.sha256(json.dumps(before, sort_keys=True).encode()).hexdigest(), "sources": before,
              "exits": exits, "sourcesUnchanged": unchanged, "dependenciesUnchanged": deps, "resourcesUnchanged": resource_ok,
              "classHashes": {p.relative_to(classes).as_posix(): sha(p) for p in sorted(classes.rglob("*.class"))},
              "dependencies": {n: h for n, (_, h) in jars.items()}, "javaVersion": version.stderr,
              "scope": "MOCK_RPC_H2_TRIAL_ORACLE_SIGNATURE_AND_RECEIPT_FIXTURES_ONLY_NO_SOCKET_OR_TRANSACTION" if direct else "MOCK_PINNED_RPC_H2_AND_UNSIGNED_REPORT_ONLY_NO_SOCKET_OR_TRANSACTION" if candidate else "MOCK_PINNED_RPC_AND_H2_NONCE_METADATA_ONLY_NO_SOCKET_OR_TRANSACTION", "dockerCalls": 0, "paidCalls": 0}
    with (out / "result.json").open("x", encoding="utf-8") as f:
        json.dump(record, f, indent=2)
    print(json.dumps({k: record[k] for k in ("run", "status", "sourceHash", "exits")}))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
