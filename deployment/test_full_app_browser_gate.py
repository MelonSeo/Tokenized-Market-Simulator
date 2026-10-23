"""Pure boundaries for the single TEST clone browser attempt; no Docker/Chrome."""
import copy,importlib.util,json,tempfile,unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location("browser_gate",Path(__file__).with_name("full-app-browser-gate.py"));gate=importlib.util.module_from_spec(spec);spec.loader.exec_module(gate)
old=gate.module("read_gate_pure_fixture",gate.ROOT/"deployment/test_full_app_read_gate.py")
class BrowserBoundaryTest(unittest.TestCase):
 def setUp(self):
  self.fixture=old.FullReadPolicyTest();self.fixture.setUp();self.run=self.fixture.run;self.paths=self.fixture.paths
  self.paths["helper"]=Path("C:/fresh/helper")
 def test_all_actors_reject_publish_and_only_helper_ro_mount_is_added(self):
  for role in ("pg","anvil","app"):
   cmd=gate.launch(role,self.run,self.paths,Path("private.env"))
   self.assertNotIn("--publish",cmd);self.assertNotIn("-p",cmd)
   info=self.fixture.info(role)
   if role=="app":
    info["Mounts"].append({"Type":"bind","Source":str(self.paths["helper"].resolve()),"Destination":"/server-fixture","RW":False})
    gate.validate(info,role,self.run,self.paths,Path("p"))
    for host in ("127.0.0.1","0.0.0.0","::"):
     drift=copy.deepcopy(info);drift["HostConfig"]["PortBindings"]={"8080/tcp":[{"HostIp":host,"HostPort":"49123"}]}
     with self.assertRaises(ValueError):gate.validate(drift,role,self.run,self.paths,Path("p"))
    info["Mounts"][-1]["RW"]=True
    with self.assertRaises(ValueError):gate.validate(info,role,self.run,self.paths,Path("p"))
   else:gate.validate(info,role,self.run,self.paths,Path("p"))
 def test_cold_copy_reuses_exact_reviewed_script_and_only_test_volume(self):
  cmd=gate.copy_cmd(self.run);self.assertEqual(cmd[-1],gate.resume.COPY)
  self.assertIn("type=volume,source="+gate.DATA_RUN+"-data,target=/from,readonly",cmd)
  self.assertIn("type=volume,source="+self.run+"-data,target=/to",cmd)
  self.assertNotIn("--publish",cmd);self.assertEqual(cmd[cmd.index("--network")+1],"none")
 def test_final_raw_hash_actor_cannot_write_source_or_use_network(self):
  cmd=gate.hash_cmd(self.run);self.assertIn("type=volume,source="+gate.DATA_RUN+"-data,target=/from,readonly",cmd)
  self.assertNotIn("CHOWN",cmd);self.assertNotIn("FOWNER",cmd);self.assertNotIn("--publish",cmd)
  self.assertIn("test ! -e /from/postmaster.pid",gate.RAW_HASH)
 def test_budgets_and_accepted_source_are_fixed_without_recompiling(self):
  self.assertEqual((gate.WORK,gate.TOTAL,gate.READY,gate.BROWSER),(310,400,120,100))
  self.assertEqual(gate.TOTAL-gate.WORK,90)
  _,r,_=gate.accepted();self.assertEqual(r["sourceIdentity"],gate.SOURCE);self.assertEqual(r["fullProductionSourceCount"],310)
 def test_adapter_has_no_original_environment_or_key_reference(self):
  source=Path(gate.__file__).read_text(encoding="utf-8")
  for forbidden in ("oracle-key.private","keys.private.json","LIVE_USER_OPEN_CLAIM","run_gate(PREPARE)"):
   self.assertNotIn(forbidden,source)
  self.assertIn("TARGET_ADMIN_ONLY",source);self.assertIn("PRE_NODE_IDENTITY",source)
 def test_app_only_relay_is_bounded_and_transport_is_original_source(self):
  source=Path(gate.__file__).read_text(encoding="utf-8")
  self.assertIn('listener.bind(("127.0.0.1",0))',source);self.assertNotIn("SO_REUSEADDR",source)
  self.assertIn('self.slots={"APP":threading.BoundedSemaphore(4)}',source);self.assertIn('self.max_connections=64',source)
  self.assertIn('pipe_command(self.meta["appId"],"APP")',source);self.assertNotIn('pipe_command(self.meta["appId"],"RPC")',source)
  self.assertIn('"-parameters"',source);self.assertIn('"CDirectBrowserPipe","--self-test"',source)
 def test_dist_manifest_excludes_smoke_and_has_stable_relative_keys(self):
  result=gate.dist_hashes();self.assertIn("index.html",result);self.assertEqual(list(result),sorted(result))
  self.assertFalse(any(".smoke" in Path(k).parts for k in result))
 def test_browser_pass_requires_cleanup_exact_images_and_dist_hash(self):
  with tempfile.TemporaryDirectory() as tmp:
   out=Path(tmp);images=[]
   for name in ("login-dialog","market","market-chart","futures","custody","ai","spot-form","c-account-metrics","admin","admin-audit-off"):
    p=out/(name+".png");p.write_bytes(b"\x89PNG\r\n\x1a\n");images.append({"name":name,"sha256":gate.base.sha(p)})
   r={"verdict":"PASS","scope":"ACTUAL_SERVICE_READ_ONLY_TEST_WALLET_ADAPTER_NOT_METAMASK_NOT_TRADING","sourceIdentity":"source","distUnchanged":True,"accountRendered":True,"ownSpawnExited":True,"serverClosed":True,"traceFailed":False,"runtimeError":False,"externalRequestsAllowed":False,"financialCalls":0,"paidCalls":0,"deniedSigning":0,"wsUpgrades":1,"images":images,"pages":[{"route":n,"rendered":True} for n in ("market","futures","custody","ai","admin")],"distBefore":{"index.html":"hash"}}
   r["api"]=[{"path":p,"status":200} for p in ("/api/auth/login","/api/me","/api/markets/mSEC","/api/c-futures/config","/api/c-futures/account")]
   for page in r["pages"]:page.update(api=[],controls=[])
   (out/"result.json").write_text(json.dumps(r));gate.browser_result(out,"source",r["distBefore"])
   for key,value in (("ownSpawnExited",False),("sourceIdentity","wrong"),("distBefore",{}),("images",images[:-1])):
    bad={**r,key:value};(out/"result.json").write_text(json.dumps(bad))
    with self.assertRaises(ValueError):gate.browser_result(out,"source",r["distBefore"])
if __name__=="__main__":unittest.main()
