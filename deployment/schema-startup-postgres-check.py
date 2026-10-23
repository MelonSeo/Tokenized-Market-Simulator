"""New PG-only startup unit. prepare is offline; run requires independent review and resource approval."""
import copy,datetime,hashlib,importlib.util,json,os,re,secrets,shutil,subprocess,sys,time,uuid
from pathlib import Path
sys.dont_write_bytecode=True
CROOT=Path(__file__).resolve().parents[1].parent/"dapp-c-development-worktree"
sys.path.insert(0,str(CROOT/"deployment"))
spec=importlib.util.spec_from_file_location("immutable_consent_pg_helpers",CROOT/"deployment/c-consent-postgres-check.py")
legacy=importlib.util.module_from_spec(spec);spec.loader.exec_module(legacy)
PG_IMAGE,JAVA_IMAGE=legacy.PG_IMAGE,legacy.JAVA_IMAGE
resources,tree_hashes,quote,observation,clean_env=legacy.resources,legacy.tree_hashes,legacy.quote,legacy.observation,legacy.clean_env
BASE="backend/src/main/java/com/pricetrack/exchange/"
SOURCES=tuple(BASE+p+".java" for p in ("common/schema/SchemaStartupMode","common/schema/PreinstalledSchemaVerifier",
 "wallet/faucet/FaucetStore","wallet/faucet/FaucetPolicy","wallet/faucet/FaucetFailure","conditional/ConditionalSchema"))+(
 "deployment/FaucetSchemaFixtureBridge.java","deployment/ConditionalSchemaFixtureBridge.java","deployment/SchemaStartupPostgresTests.java")
SQL=tuple("backend/src/main/resources/"+p for p in ("schema.sql","faucet-schema.sql","conditional-schema.sql"))
FILES=(*SOURCES,*SQL,"deployment/schema-startup-postgres-check.py","deployment/test_schema_startup_postgres_check.py")
PREFIXES=("spring-core-","spring-jcl-","spring-beans-","spring-context-","spring-expression-","spring-aop-","spring-jdbc-","spring-tx-",
 "jakarta.annotation-api-","postgresql-","junit-jupiter-api-","junit-jupiter-engine-","junit-platform-commons-","junit-platform-engine-",
 "junit-platform-launcher-","opentest4j-","apiguardian-api-","jackson-core-","jackson-databind-","jackson-annotations-")
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def save(p,v):
 with p.open("x",encoding="utf-8") as f:json.dump(v,f,indent=2)
def require(ok,code):
 if not ok:raise ValueError("SCHEMA_PG_"+code)
def helper_hashes():
 return {p:sha(CROOT/"deployment"/p) for p in ("c-consent-postgres-check.py","c_gate_network.py")}
def execution_identity(own,helpers):
 return hashlib.sha256(json.dumps({"own":own,"helpers":helpers},sort_keys=True).encode()).hexdigest()
def args(kind,run,fixture,evidence,envfile):
 cmd=legacy.args(kind,run,fixture,evidence,envfile)
 if kind=="java":cmd[cmd.index("CConsentPostgresTests")]="SchemaStartupPostgresTests"
 return cmd
def validate(info,kind,run,fixture,evidence):
 # Verify the exact new command before translating its sole fixed entrypoint for reused isolation checks.
 if kind=="java":
  cmd=args(kind,run,fixture,evidence,Path("unused"));require(info.get("Config",{}).get("Cmd")==cmd[cmd.index(JAVA_IMAGE)+1:],"JAVA_COMMAND")
  info=copy.deepcopy(info);info["Config"]["Cmd"]=legacy.args(kind,run,fixture,evidence,Path("unused"))[cmd.index(JAVA_IMAGE)+1:]
 legacy.validate(info,kind,run,fixture,evidence)
def prepared(root,identity):
 require(bool(re.fullmatch(r"\d{8}T\d{6}Z-[0-9a-f]{8}",identity)),"PREPARE_ID")
 p=root/"deployment/runtime/schema-pg-prepare"/identity;r=json.loads((p/"result.json").read_text())
 require(r["status"]=="PREPARED_NOT_PG" and all(r.get(k)==0 for k in ("compileExit","selfTestExit","pureGuardExit")) and r["sources"]=={k:sha(root/k) for k in FILES},"SOURCE_IDENTITY")
 require(tree_hashes(p/"fixture")==r["fixtureHashes"] and not (p/"RUN_CLAIM").exists(),"FIXTURE_OR_CLAIM")
 require(sha(CROOT/"deployment/c-consent-postgres-check.py")==r["legacyHelperSha256"],"HELPER_CHANGED")
 require(sha(CROOT/"deployment/c_gate_network.py")==r["networkHelperSha256"],"NETWORK_HELPER_CHANGED")
 require(helper_hashes()==r["helperHashes"] and execution_identity(r["sources"],r["helperHashes"])==r["sourceIdentity"],"EXECUTION_IDENTITY")
 return p,r
def prepare(root):
 prior=json.loads((root/"deployment/runtime/schema-startup-results/20261023T193346Z-a2f3c6fd/result.json").read_text())
 require(prior["exits"]=={"compile":0,"new":0} and prior["sourcesUnchanged"] and prior["dependenciesUnchanged"],"OFFLINE_BASELINE")
 require(all(prior["sources"].get(str(root/p))==sha(root/p) for p in SOURCES[:6]),"PRODUCTION_CHANGED")
 cache=root.parent/"dapp-audit-development-worktree/deployment/runtime/dapp-stage2-offline/java-classpath.args"
 lines=cache.read_text(encoding="utf-8-sig").splitlines();require(len(lines)==2 and lines[0]=="-cp","CACHE_FORMAT")
 jars={}
 for entry in lines[1].strip('"').split(os.pathsep):
  p=Path(entry)
  if p.suffix.lower()!=".jar" or not p.name.startswith(PREFIXES):continue
  require("/caches/modules-2/files-2.1/" in p.as_posix() and prior["dependencies"].get(str(p))==sha(p) and p.name not in jars,"JAR_IDENTITY")
  jars[p.name]=p
 # The accepted API Guardian is not present in every Gradle compile classpath.
 for text,digest in prior["dependencies"].items():
  p=Path(text)
  if p.name.startswith("apiguardian-api-") and p.name not in jars:require(sha(p)==digest,"APIGUARDIAN");jars[p.name]=p
 require(all(sum(n.startswith(prefix) for n in jars)==1 for prefix in PREFIXES),"DEPENDENCY_SET")
 identity=datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")+"-"+uuid.uuid4().hex[:8]
 out=root/"deployment/runtime/schema-pg-prepare"/identity;fixture=out/"fixture";(fixture/"classes").mkdir(parents=True,exist_ok=False);(fixture/"jars").mkdir()
 before={p:sha(root/p) for p in FILES};helpers_before=helper_hashes()
 for name,p in jars.items():shutil.copyfile(p,fixture/"jars"/name)
 for p in SQL:
  for target in (fixture/Path(p).name,fixture/"classes"/Path(p).name):shutil.copyfile(root/p,target)
 cp=os.pathsep.join(str(p) for p in jars.values());jdk=Path("C:/Program Files/Java/jdk-21.0.10/bin")
 compile_args=["--release","21","-parameters","-encoding","UTF-8","-proc:none","-implicit:none","-cp",cp,"-d",str(fixture/"classes"),*[str(root/p) for p in SOURCES]]
 with (out/"compile.args").open("x",encoding="utf-8") as f:f.write("\n".join(quote(a) for a in compile_args))
 with (out/"compile.stdout.log").open("xb") as stdout,(out/"compile.stderr.log").open("xb") as stderr:
  code=subprocess.run([str(jdk/"javac.exe"),"-J-Xmx192m","@"+str(out/"compile.args")],cwd=root,env=clean_env(),stdout=stdout,stderr=stderr,timeout=90).returncode
 require(code==0,"COMPILE")
 version=subprocess.run([str(jdk/"java.exe"),"-version"],cwd=root,env=clean_env(),capture_output=True,text=True,timeout=10)
 require(version.returncode==0 and 'version "21.0.10"' in version.stderr,"JDK_VERSION")
 probe=subprocess.run([str(jdk/"java.exe"),"-Xmx128m","-cp",str(fixture/"classes")+os.pathsep+cp,"SchemaStartupPostgresTests","--self-test",str(fixture)],cwd=root,env=clean_env(),capture_output=True,text=True,timeout=30)
 expected={Path(p).name:sha(root/p) for p in SQL};expected["scope"]="RESOURCE_METADATA_ONLY_NO_JDBC_ENV_OR_SOCKET"
 require(probe.returncode==0 and not probe.stderr and json.loads(probe.stdout)==expected,"CLASSLOADER_RESOURCE_SELF_TEST")
 save(out/"self-test.json",expected)
 tests=subprocess.run([sys.executable,"-B","-m","unittest","discover","-s","deployment","-p","test_schema_startup_postgres_check.py"],cwd=root,env=clean_env(),capture_output=True,text=True,timeout=30)
 with (out/"pure-tests.stdout.log").open("x",encoding="utf-8") as f:f.write(tests.stdout)
 with (out/"pure-tests.stderr.log").open("x",encoding="utf-8") as f:f.write(tests.stderr)
 require(tests.returncode==0,"PURE_GUARDS")
 require(before=={p:sha(root/p) for p in FILES},"SOURCE_CHANGED")
 require(helper_hashes()==helpers_before,"HELPER_CHANGED")
 require(all(sha(p)==sha(fixture/"jars"/name) for name,p in jars.items()),"JAR_CHANGED")
 record={"status":"PREPARED_NOT_PG","unitScope":"SCHEMA_STARTUP_PG_ONLY","sources":before,
  "sourceIdentity":execution_identity(before,helpers_before),"helperHashes":helpers_before,"compileExit":code,"selfTestExit":probe.returncode,"jdkVersion":version.stderr.strip(),
  "pureGuardExit":tests.returncode,"sqlHashes":{Path(p).name:sha(root/p) for p in SQL},"fixtureHashes":tree_hashes(fixture),
  "legacyHelperSha256":sha(CROOT/"deployment/c-consent-postgres-check.py"),"networkHelperSha256":sha(CROOT/"deployment/c_gate_network.py"),"dependencies":{n:sha(p) for n,p in jars.items()},
  "productionSources":6,"fixtureJavaSources":3,"dockerCalls":0,"pgCalls":0}
 save(out/"result.json",record);print(json.dumps({"prepare":identity,"status":record["status"],"sourceIdentity":record["sourceIdentity"]}));return 0
def run(root,identity):
 prep,r=prepared(root,identity);initial_identity=r["sourceIdentity"];start=time.monotonic();deadline=start+150;total=start+180;commands=[]
 def docker(cmd,timeout=10,check=True):
  remaining=int(deadline-time.monotonic());require(remaining>0,"DEADLINE")
  result=subprocess.run(["docker",*cmd],cwd=root,env=clean_env(),capture_output=True,text=True,timeout=min(timeout,remaining))
  commands.append(observation(["docker",*cmd],result))
  if check:require(result.returncode==0,"DOCKER_COMMAND")
  return result
 ram=subprocess.run(["powershell.exe","-NoProfile","-NonInteractive","-Command","(Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory"],env=clean_env(),capture_output=True,text=True,timeout=10)
 resources(int(ram.stdout.strip())*1024 if ram.returncode==0 and ram.stdout.strip().isdigit() else 0,shutil.disk_usage(root).free)
 docker(["version","--format","{{.Server.Version}}"])
 for image in (PG_IMAGE,JAVA_IMAGE):require(docker(["image","inspect",image,"--format","{{.Id}}|{{.Os}}|{{.Architecture}}"]).stdout.strip()==image+"|linux|amd64","PINNED_IMAGE")
 nets=docker(["network","ls","--format","{{.ID}}"]).stdout.splitlines();require(len(nets)<=1024 and all(re.fullmatch(r"[0-9a-f]{12,64}",n) for n in nets),"NETWORK_LIST")
 occupied=[]
 for i in range(0,len(nets),64):
  for line in docker(["network","inspect",*nets[i:i+64],"--format","{{json .IPAM.Config}}"]).stdout.splitlines():occupied.extend(x["Subnet"] for x in json.loads(line) or [] if x.get("Subnet"))
 routes=subprocess.run(["powershell.exe","-NoProfile","-NonInteractive","-Command","@(Get-NetRoute -AddressFamily IPv4 -ErrorAction Stop | Select-Object -ExpandProperty DestinationPrefix) | ConvertTo-Json -Compress"],env=clean_env(),capture_output=True,text=True,timeout=10)
 require(routes.returncode==0,"ROUTES");values=json.loads(routes.stdout);subnet=legacy.select_subnet(occupied,[values] if isinstance(values,str) else values)
 prep,r=prepared(root,identity);require(r["sourceIdentity"]==initial_identity,"PRECLAIM_IDENTITY")
 with (prep/"RUN_CLAIM").open("x",encoding="ascii") as f:f.write("ONE_NEW_SCHEMA_PG_UNIT_ATTEMPT_NO_RETRY\n")
 runid="ccpg-"+uuid.uuid4().hex[:16];out=root/"deployment/runtime/schema-pg"/runid
 try:
  out.mkdir(parents=True,exist_ok=False)
  fixture=out/"fixture";shutil.copytree(prep/"fixture",fixture);evidence=out/"java-evidence";evidence.mkdir()
  suffix=runid[5:];db,owner,api="ccpg_"+suffix,"ccowner_"+suffix,"ccapi_"+suffix
  passwords=[secrets.token_hex(32) for _ in range(3)]
  pg_env,java_env=out/"pg.private.env",out/"java.private.env"
  with pg_env.open("x",encoding="ascii") as f:f.write("POSTGRES_DB="+db+"\nPOSTGRES_USER="+owner+"\nPOSTGRES_PASSWORD="+passwords[0]+"\nPOSTGRES_INITDB_ARGS=--auth-host=scram-sha-256\n")
  with java_env.open("x",encoding="ascii") as f:f.write("SCHEMA_STARTUP_PG_TESTS=true\n"+"\n".join(k+"="+v for k,v in zip(("SCHEMA_PG_OWNER_PASSWORD","SCHEMA_PG_API_PASSWORD","SCHEMA_PG_BAD_PASSWORD"),passwords))+"\n")
  props={"unitScope":"SCHEMA_STARTUP_PG_ONLY","runId":runid,"database":db,"databaseOwner":owner,"apiUser":api,"sourceIdentity":r["sourceIdentity"],"jdbcUrl":"jdbc:postgresql://pg:5432/"+db+"?sslmode=disable&connectTimeout=3&socketTimeout=8",**{"sha."+k:v for k,v in r["sqlHashes"].items()}}
  with (fixture/"manifest.properties").open("x",encoding="ascii") as f:f.write("\n".join(k+"="+v for k,v in props.items())+"\n")
  runtime_hashes=tree_hashes(fixture)
 except Exception as ex:
  failure={"unitScope":"SCHEMA_STARTUP_PG_ONLY","run":runid,"sourceIdentity":r["sourceIdentity"],"status":"BLOCKED","safeError":"SCHEMA_PG_POSTCLAIM_STAGING_"+type(ex).__name__,"resourceCreates":0,"claimPreserved":True}
  try:save(out/"result.json",failure)
  except Exception:
   try:save(prep/("postclaim-failure-"+uuid.uuid4().hex[:8]+".json"),failure)
   except Exception:pass
  print(json.dumps(failure));return 1
 states={};preserved={};status="BLOCKED";error=None
 try:
  for kind,name in (("network",runid+"-network"),("volume",runid+"-data"),("container",runid+"-pg"),("container",runid+"-java")):require(docker([kind,"inspect",name],check=False).returncode!=0,"COLLISION")
  docker(["network","create","--internal","--subnet",subnet,"--label","tms.c.owner="+runid,runid+"-network"])
  docker(["volume","create","--label","tms.c.owner="+runid,runid+"-data"])
  for kind,name in (("network",runid+"-network"),("volume",runid+"-data")):
   info=json.loads(docker([kind,"inspect",name]).stdout)[0];require(info.get("Labels",{}).get("tms.c.owner")==runid,"OWNER")
   if kind=="network":
    ipam=info.get("IPAM",{}).get("Config",[])
    require(info.get("Internal") is True and len(ipam)==1 and ipam[0].get("Subnet")==subnet,"INTERNAL_NETWORK")
  def inspect(kind):
   values=json.loads(docker(["container","inspect",runid+"-"+kind]).stdout);require(len(values)==1,"INSPECT")
   validate(values[0],kind,runid,fixture,evidence);return values[0]
  docker(args("pg",runid,fixture,evidence,pg_env));inspect("pg")
  for _ in range(30):
   if docker(["exec",runid+"-pg","pg_isready","-h","127.0.0.1","-p","5432","-U",owner,"-d",db],check=False).returncode==0:break
   time.sleep(1)
  else:raise ValueError("SCHEMA_PG_NOT_READY")
  docker(args("java",runid,fixture,evidence,java_env));inspect("java")
  while time.monotonic()<deadline:
   state=inspect("java").get("State",{})
   if not state.get("Running"):require(state.get("ExitCode")==0 and not state.get("OOMKilled") and not state.get("Error"),"JAVA_FAILED");break
   time.sleep(1)
  else:raise ValueError("SCHEMA_PG_DEADLINE")
  result=json.loads((evidence/"result.json").read_text())
  require(result.get("unitScope")=="SCHEMA_STARTUP_PG_ONLY" and result.get("runId")==runid and result.get("sourceIdentity")==r["sourceIdentity"] and result.get("result")=="PASS" and all(result.get(k)==v for k,v in {"testsFound":6,"testsStarted":6,"testsSucceeded":6,"testsFailed":0,"testsSkipped":0,"testsAborted":0}.items()),"NEW_SIX_CASE_RESULT")
  status="ACTUAL_SCHEMA_STARTUP_PG_6_PASS_NOT_APP_OR_CHAIN"
 except Exception as ex:error=str(ex) if re.fullmatch(r"(SCHEMA_PG|C_CONSENT)_[A-Z0-9_]+",str(ex)) else "SCHEMA_PG_SAFE_FAILURE_"+type(ex).__name__
 finally:
  deadline=total
  for kind,name in legacy.cleanup_targets(runid):
   try:
    probe=docker(["container","inspect",name],timeout=2,check=False)
    if legacy.missing_object(probe):states[kind]={"normalStop":False,"present":False};continue
    require(probe.returncode==0,"STOP_INSPECT");info=json.loads(probe.stdout);require(len(info)==1 and info[0].get("Config",{}).get("Labels",{}).get("tms.c.owner")==runid,"STOP_OWNER")
    if info[0].get("State",{}).get("Running"):docker(["stop","-t","10",name],timeout=15)
    stop=json.loads(docker(["container","inspect",name],timeout=2).stdout)[0];require(stop.get("Config",{}).get("Labels",{}).get("tms.c.owner")==runid,"STOP_OWNER");s=stop.get("State",{})
    states[kind]={"normalStop":s.get("Running") is False and s.get("ExitCode") in (0,143) and not s.get("OOMKilled") and not s.get("Error")}
   except Exception:states[kind]={"normalStop":False}
  for kind,name in (("network",runid+"-network"),("volume",runid+"-data")):
   try:info=json.loads(docker([kind,"inspect",name],timeout=2).stdout);preserved[kind]=len(info)==1 and info[0].get("Labels",{}).get("tms.c.owner")==runid
   except Exception:preserved[kind]=False
  try:stable=tree_hashes(fixture)==runtime_hashes and tree_hashes(prep/"fixture")==r["fixtureHashes"] and r["sources"]=={p:sha(root/p) for p in FILES} and helper_hashes()==r["helperHashes"] and execution_identity(r["sources"],helper_hashes())==initial_identity
  except Exception:stable=False
  if not stable or not all(states.get(k,{}).get("normalStop") for k in ("pg","java")) or not all(preserved.values()) or time.monotonic()>total:status="BLOCKED";error=error or "SCHEMA_PG_FINAL_PRESERVATION"
  save(out/"result.json",{"unitScope":"SCHEMA_STARTUP_PG_ONLY","run":runid,"status":status,"safeError":error,"sourceIdentity":r["sourceIdentity"],"commands":commands,"stopped":states,"preserved":preserved,"sourcesAndFixturesUnchanged":stable,"elapsedSeconds":time.monotonic()-start,"noAnvilAppWalletKeysPaid":True})
 print(json.dumps({"run":runid,"status":status,"safeError":error}));return 0 if status.startswith("ACTUAL_") else 1
def main():
 require(len(sys.argv) in (2,3) and sys.argv[1] in ("prepare","run"),"ARGS")
 root=Path(__file__).resolve().parents[1]
 return prepare(root) if sys.argv[1]=="prepare" and len(sys.argv)==2 else run(root,sys.argv[2]) if len(sys.argv)==3 else 1
if __name__=="__main__":raise SystemExit(main())
