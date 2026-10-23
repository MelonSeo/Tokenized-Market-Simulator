"""Pure fullApp read Gate guards. No Docker/JVM/socket or checkpoint claim consumption."""
import copy,hashlib,importlib.util,json,subprocess,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
spec=importlib.util.spec_from_file_location("full_read_gate",Path(__file__).with_name("full-app-read-gate.py"))
gate=importlib.util.module_from_spec(spec);spec.loader.exec_module(gate)
class FullReadPolicyTest(unittest.TestCase):
 def test_readiness_is_bounded_with_unchanged_cleanup_reserve(self):
  self.assertEqual(gate.READY_SECONDS,120);self.assertEqual(gate.WORK_SECONDS,300);self.assertEqual(gate.TOTAL_SECONDS,390)
  self.assertEqual(gate.TOTAL_SECONDS-gate.WORK_SECONDS,90)
  self.assertLess(gate.READY_SECONDS+45+80,gate.WORK_SECONDS)
  wrapper=(gate.ROOT/"deployment/FullAppReadGate.java").read_text(encoding="utf-8")
  for stage in ("JVM_ENTER","SPRING_RUN_START","SPRING_RUN_DONE","POSTCHECK_DONE","READY","STOP","CTX_CLOSED"):
   self.assertIn('"'+stage+'"',wrapper)
  self.assertIn('startup-timing.jsonl',wrapper);self.assertIn('setLogStartupInfo(false)',wrapper)
 def setUp(self):
  self.run="ccpg-"+"a"*16;self.paths={k:Path("C:/fresh/"+k) for k in ("app","driver","config","control","appEvidence","forkEvidence","bootstrapEvidence","verifyEvidence")}
 def info(self,role):
  cmd=gate.launch(role,self.run,self.paths,Path("private.env"));image=gate.PG_IMAGE if role=="pg" else gate.ANVIL_IMAGE if role=="anvil" else gate.JAVA_IMAGE
  mounts=[]
  for i,value in enumerate(cmd):
   if value=="--mount":
    parts=dict(v.split("=",1) for v in cmd[i+1].split(",") if "=" in v)
    mounts.append({"Type":parts["type"],"Destination":parts["target"],"RW":"readonly" not in cmd[i+1].split(","),**({"Name":parts["source"]} if parts["type"]=="volume" else {"Source":parts["source"]})})
  return {"Name":"/"+self.run+"-"+role,"Image":image,"Config":{"Labels":{"tms.c.owner":self.run,"tms.full.read.scope":"NON_USER_READ_INTEGRATION"},
   "User":"" if role=="pg" else "0:0" if role=="anvil" else "10001:10001","WorkingDir":"" if role=="pg" else "/tmp","Entrypoint":["docker-entrypoint.sh"] if role=="pg" else [cmd[cmd.index("--entrypoint")+1]],"Cmd":["postgres"] if role=="pg" else cmd[cmd.index("--entrypoint")+3:]},
   "HostConfig":{"NetworkMode":self.run+"-network","Memory":{"pg":256,"anvil":192,"app":768,"bootstrap":384,"verify":384}[role]*1024**2,
   "ReadonlyRootfs":role!="pg","CapDrop":["NET_RAW"] if role=="pg" else ["ALL"],"SecurityOpt":[] if role=="pg" else ["no-new-privileges"]},
   "NetworkSettings":{"Networks":{self.run+"-network":{}},"Ports":{}},"Mounts":mounts}
 def test_fixed_actors_have_no_host_publish_pull_or_old_wallet_secret_mount(self):
  for role in ("pg","anvil","app","bootstrap","verify"):
   cmd=gate.launch(role,self.run,self.paths,Path("private.env"));gate.validate(self.info(role),role,self.run,self.paths,Path("private.env"))
   for flag in ("-p","--publish","--network=host","-e","--env"):self.assertNotIn(flag,cmd)
   self.assertIn("--pull=never",cmd)
   for value in cmd:self.assertFalse(any(name in value for name in ("oracle-key.private","pg.private.env","keys.private.json","LIVE_USER_OPEN_CLAIM")))
  app=gate.launch("app",self.run,self.paths,Path("owner.env"));self.assertNotIn("--env-file",app);self.assertIn("FullAppReadGate",app)
 def test_isolation_owner_command_mount_and_memory_drift_are_rejected(self):
  for role in ("pg","anvil","app","bootstrap","verify"):
   for mutate in (lambda i:i["HostConfig"].update(Privileged=True),lambda i:i["HostConfig"].update(PortBindings={"8080/tcp":[{}]}),
    lambda i:i["HostConfig"].update(Memory=0),lambda i:i["Config"]["Labels"].update({"tms.c.owner":"old"}),lambda i:i["Mounts"][0].update(RW=not i["Mounts"][0]["RW"])):
    info=self.info(role);mutate(info)
    with self.assertRaises(ValueError):gate.validate(info,role,self.run,self.paths,Path("p"))
 def test_original_snapshot_is_readonly_and_only_new_fork_output_is_writable(self):
  info=self.info("anvil");mounts={m["Destination"]:m for m in info["Mounts"]}
  self.assertFalse(mounts["/snapshot/anvil-state.private.json"]["RW"]);self.assertTrue(mounts["/fork-evidence"]["RW"])
  cmd=gate.launch("anvil",self.run,self.paths,Path("p"));self.assertEqual(cmd[cmd.index("--accounts")+1],"0")
  self.assertNotIn("--timestamp",cmd);self.assertNotIn("eth_sendRawTransaction",cmd)
 def test_full_source_selection_pins_two_intentional_overrides_and_rejects_other_drift(self):
  sources,exceptions=gate.source_map(gate.ROOT);self.assertEqual(len(sources),310);self.assertEqual(len(exceptions),2)
  self.assertTrue(all(p.endswith(("FaucetStore.java","ConditionalSchema.java")) for p in exceptions))
  original=gate.sha
  def drift(p):return "0"*64 if p.name=="FaucetStore.java" else original(p)
  with patch.object(gate,"sha",side_effect=drift):
   with self.assertRaisesRegex(ValueError,"COMMON_SOURCE_DRIFT"):gate.source_map(gate.ROOT)
 def test_consumed_claim_and_artifact_drift_are_rejected_before_runtime(self):
  with tempfile.TemporaryDirectory() as tmp:
   root=Path(tmp);identity="20261024T000000Z-"+"b"*8;prep=root/"deployment/runtime/full-read-prepare"/identity
   (prep/"app-fixture").mkdir(parents=True);(prep/"driver-fixture").mkdir()
   value={"status":"PREPARED_NOT_ACTUAL_FULL_APP_GATE","scope":"NON_USER_READ_INTEGRATION","sources":{},"helpers":{},"anchors":{},"appFixtureHashes":{},"driverFixtureHashes":{}}
   value["sourceIdentity"]=hashlib.sha256(json.dumps({k:value[k] for k in ("sources","helpers","anchors")},sort_keys=True).encode()).hexdigest()
   (prep/"result.json").write_text(json.dumps(value),encoding="utf-8")
   with patch.object(gate,"ROOT",root),patch.object(gate,"helpers",return_value={}),patch.object(gate,"anchors",return_value={}):
    gate.checked_prepare(identity)
    (prep/"app-fixture/drift").write_text("different",encoding="ascii")
    with self.assertRaisesRegex(ValueError,"FIXTURE_CLAIM"):gate.checked_prepare(identity)
    (prep/"app-fixture/drift").unlink();(prep/"RUN_CLAIM").write_text("permanent",encoding="ascii")
    with self.assertRaisesRegex(ValueError,"FIXTURE_CLAIM"):gate.checked_prepare(identity)
 def test_rowlock_permission_is_column_limited_and_proof_update_columns_exist(self):
  driver=(gate.ROOT/"deployment/FullAppReadDriver.java").read_text(encoding="utf-8")
  self.assertIn("SELECT,INSERT,UPDATE(nickname) ON public.users",driver);self.assertNotIn("UPDATE(role)",driver);self.assertNotIn("UPDATE(id)",driver)
  sql=(gate.CROOT/"backend/src/main/resources/c-wallet-schema.sql").read_text(encoding="utf-8")
  self.assertIn("consumed BOOLEAN",sql);self.assertIn("proof_signature VARCHAR",sql)
 def test_global_resource_floor_and_safe_observation_reused_without_process(self):
  gate.pg.resources(1920*1024**2,2*1024**3)
  with self.assertRaises(ValueError):gate.pg.resources(1919*1024**2,2*1024**3)
  value=gate.pg.observation(["docker","container","inspect","new"],subprocess.CompletedProcess([],1,"secret","SQL password=secret"))
  self.assertNotIn("secret",str(value))
 def test_off_flags_include_faucet_denial_not_an_invented_faucet_flag(self):
  for flag in ("app.blockchain.enabled","app.blockchain.price-report.enabled","app.futures.enabled","app.reserve.enabled","app.trade-audit.enabled","app.conditional.enabled","app.google.enabled","app.ai.enabled"):self.assertIn(flag,gate.OFF)
  self.assertFalse(any("faucet" in flag for flag in gate.OFF))
if __name__=="__main__":unittest.main()
