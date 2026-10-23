"""Pure C5a1 orchestration guards. No actual Docker, sockets or RPC."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC=importlib.util.spec_from_file_location("c_anvil_check",Path(__file__).with_name("c-anvil-check.py"))
gate=importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)


class CAnvilOfflineGuards(unittest.TestCase):
    def test_scope_is_explicit_and_unknown_mode_rejected(self):
        self.assertEqual("C5A1_ANVIL_ONLY", gate.gate_scope("basic"))
        self.assertEqual("C5A2_RISK_CLAIMS_ANVIL_ONLY", gate.gate_scope("risk-claims"))
        for mode in (None, "", "RISK", "other"):
            with self.subTest(mode=mode), self.assertRaisesRegex(ValueError, "C5_MODE"):
                gate.gate_scope(mode)

    def test_basic_success_never_becomes_risk_claims_success(self):
        basic={"result":"PASS", "scope":"C5A1_ACTUAL_LOCAL_ANVIL_CONTRACT_ONLY", "sourceIdentity":"a"*64}
        self.assertTrue(gate.gate_result_matches(basic, "a"*64, "basic"))
        self.assertFalse(gate.gate_result_matches(basic, "a"*64, "risk-claims"))
        full={**basic, "scope":"C5A2_ACTUAL_LOCAL_ANVIL_RISK_CLAIMS_ONLY", "riskWall60Run":True, "claimsInsolvencyRun":True}
        self.assertTrue(gate.gate_result_matches(full, "a"*64, "risk-claims"))
        self.assertFalse(gate.gate_result_matches(full, "a"*64, "basic"))
        for field in ("riskWall60Run", "claimsInsolvencyRun"):
            for bad in (False, 1, "true", None):
                self.assertFalse(gate.gate_result_matches({**full, field:bad}, "a"*64, "risk-claims"))
        self.assertFalse(gate.gate_result_matches(full, "b"*64, "risk-claims"))

    def metadata(self):
        a={"compiler":{"version":"0.8.24+commit"},"language":"Solidity","sources":{"src/A.sol":{"keccak256":"0x"+"a"*64}},"version":1,"settings":{"remappings":[":@openzeppelin/=cached/"],"optimizer":{"enabled":True,"runs":200}},"output":{"abi":[{"type":"function","name":"a","outputs":[]},{"type":"function","name":"b","outputs":[{"type":"uint256","name":"","internalType":"uint256"}]}],"userdoc":{"notice":"compiler full docs"}}}
        b=json.loads(json.dumps(a));b["settings"]["remappings"]=["@openzeppelin/=cached/"];b["output"]["abi"].reverse();b["output"]["userdoc"]={}
        return {"rawMetadata":json.dumps(a),"metadata":b,"abi":list(reversed(a["output"]["abi"]))}

    def test_forge_transport_normalization_does_not_change_compiler_inputs(self):
        a=self.metadata();self.assertEqual(json.loads(a["rawMetadata"]),gate.compiler_metadata(a))

    def test_actual_settings_source_or_abi_changes_are_rejected(self):
        for kind in ("settings","sources","abi","compiler"):
            a=self.metadata()
            if kind=="settings":a["metadata"]["settings"]["optimizer"]["runs"]=201
            elif kind=="sources":a["metadata"]["sources"]={}
            elif kind=="compiler":a["metadata"]["compiler"]["version"]="unknown"
            else:a["abi"][0]["name"]="different"
            with self.subTest(kind=kind),self.assertRaises(ValueError):gate.compiler_metadata(a)

    def test_only_parsed_empty_function_outputs_can_be_omitted(self):
        a=self.metadata();void=next(x for x in a["metadata"]["output"]["abi"] if x["name"]=="a");del void["outputs"]
        gate.compiler_metadata(a)
        returning=next(x for x in a["metadata"]["output"]["abi"] if x["name"]=="b");del returning["outputs"]
        with self.assertRaisesRegex(ValueError,"ARTIFACT_ABI"):gate.compiler_metadata(a)
        a=self.metadata();del a["abi"][0]["outputs"]
        with self.assertRaisesRegex(ValueError,"ARTIFACT_ABI"):gate.compiler_metadata(a)

    def test_actual_preserved_fresh_artifact_metadata_all_four(self):
        # Read-only reproduction against the failed run; never overwrite or reuse it for live execution.
        root=Path(__file__).resolve().parents[1]
        existing=root/"deployment/runtime/c-anvil-offline/20261023T053010Z-3239f38a/fresh-artifacts"
        observed_omissions=0
        for name in gate.CONTRACTS:
            path=existing/(name+".sol")/(name+".json")
            artifact=json.loads(path.read_text(encoding="utf-8"))
            expected=json.loads(artifact["rawMetadata"])
            with self.subTest(name=name):
                self.assertEqual(expected,gate.compiler_metadata(artifact))
                gate.artifact_ranges(artifact)
            observed_omissions+=sum(x.get("type")=="function" and "outputs" not in x for x in artifact["metadata"]["output"]["abi"])
        self.assertGreater(observed_omissions,0)

    def artifact(self, refs=None):
        return {"deployedBytecode":{"object":"0x"+"00"*96,"immutableReferences":{} if refs is None else refs}}

    def test_no_immutables_valid(self):
        self.assertEqual([],gate.artifact_ranges(self.artifact()))

    def test_disjoint_sorted_ranges(self):
        self.assertEqual([(0,32),(64,96)],gate.artifact_ranges(self.artifact({"2":[{"start":64,"length":32}],"1":[{"start":0,"length":32}]})))

    def test_overlap_refused(self):
        with self.assertRaisesRegex(ValueError,"OVERLAP"):
            gate.artifact_ranges(self.artifact({"1":[{"start":0,"length":32}],"2":[{"start":31,"length":32}]}))

    def test_wrong_width_negative_and_outside_refused(self):
        for at,count in [(-1,32),(65,32),(0,31),(True,32),(0,True)]:
            with self.subTest(at=at,count=count),self.assertRaises(ValueError):
                gate.artifact_ranges(self.artifact({"1":[{"start":at,"length":count}]}))

    def test_unknown_immutable_shape_refused(self):
        for refs in [[],{"not-an-id":[{"start":0,"length":32}]},{"1":[]},{"1":None}]:
            with self.subTest(refs=refs),self.assertRaises(ValueError):
                gate.artifact_ranges(self.artifact(refs))

    def test_bad_bytecode_refused(self):
        for value in ["0x", "0x0", "0x__library__", "ABC", None]:
            with self.subTest(value=value),self.assertRaises(ValueError):
                a=self.artifact();a["deployedBytecode"]["object"]=value;gate.artifact_ranges(a)

    def test_java_explicit_entrypoint_and_memory(self):
        args=gate.launch_args("java","canvil-"+"a"*16,Path("fixture"),Path("evidence"))
        self.assertEqual("java",args[args.index("--entrypoint")+1]);self.assertIn("--memory=384m",args)
        self.assertEqual(["/fixture","/evidence"],args[-2:]);self.assertIn("-Xmx192m",args)
        self.assertIn("--user=10001:10001",args)
        for flag in gate.RPC_JVM_ARGS:self.assertEqual(1,args.count(flag))

    def test_anvil_explicit_binary_no_default_private_accounts(self):
        args=gate.launch_args("anvil","canvil-"+"a"*16,Path("fixture"),Path("evidence"))
        self.assertEqual("/usr/local/bin/anvil",args[args.index("--entrypoint")+1])
        self.assertEqual("0",args[args.index("--accounts")+1]);self.assertIn("--silent",args)
        self.assertIn("--state",args);self.assertNotIn("--disable-code-size-limit",args)
        self.assertIn("--user=0:0",args)

    def test_jdk_internal_and_application_retry_controls_all_explicit(self):
        self.assertIn("-Djdk.httpclient.disableRetryConnect=true",gate.RPC_JVM_ARGS)
        self.assertIn("-Djdk.httpclient.enableAllMethodRetry=false",gate.RPC_JVM_ARGS)
        self.assertIn("-Djdk.httpclient.redirects.retrylimit=1",gate.RPC_JVM_ARGS)
        self.assertIn("--add-opens=java.net.http/jdk.internal.net.http=ALL-UNNAMED",gate.RPC_JVM_ARGS)

    def container(self):
        run="canvil-"+"a"*16;fixture=Path("fixture").resolve();evidence=Path("evidence").resolve()
        info={"Config":{"User":"10001:10001","Entrypoint":["java"],"Cmd":list(gate.RPC_JVM_ARGS),"Labels":{"tms.c.owner":run}},"HostConfig":{"PortBindings":{},"Privileged":False,"ReadonlyRootfs":True,"NetworkMode":run+"-network"},"NetworkSettings":{"Networks":{run+"-network":{}},"Ports":{}},"Mounts":[{"Destination":"/fixture","Type":"bind","Source":str(fixture),"RW":False},{"Destination":"/evidence","Type":"bind","Source":str(evidence),"RW":True}]}
        return info,run,fixture,evidence

    def test_actual_java_container_inspection_accepts_only_expected_identity(self):
        info,run,f,e=self.container();gate.validate_container(info,"java",run,f,e)
        for field,value in [("User","0:0"),("Entrypoint",["java","-jar"]),("Cmd",[])]:
            altered=json.loads(json.dumps(info));altered["Config"][field]=value
            with self.subTest(field=field),self.assertRaises(ValueError):gate.validate_container(altered,"java",run,f,e)

    def test_actual_inspection_rejects_port_network_and_mount_drift(self):
        info,run,f,e=self.container()
        mutations=[lambda x:x["HostConfig"].update(PortBindings={"8545/tcp":[{"HostPort":"8545"}]}),lambda x:x["NetworkSettings"]["Networks"].update(old={}),lambda x:x["Mounts"][0].update(RW=True),lambda x:x["Mounts"][1].update(Source=str(Path("old-evidence").resolve()))]
        for mutate in mutations:
            altered=json.loads(json.dumps(info));mutate(altered)
            with self.assertRaises(ValueError):gate.validate_container(altered,"java",run,f,e)

    def test_actual_anvil_inspection_requires_only_new_volume_and_root(self):
        info,run,f,e=self.container();info["Config"].update(User="0:0",Entrypoint=["/usr/local/bin/anvil"]);info["Mounts"]=[{"Destination":"/state","Type":"volume","Name":run+"-state","RW":True}]
        gate.validate_container(info,"anvil",run,f,e)
        info["Mounts"][0]["Name"]="old-state"
        with self.assertRaises(ValueError):gate.validate_container(info,"anvil",run,f,e)

    def test_stop_return_zero_does_not_replace_normal_exit_and_no_oom(self):
        state={"Running":False,"OOMKilled":False,"ExitCode":0,"Error":""}
        self.assertTrue(gate.normal_stop_state({"State":state}))
        for field,value in [("Running",True),("OOMKilled",True),("ExitCode",137),("ExitCode",143),("ExitCode",True),("Error","sentinel")]:
            bad=dict(state);bad[field]=value
            with self.subTest(field=field,value=value):self.assertFalse(gate.normal_stop_state({"State":bad}))

    def state_tar(self, payload=b'{"new-state":true}', name="anvil.json", symlink=False):
        data=gate.io.BytesIO()
        with gate.tarfile.open(fileobj=data,mode="w") as archive:
            info=gate.tarfile.TarInfo(name)
            if symlink:info.type=gate.tarfile.SYMTYPE;info.linkname="/old/state"
            else:info.size=len(payload)
            archive.addfile(info,None if symlink else gate.io.BytesIO(payload))
        return data.getvalue()

    def test_bounded_state_preservation_accepts_file_without_extracting_paths(self):
        self.assertEqual(b'{"new-state":true}',gate.extract_state_tar(self.state_tar()))

    def test_state_copy_rejects_traversal_links_empty_and_oversized_member(self):
        for data in [self.state_tar(name="../anvil.json"),self.state_tar(symlink=True),self.state_tar(payload=b""),b"invalid archive"]:
            with self.assertRaises(ValueError):gate.extract_state_tar(data)
        info=gate.tarfile.TarInfo("anvil.json");info.size=16*1024**2+1
        with self.assertRaises(ValueError):gate.extract_state_tar(info.tobuf()+b"\0"*1024)

    def test_no_host_ports_network_or_privileged_mounts(self):
        for kind in ("java","anvil"):
            args=gate.launch_args(kind,"canvil-"+"a"*16,Path("fixture"),Path("evidence"))
            for forbidden in ("-p","--publish","--network=host","--privileged","--rm"):
                self.assertNotIn(forbidden,args)
            self.assertEqual("canvil-"+"a"*16+"-network",args[args.index("--network")+1])
            self.assertIn("--pull=never",args);self.assertIn("--read-only",args)

    def test_cached_images_are_image_ids_not_invented_repo_digest(self):
        for image in (gate.FOUNDRY_IMAGE,gate.JAVA_IMAGE):
            self.assertRegex(image,r"^sha256:[0-9a-f]{64}$")

    def test_bad_run_identity_and_kind_refused(self):
        for run in ("../old", "canvil-"+"A"*16, "cpg-"+"a"*16):
            with self.assertRaises(ValueError):gate.launch_args("java",run,Path("f"),Path("e"))
        with self.assertRaises(ValueError):gate.launch_args("pg","canvil-"+"a"*16,Path("f"),Path("e"))

    def test_environment_excludes_secrets_proxy_java_injection(self):
        with patch.dict(gate.os.environ,{"OPENAI_API_KEY":"sentinel","HTTP_PROXY":"secret","JAVA_TOOL_OPTIONS":"secret","_JAVA_OPTIONS":"secret","PATH":"expected"},clear=True):
            self.assertEqual({"PATH":"expected"},gate.clean_env())

    def test_evidence_append_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/"result.json";gate.write_new(p,{"status":"first"})
            with self.assertRaises(FileExistsError):gate.write_new(p,{"status":"overwritten"})
            self.assertEqual("first",json.loads(p.read_text())["status"])

    def test_invalid_offline_id_blocks_before_any_process(self):
        with patch.object(gate.subprocess,"run",side_effect=AssertionError("must not call process")):
            with self.assertRaises(ValueError):gate.run_live(Path("."),"../old")

    def test_precondition_cannot_be_reset_by_a_new_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);prior=root/"deployment/runtime/c-anvil-offline/20261023T000000Z-12345678";prior.mkdir(parents=True)
            gate.write_new(prior/"result.json",{"status":"BLOCKED","sourcesUnchanged":True})
            with patch.object(gate.subprocess,"run",side_effect=AssertionError("must not call process")):
                with self.assertRaisesRegex(ValueError,"PRECONDITION"):gate.run_live(root,"20261023T000000Z-12345678")


if __name__=="__main__":
    unittest.main()
