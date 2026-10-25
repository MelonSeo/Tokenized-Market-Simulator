"""Bounded read acceptance. Fresh PG + read-only clone of a NON-USER contract test chain.

prepare compiles current sources only. run consumes one claim; no financial sender exists.
All runtime credentials and test keys stay in ignored runtime, never in public output.
"""
import datetime,hashlib,json,os,secrets,shutil,socket,socketserver,subprocess,sys,threading,time,uuid
from pathlib import Path
import product_read_network as network
import product_read_resources as resources
from product_read_tools import find_jdk,ToolError
sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parents[1]
ORIGINAL=ROOT.parents[2]
CROOT=ROOT.parent/'dapp-c-development-worktree'
TEST=CROOT/'deployment/runtime/c-anvil/canvil-9c60eadeb80647f8'
PG='sha256:c1b3783309b6e4a30e9f3bf86887f8fba0c49c2ce4d6d8732eb6e16b165d2387'
JAVA='sha256:35be56d41389c9ad515c089dfded911ae9a8bdb362c8a303224666cd358bead3'
ANVIL='sha256:0c00cb0bda1ab1b91c9a6bf60f4c76c09c1a8870824b6d4718afbabacf6f9a17'
OWN=['ProductReadGate.java','ProductReadDriver.java','ProductReadDriverRpcTest.java','ProductReadConnection.java','ProductReadConnectionTest.java','FullAppReadGate.java','FullAppReadDriver.java','FaucetSchemaFixtureBridge.java','ConditionalSchemaFixtureBridge.java','CDirectBrowserPipe.java']
def require(ok,code):
 if not ok:raise RuntimeError('PRODUCT_READ_'+code)
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def save(p,obj):p.write_text(json.dumps(obj,indent=2),encoding='utf8')
def clean():return {k:v for k,v in os.environ.items() if k.upper() in {'PATH','PATHEXT','SYSTEMROOT','WINDIR','COMSPEC','TEMP','TMP','USERPROFILE','APPDATA','LOCALAPPDATA','PROGRAMFILES'}}
def execute(cmd,timeout=30,**kw):return subprocess.run(list(map(str,cmd)),timeout=timeout,env=clean(),capture_output=True,**kw)
def docker(*args,timeout=30):
 r=execute(['docker',*args],timeout);require(r.returncode==0,'DOCKER_'+str(args[0]).upper());return r.stdout.decode('utf8').strip()
def q(v):return '"'+str(v).replace('\\','/').replace('"','\\"')+'"'

def aliases(role):return ['--network-alias',role] if role in {'pg','anvil','app'} else []
def source_map():
 paths=[p for d in ['backend/src/main','tools/websocket-test-client/src'] for p in (ROOT/d).rglob('*') if p.is_file()]
 paths += [ROOT/'deployment'/n for n in OWN]+[Path(__file__),ROOT/'tools/websocket-test-client/scripts/product-read-browser.mjs']
 paths += [ROOT/'tools/websocket-test-client'/n for n in ['index.html','package.json','package-lock.json','scripts/cdp-boundary.mjs','scripts/c-live-browser-smoke.mjs','scripts/browser-executable.mjs','test/browser-executable.test.mjs']]
 paths += [ROOT/'deployment'/n for n in ['product_read_network.py','test_product_read_network.py','c_gate_network.py']]
 paths += [ROOT/'deployment'/n for n in ['product-read-rpc-unit.py','test_product_read_connections.py','product_read_resources.py','test_product_read_resources.py','product-read-resource-compare.py','product_read_tools.py','test_product_read_tools.py']]
 return {str(p.relative_to(ROOT)):sha(p) for p in sorted(paths)}
def test_inputs():
 paths=[TEST/'result.json',TEST/'private/anvil-stopped-state.private.json',TEST/'private/addresses.json',TEST/'private/gate-result.json',TEST/'private/keys.private.json']
 paths += [TEST/('private/code-'+n+'.json') for n in ['CMockCollateral','CCollateralizedFuturesController-claims','FuturesEngine-claims','CMarginVault-claims']]
 paths += sorted((TEST/'private').glob('*-receipt.observed.private.json'))
 return {str(p.relative_to(TEST)):sha(p) for p in paths}
def build():
 jdk=find_jdk()
 out=ROOT/'deployment/runtime/product-read'/('prepare-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')+'-'+uuid.uuid4().hex[:8]);out.mkdir(parents=True)
 previous=ROOT.parent/'c-product-web-integration-worktree/deployment/runtime/c-full-read-compile-results/20261023T190509Z-a2f8c06a/result.json'
 deps={Path(k):v for k,v in json.loads(previous.read_text())['dependencies'].items()};require(all(sha(p)==h for p,h in deps.items()),'DEPENDENCIES')
 lombok=next(p for p in deps if p.name.startswith('lombok-'));runtime=[p for p in deps if p!=lombok]
 fixture=out/'fixture'
 for name in ['classes','helpers','jars']:(fixture/name).mkdir(parents=True)
 for p in runtime:shutil.copy2(p,fixture/'jars'/p.name)
 before=source_map();sources=list((ROOT/'backend/src/main/java').rglob('*.java'))
 def compile(label,files,cp,dest,processor=False):
  args=['--release','21','-parameters','-encoding','UTF-8','-cp',cp,'-d',str(dest)]
  args += ['-processorpath',str(lombok),'-processor','lombok.launch.AnnotationProcessorHider$AnnotationProcessor'] if processor else ['-proc:none']
  args += list(map(str,files));argfile=out/(label+'.args');argfile.write_text('\n'.join(map(q,args)),encoding='utf8')
  r=execute([jdk['javac'],'-J-Xmx512m','@'+str(argfile)],120);(out/(label+'.stdout.log')).write_bytes(r.stdout);(out/(label+'.stderr.log')).write_bytes(r.stderr);require(r.returncode==0,label.upper())
 compile('main',sources,os.pathsep.join(map(str,[*runtime,lombok])),fixture/'classes',True)
 shutil.copytree(ROOT/'backend/src/main/resources',fixture/'classes',dirs_exist_ok=True)
 compile('helpers',[ROOT/'deployment'/n for n in OWN],os.pathsep.join(map(str,[fixture/'classes',*runtime])),fixture/'helpers')
 for cls in ['ProductReadGate','CDirectBrowserPipe','ProductReadDriverRpcTest','ProductReadConnectionTest']:
  r=execute([jdk['java'],'-cp',os.pathsep.join(map(str,[fixture/'helpers',fixture/'classes',*runtime])),cls,'--self-test'],15);require(r.returncode==0,'SELF_TEST');(out/(cls+'.log')).write_bytes(r.stdout+r.stderr)
 require((ROOT/'tools/websocket-test-client/dist/index.html').is_file(),'BUILD_WEB_FIRST')
 shutil.copytree(ROOT/'tools/websocket-test-client/dist',fixture/'classes/static',ignore=shutil.ignore_patterns('.smoke'))
 require(before==source_map(),'SOURCE_DRIFT')
 hashes={str(p.relative_to(fixture)):sha(p) for p in fixture.rglob('*') if p.is_file()}
 save(out/'prepared.json',{'status':'PREPARED_NOT_LIVE','sources':before,'fixture':hashes,'mainSources':len(sources),'testInputs':test_inputs()})
 print(json.dumps({'prepare':out.name,'status':'PREPARED_NOT_LIVE'}))
def run(prepare):
 out=ROOT/'deployment/runtime/product-read'/prepare;r=json.loads((out/'prepared.json').read_text());fixture=out/'fixture'
 require(r['sources']==source_map(),'SOURCE_DRIFT');require(r['fixture']=={str(p.relative_to(fixture)):sha(p) for p in fixture.rglob('*') if p.is_file()},'COMPILED_DRIFT')
 require(r['testInputs']==test_inputs(),'TEST_INPUT_DRIFT')
 # A unique small log exists even if admission fails before run identity/claim creation.
 require(not (out/'RUN_CLAIM').exists(),'CLAIM_USED')
 observer=resources.Observer(out/('resources-'+uuid.uuid4().hex[:12]+'.jsonl'))
 admission=observer.sample('ADMISSION',enforce=True);mem=admission['availableBytes']//1024
 # Resolve the locally installed PG image by immutable digest, never pull.
 global PG
 PG=docker('image','inspect','postgres:16','--format','{{.Id}}');require(PG.startswith('sha256:'),'PG_IMAGE')
 for image in [JAVA,ANVIL]:require(docker('image','inspect',image,'--format','{{.Id}}')==image,'IMAGE_ID')
 network_plan=network.inventory(execute)
 snapshot=TEST/'private/anvil-stopped-state.private.json';old_result=json.loads((TEST/'result.json').read_text());require(old_result['status']=='C5A2_CONTRACT_PASS_NOT_SERVER_OR_FULL_AUDIT','TEST_PASS')
 require(sha(snapshot)==old_result['anvilStatePreserved']['sha256'],'TEST_SNAPSHOT')
 require(not (out/'RUN_CLAIM').exists(),'CLAIM_USED')
 run='ccpg-'+uuid.uuid4().hex[:16];live=out/run;live.mkdir()
 for n in ['config','control','appEvidence','verifyEvidence','bootstrapEvidence','driver']:(live/n).mkdir()
 # Only the fresh test actor gets old TEST EOA keys. No deployment or signing of financial transactions.
 actors=json.loads((TEST/'private/keys.private.json').read_text());meta=json.loads((TEST/'private/gate-result.json').read_text());meta.update(json.loads((TEST/'private/addresses.json').read_text()))
 meta['codeHashes']={}
 for label,contract in [('token','CMockCollateral'),('controller','CCollateralizedFuturesController-claims'),('rules','FuturesEngine-claims'),('vault','CMarginVault-claims')]:
  recorded=json.loads((TEST/('private/code-'+contract+'.json')).read_text());meta[label]=recorded['address'];meta['codeHashes'][label]=recorded['runtimeCodeHash']
 for label in ['A','B']:
  owner=meta['user'+label].lower();found=[]
  for receipt in sorted((TEST/'private').glob('*-receipt.observed.private.json')):
   obj=json.loads(receipt.read_text());logs=obj.get('logs',obj.get('result',{}).get('logs',[]))
   for log in logs:
    topics=log.get('topics',[])
    if log.get('address','').lower()==meta['controller'] and len(topics)==4 and topics[3].lower().endswith(owner[2:]):found.append(topics[2])
  require(found,'TEST_POSITION');meta['position'+label]=found[-1]
 save(live/'driver/test-chain.json',meta)
 passwords={k:secrets.token_hex(32) for k in ['OWNER','API','JWT','TEST']}
 owner='ccowner_'+run[5:];db='ccpg_'+run[5:]
 (live/'pg.private.env').write_text('POSTGRES_USER='+owner+'\nPOSTGRES_DB='+db+'\nPOSTGRES_PASSWORD='+passwords['OWNER']+'\nPOSTGRES_INITDB_ARGS=--auth-host=scram-sha-256 --auth-local=trust\n')
 actorenv={'FULL_READ_OWNER_PASSWORD':passwords['OWNER'],'FULL_READ_API_PASSWORD':passwords['API'],'FULL_READ_JWT_SECRET':passwords['JWT'],'FULL_READ_TEST_PASSWORD':passwords['TEST'],'READ_RUN_ID':run,
  'READ_TEST_KEY_A':actors['userA'].removeprefix('0x'),'READ_TEST_KEY_B':actors['userB'].removeprefix('0x')}
 (live/'driver.private.env').write_text('\n'.join(k+'='+v for k,v in actorenv.items())+'\n')
 observer.sample('BEFORE_CLAIM',enforce=True)
 (out/'RUN_CLAIM').open('x').write(run)
 save(live/'network-plan.json',network_plan)
 started=[];expected_images={};server=None;thread=None;failure=None;stage='NETWORK';succeeded=False;children=[];bridge_threads=[];bridge_clean=True
 def mount(p,d,ro=True):return 'type=bind,source='+str(p.resolve())+',target='+d+(',readonly' if ro else '')
 def launch(role,image,extra):
  name=run+'-'+role;args=['run','-d','--pull=never','--name',name,'--label','tms.product.read.owner='+run,'--network',run+'-network','--restart=no','--cpus=1','--pids-limit=128',*aliases(role),*extra,image]
  # Image must precede its command. Caller supplies a split sentinel.
  at=args.index('COMMAND') if 'COMMAND' in args else -1
  if at>=0:args=args[:at]+[image]+args[at+1:-1]
  # Include CLI timeout/uncertain creation in owned cleanup. Never stop a name without exact labels/image.
  started.append(name);expected_images[name]=image;docker(*args,timeout=30)
  info=json.loads(docker('inspect',name))[0];require(info['Image']==image and info['Config']['Labels'].get('tms.product.read.owner')==run and info['HostConfig']['NetworkMode']==run+'-network' and set(info['NetworkSettings']['Networks'])=={run+'-network'} and not info['HostConfig'].get('PortBindings'),'OWNERSHIP')
  if aliases(role):require(role in (info['NetworkSettings']['Networks'][run+'-network'].get('Aliases') or []),'NETWORK_ALIAS')
 def actor(mode):
  launch(mode,JAVA,['--memory=384m','--read-only','--cap-drop=ALL','--security-opt=no-new-privileges','--tmpfs','/tmp:rw,nosuid,size=32m','--env-file',str(live/'driver.private.env'),'--mount',mount(fixture,'/app-fixture'),'--mount',mount(live/'driver','/fixture',False),'--mount',mount(live/'config','/gate-config',mode!='bootstrap'),'--mount',mount(live/(mode+'Evidence'),'/evidence',False),'--entrypoint','java','COMMAND','-Xmx192m','-cp','/app-fixture/helpers:/app-fixture/classes:/app-fixture/jars/*','ProductReadDriver',mode])
  code=docker('wait',run+'-'+mode,timeout=65);logs=execute(['docker','logs',run+'-'+mode],10);(live/(mode+'.private.log')).write_bytes(logs.stdout+logs.stderr);require(code=='0',mode.upper())
 try:
  observer.sample('NETWORK_START')
  stage='NETWORK_CREATE';created,diagnostic=network.command(execute,stage,network.create_args(run,network_plan['subnet']));save(live/'network-create.json',diagnostic)
  require(created is not None and created.returncode==0,'NETWORK_CREATE')
  stage='NETWORK_VERIFY';inspected,diagnostic=network.command(execute,stage,['docker','network','inspect',run+'-network']);save(live/'network-verify.json',diagnostic)
  require(inspected is not None and inspected.returncode==0,'NETWORK_VERIFY');net=json.loads(inspected.stdout)[0]
  require(net['Internal'] is True and net['Labels'].get('tms.product.read.owner')==run and [v.get('Subnet') for v in net['IPAM']['Config']]==[network_plan['subnet']],'NETWORK_INTERNAL_SUBNET')
  stage='PG';launch('pg',PG,['--memory=256m','--env-file',str(live/'pg.private.env'),'--mount','type=volume,source='+run+'-data,target=/var/lib/postgresql/data'])
  end=time.monotonic()+30
  while time.monotonic()<end:
   if execute(['docker','exec',run+'-pg','pg_isready','-U',owner,'-d',db],5).returncode==0:break
   time.sleep(.3)
  else:raise RuntimeError('PRODUCT_READ_PG_READY')
  observer.sample('PG_READY')
  stage='ANVIL';launch('anvil',ANVIL,['--memory=192m','--read-only','--cap-drop=ALL','--mount',mount(snapshot,'/snapshot.json'),'--entrypoint','/usr/local/bin/anvil','COMMAND','--host','0.0.0.0','--port','8545','--accounts','0','--chain-id','31337','--silent','--load-state','/snapshot.json'])
  stage='BOOTSTRAP';actor('bootstrap')
  observer.sample('BOOTSTRAP_COMPLETE')
  stage='APP';launch('app',JAVA,['--memory=768m','--read-only','--cap-drop=ALL','--security-opt=no-new-privileges','--tmpfs','/tmp:rw,nosuid,size=32m','--mount',mount(fixture,'/app-fixture'),'--mount',mount(live/'config','/gate-config'),'--mount',mount(live/'control','/gate-control'),'--mount',mount(live/'appEvidence','/gate-evidence',False),'--entrypoint','java','COMMAND','-Xms32m','-Xmx256m','-XX:MaxMetaspaceSize=192m','-cp','/app-fixture/helpers:/app-fixture/classes:/app-fixture/jars/*','ProductReadGate','/gate-config/application.properties','/gate-control','/gate-evidence'])
  end=time.monotonic()+120
  while time.monotonic()<end:
   if (live/'appEvidence/ready.json').exists():break
   if docker('inspect',run+'-app','--format','{{.State.Running}}')!='true':raise RuntimeError('PRODUCT_READ_APP_EXIT')
   time.sleep(.5)
  else:raise RuntimeError('PRODUCT_READ_APP_READY_TIMEOUT')
  observer.sample('APP_READY')
  stage='ACTUAL_HTTP';actor('verify')
  observer.sample('HTTP_COMPLETE')
  stage='BROWSER'
  observer.sample('BROWSER_START')
  # Opaque loopback bridge to only this owned App. No host Docker port publishing.
  gate_end=time.monotonic()+110;slots=threading.BoundedSemaphore(8)
  class Bridge(socketserver.BaseRequestHandler):
   def handle(self):
    if not slots.acquire(timeout=2):return
    proc=None
    try:
     self.request.settimeout(15)
     proc=subprocess.Popen(['docker','exec','-i',run+'-app','java','-Xmx24m','-cp','/app-fixture/helpers','CDirectBrowserPipe','APP','100'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,bufsize=0,env=clean());children.append(proc)
     def upload():
      try:
       while time.monotonic()<gate_end:
        b=self.request.recv(65536)
        if not b:break
        remaining=memoryview(b)
        while remaining:
         count=proc.stdin.write(remaining)
         if not count:raise OSError('PIPE_CLOSED')
         remaining=remaining[count:]
        proc.stdin.flush()
      except (OSError,ValueError):pass
      finally:
       try:proc.stdin.close()
       except (OSError,ValueError):pass
     t=threading.Thread(target=upload,daemon=True);bridge_threads.append(t);t.start()
     while time.monotonic()<gate_end:
      b=proc.stdout.read(65536)
      if not b:break
      self.request.sendall(b)
    except (OSError,ValueError):pass
    finally:
     try:self.request.shutdown(socket.SHUT_RDWR)
     except OSError:pass
     if proc and proc.poll() is None:proc.terminate()
     if proc:
      try:proc.wait(timeout=3)
      except subprocess.TimeoutExpired:pass
     slots.release()
  class Server(socketserver.ThreadingTCPServer):daemon_threads=True;allow_reuse_address=False
  server=Server(('127.0.0.1',32171),Bridge);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
  browser=execute(['node',ROOT/'tools/websocket-test-client/scripts/product-read-browser.mjs',live/'verifyEvidence/browser.private.json',live/'browser'],105,cwd=ROOT/'tools/websocket-test-client');(live/'browser.private.log').write_bytes(browser.stdout+browser.stderr);require(browser.returncode==0,'BROWSER')
  b=json.loads((live/'browser/result.json').read_text());require(b['status']=='PASS' and b['cleanup']['childCleanupComplete'] is True and b['financialRequests']==0 and b['runtimeError'] is False and b['marketPriceMessageReceived'] is True,'BROWSER_RESULT')
  observer.sample('BROWSER_COMPLETE')
  succeeded=True
 except Exception as e:failure=str(e) if str(e).startswith('PRODUCT_READ_') else type(e).__name__
 finally:
  try:observer.sample('CLEANUP_START')
  except Exception:succeeded=False;failure=failure or 'PRODUCT_READ_RESOURCE_OBSERVATION_FAILED'
  if server:server.shutdown();server.server_close()
  if (live/'control').exists():(live/'control/STOP').touch()
  if run+'-app' in started:
   end=time.monotonic()+15
   while time.monotonic()<end and not (live/'appEvidence/normal-stop.json').is_file():time.sleep(.2)
  for proc in children:
   if proc.poll() is None:proc.terminate()
   try:proc.wait(timeout=3)
   except subprocess.TimeoutExpired:bridge_clean=False
  for t in bridge_threads:t.join(1);bridge_clean=bridge_clean and not t.is_alive()
  stopped={}
  for name in reversed(started):
   try:
    info=json.loads(docker('inspect',name))[0];require(info['Config']['Labels'].get('tms.product.read.owner')==run and info['Image']==expected_images[name] and info['HostConfig']['NetworkMode']==run+'-network','CLEANUP_OWNER')
    docker('stop','--time','20',name,timeout=25);s=json.loads(docker('inspect',name))[0]['State'];stopped[name]=not s['Running'] and not s['OOMKilled'] and s['ExitCode']==0
   except Exception:stopped[name]=False
  for role in ['app','anvil']:
   if run+'-'+role in started:
    try:
     logs=execute(['docker','logs',run+'-'+role],10);(live/(role+'.private.log')).write_bytes(logs.stdout+logs.stderr)
    except Exception:pass
  unchanged=source_map()==r['sources'] and test_inputs()==r['testInputs'] and sha(snapshot)==old_result['anvilStatePreserved']['sha256']
  normal=(live/'appEvidence/normal-stop.json').is_file()
  try:observer.sample('CLEANUP_COMPLETE')
  except Exception:succeeded=False;failure=failure or 'PRODUCT_READ_RESOURCE_OBSERVATION_FAILED'
  result={'run':run,'status':'PASS_READ_ONLY_NOT_METAMASK_OR_TRADING' if succeeded and all(stopped.values()) and unchanged and normal and bridge_clean else 'BLOCKED','stage':stage,'safeFailure':failure,'sourcesAndOriginalTestSnapshotUnchanged':unchanged,'stopped':stopped,'appNormalStop':normal,'bridgeChildrenExited':bridge_clean,'financialTransactions':0,'paidCalls':0,'availableMiBBefore':mem//1024,'images':{'pg':PG,'java':JAVA,'anvil':ANVIL},'sourceHash':hashlib.sha256(json.dumps(r['sources'],sort_keys=True).encode()).hexdigest()}
  result.update({'resourceLog':observer.path.name,'minimumObservedAvailableBytes':observer.minimum,'memorySamplesAreStageBoundariesNotContinuous':True})
  save(live/'result.json',result);print(json.dumps(result));return 0 if result['status'].startswith('PASS') else 1
if __name__=='__main__':
 try:
  if len(sys.argv)==2 and sys.argv[1]=='prepare':build()
  elif len(sys.argv)==3 and sys.argv[1]=='run':sys.exit(run(sys.argv[2]))
  else:raise SystemExit('prepare | run PREPARE_ID')
 except ToolError as e:raise SystemExit(str(e)) from None
