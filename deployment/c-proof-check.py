"""Pure C proof tests, explicit cached jars, no services/providers."""
import datetime
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import uuid


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def proof_success(compile_exit, test_exit, unchanged, compile_clean, test_clean):
    """A zero exit cannot erase compiler cleanup errors or missing test execution."""
    return compile_exit == 0 and test_exit == 0 and unchanged and compile_clean and test_clean


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="backslashreplace")
    if len(sys.argv) > 2 or (len(sys.argv) == 2 and sys.argv[1] not in ("transaction", "rules", "authorization", "envelope", "token", "user", "risk", "vault", "control", "token-call", "overlay")):
        raise SystemExit("C_PROOF_TEST_SCOPE")
    mode = sys.argv[1] if len(sys.argv) == 2 else "eip712"
    root = Path(__file__).resolve().parents[1]
    args = root.parents[2] / "deployment/runtime/dapp-audit-development-worktree/deployment/runtime/dapp-stage2-offline/java-classpath.args"
    lines = args.read_text(encoding="utf-8-sig").splitlines()
    if len(lines) != 2 or lines[0] != "-cp":
        raise SystemExit("CACHED_CLASSPATH_FORMAT")
    paths = [Path(p) for p in lines[1].strip('"').split(os.pathsep)]
    required = ["junit-jupiter-api-5.10.3.jar", "junit-jupiter-engine-5.10.3.jar",
                "junit-platform-commons-1.10.3.jar", "junit-platform-engine-1.10.3.jar",
                "junit-platform-launcher-1.10.3.jar", "opentest4j-1.3.0.jar",
                "apiguardian-api-1.1.2.jar"]
    if mode not in ("rules", "token"):
        required += ["crypto-4.12.2.jar", "utils-4.12.2.jar", "rlp-4.12.2.jar", "bcprov-jdk18on-1.78.1.jar"]
    jars = []
    for name in required:
        matches = [p for p in paths if p.name == name]
        if name == "apiguardian-api-1.1.2.jar" and not matches and jars:
            matches = list((jars[0].parents[4] / "org.apiguardian/apiguardian-api/1.1.2").glob("*/" + name))
        if len(matches) != 1 or not matches[0].is_file():
            raise SystemExit("CACHED_PROOF_DEPENDENCY_MISSING:" + name)
        jars.append(matches[0])
    sources = [root / p for p in (
        "backend/src/main/java/com/pricetrack/exchange/custody/abi/CAbiModels.java",
        "deployment/CProofTests.java")]
    if mode not in ("rules", "token"):
        sources.append(root / "backend/src/main/java/com/pricetrack/exchange/custody/proof/CEip712Verifier.java")
    if mode == "transaction":
        sources += [root / p for p in (
            "backend/src/main/java/com/pricetrack/exchange/custody/proof/CSignedTransactionVerifier.java",
            "backend/src/test/java/com/pricetrack/exchange/custody/proof/CSignedTransactionVerifierTest.java")]
    elif mode in ("user", "risk", "vault", "control", "token-call", "overlay"):
        sources += [root / p for p in (
            "backend/src/main/java/com/pricetrack/exchange/custody/abi/CAbiCodec.java",
            "backend/src/main/java/com/pricetrack/exchange/custody/abi/CAbiEventModels.java",
            "backend/src/main/java/com/pricetrack/exchange/custody/abi/CAbiEventCodec.java",
            "backend/src/main/java/com/pricetrack/exchange/custody/proof/CSignedTransactionVerifier.java",
            "backend/src/main/java/com/pricetrack/exchange/custody/proof/CRulesTransitionModels.java",
            "backend/src/main/java/com/pricetrack/exchange/custody/proof/CRulesTransitionVerifier.java",
            "backend/src/main/java/com/pricetrack/exchange/custody/proof/CAuthorizationModels.java",
            "backend/src/main/java/com/pricetrack/exchange/custody/proof/CAuthorizationVerifier.java",
            "backend/src/main/java/com/pricetrack/exchange/custody/proof/CReceiptEnvelopeModels.java",
            "backend/src/main/java/com/pricetrack/exchange/custody/proof/CReceiptEnvelopeVerifier.java",
            "backend/src/main/java/com/pricetrack/exchange/custody/model/CCustodyModels.java",
            "backend/src/main/java/com/pricetrack/exchange/custody/projection/CCustodyReducer.java",
            "backend/src/main/java/com/pricetrack/exchange/custody/token/CTokenLedgerModels.java",
            "backend/src/main/java/com/pricetrack/exchange/custody/token/CTokenLedgerReducer.java",
            "backend/src/main/java/com/pricetrack/exchange/futures/math/FuturesMath.java",
            "backend/src/main/java/com/pricetrack/exchange/custody/proof/CTransactionProofModels.java",
            "backend/src/main/java/com/pricetrack/exchange/custody/proof/CTransactionProofVerifier.java",
            "backend/src/test/java/com/pricetrack/exchange/custody/proof/CTransactionProofVerifierTest.java")]
        if mode == "risk":
            sources.append(root / "backend/src/test/java/com/pricetrack/exchange/custody/proof/CTransactionRiskProofVerifierTest.java")
        elif mode == "vault":
            sources += [root / p for p in (
                "backend/src/main/java/com/pricetrack/exchange/custody/proof/CVaultTransactionProofModels.java",
                "backend/src/main/java/com/pricetrack/exchange/custody/proof/CVaultTransactionProofVerifier.java",
                "backend/src/test/java/com/pricetrack/exchange/custody/proof/CVaultTransactionProofVerifierTest.java")]
        elif mode == "control":
            sources += [root / p for p in (
                "backend/src/main/java/com/pricetrack/exchange/custody/proof/CControllerControlProofModels.java",
                "backend/src/main/java/com/pricetrack/exchange/custody/proof/CControllerControlProofVerifier.java",
                "backend/src/test/java/com/pricetrack/exchange/custody/proof/CControllerControlProofVerifierTest.java")]
        elif mode == "token-call":
            sources += [root / p for p in (
                "backend/src/main/java/com/pricetrack/exchange/custody/proof/CTokenTransactionProofModels.java",
                "backend/src/main/java/com/pricetrack/exchange/custody/proof/CTokenTransactionProofVerifier.java",
                "backend/src/test/java/com/pricetrack/exchange/custody/proof/CTokenTransactionProofVerifierTest.java")]
        elif mode == "overlay":
            sources += [root / p for p in (
                "backend/src/main/java/com/pricetrack/exchange/custody/proof/CVaultTransactionProofModels.java",
                "backend/src/main/java/com/pricetrack/exchange/custody/proof/CVaultTransactionProofVerifier.java",
                "backend/src/main/java/com/pricetrack/exchange/custody/proof/CControllerControlProofModels.java",
                "backend/src/main/java/com/pricetrack/exchange/custody/proof/CControllerControlProofVerifier.java",
                "backend/src/main/java/com/pricetrack/exchange/custody/proof/CTokenTransactionProofModels.java",
                "backend/src/main/java/com/pricetrack/exchange/custody/proof/CTokenTransactionProofVerifier.java",
                "backend/src/main/java/com/pricetrack/exchange/custody/proof/CGlobalProofOverlay.java",
                "backend/src/test/java/com/pricetrack/exchange/custody/proof/CTransactionRiskProofVerifierTest.java",
                "backend/src/test/java/com/pricetrack/exchange/custody/proof/CVaultTransactionProofVerifierTest.java",
                "backend/src/test/java/com/pricetrack/exchange/custody/proof/CControllerControlProofVerifierTest.java",
                "backend/src/test/java/com/pricetrack/exchange/custody/proof/CTokenTransactionProofVerifierTest.java",
                "backend/src/test/java/com/pricetrack/exchange/custody/proof/CGlobalProofOverlayTest.java")]
    elif mode == "token":
        sources += [root / p for p in (
            "backend/src/main/java/com/pricetrack/exchange/custody/abi/CAbiEventModels.java",
            "backend/src/main/java/com/pricetrack/exchange/custody/token/CTokenLedgerModels.java",
            "backend/src/main/java/com/pricetrack/exchange/custody/token/CTokenLedgerReducer.java",
            "backend/src/test/java/com/pricetrack/exchange/custody/token/CTokenLedgerReducerTest.java")]
    elif mode == "envelope":
        sources += [root / p for p in (
            "backend/src/main/java/com/pricetrack/exchange/custody/proof/CSignedTransactionVerifier.java",
            "backend/src/main/java/com/pricetrack/exchange/custody/abi/CAbiCodec.java",
            "backend/src/main/java/com/pricetrack/exchange/custody/abi/CAbiEventModels.java",
            "backend/src/main/java/com/pricetrack/exchange/custody/abi/CAbiEventCodec.java",
            "backend/src/main/java/com/pricetrack/exchange/custody/proof/CReceiptEnvelopeModels.java",
            "backend/src/main/java/com/pricetrack/exchange/custody/proof/CReceiptEnvelopeVerifier.java",
            "backend/src/test/java/com/pricetrack/exchange/custody/proof/CReceiptEnvelopeVerifierTest.java")]
    elif mode == "authorization":
        sources += [root / p for p in (
            "backend/src/main/java/com/pricetrack/exchange/custody/proof/CRulesTransitionModels.java",
            "backend/src/main/java/com/pricetrack/exchange/custody/abi/CAbiEventModels.java",
            "backend/src/main/java/com/pricetrack/exchange/futures/math/FuturesMath.java",
            "backend/src/main/java/com/pricetrack/exchange/custody/proof/CAuthorizationModels.java",
            "backend/src/main/java/com/pricetrack/exchange/custody/proof/CAuthorizationVerifier.java",
            "backend/src/test/java/com/pricetrack/exchange/custody/proof/CAuthorizationVerifierTest.java")]
    elif mode == "rules":
        sources += [root / p for p in (
            "backend/src/main/java/com/pricetrack/exchange/custody/abi/CAbiEventModels.java",
            "backend/src/main/java/com/pricetrack/exchange/custody/proof/CRulesTransitionModels.java",
            "backend/src/main/java/com/pricetrack/exchange/custody/proof/CRulesTransitionVerifier.java",
            "backend/src/main/java/com/pricetrack/exchange/futures/math/FuturesMath.java",
            "backend/src/test/java/com/pricetrack/exchange/custody/proof/CRulesTransitionVerifierTest.java")]
    else:
        sources.append(root / "backend/src/test/java/com/pricetrack/exchange/custody/proof/CEip712VerifierTest.java")
    evidence_sources = [*sources, Path(__file__).resolve(), root / "deployment/c-intent-golden.py",
                        root / "deployment/test_c_proof_check.py"]
    evidence_sources += [root / "contracts/src" / p for p in (
        "CFuturesIntentTypes.sol", "FuturesReportTypes.sol", "CCollateralizedFuturesController.sol", "FuturesEngine.sol", "FuturesMath.sol")]
    if mode in ("token", "envelope", "user", "risk", "vault", "control", "token-call", "overlay"):
        evidence_sources += [root / "contracts/src" / p for p in ("CMockCollateral.sol", "CMarginVault.sol")]
    before = {p.relative_to(root).as_posix(): sha(p) for p in evidence_sources}
    run = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    evidence = root / "deployment/runtime/c-proof" / run
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
                "-Dfile.encoding=UTF-8", "-cp", str(classes) + os.pathsep + cp, "CProofTests"]
    if mode != "eip712":
        test_cmd.append(mode)

    def execute(command, label):
        with (evidence / (label + ".stdout.log")).open("x", encoding="utf-8") as out, (evidence / (label + ".stderr.log")).open("x", encoding="utf-8") as err:
            try:
                return subprocess.run(command, cwd=root, env=env, stdout=out, stderr=err, timeout=120).returncode
            except subprocess.TimeoutExpired:
                return 124
    version = subprocess.run([str(jdk / "java.exe"), "-version"], env=env, capture_output=True, text=True, timeout=10)
    compile_exit = execute(compile_cmd, "compile")
    compile_diagnostics_empty = (evidence / "compile.stderr.log").stat().st_size == 0
    test_exit = execute(test_cmd, "test") if compile_exit == 0 and compile_diagnostics_empty else None
    test_diagnostics_empty = test_exit is not None and (evidence / "test.stderr.log").stat().st_size == 0
    unchanged = before == {p.relative_to(root).as_posix(): sha(p) for p in evidence_sources}
    scope = {"eip712": "PURE_C_EIP712_ONLY", "transaction": "PURE_C_SIGNED_TRANSACTION_ONLY",
             "rules": "PURE_C_RULES_PREVALIDATED_CONTEXT_ONLY",
             "authorization": "PURE_C_AUTHORIZATION_PREVALIDATED_CONTEXT_ONLY",
             "envelope": "PURE_C_RECEIPT_ENVELOPE_PREVALIDATED_CONTEXT_ONLY",
             "token": "PURE_C_TOKEN_LOGS_PREVALIDATED_CONTEXT_ONLY",
             "user": "PURE_C_USER_TRANSACTION_PREVALIDATED_CONTEXT_ONLY",
             "risk": "PURE_C_KEEPER_TRANSACTION_PREVALIDATED_CONTEXT_ONLY",
             "vault": "PURE_C_VAULT_TRANSACTION_PREVALIDATED_CONTEXT_ONLY",
             "control": "PURE_C_CONTROLLER_CONTROL_PREVALIDATED_CONTEXT_ONLY",
             "token-call": "PURE_C_TOKEN_TRANSACTION_PREVALIDATED_CONTEXT_ONLY",
             "overlay": "PURE_C_SAME_BLOCK_OVERLAY_PREVALIDATED_CONTEXT_ONLY"}[mode]
    record = {"run": run, "scope": scope, "sources": before,
              "sourceHash": hashlib.sha256(json.dumps(before, sort_keys=True).encode()).hexdigest(),
              "sourcesUnchanged": unchanged, "dependencies": {p.name: sha(p) for p in jars}, "jdk": version.stderr,
              "compileCommand": compile_cmd, "testCommand": test_cmd, "compileExit": compile_exit,
              "testExit": test_exit, "compileDiagnosticsEmpty": compile_diagnostics_empty,
              "testDiagnosticsEmpty": test_diagnostics_empty, "rpcCalls": 0, "dbCalls": 0, "paidCalls": 0}
    with (evidence / "result.json").open("x", encoding="utf-8") as out:
        json.dump(record, out, indent=2)
    print(json.dumps({k: record[k] for k in ("run", "scope", "sourceHash", "compileExit", "testExit", "sourcesUnchanged")}))
    for p in sorted(evidence.glob("*.log")):
        print(p.name); print(p.read_text(encoding="utf-8", errors="replace")[-16000:])
    return 0 if proof_success(compile_exit, test_exit, unchanged, compile_diagnostics_empty, test_diagnostics_empty) else 1


if __name__ == "__main__":
    raise SystemExit(main())
