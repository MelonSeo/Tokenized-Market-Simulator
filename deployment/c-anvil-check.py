"""C5a1 only. 'offline' never calls Docker/RPC; 'run' is for post-review main execution.
New internal network/owned containers only, no host ports or PG. Stop owned containers;
retain every network, volume, failed fixture and private transaction evidence.
"""
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import uuid
import io
import tarfile
import threading
from c_gate_network import select_subnet

FOUNDRY_IMAGE = "sha256:0c00cb0bda1ab1b91c9a6bf60f4c76c09c1a8870824b6d4718afbabacf6f9a17"
JAVA_IMAGE = "sha256:35be56d41389c9ad515c089dfded911ae9a8bdb362c8a303224666cd358bead3"
CONTRACTS = ("CMockCollateral", "CCollateralizedFuturesController", "CMarginVault", "FuturesEngine")
JAVA_SOURCES = (
    "deployment/CAnvilContractGate.java",
    "deployment/CAnvilRiskClaimsScenario.java",
    "backend/src/main/java/com/pricetrack/exchange/custody/abi/CAbiModels.java",
    "backend/src/main/java/com/pricetrack/exchange/custody/abi/CAbiCodec.java",
    "backend/src/main/java/com/pricetrack/exchange/custody/abi/CAbiEventModels.java",
    "backend/src/main/java/com/pricetrack/exchange/custody/abi/CAbiEventCodec.java",
    "backend/src/main/java/com/pricetrack/exchange/custody/proof/CEip712Verifier.java",
    "backend/src/main/java/com/pricetrack/exchange/futures/math/FuturesMath.java",
)
JARS = ("crypto-4.12.2.jar", "utils-4.12.2.jar", "rlp-4.12.2.jar", "bcprov-jdk18on-1.78.1.jar",
        "jackson-databind-2.17.2.jar", "jackson-core-2.17.2.jar", "jackson-annotations-2.17.2.jar")
RPC_JVM_ARGS = ["-Djdk.httpclient.disableRetryConnect=true", "-Djdk.httpclient.enableAllMethodRetry=false",
                "-Djdk.httpclient.redirects.retrylimit=1", "--add-opens=java.net.http/jdk.internal.net.http=ALL-UNNAMED"]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_new(path, obj):
    with path.open("x", encoding="utf-8") as out:
        json.dump(obj, out, indent=2, ensure_ascii=False)


def clean_env():
    return {k: v for k, v in os.environ.items() if k.upper() in {
        "PATH", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "USERPROFILE", "LOCALAPPDATA", "APPDATA", "PATHEXT", "COMSPEC"}}


def artifact_ranges(artifact):
    bytecode = artifact["deployedBytecode"]["object"]
    if not isinstance(bytecode, str) or not re.fullmatch(r"0x(?:[0-9a-fA-F]{2})+", bytecode):
        raise ValueError("C5_ARTIFACT_BYTECODE")
    size = (len(bytecode) - 2) // 2
    refs = artifact["deployedBytecode"].get("immutableReferences", {})
    if not isinstance(refs, dict) or len(refs) > 64:
        raise ValueError("C5_ARTIFACT_IMMUTABLES")
    ranges = []
    for key, group in refs.items():
        if not re.fullmatch(r"[0-9]+", key) or not isinstance(group, list) or not group or len(group) > 128:
            raise ValueError("C5_ARTIFACT_IMMUTABLES")
        for ref in group:
            at, count = ref.get("start"), ref.get("length")
            if type(at) is not int or type(count) is not int or at < 0 or count != 32 or at + count > size:
                raise ValueError("C5_ARTIFACT_IMMUTABLE_RANGE")
            ranges.append((at, at + count))
    ranges.sort()
    if any(a[1] > b[0] for a, b in zip(ranges, ranges[1:])):
        raise ValueError("C5_ARTIFACT_IMMUTABLE_OVERLAP")
    return ranges


def compiler_metadata(artifact):
    """Solc rawMetadata is authoritative; Forge normalizes contextual remappings/docs/ABI order."""
    raw=json.loads(artifact["rawMetadata"])
    parsed=artifact["metadata"]
    for key in ("compiler","language","sources","version"):
        if raw[key]!=parsed[key]:
            raise ValueError("C5_ARTIFACT_METADATA")
    left=dict(raw["settings"]);right=dict(parsed["settings"])
    for settings in (left,right):
        settings["remappings"]=[x[1:] if x.startswith(":") else x for x in settings.get("remappings",[])]
    if left!=right or raw["language"]!="Solidity":
        raise ValueError("C5_ARTIFACT_SETTINGS")
    def abi_set(value, parsed_transport=False):
        if not isinstance(value,list):
            raise ValueError("C5_ARTIFACT_ABI")
        entries=[]
        for item in value:
            if not isinstance(item,dict):
                raise ValueError("C5_ARTIFACT_ABI_ENTRY")
            item=dict(item)
            # Forge's parsed transport omits only the empty return list of void functions.
            # Normalize on that side only; raw compiler and deployable artifact stay exact.
            if parsed_transport and item.get("type")=="function" and "outputs" not in item:
                item["outputs"]=[]
            entries.append(json.dumps(item,sort_keys=True,separators=(",",":")))
        result=sorted(entries)
        if len(set(result))!=len(result):
            raise ValueError("C5_ARTIFACT_ABI_DUPLICATE")
        return result
    if abi_set(raw["output"]["abi"])!=abi_set(artifact["abi"]) or abi_set(raw["output"]["abi"])!=abi_set(parsed["output"]["abi"],parsed_transport=True):
        raise ValueError("C5_ARTIFACT_ABI")
    return raw


def artifacts(root, fixture, artifact_dir):
    """Copy every compiler-input byte (including external OZ) for Java Keccak verification before RPC."""
    source_map, by_name = [], {}
    compiler = None
    for name in CONTRACTS:
        path = artifact_dir / (name + ".sol") / (name + ".json")
        if path.stat().st_size > 2 * 1024**2:
            raise ValueError("C5_ARTIFACT_SIZE")
        artifact = json.loads(path.read_text(encoding="utf-8"))
        artifact_ranges(artifact)
        metadata = compiler_metadata(artifact)
        version = metadata["compiler"]["version"]
        if not version.startswith("0.8.24+") or (compiler is not None and compiler != version):
            raise ValueError("C5_COMPILER_IDENTITY")
        compiler = version
        target = metadata["settings"]["compilationTarget"]
        if len(target) != 1 or list(target.values()) != [name]:
            raise ValueError("C5_ARTIFACT_TARGET")
        if artifact["bytecode"].get("linkReferences") or artifact["deployedBytecode"].get("linkReferences"):
            raise ValueError("C5_UNSUPPORTED_EXTERNAL_LINK")
        for key, details in metadata["sources"].items():
            if not re.fullmatch(r"0x[0-9a-f]{64}", details["keccak256"]):
                raise ValueError("C5_METADATA_SOURCE_HASH")
            if key in by_name:
                if by_name[key]["keccak256"] != details["keccak256"]:
                    raise ValueError("C5_SOURCE_METADATA_CONFLICT")
                continue
            # Absolute deps allowed only from the exact existing project contracts/lib.
            if key.startswith("src/"):
                source = (root / "contracts" / key).resolve()
                if not source.is_relative_to((root / "contracts/src").resolve()):
                    raise ValueError("C5_SOURCE_SCOPE")
            else:
                source = Path(key).resolve()
                if not source.is_relative_to((root.parents[2] / "contracts/lib").resolve()):
                    raise ValueError("C5_DEP_SOURCE_SCOPE")
            if not source.is_file() or source.stat().st_size > 512 * 1024 or len(source_map) >= 255:
                raise ValueError("C5_SOURCE_SIZE")
            item = {"source": key, "file": f"source-{len(source_map):03d}.bin", "keccak256": details["keccak256"], "sha256": sha(source)}
            shutil.copyfile(source, fixture / item["file"])
            source_map.append(item)
            by_name[key] = item
        write_new(fixture / (name + ".json"), artifact)
    if sum(p.stat().st_size for p in fixture.glob("source-*.bin")) > 8 * 1024**2:
        raise ValueError("C5_SOURCE_AGGREGATE")
    write_new(fixture / "source-map.json", source_map)
    return {"compiler": compiler, "sourceMap": source_map,
            "artifacts": {name: sha(fixture / (name + ".json")) for name in CONTRACTS}}


def fresh_build(root, evidence):
    prior_path=root/"deployment/runtime/c-foundry/20261022T202307Z-0cb29292/result.json"
    known=json.loads(prior_path.read_text(encoding="utf-8"))
    if known.get("sourceHash")!="c42e04888a601d23cab83f9f885723e2bf779d19d8b92c3ce748df8cf1797dac" or not known.get("sourcesUnchanged") or known.get("exitCode")!=0:
        raise ValueError("C5_KNOWN_CONTRACT_BASELINE")
    if any(sha(root/p)!=value for p,value in known["sources"].items()):
        raise ValueError("C5_CONTRACT_BASELINE_CHANGED")
    forge=shutil.which("forge")
    if not forge:
        raise ValueError("C5_OFFLINE_FORGE_MISSING")
    env=clean_env();libs=root.parents[2]/"contracts/lib"
    env["FOUNDRY_REMAPPINGS"]="@openzeppelin/="+(libs/"openzeppelin-contracts").as_posix()+"/\nforge-std/="+(libs/"forge-std/src").as_posix()+"/"
    out=evidence/"fresh-artifacts";cache=evidence/"fresh-cache"
    command=[forge,"build","--offline","--force","-j","1","--out",str(out),"--cache-path",str(cache)]
    with (evidence/"forge.stdout.log").open("xb") as stdout,(evidence/"forge.stderr.log").open("xb") as stderr:
        built=subprocess.run(command,cwd=root/"contracts",env=env,stdout=stdout,stderr=stderr,timeout=180)
    record={"command":command,"exitCode":built.returncode,"contractSources":known["sources"],"knownSourceHash":known["sourceHash"],"sourcesUnchanged":all(sha(root/p)==value for p,value in known["sources"].items()),"offline":True,"testsRun":False,"rpcCalls":0}
    write_new(evidence/"build-result.json",record)
    if built.returncode or not record["sourcesUnchanged"]:
        raise ValueError("C5_FRESH_BUILD_FAILED")
    return out,known["sources"]


def gate_scope(mode):
    if mode not in ("basic", "risk-claims"):
        raise ValueError("C5_MODE")
    return "C5A1_ANVIL_ONLY" if mode == "basic" else "C5A2_RISK_CLAIMS_ANVIL_ONLY"


def gate_result_matches(result, source_identity, mode):
    gate_scope(mode)
    expected = "C5A1_ACTUAL_LOCAL_ANVIL_CONTRACT_ONLY" if mode == "basic" else "C5A2_ACTUAL_LOCAL_ANVIL_RISK_CLAIMS_ONLY"
    return (result.get("result") == "PASS" and result.get("sourceIdentity") == source_identity
            and result.get("scope") == expected
            and (mode != "risk-claims" or (result.get("riskWall60Run") is True and result.get("claimsInsolvencyRun") is True)))


def offline(root, mode="basic"):
    scope = gate_scope(mode)
    run = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ-") + uuid.uuid4().hex[:8]
    evidence = root / "deployment/runtime/c-anvil-offline" / run
    fixture = evidence / "fixture"
    fixture.mkdir(parents=True, exist_ok=False)
    (fixture / "classes").mkdir()
    (fixture / "jars").mkdir()
    result = {"run": run, "scope": scope, "mode": mode, "rpcCalls": 0, "dockerCalls": 0, "paidCalls": 0}
    sources = [*JAVA_SOURCES, "deployment/c-anvil-check.py", "deployment/test_c_anvil_check.py", "deployment/c_gate_network.py"]
    before = {p: sha(root / p) for p in sources}
    for rel in sources:
        snapshot = evidence / "source-snapshot" / rel
        snapshot.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(root / rel, snapshot)
    try:
        artifact_dir,contract_sources = fresh_build(root,evidence)
        provenance = artifacts(root, fixture,artifact_dir)
        result["contractSources"]=contract_sources
        for rel in contract_sources:
            destination=evidence/"source-snapshot"/rel
            destination.parent.mkdir(parents=True,exist_ok=True)
            shutil.copyfile(root/rel,destination)
        args = root.parents[2] / "deployment/runtime/dapp-audit-development-worktree/deployment/runtime/dapp-stage2-offline/java-classpath.args"
        cache = [Path(p) for p in args.read_text(encoding="utf-8-sig").splitlines()[1].strip('"').split(os.pathsep)]
        jars = []
        for name in JARS:
            matches = [p for p in cache if p.name == name]
            if len(matches) != 1 or not matches[0].is_file():
                raise ValueError("C5_CACHED_JAR")
            jars.append(matches[0])
            shutil.copyfile(matches[0], fixture / "jars" / name)
        result.update(provenance)
        result["sources"] = before
        result["dependencies"] = {p.name: sha(p) for p in jars}
        java = Path("C:/Program Files/Java/jdk-21.0.10/bin/java.exe")
        javac = java.with_name("javac.exe")
        compile_cmd = [str(javac), "-J-Xmx192m", "-encoding", "UTF-8", "-cp", os.pathsep.join(map(str, jars)), "-d", str(fixture / "classes"), *[str(root / p) for p in JAVA_SOURCES]]
        compiled = subprocess.run(compile_cmd, cwd=root, env=clean_env(), capture_output=True, timeout=90)
        (evidence / "compile.stdout.log").write_bytes(compiled.stdout)
        (evidence / "compile.stderr.log").write_bytes(compiled.stderr)
        result["compileExit"] = compiled.returncode
        if compiled.returncode or compiled.stderr:
            raise ValueError("C5_OFFLINE_COMPILE")
        command = [str(java), "-Xms32m", "-Xmx192m", "-Dfile.encoding=UTF-8", *RPC_JVM_ARGS, "-cp", str(fixture / "classes") + os.pathsep + os.pathsep.join(map(str, jars)), "CAnvilContractGate", "--self-test",str(fixture)]
        tested = subprocess.run(command, cwd=root, env=clean_env(), capture_output=True, timeout=30)
        (evidence / "self-test.stdout.log").write_bytes(tested.stdout)
        (evidence / "self-test.stderr.log").write_bytes(tested.stderr)
        result["testExit"] = tested.returncode
        if tested.returncode or tested.stderr or tested.stdout.strip() != b"C5A1_OFFLINE_SELF_TEST_PASS":
            raise ValueError("C5_OFFLINE_SELF_TEST")
        if mode == "risk-claims":
            extra = subprocess.run([*command[:-3], "CAnvilRiskClaimsScenario", "--self-test", str(fixture)], cwd=root, env=clean_env(), capture_output=True, timeout=30)
            (evidence / "scenario-self-test.stdout.log").write_bytes(extra.stdout)
            (evidence / "scenario-self-test.stderr.log").write_bytes(extra.stderr)
            result["scenarioTestExit"] = extra.returncode
            if extra.returncode or extra.stderr or extra.stdout.strip() != b"C5A2_OFFLINE_SELF_TEST_PASS":
                raise ValueError("C5_SCENARIO_SELF_TEST")
        py = subprocess.run([sys.executable, "-m", "unittest", "discover", "-s", "deployment", "-p", "test_c_anvil_check.py"], cwd=root, env=clean_env(), capture_output=True, timeout=30)
        (evidence / "python.stdout.log").write_bytes(py.stdout)
        (evidence / "python.stderr.log").write_bytes(py.stderr)
        result["pythonTestExit"] = py.returncode
        if py.returncode:
            raise ValueError("C5_OFFLINE_PYTHON_TEST")
        result["status"] = "OFFLINE_PASS_NOT_ACTUAL_ANVIL"
    except Exception as error:
        result["status"] = "BLOCKED"
        result["error"] = str(error) if type(error) is ValueError else "C5_OFFLINE_SAFE_FAILURE"
    finally:
        result["sourcesUnchanged"] = before == {p: sha(root / p) for p in sources} and all(sha(root/p)==v for p,v in result.get("contractSources",{}).items())
        result["fixtureHashes"] = {p.relative_to(fixture).as_posix(): sha(p) for p in sorted(fixture.rglob("*")) if p.is_file()}
        result["sourceIdentity"] = hashlib.sha256(json.dumps({"sources":before,"scope":scope}, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        write_new(evidence / "result.json", result)
        print(json.dumps({"run": run, "status": result["status"], "error": result.get("error"), "sourcesUnchanged": result["sourcesUnchanged"]}))
    return 0 if result["status"] == "OFFLINE_PASS_NOT_ACTUAL_ANVIL" and result["sourcesUnchanged"] else 1


def launch_args(kind, run, fixture, evidence):
    if not re.fullmatch(r"canvil-[0-9a-f]{16}", run):
        raise ValueError("C5_LAUNCH_IDENTITY")
    base = ["run", "-d", "--pull=never", "--name", run + "-" + kind, "--label", "tms.c.owner=" + run,
            "--network", run + "-network", "--restart=no", "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges",
            "--cpus=1", "--pids-limit=128", "--tmpfs", "/tmp:rw,noexec,nosuid,size=32m", "--log-opt", "max-size=10m", "--log-opt", "max-file=1"]
    if kind == "anvil":
        return base + ["--network-alias", "anvil", "--memory=256m", "--mount", f"type=volume,source={run}-state,target=/state",
                       "--user=0:0", "--entrypoint", "/usr/local/bin/anvil", FOUNDRY_IMAGE, "--host", "0.0.0.0", "--port", "8545", "--chain-id", "31337", "--accounts", "0", "--silent", "--state", "/state/anvil.json", "--state-interval", "1"]
    if kind == "java":
        return base + ["--memory=384m", "--mount", "type=bind,source=" + str(fixture) + ",target=/fixture,readonly",
                       "--mount", "type=bind,source=" + str(evidence) + ",target=/evidence", "--user=10001:10001", "--entrypoint", "java", JAVA_IMAGE,
                       "-Xms32m", "-Xmx192m", "-XX:MaxMetaspaceSize=128m", "-Dfile.encoding=UTF-8", *RPC_JVM_ARGS, "-cp", "/fixture/classes:/fixture/jars/*", "CAnvilContractGate", "/fixture", "/evidence"]
    raise ValueError("C5_LAUNCH_KIND")


def diagnose(root,prior_id):
    """Fresh Java-only diagnostic evidence; cannot authorize live execution or rewrite prior runs."""
    if not re.fullmatch(r"\d{8}T\d{6}Z-[0-9a-f]{8}",prior_id):
        raise ValueError("C5_DIAGNOSTIC_ID")
    old=root/"deployment/runtime/c-anvil-offline"/prior_id
    prior=json.loads((old/"result.json").read_text(encoding="utf-8"))
    if prior.get("compileExit")!=0 or prior.get("testExit")==0:
        raise ValueError("C5_DIAGNOSTIC_PRECONDITION")
    fixture=old/"fixture"
    if {p.relative_to(fixture).as_posix():sha(p) for p in sorted(fixture.rglob("*")) if p.is_file()}!=prior["fixtureHashes"]:
        raise ValueError("C5_DIAGNOSTIC_FIXTURE_CHANGED")
    stamp=datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ-")+uuid.uuid4().hex[:8]
    evidence=root/"deployment/runtime/c-anvil-diagnostic"/stamp
    classes=evidence/"classes";classes.mkdir(parents=True,exist_ok=False)
    jars=[fixture/"jars"/name for name in JARS]
    before={rel:sha(root/rel) for rel in JAVA_SOURCES}
    java=Path("C:/Program Files/Java/jdk-21.0.10/bin/java.exe")
    cp=os.pathsep.join(map(str,jars))
    compiled=subprocess.run([str(java.with_name("javac.exe")),"-J-Xmx192m","-encoding","UTF-8","-cp",cp,"-d",str(classes),*[str(root/p) for p in JAVA_SOURCES]],cwd=root,env=clean_env(),capture_output=True,timeout=90)
    (evidence/"compile.stdout.log").write_bytes(compiled.stdout);(evidence/"compile.stderr.log").write_bytes(compiled.stderr)
    test_exit=None
    if compiled.returncode==0 and not compiled.stderr:
        tested=subprocess.run([str(java),"-Xms32m","-Xmx192m","-Dfile.encoding=UTF-8",*RPC_JVM_ARGS,"-cp",str(classes)+os.pathsep+cp,"CAnvilContractGate","--self-test",str(fixture)],cwd=root,env=clean_env(),capture_output=True,timeout=30)
        (evidence/"self-test.stdout.log").write_bytes(tested.stdout);(evidence/"self-test.stderr.log").write_bytes(tested.stderr);test_exit=tested.returncode
    result={"scope":"C5A1_JAVA_DIAGNOSTIC_ONLY_NOT_LIVE_PRECONDITION","run":stamp,"priorReadOnly":prior_id,"compileExit":compiled.returncode,"testExit":test_exit,"sources":before,"sourcesUnchanged":before=={rel:sha(root/rel) for rel in JAVA_SOURCES},"dockerCalls":0,"rpcCalls":0,"paidCalls":0}
    write_new(evidence/"result.json",result);print(json.dumps(result));return 0 if result["sourcesUnchanged"] and compiled.returncode==0 and test_exit==0 else 1


def validate_container(info,kind,run,fixture,evidence):
    """Inspect actual isolation, not just the requested command line."""
    config,host,net=info["Config"],info["HostConfig"],info["NetworkSettings"]
    expected_entry=["java"] if kind=="java" else ["/usr/local/bin/anvil"]
    if config.get("Labels",{}).get("tms.c.owner")!=run or config.get("Entrypoint")!=expected_entry:
        raise ValueError("C5_CONTAINER_OWNER_ENTRYPOINT")
    if config.get("User")!=("10001:10001" if kind=="java" else "0:0"):
        raise ValueError("C5_CONTAINER_USER")
    if kind=="java" and any(config.get("Cmd",[]).count(arg)!=1 for arg in RPC_JVM_ARGS):
        raise ValueError("C5_CONTAINER_RETRY_POLICY")
    if host.get("PortBindings") or host.get("Privileged") or not host.get("ReadonlyRootfs") or host.get("NetworkMode")!=run+"-network" or set(net.get("Networks",{}))!={run+"-network"}:
        raise ValueError("C5_CONTAINER_ISOLATION")
    if any(value for value in net.get("Ports",{}).values()):
        raise ValueError("C5_CONTAINER_HOST_PORT")
    mounts=info.get("Mounts",[])
    if kind=="anvil":
        if len(mounts)!=1 or mounts[0].get("Type")!="volume" or mounts[0].get("Name")!=run+"-state" or mounts[0].get("Destination")!="/state" or not mounts[0].get("RW"):
            raise ValueError("C5_ANVIL_MOUNT")
    else:
        if len(mounts)!=2:
            raise ValueError("C5_JAVA_MOUNT")
        actual={m["Destination"]:m for m in mounts}
        for target,path,rw in (("/fixture",fixture,False),("/evidence",evidence,True)):
            m=actual.get(target,{})
            source=str(m.get("Source","")).replace("\\","/").rstrip("/").lower()
            expected=str(path.resolve()).replace("\\","/").rstrip("/").lower()
            if m.get("Type")!="bind" or source!=expected or m.get("RW") is not rw:
                raise ValueError("C5_JAVA_MOUNT")


def normal_stop_state(info):
    state=info.get("State",{})
    return state.get("Running") is False and state.get("OOMKilled") is False and type(state.get("ExitCode")) is int and state["ExitCode"]==0 and not state.get("Error")


def extract_state_tar(data):
    limit=16*1024**2
    if len(data)>limit+10240:
        raise ValueError("C5_STATE_ARCHIVE_CAP")
    try:
        with tarfile.open(fileobj=io.BytesIO(data),mode="r:") as archive:
            item=archive.next()
            if item is None or not item.isfile() or item.name!="anvil.json" or item.size<=0 or item.size>limit:
                raise ValueError("C5_STATE_MEMBER")
            content=archive.extractfile(item).read(limit+1)
            if len(content)!=item.size or archive.next() is not None:
                raise ValueError("C5_STATE_ARCHIVE_SHAPE")
            return content
    except (tarfile.TarError, OSError):
        raise ValueError("C5_STATE_ARCHIVE_INVALID") from None


def preserve_state(root,container,target,commands):
    if target.exists() or not re.fullmatch(r"canvil-[0-9a-f]{16}-anvil",container):
        raise ValueError("C5_STATE_COPY_SCOPE")
    command=["docker","cp",container+":/state/anvil.json","-"]
    record={"command":command,"scope":"BOUNDED_PRIVATE_STATE_TAR_COPY_AFTER_NORMAL_STOP"};commands.append(record)
    process=subprocess.Popen(command,cwd=root,env=clean_env(),stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
    read=[]
    def consume():
        try:read.append(process.stdout.read(16*1024**2+10241))
        except OSError:read.append(None)
    worker=threading.Thread(target=consume,daemon=True);worker.start();worker.join(15)
    if worker.is_alive() or not read or read[0] is None or len(read[0])>16*1024**2+10240:
        process.kill();process.wait(timeout=5);record["failure"]="C5_STATE_COPY_TIMEOUT_OR_CAP"
        raise ValueError("C5_STATE_COPY_TIMEOUT_OR_CAP")
    code=process.wait(timeout=5);record["exitCode"]=code
    if code:
        raise ValueError("C5_STATE_COPY_FAILED")
    content=extract_state_tar(read[0])
    with target.open("xb") as output:output.write(content)
    record.update(bytes=len(content),sha256=hashlib.sha256(content).hexdigest())
    return {"bytes":len(content),"sha256":record["sha256"],"scope":"POST_NORMAL_STOP_FILE_PRESERVATION_NOT_RESTART_RECOVERY_PROOF"}


def run_live(root, offline_id, mode="basic"):
    scope = gate_scope(mode)
    if not re.fullmatch(r"\d{8}T\d{6}Z-[0-9a-f]{8}", offline_id):
        raise ValueError("C5_OFFLINE_ID")
    compiled = root / "deployment/runtime/c-anvil-offline" / offline_id
    prior = json.loads((compiled / "result.json").read_text(encoding="utf-8"))
    if prior.get("status") != "OFFLINE_PASS_NOT_ACTUAL_ANVIL" or not prior.get("sourcesUnchanged"):
        raise ValueError("C5_OFFLINE_PRECONDITION")
    if prior.get("scope") != scope or prior.get("mode") != mode or (mode == "risk-claims" and prior.get("scenarioTestExit") != 0):
        raise ValueError("C5_OFFLINE_SCOPE_MISMATCH")
    if any(sha(root / p) != digest for p, digest in prior["sources"].items()):
        raise ValueError("C5_SOURCE_CHANGED")
    if not prior.get("contractSources") or any(sha(root/p)!=value for p,value in prior["contractSources"].items()):
        raise ValueError("C5_CONTRACT_SOURCE_CHANGED")
    if any(sha(Path(s["source"]) if not s["source"].startswith("src/") else root/"contracts"/s["source"])!=s["sha256"] for s in prior["sourceMap"]):
        raise ValueError("C5_DEPENDENCY_SOURCE_CHANGED")
    fixture = compiled / "fixture"
    if {p.relative_to(fixture).as_posix(): sha(p) for p in sorted(fixture.rglob("*")) if p.is_file()} != prior["fixtureHashes"]:
        raise ValueError("C5_COMPILED_ARTIFACT_CHANGED")
    if shutil.disk_usage(root).free < 2 * 1024**3:
        raise ValueError("C5_DISK_LOW")
    run = "canvil-" + uuid.uuid4().hex[:16]
    evidence = root / "deployment/runtime/c-anvil" / run
    evidence.mkdir(parents=True, exist_ok=False)
    private = evidence / "private"
    private.mkdir()
    runtime_fixture = evidence / "fixture"
    shutil.copytree(fixture, runtime_fixture)
    write_new(runtime_fixture / "manifest.json", {"runId": run, "scope": scope, "rpc": "http://anvil:8545", "sourceIdentity": prior["sourceIdentity"]})
    commands, stopped, stop_failures = [], {}, {}
    status, failure = "BLOCKED", None
    state_preserved=None

    def execute(args, timeout=15, check=True):
        # No keys, signatures or raw transactions are passed via Docker command/environment.
        item = {"command": ["docker", *args]}
        commands.append(item)
        try:
            r = subprocess.run(item["command"], cwd=root, env=clean_env(), capture_output=True, text=True, encoding="utf-8", timeout=timeout)
            item.update(exitCode=r.returncode, stdout=r.stdout[:1024*1024], stderr=r.stderr[:1024*1024])
            if check and r.returncode:
                raise ValueError("C5_DOCKER_COMMAND_FAILED")
            return r
        except subprocess.TimeoutExpired:
            item["timeout"] = True
            raise ValueError("C5_DOCKER_TIMEOUT_NO_RETRY") from None

    def owned(name):
        r = execute(["container", "inspect", name, "--format", '{{index .Config.Labels "tms.c.owner"}}'], check=False)
        return r.returncode == 0 and r.stdout.strip() == run

    try:
        ram = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "(Get-CimInstance Win32_OperatingSystem -ErrorAction Stop).FreePhysicalMemory"], env=clean_env(), capture_output=True, text=True, timeout=10)
        if ram.returncode or not ram.stdout.strip().isdigit():
            raise ValueError("C5_RAM_UNAVAILABLE")
        free_bytes = int(ram.stdout.strip())*1024
        write_new(evidence / "resources.json", {"hostFreeMemoryBytes": free_bytes, "hostFreeDiskBytes": shutil.disk_usage(root).free})
        if free_bytes < 1280 * 1024**2:
            raise ValueError("C5_RAM_LOW")
        execute(["version", "--format", "{{.Server.Version}}"])
        execute(["ps", "-a", "--format", "{{.ID}}|{{.Names}}|{{.Image}}|{{.Status}}"])
        execute(["volume", "ls", "--format", "{{.Name}}"])
        network_ids = execute(["network", "ls", "-q"]).stdout.splitlines()
        if len(network_ids)>1024 or any(not re.fullmatch(r"[0-9a-f]{12,64}", x) for x in network_ids):
            raise ValueError("C5_NETWORK_LIST")
        occupied = []
        for first in range(0, len(network_ids), 64):
            r = execute(["network", "inspect", *network_ids[first:first+64], "--format", "{{json .IPAM.Config}}"])
            for line in r.stdout.splitlines():
                occupied += [x["Subnet"] for x in json.loads(line) or [] if x.get("Subnet")]
        routes = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "@(Get-NetRoute -AddressFamily IPv4 -ErrorAction Stop | Select-Object -ExpandProperty DestinationPrefix) | ConvertTo-Json -Compress"], env=clean_env(), capture_output=True, text=True, timeout=10)
        if routes.returncode:
            raise ValueError("C5_ROUTE_INVENTORY")
        route_values=json.loads(routes.stdout)
        subnet=select_subnet(occupied, [route_values] if isinstance(route_values,str) else route_values)
        for image in (FOUNDRY_IMAGE, JAVA_IMAGE):
            info=json.loads(execute(["image", "inspect", image]).stdout)
            if len(info)!=1 or info[0].get("Os")!="linux" or info[0].get("Architecture")!="amd64":
                raise ValueError("C5_IMAGE_PLATFORM")
            if image==JAVA_IMAGE and info[0]["Id"]!=JAVA_IMAGE:
                raise ValueError("C5_JAVA_IMAGE")
            if image==FOUNDRY_IMAGE and info[0]["Id"]!=FOUNDRY_IMAGE:
                raise ValueError("C5_FOUNDRY_IMAGE")
        for kind, name in (("network",run+"-network"),("volume",run+"-state"),("container",run+"-anvil"),("container",run+"-java")):
            if execute([kind,"inspect",name],check=False).returncode==0:
                raise ValueError("C5_RESOURCE_EXISTS")
        execute(["network","create","--internal","--subnet",subnet,"--label","tms.c.owner="+run,run+"-network"])
        execute(["volume","create","--label","tms.c.owner="+run,run+"-state"])
        network=json.loads(execute(["network","inspect",run+"-network"]).stdout)[0]
        volume=json.loads(execute(["volume","inspect",run+"-state"]).stdout)[0]
        if not network.get("Internal") or network.get("Labels",{}).get("tms.c.owner")!=run or [v.get("Subnet") for v in network.get("IPAM",{}).get("Config",[])]!=[subnet] or volume.get("Labels",{}).get("tms.c.owner")!=run:
            raise ValueError("C5_CREATED_RESOURCE_ISOLATION")
        execute(launch_args("anvil",run,runtime_fixture,private))
        if not owned(run+"-anvil"):
            raise ValueError("C5_ANVIL_OWNER")
        validate_container(json.loads(execute(["container","inspect",run+"-anvil"]).stdout)[0],"anvil",run,runtime_fixture,private)
        # Explicit actual binary path; no fallback to shell/default entrypoint or another path.
        version=execute(["exec",run+"-anvil","/usr/local/bin/anvil","--version"])
        if "anvil" not in version.stdout.lower():
            raise ValueError("C5_ANVIL_BINARY")
        # New owned volume only. No chmod/chown and no old filesystem mutation.
        execute(["exec",run+"-anvil","/bin/sh","-c","test \"$(id -u)\" = 0 && test -w /state && umask 077 && printf '%s' C5A1_NEW_STATE_WRITE_PROBE > /state/c5a1-write-probe && test -s /state/c5a1-write-probe"])
        time.sleep(1)
        execute(launch_args("java",run,runtime_fixture,private))
        if not owned(run+"-java"):
            raise ValueError("C5_JAVA_OWNER")
        validate_container(json.loads(execute(["container","inspect",run+"-java"]).stdout)[0],"java",run,runtime_fixture,private)
        wait=execute(["wait",run+"-java"],timeout=300)
        execute(["logs","--tail","20",run+"-java"],check=False)
        if wait.stdout.strip()!="0" or not (private/"gate-result.json").is_file():
            raise ValueError("C5_CONTRACT_GATE_FAILED")
        result=json.loads((private/"gate-result.json").read_text(encoding="utf-8"))
        if not gate_result_matches(result, prior["sourceIdentity"], mode):
            raise ValueError("C5_GATE_RESULT_IDENTITY")
        status="C5A1_CONTRACT_PASS_NOT_C5A2_OR_FULL_AUDIT" if mode == "basic" else "C5A2_CONTRACT_PASS_NOT_SERVER_OR_FULL_AUDIT"
    except Exception as error:
        failure=str(error) if type(error) is ValueError else "C5_SAFE_FAILURE"
    finally:
        # Only stop exact labelled resources; never remove containers/networks/volumes/evidence.
        for kind in ("java","anvil"):
            name=run+"-"+kind
            try:
                if owned(name):
                    r=execute(["stop","--time","10",name],timeout=20,check=False)
                    info=json.loads(execute(["container","inspect",name]).stdout)[0]
                    stopped[name]=r.returncode==0 and normal_stop_state(info)
                    if not stopped[name]:stop_failures[name]="C5_NONZERO_OR_UNSAFE_CONTAINER_EXIT"
                    if kind=="anvil" and stopped[name]:
                        state_preserved=preserve_state(root,name,private/"anvil-stopped-state.private.json",commands)
            except Exception as stop_error:
                stopped[name]=False
                code=str(stop_error)
                stop_failures[name]=code if type(stop_error) is ValueError and re.fullmatch(r"C5_[A-Z0-9_]+",code) else "C5_STOP_OR_PRESERVATION_SAFE_FAILURE"
        unchanged=all(sha(root/p)==digest for p,digest in {**prior["sources"],**prior["contractSources"]}.items()) and all(sha(Path(s["source"]) if not s["source"].startswith("src/") else root/"contracts"/s["source"])==s["sha256"] for s in prior["sourceMap"])
        passed = status.startswith("C5A1_CONTRACT_PASS") or status.startswith("C5A2_CONTRACT_PASS")
        if not unchanged or any(not s for s in stopped.values()) or (passed and state_preserved is None):
            status="BLOCKED"
        write_new(evidence/"result.json",{"run":run,"scope":scope,"status":status,"failure":failure,"sourceIdentity":prior["sourceIdentity"],"sourcesUnchanged":unchanged,"ownedContainersStopped":stopped,"stopOrPreservationFailures":stop_failures,"anvilStatePreserved":state_preserved,"commands":commands,"postgresCalls":0,"paidCalls":0,"realMetaMask":False,"riskWall60Run":mode=="risk-claims" and passed,"claimsInsolvencyRun":mode=="risk-claims" and passed})
        print(json.dumps({"run":run,"status":status,"failure":failure}))
    return 0 if status.startswith(("C5A1_CONTRACT_PASS","C5A2_CONTRACT_PASS")) else 1


def main():
    sys.stdout.reconfigure(encoding="utf-8",errors="backslashreplace")
    root=Path(__file__).resolve().parents[1]
    if sys.argv[1:]==["offline"]:
        return offline(root)
    if sys.argv[1:]==["offline-risk-claims"]:
        return offline(root,"risk-claims")
    if len(sys.argv)==3 and sys.argv[1]=="run":
        return run_live(root,sys.argv[2])
    if len(sys.argv)==3 and sys.argv[1]=="run-risk-claims":
        return run_live(root,sys.argv[2],"risk-claims")
    if len(sys.argv)==3 and sys.argv[1]=="diagnose":
        return diagnose(root,sys.argv[2])
    raise SystemExit("USAGE: c-anvil-check.py offline | run <offline-run-id>; run requires completed independent review")


if __name__=="__main__":
    sys.exit(main())
