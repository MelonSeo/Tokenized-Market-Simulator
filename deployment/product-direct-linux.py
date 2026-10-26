"""Separate owner-direct trial Gate. Read Gate implementation is unchanged. prepare/run are separate; neither is run on Windows.
Public fixture recipe creates disposable test-chain transactions, never user assets.
"""
import datetime,hashlib,importlib.util,json,os,re,secrets,shutil,signal,subprocess,sys,time,uuid
from pathlib import Path
import product_read_network as network
import product_read_resources as resources
import product_read_linux_support as linux

ROOT=Path(__file__).resolve().parents[1]
LOCK=json.loads((ROOT/'deployment/product-read-ci-lock.json').read_text())
HELPERS=['ProductReadGate','ProductReadDriver','ProductReadDriverRpcTest','ProductReadConnection','ProductReadConnectionTest','ProductReadFixture','FullAppReadGate','FullAppReadDriver','FaucetSchemaFixtureBridge','ConditionalSchemaFixtureBridge','CDirectBrowserPipe','CAnvilContractGate','CAnvilRiskClaimsScenario']
FLAGS=['-Djdk.httpclient.disableRetryConnect=true','-Djdk.httpclient.enableAllMethodRetry=false','-Djdk.httpclient.redirects.retrylimit=1','--add-opens=java.net.http/jdk.internal.net.http=ALL-UNNAMED']
need=linux.need
def save(p,value):
 with p.open('x',encoding='utf8') as f:json.dump(value,f,separators=(',',':'))
def environment():return {k:v for k,v in os.environ.items() if k in {'PATH','HOME','TMPDIR','LANG','LC_ALL','JAVA_HOME'}}
def execute(args,timeout=30,**options):return subprocess.run(list(map(str,args)),capture_output=True,timeout=timeout,env=environment(),**options)
def command(args,timeout=30):
 r=execute(args,timeout);need(r.returncode==0,'COMMAND_FAILED');return r.stdout.decode('utf8').strip()
def docker(*args,timeout=30):return command(['docker',*args],timeout)
def sources():
 paths=[p for folder in ['backend/src/main','contracts/src','tools/websocket-test-client/src'] for p in (ROOT/folder).rglob('*') if p.is_file()]
 paths += [ROOT/'deployment'/(name+'.java') for name in HELPERS]
 paths += [ROOT/'deployment'/n for n in ['product-read-linux.py','product_read_linux_support.py','product_read_resources.py','product_read_network.py','c_gate_network.py','product-read-ci.gradle','product-read-ci-lock.json','test_product_read_linux.py','product_read_tools.py','test_product_read_tools.py']]
 paths += [ROOT/n for n in ['.github/workflows/product-read-linux.yml','backend/build.gradle','backend/gradle/wrapper/gradle-wrapper.properties','contracts/foundry.toml','tools/websocket-test-client/index.html','tools/websocket-test-client/package.json','tools/websocket-test-client/package-lock.json']]
 paths += [ROOT/'tools/websocket-test-client/scripts'/n for n in ['product-read-browser.mjs','cdp-boundary.mjs','c-live-browser-smoke.mjs','browser-executable.mjs']]
 paths += [ROOT/'tools/websocket-test-client/test/browser-executable.test.mjs']
 paths += [ROOT/p for p in ['deployment/ProductDirectFixture.java','deployment/ProductDirectDriver.java','deployment/ProductDirectGate.java','deployment/CDirectOracleServer.java','deployment/product-direct-ci.gradle','deployment/product-direct-linux.py','deployment/test_product_direct_linux.py','.github/workflows/product-direct-linux.yml','tools/websocket-test-client/scripts/product-direct-browser.mjs','tools/websocket-test-client/scripts/product-direct-browser-inputs.mjs','tools/websocket-test-client/test/product-direct-browser-inputs.test.mjs']]
 return {str(p.relative_to(ROOT)):linux.sha(p) for p in sorted(set(paths))}
def prepare():
 need(sys.platform=='linux','LINUX_ONLY');before=sources()
 out=ROOT/'deployment/runtime/product-direct-linux'/('prepare-'+uuid.uuid4().hex[:16]);out.mkdir(parents=True)
 fixture=out/'fixture';fixture.mkdir()
 for origin,name in [(ROOT/'backend/build/classes/java/main','classes'),(ROOT/'backend/build/product-read-ci/helpers','helpers'),(ROOT/'backend/build/product-read-ci/jars','jars')]:
  need(origin.is_dir() and any(origin.iterdir()),'BUILD_FIRST');shutil.copytree(origin,fixture/name)
 shutil.copytree(ROOT/'backend/build/resources/main',fixture/'classes',dirs_exist_ok=True)
 shutil.copytree(ROOT/'tools/websocket-test-client/dist',fixture/'classes/static')
 artifacts=fixture/'artifacts';artifacts.mkdir();contracts=linux.artifact_fixture(ROOT,artifacts)
 for name in ['ProductReadGate','ProductReadDriverRpcTest','ProductReadConnectionTest','ProductReadFixture','ProductDirectGate']:
  command(['java','-Xmx128m','-cp',os.pathsep.join(map(str,[fixture/'helpers',fixture/'classes',fixture/'jars/*'])),name,'--self-test'],20)
 need(sources()==before,'SOURCE_DRIFT')
 save(out/'prepared.json',{'status':'PREPARED_NOT_LIVE','source':before,'sourceHash':hashlib.sha256(json.dumps(before,sort_keys=True).encode()).hexdigest(),'contracts':contracts,'files':{str(p.relative_to(fixture)):linux.sha(p) for p in fixture.rglob('*') if p.is_file()}})
 print(str(out.relative_to(ROOT)))
def run(path):
 need(sys.platform=='linux','LINUX_ONLY');out=(ROOT/path).resolve();need(out.is_relative_to((ROOT/'deployment/runtime/product-direct-linux').resolve()),'PREPARE_SCOPE')
 record=json.loads((out/'prepared.json').read_text());fixture=out/'fixture'
 need(sources()==record['source'] and record['files']=={str(p.relative_to(fixture)):linux.sha(p) for p in fixture.rglob('*') if p.is_file()},'IDENTITY_DRIFT')
 need(not (out/'RUN_CLAIM').exists(),'CLAIM_USED')
 observer=resources.Observer(out/('resources-'+uuid.uuid4().hex[:12]+'.jsonl'),linux.memory,lambda:shutil.disk_usage(ROOT).free)
 try:observer.sample('ADMISSION',True)
 except Exception:
  print(observer.path.read_text().strip());raise
 need(os.geteuid()!=0,'RUNNER_NONROOT_CHROME')
 images={role:docker('image','inspect',ref,'--format','{{.Id}}') for role,ref in LOCK['images'].items()}
 plan=linux.inventory(execute);run_id='ccpg-'+uuid.uuid4().hex[:16];live=out/run_id;live.mkdir(mode=0o700)
 for name in ['config','control','appEvidence','bootstrapEvidence','verifyEvidence','fixture-public','keys','private-evidence','oracle-config','oracleEvidence','walletEvidence']:(live/name).mkdir(mode=0o700)
 passwords={k:secrets.token_hex(32) for k in ['OWNER','API','JWT','TEST','ORACLE','WALLET']}
 owner='ccowner_'+run_id[5:];db='ccpg_'+run_id[5:]
 (live/'pg.private.env').write_text('POSTGRES_USER='+owner+'\nPOSTGRES_DB='+db+'\nPOSTGRES_PASSWORD='+passwords['OWNER']+'\nPOSTGRES_INITDB_ARGS=--auth-host=scram-sha-256 --auth-local=trust\n')
 observer.sample('BEFORE_CLAIM',True);(out/'RUN_CLAIM').write_text(run_id)
 save(live/'owned.json',{'run':run_id,'images':images})
 save(live/'network-plan.json',plan)
 started=[];bridge=None;stage='NETWORK';failure=None;passed=False;stopped={};secret_values=list(passwords.values())
 def mount(p,target,ro=True):return 'type=bind,source='+str(p.resolve())+',target='+target+(',readonly' if ro else '')
 def launch(role,image,options,cmd):
  name=run_id+'-'+role;started.append((name,image))
  args=['run','-d','--pull=never','--name',name,'--label','tms.product.read.owner='+run_id,'--network',run_id+'-network','--restart=no','--pids-limit=128','--cpus=1']
  if role in ['app','pg','anvil','oracle','wallet']:args+=['--network-alias',role]
  docker(*args,*options,image,*cmd)
  info=json.loads(docker('inspect',name))[0]
  need(info['Image']==image and info['Config']['Labels'].get('tms.product.read.owner')==run_id and set(info['NetworkSettings']['Networks'])=={run_id+'-network'} and not info['HostConfig'].get('PortBindings'),'OWNER_NETWORK')
  if role in ['app','pg','anvil','oracle','wallet']:need(role in (info['NetworkSettings']['Networks'][run_id+'-network'].get('Aliases') or []),'ALIAS')
  return info
 def java_actor(role,main,args,mounts,env=None,limit='384m',timeout=90):
  options=['--memory='+limit,'--user',str(os.getuid())+':'+str(os.getgid()),'--read-only','--cap-drop=ALL','--security-opt=no-new-privileges','--tmpfs','/tmp:rw,nosuid,size=32m,mode=1777','--mount',mount(fixture,'/app-fixture'),'--entrypoint','java']
  for p,target,ro in mounts:options+=['--mount',mount(p,target,ro)]
  if env:options+=['--env-file',str(env)]
  info=launch(role,images['java'],options,['-Xmx192m',*FLAGS,'-cp','/app-fixture/helpers:/app-fixture/classes:/app-fixture/jars/*',main,*args])
  status=docker('wait',run_id+'-'+role,timeout=timeout)
  logs=execute(['docker','logs',run_id+'-'+role],10);(live/(role+'.private.log')).write_bytes(logs.stdout+logs.stderr)
  need(status=='0',role.upper());return info
 try:
  observer.sample('NETWORK_START');created,diagnostic=network.command(execute,'NETWORK_CREATE',network.create_args(run_id,plan['subnet']));save(live/'network-create.json',diagnostic)
  need(created is not None and created.returncode==0,'NETWORK_CREATE')
  net=json.loads(docker('network','inspect',run_id+'-network'))[0]
  need(net['Internal'] and net['Labels'].get('tms.product.read.owner')==run_id and [c['Subnet'] for c in net['IPAM']['Config']]==[plan['subnet']],'NETWORK_VERIFY')
  stage='PG';launch('pg',images['pg'],['--memory=256m','--env-file',str(live/'pg.private.env'),'--mount','type=volume,source='+run_id+'-data,target=/var/lib/postgresql/data'],[])
  deadline=time.monotonic()+35
  while time.monotonic()<deadline:
   if execute(['docker','exec',run_id+'-pg','pg_isready','-h','127.0.0.1','-U',owner,'-d',db],5).returncode==0:break
   time.sleep(.5)
  else:need(False,'PG_READY')
  stage='ANVIL';launch('anvil',images['anvil'],['--memory=192m','--read-only','--cap-drop=ALL','--entrypoint','/usr/local/bin/anvil'],['--host','0.0.0.0','--port','8545','--accounts','0','--chain-id','31337','--silent'])
  deadline=time.monotonic()+15
  while time.monotonic()<deadline:
   ready=execute(['docker','exec',run_id+'-anvil','/usr/local/bin/cast','chain-id','--rpc-url','http://127.0.0.1:8545'],3)
   if ready.returncode==0 and ready.stdout.decode().strip()=='31337':break
   time.sleep(.3)
  else:need(False,'ANVIL_READY')
  stage='NEW_FIXTURE';java_actor('seed','ProductDirectFixture',['/artifacts','/private-evidence','/fixture','/keys','/oracle-config'],[(fixture/'artifacts','/artifacts',True),(live/'private-evidence','/private-evidence',False),(live/'fixture-public','/fixture',False),(live/'keys','/keys',False),(live/'oracle-config','/oracle-config',False)],timeout=300)
  meta=json.loads((live/'fixture-public/test-chain.json').read_text());need(meta['scope']=='NEW_OWNER_DIRECT_CI_NOT_METAMASK','FIXTURE_SCOPE')
  keys=json.loads((live/'keys/wallet-keys.private.json').read_text());secret_values+=list(keys.values())
  secret_values.append((live/'oracle-config/oracle-key.private').read_text().strip())
  actorenv={'FULL_READ_'+k+('_SECRET' if k=='JWT' else '_PASSWORD'):v for k,v in passwords.items() if k in ['OWNER','API','JWT','TEST']}
  actorenv.update({'DIRECT_ORACLE_TOKEN':passwords['ORACLE'],'DIRECT_WALLET_TOKEN':passwords['WALLET'],'READ_RUN_ID':run_id,'READ_TEST_KEY_A':keys['userA'],'READ_TEST_KEY_B':keys['userB']})
  # FULL_READ_JWT_SECRET is kept separate from TEST_PASSWORD and both user keys.
  env=live/'actor.private.env';env.write_text('\n'.join(k+'='+v for k,v in actorenv.items())+'\n');env.chmod(0o600)
  stage='BOOTSTRAP';java_actor('bootstrap','ProductDirectDriver',['bootstrap'],[(live/'fixture-public','/fixture',False),(live/'config','/gate-config',False),(live/'bootstrapEvidence','/evidence',False),(live/'oracle-config','/oracle-config',False)],env)
  observer.sample('BOOTSTRAP_COMPLETE')
  stage='ORACLE'
  launch('oracle',images['java'],['--memory=256m','--user',str(os.getuid())+':'+str(os.getgid()),'--read-only','--cap-drop=ALL','--security-opt=no-new-privileges','--tmpfs','/tmp:rw,nosuid,size=32m,mode=1777','--mount',mount(fixture,'/app-fixture'),'--mount',mount(live/'oracle-config','/oracle-config'),'--mount',mount(live/'control','/gate-control'),'--mount',mount(live/'oracleEvidence','/oracle-evidence',False),'--entrypoint','java'],['-Xmx128m','-cp','/app-fixture/helpers:/app-fixture/classes:/app-fixture/jars/*','CDirectOracleServer','/oracle-config/identity.json','/oracle-config/oracle-key.private','/oracle-config/auth-token.private','/gate-control','/oracle-evidence','360'])
  deadline=time.monotonic()+20
  while time.monotonic()<deadline:
   if (live/'oracleEvidence/ready.json').exists():break
   need(docker('inspect',run_id+'-oracle','--format','{{.State.Running}}')=='true','ORACLE_EXIT');time.sleep(.2)
  else:need(False,'ORACLE_READY')
  stage='APP'
  app=launch('app',images['java'],['--memory=768m','--user',str(os.getuid())+':'+str(os.getgid()),'--read-only','--cap-drop=ALL','--security-opt=no-new-privileges','--tmpfs','/tmp:rw,nosuid,size=32m,mode=1777','--mount',mount(fixture,'/app-fixture'),'--mount',mount(live/'config','/gate-config'),'--mount',mount(live/'control','/gate-control'),'--mount',mount(live/'appEvidence','/gate-evidence',False),'--entrypoint','java'],['-Xms32m','-Xmx256m','-XX:MaxMetaspaceSize=192m','-cp','/app-fixture/helpers:/app-fixture/classes:/app-fixture/jars/*','ProductDirectGate','/gate-config/application.properties','/gate-control','/gate-evidence'])
  deadline=time.monotonic()+120
  while time.monotonic()<deadline:
   if (live/'appEvidence/ready.json').exists():break
   need(docker('inspect',run_id+'-app','--format','{{.State.Running}}')=='true','APP_EXIT');time.sleep(.5)
  else:need(False,'APP_READY')
  observer.sample('APP_READY');stage='ACTUAL_HTTP'
  wallet=launch('wallet',images['java'],['--memory=512m','--user',str(os.getuid())+':'+str(os.getgid()),'--read-only','--cap-drop=ALL','--security-opt=no-new-privileges','--tmpfs','/tmp:rw,nosuid,size=32m,mode=1777','--env-file',str(env),'--mount',mount(fixture,'/app-fixture'),'--mount',mount(live/'fixture-public','/fixture'),'--mount',mount(live/'keys','/keys'),'--mount',mount(live/'control','/gate-control'),'--mount',mount(live/'walletEvidence','/wallet-evidence',False),'--entrypoint','java'],['-Xmx192m',*FLAGS,'-cp','/app-fixture/helpers:/app-fixture/classes:/app-fixture/jars/*','ProductDirectDriver','wallet'])
  deadline=time.monotonic()+35
  while time.monotonic()<deadline:
   if (live/'walletEvidence/ready.json').exists():break
   need(docker('inspect',run_id+'-wallet','--format','{{.State.Running}}')=='true','WALLET_EXIT');time.sleep(.2)
  else:need(False,'WALLET_READY')
  observer.sample('HTTP_COMPLETE');stage='BROWSER_BRIDGE'
  bridge=linux.Bridge(app['NetworkSettings']['Networks'][run_id+'-network']['IPAddress'],plan['subnet'],port=32173);bridge.end=time.monotonic()+175;bridge.start()
  stage='BROWSER_CONFIG'
  browser_config=json.loads((live/'walletEvidence/browser.private.json').read_text());browser_config['walletEndpoint']='http://'+wallet['NetworkSettings']['Networks'][run_id+'-network']['IPAddress']+':8092'
  save(live/'browser.private.json',browser_config)
  chrome=shutil.which('google-chrome') or shutil.which('chromium');need(bool(chrome),'CHROME_MISSING')
  browser_env=environment();browser_env['CHROME_PATH']=chrome
  stage='BROWSER_RUN'
  r=subprocess.run(['node',str(ROOT/'tools/websocket-test-client/scripts/product-direct-browser.mjs'),str(live/'browser.private.json'),str(live/'browser')],env=browser_env,cwd=ROOT/'tools/websocket-test-client',capture_output=True,timeout=165)
  (live/'browser.private.log').write_bytes(r.stdout+r.stderr);need(r.returncode==0,'BROWSER')
  b=json.loads((live/'browser/result.json').read_text());need(b['status']=='PASS' and len(b['history'])==5 and b['childCleanupComplete'],'BROWSER_RESULT')
  need(docker('wait',run_id+'-wallet',timeout=15)=='0' and (live/'walletEvidence/direct-result.json').is_file(),'WALLET_FINAL')
  observer.sample('BROWSER_COMPLETE');passed=True
 except (Exception,KeyboardInterrupt,SystemExit) as e:failure=('OS_ERROR_'+str(e.errno)) if isinstance(e,OSError) else str(e) if str(e).startswith('PRODUCT_READ_') else type(e).__name__
 finally:
  if bridge:
   try:bridge.close()
   except Exception:passed=False;failure=failure or 'PRODUCT_READ_LINUX_BRIDGE_CLEANUP'
  (live/'control/STOP').touch()
  if any(name==run_id+'-app' for name,_ in started):
   deadline=time.monotonic()+15
   while time.monotonic()<deadline and not (live/'appEvidence/normal-stop.json').exists():time.sleep(.2)
  for name,image in reversed(started):
   try:
    info=json.loads(docker('inspect',name))[0];need(info['Config']['Labels'].get('tms.product.read.owner')==run_id and info['Image']==image and info['HostConfig']['NetworkMode']==run_id+'-network','CLEANUP_OWNER')
    docker('stop','--time','20',name,timeout=25);s=json.loads(docker('inspect',name))[0]['State'];stopped[name]={'running':s['Running'],'exit':s['ExitCode'],'oom':s['OOMKilled']}
   except Exception:stopped[name]={'cleanupFailed':True};passed=False
  try:observer.sample('CLEANUP_COMPLETE')
  except Exception:passed=False;failure=failure or 'PRODUCT_READ_LINUX_RESOURCE_LOG'
  unchanged=sources()==record['source'];normal=(live/'appEvidence/normal-stop.json').exists()
  clean=all(s.get('running') is False and s.get('oom') is False and s.get('exit')==0 for s in stopped.values())
  result={'status':'PASS_OWNER_DIRECT_TRIAL_EOA_NOT_METAMASK' if passed and clean and unchanged and normal else 'BLOCKED','stage':stage,'safeFailure':failure,'run':run_id,'sourceHash':record['sourceHash'],'sourcesUnchanged':unchanged,'appNormalStop':normal,'containers':stopped,'images':images,'newFixtureNotC5a2':True,'minimumObservedAvailableBytes':observer.minimum,'samplesAreNotContinuous':True,'productFinancialRequests':5 if passed else None,'paidCalls':0,'realMetaMask':False}
  save(live/'result.json',result)
  safe_artifacts(live,ROOT/'deployment/runtime/product-direct-linux/public',secret_values)
  print(json.dumps(result));return 0 if result['status'].startswith('PASS') else 1
def safe_artifacts(live,destination,secrets):
 """Explicit public allowlist, never recursively upload runtime or browser profiles."""
 destination.mkdir(exist_ok=False)
 allowed=['result.json','fixture-public/test-chain.json','walletEvidence/direct-result.json','walletEvidence/wallet-failure.json','browser/result.json','browser/01-open.png','browser/02-final-account.png','browser/03-closed.png','browser/04-verified-history.png']
 forbidden=re.compile(rb'-----BEGIN [A-Z ]*PRIVATE KEY-----|Bearer [A-Za-z0-9_.-]+|gh[pousr]_[A-Za-z0-9_]+')
 manifest={}
 for name in allowed:
  p=live/name
  if not p.is_file():continue
  data=p.read_bytes();need(len(data)<=8*1024**2,'PUBLIC_ARTIFACT_SIZE')
  need(not forbidden.search(data) and all(v.encode() not in data for v in secrets if v),'PUBLIC_ARTIFACT_SECRET')
  if p.suffix=='.json':
   obj=json.loads(data)
   def keys(x):
    if isinstance(x,dict):
     need(not any(re.search(r'password|private.?key|signature|access.?token|mnemonic|jwt|secret|authorization|^raw(?:Transaction)?$',k,re.I) for k in x),'PUBLIC_ARTIFACT_FIELD')
     for v in x.values():keys(v)
    elif isinstance(x,list):
     for v in x:keys(v)
   keys(obj)
  target=destination/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data);manifest[name]=hashlib.sha256(data).hexdigest()
 need(sum(p.stat().st_size for p in destination.rglob('*') if p.is_file())<=20*1024**2,'PUBLIC_ARTIFACT_TOTAL')
 (destination/'manifest.json').write_text(json.dumps({'safeAllowlistOnly':True,'files':manifest}),encoding='utf8')


def cleanup():
 """Workflow cancellation fallback: only this checkout's recorded, exactly labelled resources."""
 for claim in (ROOT/'deployment/runtime/product-direct-linux').glob('prepare-*/RUN_CLAIM'):
  run_id=claim.read_text().strip();need(bool(re.fullmatch('ccpg-[0-9a-f]{16}',run_id)),'CLEANUP_ID')
  owned=json.loads((claim.parent/run_id/'owned.json').read_text());need(owned['run']==run_id,'CLEANUP_RECORD')
  for role,image in [('pg',owned['images']['pg']),('anvil',owned['images']['anvil']),*[(r,owned['images']['java']) for r in ['seed','bootstrap','oracle','app','wallet']]]:
   name=run_id+'-'+role;r=execute(['docker','inspect',name],10)
   if r.returncode:continue
   info=json.loads(r.stdout)[0];need(info['Image']==image and info['Config']['Labels'].get('tms.product.read.owner')==run_id and info['HostConfig']['NetworkMode']==run_id+'-network','CLEANUP_OWNER')
   if info['State']['Running']:docker('stop','--time','20',name,timeout=25)
if __name__=='__main__':
 def interrupt(signum,frame):raise SystemExit('PRODUCT_READ_LINUX_INTERRUPTED')
 signal.signal(signal.SIGTERM,interrupt)
 try:
  if sys.argv[1:]==['prepare']:prepare()
  elif sys.argv[1:]==['cleanup']:cleanup()
  elif len(sys.argv)==3 and sys.argv[1]=='run':sys.exit(run(sys.argv[2]))
  else:raise RuntimeError('PRODUCT_READ_LINUX_ARGUMENTS')
 except Exception as e:
  print(str(e) if str(e).startswith('PRODUCT_READ_') else 'PRODUCT_READ_LINUX_STOP_NO_RETRY');sys.exit(1)
