"""One bounded Chrome read acceptance on a cold copy of the successful TEST database."""
import copy,datetime,hashlib,json,os,re,shutil,socket,subprocess,sys,threading,time,uuid
from pathlib import Path
sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parents[1]
def module(name,path):
 import importlib.util
 s=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
base=module("browser_read_base",ROOT/"deployment/full-app-read-gate.py")
resume=module("browser_cold_copy",base.CROOT/"deployment/c-direct-same-state-resume.py")
DATA_RUN="ccpg-8949e3a7a933490e";PREPARE="20261023T211046Z-281eab64"
DATA_SHA="49ddb45697f2fb7652dda400d691c2bd982011a55413dc5052db0da6260f3880"
SOURCE="39bfb7912247a4fd8ff4277776478dc6385f667193fbddf24d0cb222b3ef724c"
NODE=ROOT/"tools/websocket-test-client/scripts/c-live-browser-smoke.mjs"
NODE_INPUTS=(NODE,ROOT/"tools/websocket-test-client/scripts/cdp-boundary.mjs",ROOT/"tools/websocket-test-client/test/c-live-browser-smoke.test.js",ROOT/"tools/websocket-test-client/test/cdp-boundary.test.js")
PIPE=base.CROOT/"deployment/CDirectBrowserPipe.java"
DIST=ROOT/"tools/websocket-test-client/dist"
WORK=310;TOTAL=400;READY=120;BROWSER=100
RAW_HASH="set -euo pipefail; test -s /from/PG_VERSION; test ! -e /from/postmaster.pid; test -z \"$(find /from -type l -print -quit)\"; tar --sort=name -C /from -cf - . | sha256sum | cut -d' ' -f1"
def require(ok,code):
 if not ok:raise ValueError("FULL_BROWSER_"+code)
def accepted():
 p=ROOT/"deployment/runtime/full-read-prepare"/PREPARE;r=json.loads((p/"result.json").read_text());old=ROOT/"deployment/runtime/full-read"/DATA_RUN
 require(base.sha(old/"result.json")==DATA_SHA,"DATA_RESULT_SHA")
 result=json.loads((old/"result.json").read_text())
 require(result["status"]=="ACTUAL_FULL_APP_HTTP_STOMP_C_READ_PASS_NOT_UI_OR_TRADING" and result["sourceIdentity"]==SOURCE and result["sourcesFixturesAndOriginalAnchorsUnchanged"] is True,"DATA_SUCCESS")
 require(r["sourceIdentity"]==SOURCE and r["sources"]=={k:base.sha(Path(k)) for k in r["sources"]} and r["helpers"]==base.helpers() and r["anchors"]==base.anchors(),"SOURCE_IDENTITY")
 require(r["appFixtureHashes"]==base.pg.tree_hashes(p/"app-fixture")==base.pg.tree_hashes(old/"app-fixture"),"COMPILED_APP")
 return p,r,old
def dist_hashes():
 selected=[p for p in DIST.rglob("*") if ".smoke" not in p.relative_to(DIST).parts]
 require(all(not p.is_symlink() for p in selected),"DIST_LINK")
 return dict(sorted((p.relative_to(DIST).as_posix(),base.sha(p)) for p in selected if p.is_file()))
def fingerprint():
 _,r,old=accepted()
 files=[Path(__file__),ROOT/"deployment/test_full_app_browser_gate.py",*NODE_INPUTS,PIPE,base.CROOT/"deployment/c-direct-same-state-resume.py",base.CROOT/"deployment/c_direct_browser_relay.py",ROOT/"deployment/full-app-read-gate.py"]
 files += [old/n for n in ("result.json","pg.private.env","driver.private.env","config/application.properties","config/denied-oracle-token")]
 web=ROOT/"tools/websocket-test-client"
 node=shutil.which("node");require(node is not None,"NODE_RUNTIME")
 files += [Path(node),Path("C:/Program Files/Google/Chrome/Application/chrome.exe")]
 files += [p for folder in (web/"src",web/"fixtures") for p in folder.rglob("*") if p.is_file()]+[web/"index.html",web/"package-lock.json",web/"package.json"]
 return {"files":{str(p):base.sha(p) for p in files},"dist":dist_hashes(),"compiled":r["appFixtureHashes"],"anchors":base.anchors(),"helpers":base.helpers()}
def identity(f):return hashlib.sha256(json.dumps(f,sort_keys=True).encode()).hexdigest()
def browser_result(evidence,source,dist):
 r=json.loads((evidence/"result.json").read_text())
 require(r.get("verdict")=="PASS" and r.get("scope")=="ACTUAL_SERVICE_READ_ONLY_TEST_WALLET_ADAPTER_NOT_METAMASK_NOT_TRADING" and r.get("sourceIdentity")==source,"BROWSER_VERDICT")
 require(all(r.get(k) is True for k in ("distUnchanged","accountRendered","ownSpawnExited","serverClosed")) and r.get("traceFailed") is False and r.get("runtimeError") is False and r.get("externalRequestsAllowed") is False,"BROWSER_CLEANUP")
 require(r.get("financialCalls")==r.get("paidCalls")==r.get("deniedSigning")==0 and r.get("wsUpgrades",0)>0,"READ_ONLY_EVIDENCE")
 api=r.get("api",[])
 for path in ("/api/auth/login","/api/me","/api/markets/mSEC","/api/c-futures/config","/api/c-futures/account"):
  require(any(a.get("path")==path and a.get("status")==200 for a in api),"ACTUAL_API_READ_CODES")
 require(r.get("distBefore")==dist,"NODE_DIST_IDENTITY")
 names={"login-dialog","market","market-chart","futures","custody","ai","spot-form","c-account-metrics","admin","admin-audit-off"}
 images=r.get("images",[]);require(len(images)==len(names) and {i.get("name") for i in images}==names,"SCREENSHOT_COUNT")
 for item in images:
  p=evidence/(item["name"]+".png");require(p.is_file() and p.read_bytes()[:8]==b"\x89PNG\r\n\x1a\n" and base.sha(p)==item.get("sha256"),"SCREENSHOT_HASH")
 require({p.get("route") for p in r.get("pages",[])}=={"market","futures","custody","ai","admin"} and all(p.get("rendered") is True for p in r["pages"]),"RENDERED_ROUTES")
 require(all(isinstance(p.get("api"),list) and isinstance(p.get("controls"),list) for p in r["pages"]),"ROUTE_API_CONTROLS_EVIDENCE")
 return r
def launch(role,run,paths,env):
 cmd=base.launch(role,run,paths,env)
 if role=="app":
  at=cmd.index("--entrypoint");cmd[at:at]=["--mount",base.bind(paths["helper"],"/server-fixture",True)]
 return cmd
def validate(info,role,run,paths,env):
 safe=copy.deepcopy(info)
 if role=="app":
  mounts=[m for m in info.get("Mounts",[]) if m.get("Destination")=="/server-fixture"]
  require(len(mounts)==1 and mounts[0].get("Type")=="bind" and mounts[0].get("RW") is False and resume.mount_path(mounts[0].get("Source",""))==resume.mount_path(paths["helper"]),"HELPER_RO_MOUNT")
  safe["Mounts"]=[m for m in safe["Mounts"] if m.get("Destination")!="/server-fixture"]
 base.validate(safe,role,run,paths,env)
class AppRelay(resume.BrowserRelay):
 """Only APP listener. Reuses the reviewed opaque bridge/accept/close implementation."""
 def __init__(self,run,app,paths,helper_hashes):
  validate(app,"app",run,paths,Path("unused.env"))
  require(app.get("State",{}).get("Running") is True and re.fullmatch(r"[0-9a-f]{64}",app.get("Id","")),"RELAY_APP_ID")
  require(base.pg.tree_hashes(paths["helper"])==helper_hashes,"RELAY_HELPER_HASH")
  self.meta={"appId":app["Id"],"seconds":120};self.control=paths["control"]
  require(self.control.is_dir() and not(self.control/"STOP").exists(),"RELAY_CONTROL")
  self.done=threading.Event();self.lock=threading.RLock();self.slots={"APP":threading.BoundedSemaphore(4)}
  self.listeners=[];self.clients=[];self.children=[];self.threads=[];self.accepted=0;self.max_connections=64
 def start(self):
  listener=socket.socket(socket.AF_INET,socket.SOCK_STREAM);self.listeners.append(listener)
  try:
   if hasattr(socket,"SO_EXCLUSIVEADDRUSE"):listener.setsockopt(socket.SOL_SOCKET,socket.SO_EXCLUSIVEADDRUSE,1)
   listener.bind(("127.0.0.1",0));listener.listen(32);listener.settimeout(.25)
   self.port=listener.getsockname()[1];self.end=time.monotonic()+120
   thread=threading.Thread(target=self.accept,args=(listener,"APP"),daemon=True);self.threads.append(thread);thread.start();return self
  except Exception:self.close();raise
 def bridge(self,client,role):
  process=None;acquired=False;upload=None
  try:
   require(role=="APP","RELAY_APP_ONLY");acquired=self.slots["APP"].acquire(timeout=10)
   if not acquired or self.done.is_set():return
   with self.lock:
    if self.done.is_set():return
    relay_module=sys.modules[resume.BrowserRelay.__module__]
    process=subprocess.Popen(relay_module.pipe_command(self.meta["appId"],"APP"),stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,bufsize=0,env=base.pg.clean_env());self.children.append(process)
   def send():
    try:
     while not self.done.is_set():
      data=client.recv(65536)
      if not data:break
      remaining=memoryview(data)
      while remaining:
       count=process.stdin.write(remaining)
       if not count:raise OSError("PIPE_CLOSED")
       remaining=remaining[count:]
      process.stdin.flush()
    except (OSError,ValueError):pass
    finally:
     try:process.stdin.close()
     except (OSError,ValueError):pass
   upload=threading.Thread(target=send,daemon=True)
   with self.lock:self.threads.append(upload)
   upload.start()
   while not self.done.is_set():
    data=process.stdout.read(65536)
    if not data:break
    client.sendall(data)
  except (OSError,ValueError):pass
  finally:
   try:client.shutdown(socket.SHUT_RDWR)
   except OSError:pass
   client.close()
   if process:
    if process.poll() is None:process.terminate()
    try:process.wait(timeout=2)
    except subprocess.TimeoutExpired:process.kill();process.wait(timeout=1)
    process.stdout.close()
   if upload:upload.join(.5)
   if acquired:self.slots["APP"].release()
 def finish(self):
  self.close();end=time.monotonic()+4
  for thread in list(self.threads):
   if thread is not threading.current_thread():thread.join(max(0,end-time.monotonic()))
  for child in self.children:
   if child.poll() is None:child.terminate()
   try:child.wait(timeout=max(.1,end-time.monotonic()))
   except subprocess.TimeoutExpired:child.kill();child.wait(timeout=1)
  return all(child.poll() is not None for child in self.children) and all(not t.is_alive() for t in self.threads)
def copy_cmd(run):
 return ["run","-d","--pull=never","--name",run+"-copy","--label","tms.c.owner="+run,"--label","tms.c.parent="+DATA_RUN,"--label","tms.c.parent-state="+base.ANCHORS[base.SNAPSHOT],"--network","none","--read-only","--memory=256m","--pids-limit=64","--cap-drop=ALL","--cap-add=DAC_OVERRIDE","--cap-add=CHOWN","--cap-add=FOWNER","--security-opt=no-new-privileges","--tmpfs","/var/lib/postgresql/data:rw,noexec,nosuid,size=1m","--mount","type=volume,source="+DATA_RUN+"-data,target=/from,readonly","--mount","type=volume,source="+run+"-data,target=/to","--entrypoint","/bin/bash",base.PG_IMAGE,"-ceu",resume.COPY]
def hash_cmd(run):
 return ["run","-d","--pull=never","--name",run+"-hash","--label","tms.c.owner="+run,"--network","none","--read-only","--memory=128m","--pids-limit=64","--cap-drop=ALL","--cap-add=DAC_OVERRIDE","--security-opt=no-new-privileges","--tmpfs","/var/lib/postgresql/data:rw,noexec,nosuid,size=1m","--mount","type=volume,source="+DATA_RUN+"-data,target=/from,readonly","--entrypoint","/bin/bash",base.PG_IMAGE,"-ceu",RAW_HASH]
def validate_hash(info,run):
 c,h,n=info.get("Config",{}),info.get("HostConfig",{}),info.get("NetworkSettings",{})
 require(info.get("Name")=="/"+run+"-hash" and info.get("Image")==base.PG_IMAGE and c.get("Labels",{}).get("tms.c.owner")==run,"HASH_OWNER_IMAGE")
 require(h.get("NetworkMode")=="none" and set(n.get("Networks",{}))<={"none"} and not h.get("PortBindings") and not any((n.get("Ports") or {}).values()) and not h.get("Privileged") and h.get("ReadonlyRootfs") is True and h.get("Memory")==128*1024**2,"HASH_ISOLATION")
 require(h.get("CapDrop")==["ALL"] and {v.removeprefix("CAP_") for v in h.get("CapAdd",[])}=={"DAC_OVERRIDE"} and any(v in ("no-new-privileges","no-new-privileges:true") for v in h.get("SecurityOpt",[])),"HASH_CAPS")
 require(c.get("Entrypoint")==["/bin/bash"] and c.get("Cmd")==["-ceu",RAW_HASH] and h.get("Tmpfs")=={"/var/lib/postgresql/data":"rw,noexec,nosuid,size=1m"},"HASH_COMMAND")
 mounts=[m for m in info.get("Mounts",[]) if m.get("Type")!="tmpfs"]
 require(len(mounts)==1 and mounts[0].get("Type")=="volume" and mounts[0].get("Name")==DATA_RUN+"-data" and mounts[0].get("Destination")=="/from" and mounts[0].get("RW") is False,"HASH_SOURCE_RO")
def prepare():
 f=fingerprint();require(f["dist"] and NODE.is_file(),"BROWSER_DIST_REQUIRED")
 run=datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")+"-"+uuid.uuid4().hex[:8];out=ROOT/"deployment/runtime/full-browser-prepare"/run;out.mkdir(parents=True)
 helper=out/"helper-fixture";(helper/"classes").mkdir(parents=True);empty=out/"empty-sourcepath";empty.mkdir()
 jdk=Path("C:/Program Files/Java/jdk-21.0.10/bin")
 compile=subprocess.run([str(jdk/"javac.exe"),"--release","21","-parameters","-encoding","UTF-8","-proc:none","-implicit:none","-sourcepath",str(empty),"-cp",str(empty),"-d",str(helper/"classes"),str(PIPE)],cwd=ROOT,env=base.pg.clean_env(),capture_output=True,timeout=30)
 with (out/"transport-compile.stderr.log").open("xb") as log:log.write(compile.stderr)
 require(compile.returncode==0,"HELPER_COMPILE")
 require({p.name for p in (helper/"classes").iterdir()}=={"CDirectBrowserPipe.class","CDirectBrowserPipe$1.class"},"HELPER_CLASSES")
 selftest=subprocess.run([str(jdk/"java.exe"),"-Xmx32m","-cp",str(helper/"classes"),"CDirectBrowserPipe","--self-test"],env=base.pg.clean_env(),capture_output=True,text=True,timeout=10)
 with (out/"transport-selftest.stdout.log").open("x",encoding="utf-8") as log:log.write(selftest.stdout)
 require(selftest.returncode==0 and selftest.stdout.strip()=="C_BROWSER_PIPE_NO_SOCKET_SELF_TEST_PASS","HELPER_SELFTEST")
 test=subprocess.run([sys.executable,"-B","-m","unittest","discover","-s","deployment","-p","test_full_app_browser_gate.py"],cwd=ROOT,env=base.pg.clean_env(),capture_output=True,text=True,timeout=30)
 with (out/"pure.stderr.log").open("x",encoding="utf-8") as log:log.write(test.stderr)
 require(test.returncode==0,"PURE_GUARDS")
 syntax=subprocess.run(["node","--check",str(NODE)],cwd=ROOT,env=base.pg.clean_env(),capture_output=True,timeout=20);require(syntax.returncode==0,"NODE_SYNTAX")
 require(f==fingerprint(),"END_PREPARE_IDENTITY")
 helper_hashes=base.pg.tree_hashes(helper);execution=identity({"fingerprint":f,"helperFixtureHashes":helper_hashes})
 base.save(out/"result.json",{"status":"PREPARED_NOT_CHROME_EXECUTED","sourceIdentity":execution,"fingerprint":f,"helperFixtureHashes":helper_hashes,"dataRun":DATA_RUN,"compiledPrepare":PREPARE,"transportCompile":0,"transportSelftest":True,"pureGuardExitCode":test.returncode,"nodeSyntaxExitCode":syntax.returncode,"dockerCalls":0,"chromeCalls":0})
 print(json.dumps({"prepare":run,"sourceIdentity":execution,"status":"PREPARED_NOT_CHROME_EXECUTED"}))
def checked(prepared):
 require(re.fullmatch(r"\d{8}T\d{6}Z-[0-9a-f]{8}",prepared),"PREPARE_ID")
 p=ROOT/"deployment/runtime/full-browser-prepare"/prepared;r=json.loads((p/"result.json").read_text())
 require(r["status"]=="PREPARED_NOT_CHROME_EXECUTED" and r["fingerprint"]==fingerprint() and r["helperFixtureHashes"]==base.pg.tree_hashes(p/"helper-fixture") and r["sourceIdentity"]==identity({"fingerprint":r["fingerprint"],"helperFixtureHashes":r["helperFixtureHashes"]}) and not(p/"RUN_CLAIM").exists(),"PREPARED_UNUSED_IDENTITY")
 return p,r
def run_gate(prepared):
 prep,record=checked(prepared);start=time.monotonic();deadline=start+WORK;total=start+TOTAL;events=[]
 def docker(args,timeout=10,check=True):
  require(time.monotonic()<deadline,"DEADLINE")
  p=subprocess.run(["docker",*args],env=base.pg.clean_env(),capture_output=True,text=True,timeout=min(timeout,max(1,int(deadline-time.monotonic()))));events.append(base.pg.observation(["docker",*args],p))
  if check:require(p.returncode==0,"DOCKER")
  return p
 def inspect(name):return json.loads(docker(["container","inspect",name]).stdout)[0]
 ram=subprocess.run(["powershell.exe","-NoProfile","-NonInteractive","-Command","(Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory"],env=base.pg.clean_env(),capture_output=True,text=True,timeout=10)
 base.pg.resources(int(ram.stdout.strip())*1024 if ram.returncode==0 and ram.stdout.strip().isdigit() else 0,shutil.disk_usage(ROOT).free)
 for image in (base.PG_IMAGE,base.JAVA_IMAGE,base.ANVIL_IMAGE):require(docker(["image","inspect",image,"--format","{{.Id}}|{{.Os}}|{{.Architecture}}"]).stdout.strip()==image+"|linux|amd64","IMAGE")
 old_pg=inspect(DATA_RUN+"-pg");resume.check_old_role(old_pg,DATA_RUN,"pg",base.PG_IMAGE)
 base.validate(old_pg,"pg",DATA_RUN,{},Path("unused.env"));require(old_pg.get("Config",{}).get("Labels",{}).get("tms.full.read.scope")=="NON_USER_READ_INTEGRATION","SOURCE_READ_SCOPE")
 source_metadata=identity(old_pg)
 require(any(m.get("Name")==DATA_RUN+"-data" and m.get("Destination")=="/var/lib/postgresql/data" for m in old_pg.get("Mounts",[])),"SOURCE_VOLUME")
 volume=json.loads(docker(["volume","inspect",DATA_RUN+"-data"]).stdout)[0];require(volume.get("Labels",{}).get("tms.c.owner")==DATA_RUN,"SOURCE_VOLUME_OWNER")
 # Reuse bounded subnet selection, never attach to an existing network.
 ids=docker(["network","ls","--format","{{.ID}}"]).stdout.splitlines();require(len(ids)<=1024,"NETWORK_BOUND")
 occupied=[]
 for i in range(0,len(ids),64):
  for line in docker(["network","inspect",*ids[i:i+64],"--format","{{json .IPAM.Config}}"]).stdout.splitlines():occupied.extend(x["Subnet"] for x in json.loads(line) or [] if x.get("Subnet"))
 route=subprocess.run(["powershell.exe","-NoProfile","-NonInteractive","-Command","@(Get-NetRoute -AddressFamily IPv4 -ErrorAction Stop | Select-Object -ExpandProperty DestinationPrefix) | ConvertTo-Json -Compress"],env=base.pg.clean_env(),capture_output=True,text=True,timeout=10)
 require(route.returncode==0,"ROUTES");routes=json.loads(route.stdout);subnet=base.pg.legacy.select_subnet(occupied,[routes] if isinstance(routes,str) else routes)
 checked(prepared)
 with (prep/"RUN_CLAIM").open("x",encoding="ascii") as f:f.write("ONE_TEST_DB_BROWSER_ATTEMPT_NO_RETRY\n")
 run="ccpg-"+uuid.uuid4().hex[:16];out=ROOT/"deployment/runtime/full-browser"/run
 try:out.mkdir(parents=True)
 except Exception as failure:
  base.save(prep/("postclaim-"+run+".json"),{"status":"BLOCKED","stage":"POSTCLAIM_STAGING","safeError":type(failure).__name__,"resourceCreates":0,"claimPreserved":True});return 1
 stage="STAGING";status="BLOCKED";error=None;states={};paths={};preserved={};raw_hash=None;relay=None
 try:
  _,_,old=accepted()
  for key in ("config","control","appEvidence","forkEvidence"):paths[key]=out/key;paths[key].mkdir()
  paths["browserEvidence"]=out/"browserEvidence" # Node exclusively creates this directory.
  paths["app"]=out/"app-fixture";shutil.copytree(old/"app-fixture",paths["app"])
  paths["helper"]=out/"helper-fixture";shutil.copytree(prep/"helper-fixture",paths["helper"])
  for name in ("application.properties","denied-oracle-token"):shutil.copyfile(old/"config"/name,paths["config"]/name)
  for name in ("pg.private.env","driver.private.env"):shutil.copyfile(old/name,out/name)
  runtime_app=base.pg.tree_hashes(paths["app"]);runtime_config=base.pg.tree_hashes(paths["config"])
  env=out/"pg.private.env"
  for kind,name in (("network",run+"-network"),("volume",run+"-data"),*[("container",run+"-"+r) for r in ("copy","hash","pg","anvil","app")]):require(docker([kind,"inspect",name],check=False).returncode!=0,"COLLISION")
  docker(["network","create","--internal","--subnet",subnet,"--label","tms.c.owner="+run,run+"-network"]);docker(["volume","create","--label","tms.c.owner="+run,run+"-data"])
  for kind,name in (("network",run+"-network"),("volume",run+"-data")):
   obj=json.loads(docker([kind,"inspect",name]).stdout)[0];require(obj.get("Labels",{}).get("tms.c.owner")==run,"NEW_OWNER")
   if kind=="network":require(obj.get("Internal") is True and obj.get("IPAM",{}).get("Config",[])[0].get("Subnet")==subnet,"NEW_INTERNAL_NETWORK")
  stage="COLD_COPY";docker(copy_cmd(run));resume.validate_copy(inspect(run+"-copy"),run,DATA_RUN,base.ANCHORS[base.SNAPSHOT],DATA_RUN+"-data",run+"-data",base.PG_IMAGE)
  require(docker(["wait",run+"-copy"],timeout=55).stdout.strip()=="0","COPY_EXIT")
  logs=docker(["logs",run+"-copy"]).stdout;matches=re.findall(r"C_RESUME_PG_COLD_COPY_MATCH ([0-9a-f]{64})",logs);require(len(matches)==1,"COPY_FINGERPRINT");raw_hash=matches[0]
  stage="TARGET_PG";docker(launch("pg",run,paths,env));validate(inspect(run+"-pg"),"pg",run,paths,env)
  suffix=DATA_RUN[5:];owner="ccowner_"+suffix;db="ccpg_"+suffix
  for _ in range(30):
   if docker(["exec",run+"-pg","pg_isready","-h","127.0.0.1","-U",owner,"-d",db],check=False).returncode==0:break
   time.sleep(.5)
  else:raise ValueError("FULL_BROWSER_PG_READY")
  def sql(query):return docker(["exec",run+"-pg","psql","-XAt","-v","ON_ERROR_STOP=1","-U",owner,"-d",db,"-c",query]).stdout.strip()
  rows=json.loads(sql("SELECT json_agg(json_build_object('login',u.login_id,'role',u.role,'wallet',w.wallet) ORDER BY u.login_id) FROM public.users u JOIN tms_c_identity.wallet_links w ON w.user_id=u.id"))
  require(len(rows)==2 and all(r["role"]=="USER" for r in rows) and [r["login"] for r in rows]==["read_"+suffix+"_0","read_"+suffix+"_1"] and all(re.fullmatch(r"0x[0-9a-f]{40}",r["wallet"]) for r in rows),"TEST_USERS")
  require(sql("SELECT count(*) FROM public.user_balances WHERE amount<>0 OR locked_amount<>0")=="0","ZERO_BALANCES")
  require(sql("UPDATE public.users SET role='ADMIN' WHERE login_id='read_"+suffix+"_1' AND role='USER' RETURNING role").splitlines()[0]=="ADMIN","TARGET_ADMIN_ONLY")
  require(sql("SELECT string_agg(role,',' ORDER BY login_id) FROM public.users")=="USER,ADMIN","TARGET_ROLES")
  stage="NEW_HIDDEN_FORK";docker(launch("anvil",run,paths,env));validate(inspect(run+"-anvil"),"anvil",run,paths,env)
  stage="ACTUAL_APP";docker(launch("app",run,paths,env));validate(inspect(run+"-app"),"app",run,paths,env)
  end=min(deadline,time.monotonic()+READY)
  while time.monotonic()<end:
   require(inspect(run+"-app")["State"]["Running"],"APP_STOPPED")
   if (paths["appEvidence"]/"ready.json").exists():break
   time.sleep(.5)
  else:raise ValueError("FULL_BROWSER_APP_READY_TIMEOUT")
  require(json.loads((paths["appEvidence"]/"ready.json").read_text()).get("actualExchangeApplication") is True,"ACTUAL_APP_READY")
  stage="APP_ONLY_STDIO_RELAY";relay=AppRelay(run,inspect(run+"-app"),paths,record["helperFixtureHashes"]).start();port=relay.port
  values=dict(line.split("=",1) for line in (out/"driver.private.env").read_text().splitlines() if "=" in line)
  public=json.loads((base.CROOT/base.PUBLIC_IDENTITY).read_text());require(public["origin"]=="http://127.0.0.1:32171","ORIGIN")
  config={"appBaseUrl":"http://127.0.0.1:"+str(port),"origin":public["origin"],"login0":rows[0]["login"],"login1":rows[1]["login"],"password":values["FULL_READ_LOGIN_PASSWORD"],"wallet0":rows[0]["wallet"],"wallet1":rows[1]["wallet"],"chainId":str(public["chainId"]),"genesisHash":public["genesisHash"],"sourceIdentity":record["sourceIdentity"],"distHashes":record["fingerprint"]["dist"]}
  base.save(out/"browser.private.json",config)
  require(record["fingerprint"]==fingerprint() and record["helperFixtureHashes"]==base.pg.tree_hashes(paths["helper"])==base.pg.tree_hashes(prep/"helper-fixture") and runtime_app==base.pg.tree_hashes(paths["app"]) and runtime_config==base.pg.tree_hashes(paths["config"]),"PRE_NODE_IDENTITY")
  stage="ACTUAL_CHROME";node=subprocess.run(["node",str(NODE),str(out/"browser.private.json"),str(paths["browserEvidence"])],cwd=ROOT/"tools/websocket-test-client",env=base.pg.clean_env(),capture_output=True,timeout=min(BROWSER,max(1,int(deadline-time.monotonic()))));require(node.returncode==0,"CHROME_FAILED")
  browser_result(paths["browserEvidence"],record["sourceIdentity"],record["fingerprint"]["dist"])
  status="ACTUAL_FULL_APP_CHROME_READ_PASS_NOT_METAMASK_OR_TRADING"
 except Exception as failure:error=str(failure) if re.fullmatch(r"FULL_BROWSER_[A-Z0-9_]+",str(failure)) else "FULL_BROWSER_SAFE_"+type(failure).__name__
 finally:
  deadline=total
  try:states["relay"]=relay.finish() if relay else True
  except Exception:states["relay"]=False
  try:
   with (paths["control"]/"STOP").open("x",encoding="ascii") as f:f.write("STOP_THIS_TEST_COPY\n")
  except Exception:pass
  for role in ("app","anvil","pg","copy"):
   try:
    obj=inspect(run+"-"+role);require(obj["Config"]["Labels"].get("tms.c.owner")==run,"STOP_OWNER")
    if role=="app":
     end=min(deadline,time.monotonic()+10)
     while time.monotonic()<end and not (paths["appEvidence"]/"normal-stop.json").exists():time.sleep(.2)
    if obj["State"]["Running"]:docker(["stop","-t","10",run+"-"+role],timeout=15)
    s=inspect(run+"-"+role)["State"];states[role]=not s["Running"] and s["ExitCode"] in (0,143) and not s["OOMKilled"] and not s["Error"]
   except Exception:states[role]=False
  try:
   stable=record["fingerprint"]==fingerprint() and record["helperFixtureHashes"]==base.pg.tree_hashes(paths["helper"])==base.pg.tree_hashes(prep/"helper-fixture") and base.transaction_ids(base.CROOT/base.SNAPSHOT)==base.transaction_ids(paths["forkEvidence"]/"anvil.json") and runtime_app==base.pg.tree_hashes(paths["app"]) and runtime_config==base.pg.tree_hashes(paths["config"])
   original=inspect(DATA_RUN+"-pg");resume.check_old_role(original,DATA_RUN,"pg",base.PG_IMAGE)
   require(identity(original)==source_metadata and identity(json.loads(docker(["volume","inspect",DATA_RUN+"-data"]).stdout)[0])==identity(volume),"SOURCE_METADATA_UNCHANGED")
   require(raw_hash is not None,"RAW_COPY_HASH_MISSING")
   docker(hash_cmd(run));validate_hash(inspect(run+"-hash"),run)
   require(docker(["wait",run+"-hash"],timeout=45).stdout.strip()=="0","FINAL_SOURCE_HASH_EXIT")
   require(docker(["logs",run+"-hash"]).stdout.strip()==raw_hash,"SOURCE_RAW_UNCHANGED")
   states["hash"]=inspect(run+"-hash")["State"]["ExitCode"]==0
   closed=json.loads((paths["appEvidence"]/"normal-stop.json").read_text()).get("normalStop") is True
  except Exception:
   stable=False;closed=False
   try:
    h=inspect(run+"-hash");require(h["Config"]["Labels"].get("tms.c.owner")==run,"HASH_STOP_OWNER")
    if h["State"]["Running"]:docker(["stop","-t","2",run+"-hash"],timeout=5)
    states["hash"]=False
   except Exception:pass
  for kind,name in (("network",run+"-network"),("volume",run+"-data")):
   try:preserved[kind]=json.loads(docker([kind,"inspect",name]).stdout)[0].get("Labels",{}).get("tms.c.owner")==run
   except Exception:preserved[kind]=False
  if not stable or not closed or not all(states.values()) or not all(preserved.values()) or time.monotonic()>total:status="BLOCKED";error=error or "FULL_BROWSER_FINAL_PRESERVATION"
  base.save(out/"result.json",{"run":run,"dataRun":DATA_RUN,"dataIdentity":"TEST_DB_CLONE_USER0_USER_USER1_ADMIN_NOT_OFFICIAL_8D_USER","status":status,"stage":stage,"safeError":error,"sourceIdentity":record["sourceIdentity"],"sourcePgMetadataSHA256":source_metadata,"stopped":states,"preserved":preserved,"sourcesAnchorsFixturesUnchanged":stable,"sourceRawHashAtColdCopy":raw_hash,"officialClaimTouched":False,"commands":events,"elapsedSeconds":time.monotonic()-start})
 print(json.dumps({"run":run,"status":status,"safeError":error}))
 return 0 if status=="ACTUAL_FULL_APP_CHROME_READ_PASS_NOT_METAMASK_OR_TRADING" else 1
if __name__=="__main__":
 require(sys.argv[1:]==["prepare"] or len(sys.argv)==3 and sys.argv[1]=="run","EXPLICIT_MODE")
 raise SystemExit(prepare() if sys.argv[1]=="prepare" else run_gate(sys.argv[2]))
