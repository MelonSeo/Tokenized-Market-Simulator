"""Pure guard regression: no process, socket, PG or existing claim access."""
import copy,importlib.util,json,subprocess,tempfile,unittest,contextlib,io
from types import SimpleNamespace
from pathlib import Path
from unittest.mock import patch
spec=importlib.util.spec_from_file_location("schema_pg_unit",Path(__file__).with_name("schema-startup-postgres-check.py"))
gate=importlib.util.module_from_spec(spec);spec.loader.exec_module(gate)

class SchemaPostgresGuardTest(unittest.TestCase):
 def setUp(self):self.run="ccpg-"+"a"*16;self.fixture=Path("C:/new/fixture");self.evidence=Path("C:/new/evidence")
 def info(self,kind):
  java=kind=="java";cmd=gate.args(kind,self.run,self.fixture,self.evidence,Path("private.env"));image=gate.JAVA_IMAGE if java else gate.PG_IMAGE
  return {"Image":image,"Config":{"Labels":{"tms.c.owner":self.run},"User":"10001:10001" if java else "",
   "Entrypoint":["java"] if java else ["docker-entrypoint.sh"],"Cmd":cmd[cmd.index(image)+1:] if java else ["postgres"]},
   "HostConfig":{"NetworkMode":self.run+"-network","Memory":(384 if java else 256)*1024**2,"Privileged":False,"ReadonlyRootfs":java,
   "CapDrop":["ALL"] if java else ["CAP_NET_RAW"],"SecurityOpt":["no-new-privileges"] if java else []},
   "NetworkSettings":{"Networks":{self.run+"-network":{}},"Ports":{}},
   "Mounts":[{"Type":"bind","Source":str(self.fixture.resolve()),"Destination":"/fixture","RW":False},
    {"Type":"bind","Source":str(self.evidence.resolve()),"Destination":"/evidence","RW":True}] if java else
    [{"Type":"volume","Name":self.run+"-data","Destination":"/var/lib/postgresql/data","RW":True}]}
 def test_resource_minimum_and_namespace_are_fixed(self):
  gate.resources(1920*1024**2,2*1024**3)
  for ram,disk in ((1919*1024**2,2*1024**3),(1920*1024**2,2*1024**3-1)):
   with self.assertRaises(ValueError):gate.resources(ram,disk)
  for bad in ("cdemo-"+"a"*16,"exchange","../old"):
   with self.assertRaises(ValueError):gate.args("pg",bad,self.fixture,self.evidence,Path("p"))
 def test_new_entrypoint_no_old_six_test_or_inline_secret_reuse(self):
  for kind in ("pg","java"):
   cmd=gate.args(kind,self.run,self.fixture,self.evidence,Path("private.env"))
   for forbidden in ("-p","--publish","--network=host","-e","--env","CConsentPostgresTests"):self.assertNotIn(forbidden,cmd)
   self.assertIn("--pull=never",cmd);gate.validate(self.info(kind),kind,self.run,self.fixture,self.evidence)
  self.assertEqual(gate.args("java",self.run,self.fixture,self.evidence,Path("p"))[-3:],["SchemaStartupPostgresTests","/fixture","/evidence"])
 def test_actual_argv_owner_isolation_mount_or_jvm_weakenings_fail_closed(self):
  for kind in ("pg","java"):
   for mutate in (lambda i:i["HostConfig"].update(Privileged=True),lambda i:i["HostConfig"].update(PortBindings={"5432/tcp":[{}]}),
    lambda i:i["HostConfig"].update(Memory=0),lambda i:i["Config"]["Labels"].update({"tms.c.owner":"other"})):
    info=self.info(kind);mutate(info)
    with self.assertRaises(ValueError):gate.validate(info,kind,self.run,self.fixture,self.evidence)
  for mutate in (lambda i:i["Config"].update(Cmd=["CConsentPostgresTests"]),lambda i:i["Config"].update(User="0:0"),
   lambda i:i["Mounts"][0].update(RW=True),lambda i:i["HostConfig"].update(ReadonlyRootfs=False)):
   info=self.info("java");mutate(info)
   with self.assertRaises(ValueError):gate.validate(info,"java",self.run,self.fixture,self.evidence)
 def test_selected_sources_are_six_actual_production_and_three_fixture_peers_only(self):
  self.assertEqual(len(gate.SOURCES),9);self.assertEqual(sum("src/main/java/" in p for p in gate.SOURCES),6)
  self.assertFalse(any("CAnvil" in p or "custody/" in p or "src/test/" in p for p in gate.SOURCES))
  self.assertEqual([Path(p).name for p in gate.SQL],["schema.sql","faucet-schema.sql","conditional-schema.sql"])
 def test_recording_never_copies_password_or_sql_error_output(self):
  secret="do-not-record-password";result=subprocess.CompletedProcess([],1,secret,"SQL password="+secret)
  self.assertNotIn(secret,json.dumps(gate.observation(["docker","container","inspect","owned"],result)))
  with self.assertRaises(ValueError):gate.observation(["docker","run","--env=PASSWORD="+secret],result)
 def test_prepare_claim_is_once_only_and_fixture_mutation_is_rejected_without_running_anything(self):
  with tempfile.TemporaryDirectory() as directory,patch.object(gate,"FILES",("one.txt",)),patch("subprocess.run",side_effect=AssertionError("process")):
   root=Path(directory);(root/"one.txt").write_text("source",encoding="utf-8");identity="20261024T000000Z-"+"a"*8
   prep=root/"deployment/runtime/schema-pg-prepare"/identity;fixture=prep/"fixture";fixture.mkdir(parents=True)
   (fixture/"only.txt").write_text("compiled",encoding="utf-8")
   record={"status":"PREPARED_NOT_PG","compileExit":0,"selfTestExit":0,"pureGuardExit":0,"sources":{"one.txt":gate.sha(root/"one.txt")},
    "fixtureHashes":gate.tree_hashes(fixture),"legacyHelperSha256":gate.sha(gate.CROOT/"deployment/c-consent-postgres-check.py"),
    "networkHelperSha256":gate.sha(gate.CROOT/"deployment/c_gate_network.py")}
   record["helperHashes"]=gate.helper_hashes();record["sourceIdentity"]=gate.execution_identity(record["sources"],record["helperHashes"])
   (prep/"result.json").write_text(json.dumps(record),encoding="utf-8")
   gate.prepared(root,identity)
   with patch.object(gate,"helper_hashes",return_value={"changed":"0"*64}):
    with self.assertRaises(ValueError):gate.prepared(root,identity)
   (fixture/"only.txt").write_text("mutated",encoding="utf-8")
   with self.assertRaises(ValueError):gate.prepared(root,identity)
   (fixture/"only.txt").write_text("compiled",encoding="utf-8")
   (prep/"RUN_CLAIM").write_text("CONSUMED",encoding="ascii")
   with self.assertRaises(ValueError):gate.prepared(root,identity)
 def test_cleanup_is_limited_to_new_two_roles_and_unknown_inspect_is_not_missing(self):
  self.assertEqual(gate.legacy.cleanup_targets(self.run),[("java",self.run+"-java"),("pg",self.run+"-pg")])
  self.assertFalse(gate.legacy.missing_object(subprocess.CompletedProcess([],1,"","daemon unavailable")))
  self.assertTrue(gate.legacy.missing_object(subprocess.CompletedProcess([],1,"","No such object: owned")))
 def test_postclaim_staging_failure_preserves_claim_and_creates_no_docker_resource(self):
  with tempfile.TemporaryDirectory() as directory:
   root=Path(directory);prep=root/"prepared";prep.mkdir();(prep/"fixture").mkdir()
   record={"sourceIdentity":"a"*64}
   def fake(command,**kwargs):
    text=" ".join(command);stdout=""
    if "FreePhysicalMemory" in text:stdout=str(1920*1024)
    elif "Get-NetRoute" in text:stdout='["10.0.0.0/24"]'
    elif command[:3]==["docker","image","inspect"]:stdout=command[3]+"|linux|amd64"
    return subprocess.CompletedProcess(command,0,stdout,"")
   with patch.object(gate,"prepared",return_value=(prep,record)),patch("subprocess.run",side_effect=fake) as processes,patch.object(gate.shutil,"disk_usage",return_value=SimpleNamespace(free=2*1024**3)),patch.object(gate.shutil,"copytree",side_effect=OSError("injected-safe-test")):
    with contextlib.redirect_stdout(io.StringIO()):self.assertEqual(gate.run(root,"ignored-prepared-id"),1)
    self.assertTrue((prep/"RUN_CLAIM").is_file())
    results=list((root/"deployment/runtime/schema-pg").glob("*/result.json"));self.assertEqual(len(results),1)
    result=json.loads(results[0].read_text());self.assertEqual(result["status"],"BLOCKED");self.assertEqual(result["resourceCreates"],0)
    for call in processes.call_args_list:
     if call.args[0][0]=="docker":self.assertFalse(any(v in {"run","create","stop"} for v in call.args[0]))

if __name__=="__main__":unittest.main()
