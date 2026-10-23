"""Offline preparation and fixed launch plan for NON_USER_READ_INTEGRATION. No implicit live execution."""
import copy,datetime,hashlib,importlib.util,json,os,re,shutil,subprocess,sys,uuid,secrets,time
from pathlib import Path
sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parents[1];CROOT=ROOT.parent/"dapp-c-development-worktree"
sys.path.insert(0,str(CROOT/"deployment"))
def module(name,path):
 spec=importlib.util.spec_from_file_location(name,path);value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value
pg=module("full_read_pg_policy",ROOT/"deployment/schema-startup-postgres-check.py")
flags=module("full_read_cached_flags",CROOT/"deployment/c-direct-demo-check.py")
PG_IMAGE,JAVA_IMAGE=pg.PG_IMAGE,pg.JAVA_IMAGE
ANVIL_IMAGE="sha256:0c00cb0bda1ab1b91c9a6bf60f4c76c09c1a8870824b6d4718afbabacf6f9a17"
WORK_SECONDS=300;TOTAL_SECONDS=390;READY_SECONDS=120
SNAPSHOT="deployment/runtime/c-checkpoint-resume/cdemo-8d5fd2ff832345bb/anvil-state.private.json"
PUBLIC_IDENTITY="deployment/runtime/c-direct-browser/cdemo-e0e5a24638b0416f/oracleConfig/identity.json"
ANCHORS={SNAPSHOT:"71192dd8e8cdbb58e78d6463efea3cb0d793964c9c1ccb71fae6b7ea123f6b19",
 "deployment/runtime/c-checkpoint-resume/cdemo-8d5fd2ff832345bb/result.json":"666389a4809e614f9bc7f3e738242328e822ec1431322f319629b3da61ab7608",
 "deployment/runtime/c-checkpoint-resume/cdemo-8d5fd2ff832345bb/checkpoint-lineage.json":"b18407be0c566208f53d933c28bf8756622c985a674938f50d030a5174e2ea9e",
 PUBLIC_IDENTITY:"3ec05c3261c6f17ba0b8d3747926093d346cf63e279ece34e81107c1d70aaa5d"}
OWN=("deployment/full-app-read-gate.py","deployment/FullAppReadGate.java","deployment/FullAppReadDriver.java","deployment/test_full_app_read_gate.py")
REVIEWED={
 "com/pricetrack/exchange/wallet/faucet/FaucetStore.java":"6bafdc7a7ffb20d3dfb9e433c63a2b560fb19a110d71d73e744263483d55cdad",
 "com/pricetrack/exchange/conditional/ConditionalSchema.java":"5c68944a247b8f875118c95230d89373151c992e192fe2098bc9c9f0519b45e6",
 "com/pricetrack/exchange/common/schema/SchemaStartupMode.java":"7d92e79627d8a902c48abdd1ef410a122454f3cc4782199590ae9191d9487d3a",
 "com/pricetrack/exchange/common/schema/PreinstalledSchemaVerifier.java":"dd261552c77c25194d13f984f5dffcae28fb2cdf3a2afaef53cce8224aaf1f61",
 "com/pricetrack/exchange/custody/direct/CDirectMarketController.java":"eb053922b5462ad1e689a57e34b3c811ffdf8a89f44bd081eae32f9df1bd271b"}
OFF=("app.ai.enabled","app.ai.agent.enabled","app.ai.tools.enabled","app.ai.skills.enabled","app.ai.diagnosis.enabled","app.blockchain.enabled",
 "app.blockchain.price-report.enabled","app.futures.enabled","app.futures.runtime-enabled","app.conditional.enabled","app.reserve.enabled",
 "app.trade-audit.enabled","app.google.enabled","app.c-demo.enabled")
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def require(ok,code):
 if not ok:raise ValueError("FULL_READ_"+code)
def save(p,value):
 with p.open("x",encoding="utf-8") as f:json.dump(value,f,indent=2)
def helpers():
 return {str(p):sha(p) for p in (ROOT/"deployment/schema-startup-postgres-check.py",CROOT/"deployment/c-consent-postgres-check.py",CROOT/"deployment/c_gate_network.py",CROOT/"deployment/c-direct-demo-check.py")}
def anchors():
 require((CROOT/SNAPSHOT).stat().st_size==14800699,"SNAPSHOT_SIZE")
 actual={p:sha(CROOT/p) for p in ANCHORS};require(actual==ANCHORS,"PRESENTATION_ANCHOR");return actual
def transaction_ids(path):
 data=json.loads(path.read_text(encoding="utf-8"))
 values=[tx.get("info",{}).get("transaction_hash","").lower() for tx in data.get("transactions",[])]
 require(len(values)==14 and len(set(values))==14 and all(re.fullmatch(r"0x[0-9a-f]{64}",v) for v in values),"FORK_TRANSACTION_HISTORY")
 return sorted(values)
def bind(path,target,ro):
 text=str(path.resolve());require(not any(c in text+target for c in (",","\n","\r","\0")),"BIND")
 return "type=bind,source="+text+",target="+target+(",readonly" if ro else "")
def launch(role,run,paths,envfile):
 require(bool(re.fullmatch(r"ccpg-[0-9a-f]{16}",run)) and role in ("pg","anvil","app","bootstrap","verify"),"ROLE")
 common=["run","-d","--pull=never","--name",run+"-"+role,"--label","tms.c.owner="+run,"--label","tms.full.read.scope=NON_USER_READ_INTEGRATION",
  "--network",run+"-network","--restart=no","--cpus=1","--pids-limit=128"]
 if role=="pg":
  return common+["--network-alias","pg","--memory=256m","--cap-drop=NET_RAW","--env-file",str(envfile),
   "--mount","type=volume,source="+run+"-data,target=/var/lib/postgresql/data",PG_IMAGE]
 common+=["--read-only","--cap-drop=ALL","--security-opt=no-new-privileges","--workdir","/tmp","--tmpfs","/tmp:rw,noexec,nosuid,size=32m"]
 if role=="anvil":
  return common+["--user=0:0","--network-alias","anvil","--memory=192m","--mount",bind(CROOT/SNAPSHOT,"/snapshot/anvil-state.private.json",True),
   "--mount",bind(paths["forkEvidence"],"/fork-evidence",False),"--entrypoint","/usr/local/bin/anvil",ANVIL_IMAGE,
   "--host","0.0.0.0","--port","8545","--chain-id","31337","--accounts","0","--block-time","1","--silent",
   "--load-state","/snapshot/anvil-state.private.json","--dump-state","/fork-evidence/anvil.json"]
 common+=["--user=10001:10001","--mount",bind(paths["app"],"/app-fixture",True),"--mount",bind(paths["config"],"/gate-config",role!="bootstrap")]
 if role=="app":
  return common+["--network-alias","app","--memory=768m","--mount",bind(paths["control"],"/gate-control",True),
   "--mount",bind(paths["appEvidence"],"/gate-evidence",False),"--entrypoint","java",JAVA_IMAGE,
   "-Xms32m","-Xmx256m","-XX:MaxMetaspaceSize=192m","-Dfile.encoding=UTF-8","-cp","/app-fixture/classes:/app-fixture/jars/*",
   "FullAppReadGate","/gate-config/application.properties","/gate-control","/gate-evidence"]
 # Only bootstrap gets write access to this new App config; no wallet private key file exists.
 return common+["--memory=384m","--env-file",str(envfile),"--mount",bind(paths["driver"],"/fixture",True),
  "--mount",bind(paths[role+"Evidence"],"/evidence",False),"--entrypoint","java",JAVA_IMAGE,
  "-Xms16m","-Xmx192m","-XX:MaxMetaspaceSize=128m","-Dfile.encoding=UTF-8","-cp",
  "/fixture/classes:/app-fixture/classes:/app-fixture/jars/*","FullAppReadDriver",role,"/fixture","/gate-config","/evidence"]
def validate(info,role,run,paths,envfile):
 expected=launch(role,run,paths,envfile);image=PG_IMAGE if role=="pg" else ANVIL_IMAGE if role=="anvil" else JAVA_IMAGE
 c,h,n=info.get("Config",{}),info.get("HostConfig",{}),info.get("NetworkSettings",{})
 require(info.get("Image")==image and info.get("Name")=="/"+run+"-"+role and c.get("Labels",{}).get("tms.c.owner")==run and c.get("Labels",{}).get("tms.full.read.scope")=="NON_USER_READ_INTEGRATION","OWNER_IMAGE")
 require(h.get("NetworkMode")==run+"-network" and set(n.get("Networks",{}))=={run+"-network"} and not h.get("Privileged") and not h.get("CapAdd") and not h.get("PortBindings") and not any(n.get("Ports",{}).values()),"ISOLATION")
 require(h.get("Memory")=={"pg":256,"anvil":192,"app":768,"bootstrap":384,"verify":384}[role]*1024**2,"MEMORY")
 expected_mounts={}
 for i,v in enumerate(expected):
  if v=="--mount":
   parts=dict(part.split("=",1) for part in expected[i+1].split(",") if "=" in part);expected_mounts[parts["target"]]=(parts,"readonly" not in expected[i+1].split(","))
 mounts={m["Destination"]:m for m in info.get("Mounts",[]) if m.get("Type")!="tmpfs"};require(set(mounts)==set(expected_mounts),"MOUNTS")
 for target,(parts,rw) in expected_mounts.items():
  actual=mounts[target];require(actual.get("Type")==parts["type"] and actual.get("RW")==rw,"MOUNT_MODE")
  if parts["type"]=="volume":require(actual.get("Name")==parts["source"],"VOLUME")
  else:require(str(actual.get("Source","")).replace("\\","/").lower()==parts["source"].replace("\\","/").lower(),"BIND_SOURCE")
 if role=="pg":
  require(c.get("Entrypoint")==["docker-entrypoint.sh"] and c.get("Cmd")==["postgres"] and not h.get("ReadonlyRootfs") and h.get("CapDrop") in (["NET_RAW"],["CAP_NET_RAW"]),"PG_COMMAND")
 else:
  at=expected.index("--entrypoint");require(c.get("Entrypoint")==[expected[at+1]] and c.get("Cmd")==expected[at+3:],"COMMAND")
  require(h.get("ReadonlyRootfs") is True and h.get("CapDrop")==["ALL"] and c.get("User")==("0:0" if role=="anvil" else "10001:10001") and any(x in ("no-new-privileges","no-new-privileges:true") for x in h.get("SecurityOpt",[])),"READONLY_USER")
  require(c.get("WorkingDir")=="/tmp","EMPTY_WORKDIR")
def source_map(root):
 main=root/"backend/src/main/java";prior=json.loads((root/"deployment/runtime/c-full-read-compile-results/20261023T190509Z-a2f8c06a/manifest.json").read_text())
 exceptions={p:REVIEWED[p] for p in ("com/pricetrack/exchange/wallet/faucet/FaucetStore.java","com/pricetrack/exchange/conditional/ConditionalSchema.java")}
 sources=sorted(main.rglob("*.java"));require(len(sources)==279,"MAIN_COUNT")
 unique={"com/pricetrack/exchange/custody/direct/CDirectMarketController.java","com/pricetrack/exchange/common/schema/SchemaStartupMode.java","com/pricetrack/exchange/common/schema/PreinstalledSchemaVerifier.java"}
 for p in sources:
  rel=p.relative_to(main).as_posix();old=CROOT/"backend/src/main/java"/rel
  if old.is_file():require(sha(p)==(exceptions[rel] if rel in exceptions else sha(old)),"COMMON_SOURCE_DRIFT")
  else:
   require(rel in unique,"UNREGISTERED_SOURCE")
   require(sha(p)==REVIEWED[rel],"ADDED_SOURCE_DRIFT")
 for item in prior["sources"]:
  if item["origin"]=="CWORK_RO":require(sha(Path(item["path"]))==item["sha256"],"C_CLOSURE_CHANGED");sources.append(Path(item["path"]))
 require(len(sources)==310 and len(set(sources))==310,"UNIQUE_FULL_SOURCES")
 return sources,exceptions
def prepare(root):
 pinned=anchors();transaction_ids(CROOT/SNAPSHOT);source,exceptions=source_map(root);helper_before=helpers()
 previous=json.loads((root/"deployment/runtime/c-full-read-compile-results/20261023T190509Z-a2f8c06a/result.json").read_text())
 jars={Path(p).name:Path(p) for p,digest in previous["dependencies"].items() if not Path(p).name.startswith("lombok-")}
 require(len(jars)==123 and all(sha(p)==previous["dependencies"][str(p)] for p in jars.values()),"JARS")
 lombok=next(Path(p) for p in previous["dependencies"] if Path(p).name.startswith("lombok-"))
 require(sha(lombok)==previous["dependencies"][str(lombok)],"LOMBOK")
 resources=[root/"backend/src/main/resources"/p for p in ("schema.sql","faucet-schema.sql","conditional-schema.sql","application.yml")]+[CROOT/"backend/src/main/resources/c-wallet-schema.sql"]
 inputs=[*source,*[root/p for p in OWN],*[root/"deployment"/p for p in ("FaucetSchemaFixtureBridge.java","ConditionalSchemaFixtureBridge.java")],*resources]
 before={str(p):sha(p) for p in inputs}
 run=datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")+"-"+uuid.uuid4().hex[:8];out=root/"deployment/runtime/full-read-prepare"/run
 app=out/"app-fixture";driver=out/"driver-fixture";(app/"classes").mkdir(parents=True,exist_ok=False);(app/"jars").mkdir();(driver/"classes").mkdir(parents=True)
 for name,p in jars.items():shutil.copyfile(p,app/"jars"/name)
 for p in resources:
  for target in (app/p.name,app/"classes"/p.name):shutil.copyfile(p,target)
 shutil.copyfile(CROOT/PUBLIC_IDENTITY,driver/"c-identity.json")
 cp=os.pathsep.join(str(p) for p in jars.values());jdk=Path("C:/Program Files/Java/jdk-21.0.10/bin")
 version=subprocess.run([str(jdk/"java.exe"),"-version"],env=pg.clean_env(),capture_output=True,text=True,timeout=10)
 require(version.returncode==0 and '"21.0.10"' in version.stderr,"JDK_VERSION")
 empty=out/"empty-sourcepath";empty.mkdir()
 def compile(label,selected,output,classpath,processor):
  args=flags.javac_args("server",selected,classpath,output,Path("."),lombok) if processor else ["--release","21","-parameters","-encoding","UTF-8","-proc:none","-cp",classpath,"-d",str(output),*selected]
  args=["-sourcepath",str(empty),"-implicit:none",*args]
  with (out/(label+".args")).open("x",encoding="utf-8") as f:f.write("\n".join(pg.quote(a) for a in args))
  with (out/(label+".stdout.log")).open("xb") as stdout,(out/(label+".stderr.log")).open("xb") as stderr:
   return subprocess.run([str(jdk/"javac.exe"),"-J-Xmx384m","@"+str(out/(label+".args"))],cwd=root,env=pg.clean_env(),stdout=stdout,stderr=stderr,timeout=90).returncode
 require(compile("app-compile",[*map(str,source),str(root/"deployment/FullAppReadGate.java")],app/"classes",cp,True)==0,"APP_COMPILE")
 require(compile("driver-compile",[str(root/"deployment"/p) for p in ("FullAppReadDriver.java","FaucetSchemaFixtureBridge.java","ConditionalSchemaFixtureBridge.java")],driver/"classes",str(app/"classes")+os.pathsep+cp,False)==0,"DRIVER_COMPILE")
 classpath=str(driver/"classes")+os.pathsep+str(app/"classes")+os.pathsep+cp
 for label,main,args in (("wrapper-self","FullAppReadGate",["--self-test"]),("driver-self","FullAppReadDriver",["--self-test",str(app)])):
  result=subprocess.run([str(jdk/"java.exe"),"-Xmx128m","-cp",classpath,main,*args],cwd=root,env=pg.clean_env(),capture_output=True,text=True,timeout=30)
  with (out/(label+".stdout.log")).open("x",encoding="utf-8") as f:f.write(result.stdout)
  with (out/(label+".stderr.log")).open("x",encoding="utf-8") as f:f.write(result.stderr)
  require(result.returncode==0,"METADATA_SELF_TEST")
 tests=subprocess.run([sys.executable,"-B","-m","unittest","discover","-s","deployment","-p","test_full_app_read_gate.py"],cwd=root,env=pg.clean_env(),capture_output=True,text=True,timeout=30)
 with (out/"pure.stdout.log").open("x",encoding="utf-8") as f:f.write(tests.stdout)
 with (out/"pure.stderr.log").open("x",encoding="utf-8") as f:f.write(tests.stderr)
 require(tests.returncode==0,"PURE_GUARDS")
 require(before=={p:sha(Path(p)) for p in before} and helper_before==helpers() and pinned==anchors(),"END_IDENTITY")
 execution={"sources":before,"helpers":helper_before,"anchors":pinned}
 record={"status":"PREPARED_NOT_ACTUAL_FULL_APP_GATE","scope":"NON_USER_READ_INTEGRATION","sourceIdentity":hashlib.sha256(json.dumps(execution,sort_keys=True).encode()).hexdigest(),
  **execution,"intentionalCommonOverrides":exceptions,"fullProductionSourceCount":310,"appFixtureHashes":pg.tree_hashes(app),"driverFixtureHashes":pg.tree_hashes(driver),
  "sourceCopies":0,"dockerCalls":0,"jdkVersion":version.stderr.strip(),"officialClaimReadsOrWrites":0,"runOrchestrator":"IMPLEMENTED_NOT_EXECUTED_REVIEW_RESOURCE_GATE_REQUIRED"}
 save(out/"result.json",record);print(json.dumps({"prepare":run,"status":record["status"],"sourceIdentity":record["sourceIdentity"]}));return 0
def checked_prepare(identity):
 require(bool(re.fullmatch(r"\d{8}T\d{6}Z-[0-9a-f]{8}",identity)),"PREPARE_ID")
 out=ROOT/"deployment/runtime/full-read-prepare"/identity;r=json.loads((out/"result.json").read_text())
 require(r["status"]=="PREPARED_NOT_ACTUAL_FULL_APP_GATE" and r["scope"]=="NON_USER_READ_INTEGRATION","PREPARE_SCOPE")
 require(r["sources"]=={p:sha(Path(p)) for p in r["sources"]} and r["helpers"]==helpers() and r["anchors"]==anchors(),"PREPARE_SOURCE")
 require(r["appFixtureHashes"]==pg.tree_hashes(out/"app-fixture") and r["driverFixtureHashes"]==pg.tree_hashes(out/"driver-fixture") and not (out/"RUN_CLAIM").exists(),"PREPARE_FIXTURE_CLAIM")
 require(r["sourceIdentity"]==hashlib.sha256(json.dumps({k:r[k] for k in ("sources","helpers","anchors")},sort_keys=True).encode()).hexdigest(),"EXECUTION_IDENTITY")
 return out,r
def run_gate(identity):
 prep,r=checked_prepare(identity);initial=r["sourceIdentity"];start=time.monotonic();deadline=start+WORK_SECONDS;total=start+TOTAL_SECONDS;commands=[]
 def docker(command,timeout=10,check=True):
  require(int(deadline-time.monotonic())>0,"DEADLINE")
  p=subprocess.run(["docker",*command],cwd=ROOT,env=pg.clean_env(),capture_output=True,text=True,timeout=min(timeout,max(1,int(deadline-time.monotonic()))))
  commands.append(pg.observation(["docker",*command],p))
  if check:require(p.returncode==0,"DOCKER_COMMAND")
  return p
 ram=subprocess.run(["powershell.exe","-NoProfile","-NonInteractive","-Command","(Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory"],env=pg.clean_env(),capture_output=True,text=True,timeout=10)
 pg.resources(int(ram.stdout.strip())*1024 if ram.returncode==0 and ram.stdout.strip().isdigit() else 0,shutil.disk_usage(ROOT).free)
 docker(["version","--format","{{.Server.Version}}"])
 for image in (PG_IMAGE,JAVA_IMAGE,ANVIL_IMAGE):require(docker(["image","inspect",image,"--format","{{.Id}}|{{.Os}}|{{.Architecture}}"]).stdout.strip()==image+"|linux|amd64","PINNED_IMAGE")
 ids=docker(["network","ls","--format","{{.ID}}"]).stdout.splitlines();require(len(ids)<=1024 and all(re.fullmatch(r"[0-9a-f]{12,64}",n) for n in ids),"NETWORK_LIST")
 occupied=[]
 for i in range(0,len(ids),64):
  for line in docker(["network","inspect",*ids[i:i+64],"--format","{{json .IPAM.Config}}"]).stdout.splitlines():occupied.extend(x["Subnet"] for x in json.loads(line) or [] if x.get("Subnet"))
 routes=subprocess.run(["powershell.exe","-NoProfile","-NonInteractive","-Command","@(Get-NetRoute -AddressFamily IPv4 -ErrorAction Stop | Select-Object -ExpandProperty DestinationPrefix) | ConvertTo-Json -Compress"],env=pg.clean_env(),capture_output=True,text=True,timeout=10)
 require(routes.returncode==0,"ROUTES");values=json.loads(routes.stdout);subnet=pg.legacy.select_subnet(occupied,[values] if isinstance(values,str) else values)
 prep,r=checked_prepare(identity);require(initial==r["sourceIdentity"],"PRECLAIM_IDENTITY")
 with (prep/"RUN_CLAIM").open("x",encoding="ascii") as f:f.write("ONE_HIDDEN_NON_USER_FULL_READ_GATE_NO_RETRY\n")
 run="ccpg-"+uuid.uuid4().hex[:16];out=ROOT/"deployment/runtime/full-read"/run
 try:
  out.mkdir(parents=True,exist_ok=False);paths={"app":out/"app-fixture","driver":out/"driver-fixture"}
  shutil.copytree(prep/"app-fixture",paths["app"]);shutil.copytree(prep/"driver-fixture",paths["driver"])
  for key in ("config","control","appEvidence","forkEvidence","bootstrapEvidence","verifyEvidence"):paths[key]=out/key;paths[key].mkdir()
  suffix=run[5:];values={k:secrets.token_hex(32) for k in ("FULL_READ_OWNER_PASSWORD","FULL_READ_API_PASSWORD","FULL_READ_JWT_SECRET","FULL_READ_LOGIN_PASSWORD")}
  pg_env=out/"pg.private.env";driver_env=out/"driver.private.env"
  with pg_env.open("x",encoding="ascii") as f:f.write("POSTGRES_DB=ccpg_"+suffix+"\nPOSTGRES_USER=ccowner_"+suffix+"\nPOSTGRES_PASSWORD="+values["FULL_READ_OWNER_PASSWORD"]+"\nPOSTGRES_INITDB_ARGS=--auth-host=scram-sha-256\n")
  with driver_env.open("x",encoding="ascii") as f:f.write("\n".join(k+"="+v for k,v in values.items())+"\n")
  with (paths["driver"]/"manifest.properties").open("x",encoding="ascii") as f:f.write("scope=NON_USER_READ_INTEGRATION\nrunId="+run+"\nsourceIdentity="+initial+"\n")
  runtime_app=pg.tree_hashes(paths["app"]);runtime_driver=pg.tree_hashes(paths["driver"])
 except Exception as error:
  result={"run":run,"status":"BLOCKED","stage":"POSTCLAIM_STAGING","safeError":type(error).__name__,"resourceCreates":0,"claimPreserved":True}
  try:save(out/"result.json",result)
  except Exception:
   try:save(prep/("postclaim-failure-"+uuid.uuid4().hex[:8]+".json"),result)
   except Exception:pass
  print(json.dumps(result));return 1
 roles=("pg","anvil","bootstrap","app","verify");states={};preservation={};status="BLOCKED";error=None;stage="RESOURCE_COLLISION"
 def inspect(role):
  data=json.loads(docker(["container","inspect",run+"-"+role]).stdout);require(len(data)==1,"INSPECT")
  validate(data[0],role,run,paths,pg_env if role=="pg" else driver_env);return data[0]
 def wait_done(role,limit):
  end=min(deadline,time.monotonic()+limit)
  while time.monotonic()<end:
   state=inspect(role).get("State",{})
   if not state.get("Running"):require(state.get("ExitCode")==0 and not state.get("OOMKilled") and not state.get("Error"),role.upper()+"_FAILED");return
   time.sleep(.5)
  raise ValueError("FULL_READ_"+role.upper()+"_TIMEOUT")
 try:
  for kind,name in [("network",run+"-network"),("volume",run+"-data"),*[( "container",run+"-"+role) for role in roles]]:
   require(docker([kind,"inspect",name],check=False).returncode!=0,"COLLISION")
  docker(["network","create","--internal","--subnet",subnet,"--label","tms.c.owner="+run,run+"-network"]);docker(["volume","create","--label","tms.c.owner="+run,run+"-data"])
  for kind,name in (("network",run+"-network"),("volume",run+"-data")):
   obj=json.loads(docker([kind,"inspect",name]).stdout)[0];require(obj.get("Labels",{}).get("tms.c.owner")==run,"RESOURCE_OWNER")
   if kind=="network":
    ipam=obj.get("IPAM",{}).get("Config",[]);require(obj.get("Internal") is True and len(ipam)==1 and ipam[0].get("Subnet")==subnet,"INTERNAL_NETWORK")
  stage="FRESH_PG";docker(launch("pg",run,paths,pg_env));inspect("pg")
  for _ in range(30):
   if docker(["exec",run+"-pg","pg_isready","-h","127.0.0.1","-p","5432","-U","ccowner_"+suffix,"-d","ccpg_"+suffix],check=False).returncode==0:break
   time.sleep(.5)
  else:raise ValueError("FULL_READ_PG_NOT_READY")
  stage="HIDDEN_RO_FORK";docker(launch("anvil",run,paths,driver_env));inspect("anvil")
  stage="OWNER_BOOTSTRAP";docker(launch("bootstrap",run,paths,driver_env));inspect("bootstrap");wait_done("bootstrap",45)
  stage="ACTUAL_FULL_APP";docker(launch("app",run,paths,driver_env));inspect("app")
  end=min(deadline,time.monotonic()+READY_SECONDS)
  while time.monotonic()<end:
   state=inspect("app").get("State",{});require(state.get("Running"),"APP_STOPPED_BEFORE_READY")
   if (paths["appEvidence"]/"ready.json").exists():
    require(json.loads((paths["appEvidence"]/"ready.json").read_text()).get("actualExchangeApplication") is True,"ACTUAL_APP_READY");break
   time.sleep(.5)
  else:raise ValueError("FULL_READ_APP_READY_TIMEOUT")
  stage="HTTP_STOMP_C_READ";docker(launch("verify",run,paths,driver_env));inspect("verify");wait_done("verify",80)
  checked=json.loads((paths["verifyEvidence"]/"verify.json").read_text())
  require(checked.get("result")=="PASS" and checked.get("runId")==run and checked.get("sourceIdentity")==initial and checked.get("common401") is True and checked.get("common403") is True and checked.get("stompRead") is True,"VERIFY_RESULT")
  status="ACTUAL_FULL_APP_HTTP_STOMP_C_READ_PASS_NOT_UI_OR_TRADING"
 except Exception as failure:error=str(failure) if re.fullmatch(r"(FULL_READ|SCHEMA_PG|C_CONSENT)_[A-Z0-9_]+",str(failure)) else "FULL_READ_SAFE_"+type(failure).__name__
 finally:
  deadline=total
  try:
   with (paths["control"]/"STOP").open("x",encoding="ascii") as f:f.write("STOP_ONLY_THIS_OWNED_WRAPPER\n")
  except Exception:pass
  app_closed=False
  try:
   close_end=min(total,time.monotonic()+10)
   while time.monotonic()<close_end:
    marker=paths["appEvidence"]/"normal-stop.json"
    if marker.exists():
     app_closed=json.loads(marker.read_text()).get("normalStop") is True;break
    time.sleep(.2)
  except Exception:pass
  for role in ("verify","bootstrap","app","anvil","pg"):
   name=run+"-"+role
   try:
    probe=docker(["container","inspect",name],timeout=2,check=False)
    if pg.legacy.missing_object(probe):states[role]={"present":False,"normalStop":False};continue
    require(probe.returncode==0,"STOP_INSPECT");obj=json.loads(probe.stdout);require(len(obj)==1 and obj[0].get("Config",{}).get("Labels",{}).get("tms.c.owner")==run,"STOP_OWNER")
    if obj[0].get("State",{}).get("Running"):docker(["stop","-t","10",name],timeout=15)
    obj=json.loads(docker(["container","inspect",name],timeout=2).stdout)[0];require(obj.get("Config",{}).get("Labels",{}).get("tms.c.owner")==run,"STOP_OWNER")
    s=obj.get("State",{});states[role]={"normalStop":s.get("Running") is False and s.get("ExitCode") in (0,143) and not s.get("OOMKilled") and not s.get("Error")}
   except Exception:states[role]={"normalStop":False}
  for kind,name in (("network",run+"-network"),("volume",run+"-data")):
   try:obj=json.loads(docker([kind,"inspect",name],timeout=2).stdout);preservation[kind]=len(obj)==1 and obj[0].get("Labels",{}).get("tms.c.owner")==run
   except Exception:preservation[kind]=False
  try:
   fork_tx_unchanged=transaction_ids(CROOT/SNAPSHOT)==transaction_ids(paths["forkEvidence"]/"anvil.json")
   stable=fork_tx_unchanged and r["sources"]=={p:sha(Path(p)) for p in r["sources"]} and r["helpers"]==helpers() and r["anchors"]==anchors() and runtime_app==pg.tree_hashes(paths["app"]) and runtime_driver==pg.tree_hashes(paths["driver"]) and r["appFixtureHashes"]==pg.tree_hashes(prep/"app-fixture") and r["driverFixtureHashes"]==pg.tree_hashes(prep/"driver-fixture")
  except Exception:stable=False
  if not app_closed or not stable or not all(states.get(role,{}).get("normalStop") for role in roles) or not all(preservation.values()) or time.monotonic()>total:status="BLOCKED";error=error or "FULL_READ_FINAL_PRESERVATION"
  save(out/"result.json",{"run":run,"scope":"NON_USER_READ_INTEGRATION","status":status,"stage":stage,"safeError":error,"sourceIdentity":initial,"commands":commands,"stopped":states,"preserved":preservation,"sourcesFixturesAndOriginalAnchorsUnchanged":stable,"forkTransactionHistorySame":locals().get("fork_tx_unchanged",False),"elapsedSeconds":time.monotonic()-start,"officialClaimTouched":False,"hostPorts":0})
 print(json.dumps({"run":run,"status":status,"safeError":error}));return 0 if status.startswith("ACTUAL_") else 1
def main():
 require(sys.argv[1:]==["prepare"] or len(sys.argv)==3 and sys.argv[1]=="run","EXPLICIT_PREPARE_OR_REVIEWED_RUN")
 return prepare(ROOT) if sys.argv[1]=="prepare" else run_gate(sys.argv[2])
if __name__=="__main__":raise SystemExit(main())
