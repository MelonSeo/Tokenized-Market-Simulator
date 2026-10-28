"""EXPLICIT local manual session; import/prepare/status never starts a service.
No wallet actor, browser automation, user private key, automatic user transaction or retry.
start requires the user's manual-environment authorization; no user transaction is automated.
"""
import argparse, hashlib, http.client, importlib.util, json, os, re, secrets, shutil, socket, subprocess, sys, time, uuid, urllib.request, urllib.error
from pathlib import Path
import product_read_resources as resources
import product_read_network as network
import product_read_linux_support as linux
import product_manual_clock as manual_clock
from product_read_tools import find_jdk

ROOT=Path(__file__).resolve().parents[1]
RUNTIME=ROOT/'deployment/runtime/product-manual'
PORTS={'app':32175,'anvil':32176}
LABEL='tms.product.manual.owner'
LOCK=json.loads((ROOT/'deployment/product-read-ci-lock.json').read_text())
# Only a fresh, empty, labelled volume may reach this one-shot helper. No recursive chown/chmod.
STATE_INIT='set -eu; test "$(id -u)" = 0; test "$(stat -c %u:%g:%a /state)" = 0:0:755; test -z "$(ls -A /state)"; chown 1000:1000 /state; test "$(stat -c %u:%g:%a /state)" = 1000:1000:755'
STATE_RESUME_GUARD='if test -e "$STATE_DIR/.manual-write-probe"; then test "$(cat "$STATE_DIR/.manual-write-probe")" = writable; test -s "$STATE_DIR/anvil.json"; else test ! -e "$STATE_DIR/anvil.json"; fi'
STATE_START='set -eu; test "$(id -u):$(id -g)" = 1000:1000; test "$(stat -c %u:%g:%a /state)" = 1000:1000:755; STATE_DIR=/state; '+STATE_RESUME_GUARD+'; printf writable > /state/.manual-write-probe; printf "MANUAL_STATE_WRITABLE\\n"; exec /usr/local/bin/anvil "$@"'
ANVIL_ARGS=['--host','0.0.0.0','--accounts','0','--chain-id','31337','--state','/state/anvil.json','--state-interval','5','--silent']
PROBE_STAGES={'CHILD_START','TCP_CONNECT','TCP_CONNECTED','HTTP_REQUEST','HTTP_HEADERS','BODY_READ','IDENTITY_OK','ERROR_CONNECT_REFUSED','ERROR_SOCKET_TIMEOUT','ERROR_CONNECTION','ERROR_PROTOCOL'}

def direct_module():
 spec=importlib.util.spec_from_file_location('manual_direct_source',ROOT/'deployment/product-direct-linux.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m

def actor_argv(args):
 return ['-Xmx192m',*direct_module().FLAGS,'-cp','/app-fixture/helpers:/app-fixture/classes:/app-fixture/jars/*','ProductManualActor',*args]

def need(ok,code):
 if not ok:raise ValueError('MANUAL_'+code)

def owner(value):
 need(isinstance(value,str) and re.fullmatch(r'0x[0-9a-fA-F]{40}',value) and int(value[2:],16)>0,'PUBLIC_ADDRESS_ONLY')
 return value.lower()

def save(path,obj):
 with path.open('x',encoding='utf8') as f:json.dump(obj,f,separators=(',',':'))

def environment():
 return {k:v for k,v in os.environ.items() if k.upper() in {'PATH','PATHEXT','SYSTEMROOT','WINDIR','COMSPEC','TEMP','TMP','HOME','USERPROFILE','APPDATA','LOCALAPPDATA','JAVA_HOME'}}

def execute(args,timeout=20):
 return subprocess.run(list(map(str,args)),capture_output=True,timeout=timeout,env=environment())

def docker(*args,timeout=20):
 r=execute(['docker',*args],timeout);need(r.returncode==0,'DOCKER_'+str(args[0]).upper());return r.stdout.decode('utf8').strip()

def tree(path):
 need(path.is_dir(),'TREE_MISSING');result={}
 for p in sorted(path.rglob('*')):
  need(not p.is_symlink(),'NO_SYMLINK')
  if p.is_file():result[str(p.relative_to(path)).replace('\\','/')]=linux.sha(p)
 return result

def source_map():
 rows=direct_module().sources()
 for n in ['ProductManualActor.java','product-manual.py','product-manual.gradle','test_product_manual.py','product_manual_clock.py','test_product_manual_clock.py']:rows['deployment/'+n]=linux.sha(ROOT/'deployment'/n)
 return rows

def build_inputs():
 rows={}
 for folder in ['backend/src/main','tools/websocket-test-client/src']:
  rows.update({str(p.relative_to(ROOT)).replace('\\','/'):linux.sha(p) for p in (ROOT/folder).rglob('*') if p.is_file()})
 rows.update({'deployment/'+p.name:linux.sha(p) for p in (ROOT/'deployment').glob('*.java')})
 for n in ['index.html','package.json','package-lock.json']:rows['tools/websocket-test-client/'+n]=linux.sha(ROOT/'tools/websocket-test-client'/n)
 return rows

def build_outputs():
 rows={}
 for folder in ['backend/build/classes/java/main','backend/build/resources/main','backend/build/product-read-ci/helpers','backend/build/product-read-ci/jars','tools/websocket-test-client/dist']:
  rows.update({folder+'/'+n:h for n,h in tree(ROOT/folder).items()})
 return rows

def packaged_outputs(outputs):
 rows={}
 for source,target in [('backend/build/classes/java/main/','classes/'),('backend/build/resources/main/','classes/'),('backend/build/product-read-ci/helpers/','helpers/'),('backend/build/product-read-ci/jars/','jars/'),('tools/websocket-test-client/dist/','classes/static/')]:
  for name,digest in outputs.items():
   if name.startswith(source):
    relative=name[len(source):]
    if source.endswith('/dist/') and relative.startswith('.smoke/'):continue
    rows[target+relative]=digest
 return rows

def verify_packaged(stamp,files):
 need(stamp['source']==build_inputs() and stamp['outputs']==build_outputs(),'BUILD_CHANGED_DURING_COPY')
 need({n:h for n,h in files.items() if not n.startswith('artifacts/')}==packaged_outputs(stamp['outputs']),'PACKAGED_OUTPUT_MISMATCH')

def resume_compatible(old,new):
 allowed={'deployment/ProductManualActor.java','deployment/CDirectOracleServer.java','deployment/product-manual.py','deployment/test_product_manual.py','deployment/product_manual_clock.py','deployment/test_product_manual_clock.py'}
 def normalized(rows):
  result={re.sub(r'[\\/]+','/',name):digest for name,digest in rows.items()}
  need(len(result)==len(rows),'RESUME_PATH_ALIAS');return result
 oldsrc,newsrc=normalized(old['source']),normalized(new['source']);oldfiles,newfiles=normalized(old['files']),normalized(new['files'])
 need({k:v for k,v in oldsrc.items() if k not in allowed}=={k:v for k,v in newsrc.items() if k not in allowed},'RESUME_PRODUCT_SOURCE_CHANGED')
 need({k:v for k,v in oldfiles.items() if not k.startswith('helpers/')}=={k:v for k,v in newfiles.items() if not k.startswith('helpers/')},'RESUME_PRODUCT_BUILD_CHANGED')

def prepare():
 """Package already built current product/manual classes; no Docker or compiler invocation."""
 before=source_map();source=ROOT/'backend/build';helpers=source/'product-read-ci/helpers'
 stamp_path=source/'product-manual-build.json';stamp_bytes=stamp_path.read_bytes();stamp=json.loads(stamp_bytes)
 need(stamp['source']==build_inputs() and stamp['outputs']==build_outputs(),'BUILD_STAMP_DRIFT')
 for n in ['ProductManualActor','ProductDirectDriver','ProductDirectGate','ProductReadDriver','CDirectOracleServer']:
  need((helpers/(n+'.class')).is_file(),'BUILD_MANUAL_CLASSES_FIRST')
 need((ROOT/'tools/websocket-test-client/dist/index.html').is_file(),'BUILD_WEB_FIRST')
 RUNTIME.mkdir(parents=True,exist_ok=True);out=RUNTIME/('prepare-'+uuid.uuid4().hex[:16]);out.mkdir()
 fixture=out/'fixture';fixture.mkdir()
 for src,dst in [(source/'classes/java/main','classes'),(helpers,'helpers'),(source/'product-read-ci/jars','jars')]:
  need(bool(tree(src)),'BUILD_FIRST');shutil.copytree(src,fixture/dst)
 shutil.copytree(source/'resources/main',fixture/'classes',dirs_exist_ok=True)
 shutil.copytree(ROOT/'tools/websocket-test-client/dist',fixture/'classes/static',ignore=shutil.ignore_patterns('.smoke'))
 artifacts=fixture/'artifacts';artifacts.mkdir();attestation=linux.artifact_fixture(ROOT,artifacts)
 jdk=find_jdk();cp=os.pathsep.join(map(str,[fixture/'helpers',fixture/'classes',fixture/'jars/*']))
 r=execute([jdk['java'],'-Xmx96m',*direct_module().FLAGS,'-cp',cp,'ProductManualActor','--self-test'],20);need(r.returncode==0,'COMPILED_SELF_TEST')
 for n in ['schema.sql','c-wallet-schema.sql','c-consent-schema.sql','c-direct-execution-schema.sql']:
  need((fixture/'classes'/n).is_file() and (fixture/'classes'/n).read_bytes()==(ROOT/'backend/src/main/resources'/n).read_bytes(),'SQL_RESOURCE')
 need(source_map()==before,'SOURCE_DRIFT')
 files=tree(fixture);verify_packaged(stamp,files);need(stamp_path.read_bytes()==stamp_bytes,'BUILD_STAMP_CHANGED_DURING_COPY')
 save(out/'prepared.json',{'status':'PREPARED_NOT_RUNNING','source':before,'files':files,'contracts':attestation,'javaVersion':jdk['version']})
 return {'status':'PREPARED_NOT_RUNNING','prepare':str(out.relative_to(ROOT))}

def scoped(path):
 p=(ROOT/path).resolve();need(p.parent==RUNTIME.resolve() and re.fullmatch(r'(?:prepare-|session-)[0-9a-f]{16}',p.name),'RUNTIME_SCOPE');return p

def memory():return resources.memory() if os.name=='nt' else linux.memory()

def admission(path):
 observer=resources.Observer(path,memory,lambda:shutil.disk_usage(ROOT).free)
 observer.sample('BEFORE_START',True);return observer

def port_check():
 for port in PORTS.values():
  with socket.socket(socket.AF_INET,socket.SOCK_STREAM) as s:
   if hasattr(socket,'SO_EXCLUSIVEADDRUSE'):s.setsockopt(socket.SOL_SOCKET,socket.SO_EXCLUSIVEADDRUSE,1)
   s.bind(('127.0.0.1',port))

def inspect(kind,name):
 rows=json.loads(docker(kind,'inspect',name));need(len(rows)==1,'INSPECT_SINGLE');return rows[0]

def host_probe(meta):
 """Bounded read-only host path check; no proxy, credentials, wallet, retry or transaction."""
 http=urllib.request.build_opener(urllib.request.ProxyHandler({}))
 def read(url,payload=None):
  request=urllib.request.Request(url,data=None if payload is None else json.dumps(payload).encode(),headers={'Content-Type':'application/json'})
  with http.open(request,timeout=3) as r:
   data=r.read(65537);need(len(data)<=65536,'HOST_BODY_BOUND');return json.loads(data)
 market=read('http://127.0.0.1:32175/api/markets/mSEC');need(market.get('priceStatus')=='SIMULATED','HOST_MARKET')
 try:read('http://127.0.0.1:32175/api/c-futures/account');need(False,'HOST_AUTH_MISSING')
 except urllib.error.HTTPError as e:need(e.code==401,'HOST_AUTH_STATUS')
 def rpc(method,params):
  reply=read('http://127.0.0.1:32176',{'jsonrpc':'2.0','id':1,'method':method,'params':params});need(reply.get('id')==1 and 'error' not in reply,'HOST_RPC');return reply['result']
 need(rpc('eth_chainId',[])=='0x7a69' and rpc('eth_getBlockByNumber',['0x0',False])['hash']==meta['genesisHash'],'HOST_CHAIN')
 head=rpc('eth_getBlockByNumber',['latest',False]);pin={'blockHash':head['hash'],'requireCanonical':True}
 for n in ['token','controller','rules','vault']:need(rpc('eth_getCode',[meta[n],pin])!='0x','HOST_CODE')
 need(rpc('eth_getBlockByNumber',[head['number'],False])==head,'HOST_CANONICAL')
 manual_clock.require_ready(rpc)

def owned(record,role):
 item=record['containers'][role];info=inspect('container',item['name']);run=record['run'];net=run+'-network'
 need(info['Id']==item.get('id',info['Id']) and info['Name']=='/'+item['name'] and info['Image']==item['image'],'CONTAINER_ID')
 need(info['Config']['Labels'].get(LABEL)==run and info['HostConfig']['NetworkMode']==net and set(info['NetworkSettings']['Networks'])=={net},'CONTAINER_OWNER')
 need(not info['HostConfig'].get('Privileged'),'PRIVILEGES')
 if role=='state-init':
  need(info['HostConfig'].get('CapAdd') in (['CHOWN'],['CAP_CHOWN']) and info['HostConfig'].get('CapDrop')==['ALL'] and info['HostConfig'].get('ReadonlyRootfs') is True,'STATE_INIT_PRIVILEGES')
  need(info['Config'].get('User')=='0:0' and info['Config'].get('Entrypoint')==['/bin/sh'] and info['Config'].get('Cmd')==['-ec',STATE_INIT],'STATE_INIT_COMMAND')
  need('no-new-privileges' in info['HostConfig'].get('SecurityOpt',[]),'STATE_INIT_SECURITY')
  need(len(info.get('Mounts',[]))==1 and info['Mounts'][0].get('Type')=='volume' and info['Mounts'][0].get('Name')==run+'-anvil-data' and info['Mounts'][0].get('Destination')=='/state' and info['Mounts'][0].get('RW') is True,'STATE_INIT_MOUNT')
 else:need(not info['HostConfig'].get('CapAdd'),'PRIVILEGES')
 if role=='anvil':
  need(info['Config'].get('User')=='1000:1000' and info['Config'].get('Entrypoint')==['/bin/sh'] and info['Config'].get('Cmd')==['-ec',STATE_START,'manual-anvil',*ANVIL_ARGS] and info['HostConfig'].get('CapDrop')==['ALL'],'ANVIL_NONROOT_COMMAND')
 binds=info['HostConfig'].get('PortBindings') or {}
 expected={'8080/tcp':str(PORTS['app'])} if role=='app' else {'8545/tcp':str(PORTS['anvil'])} if role=='anvil' else {}
 need(set(binds)==set(expected),'PUBLISHED_SCOPE')
 for key,port in expected.items():need(binds[key]==[{'HostIp':'127.0.0.1','HostPort':port}],'LOOPBACK_ONLY')
 return info

def persist(session,record):
 # Lifecycle registry only; immutable prepared hashes and receipts are never overwritten.
 temp=session/'owned.next';need(not temp.exists(),'REGISTRY_TEMP_EXISTS');save(temp,record);os.replace(temp,session/'owned.json')

def mount(path,target,read_only=True):
 return 'type=bind,source='+str(path.resolve())+',target='+target+(',readonly' if read_only else '')

def active_path(session,record=None):
 record=record if record is not None else json.loads((session/'owned.json').read_text()) if (session/'owned.json').exists() else {}
 name=record.get('lifecycle')
 if name is None:return session
 need(isinstance(name,str) and re.fullmatch('lifecycle-[0-9a-f]{16}',name),'LIFECYCLE_NAME')
 path=session/name;need(path.is_dir() and not path.is_symlink() and path.resolve().parent==session.resolve(),'LIFECYCLE_SCOPE');return path

def check_stop(session):need(not (active_path(session)/'control/STOP').exists(),'STOP_REQUESTED')

def diagnostic(session,stage,**fields):
 # Callers pass only fixed enums, numeric durations and sanitized state; never raw stderr/config.
 row={'atUtc':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),'stage':stage,**fields}
 with (session/'anvil-readiness.jsonl').open('a',encoding='utf8') as f:f.write(json.dumps(row,separators=(',',':'))+'\n')

def probe_events(data):
 result=[]
 for line in (data or b'')[:4096].decode('utf8',errors='replace').splitlines():
  try:row=json.loads(line)
  except ValueError:continue
  if isinstance(row,dict) and set(row)=={'stage','elapsedMs'} and isinstance(row['stage'],str) and row['stage'] in PROBE_STAGES and type(row['elapsedMs']) is int and 0<=row['elapsedMs']<=30000:result.append(row)
 return result[:8]

def anvil_probe(opener=None,trace=lambda stage:None,connection_factory=http.client.HTTPConnection):
 """One child-process read. Parent, not socket timeout, bounds its wall-clock lifetime."""
 payload=json.dumps({'jsonrpc':'2.0','id':1,'method':'eth_chainId','params':[]}).encode()
 if opener is not None: # deterministic offline test seam, not used by the CLI child
  request=urllib.request.Request('http://127.0.0.1:32176',data=payload,headers={'Content-Type':'application/json'})
  with opener.open(request,timeout=3) as response:data=response.read(4097)
 else:
  conn=connection_factory('127.0.0.1',32176,timeout=3) # numeric loopback; no DNS/proxy/environment lookup
  try:
   trace('TCP_CONNECT');conn.connect();trace('TCP_CONNECTED')
   trace('HTTP_REQUEST');conn.request('POST','/',body=payload,headers={'Content-Type':'application/json'})
   response=conn.getresponse();trace('HTTP_HEADERS');need(response.status==200,'ANVIL_READY_HTTP')
   trace('BODY_READ');data=response.read(4097)
  finally:conn.close()
 need(len(data)<=4096,'ANVIL_READY_BODY');value=json.loads(data)
 need(value.get('jsonrpc')=='2.0' and value.get('id')==1 and value.get('result')=='0x7a69' and 'error' not in value,'ANVIL_READY_IDENTITY')
 trace('IDENTITY_OK');return {'jsonrpc':'2.0','id':1,'result':'0x7a69'}

def invoke_anvil_probe(timeout):
 return execute([sys.executable,'-B',str(Path(__file__).resolve()),'--anvil-readiness-probe'],timeout)

def anvil_ready(session,invoke=invoke_anvil_probe,clock=time.monotonic,sleep=time.sleep):
 """Up to five isolated read-only children, each <=3s within a 15s total budget."""
 end=clock()+15
 for attempt in range(5):
  check_stop(session);remaining=end-clock()
  if remaining<=0:break
  began=clock();budget=min(3,remaining)
  diagnostic(session,'HOST_ATTEMPT',attempt=attempt+1,budgetMs=int(budget*1000))
  try:
   reply=invoke(budget)
  except subprocess.TimeoutExpired as e:
   diagnostic(session,'HOST_CHILD_TIMEOUT',attempt=attempt+1,elapsedMs=int((clock()-began)*1000),events=probe_events(e.stderr));reply=None
  if reply is not None:diagnostic(session,'HOST_CHILD_EXIT',attempt=attempt+1,exitCode=reply.returncode,elapsedMs=int((clock()-began)*1000),events=probe_events(reply.stderr))
  check_stop(session);now=clock()
  need(now<=end,'ANVIL_READY_TOTAL_DEADLINE')
  if reply is not None:
   need(now-began<=budget,'ANVIL_READY_RESPONSE_DEADLINE')
   if reply.returncode==0:
    need(len(reply.stdout)<=4096 and json.loads(reply.stdout)=={'jsonrpc':'2.0','id':1,'result':'0x7a69'},'ANVIL_READY_IDENTITY');return
   need(reply.returncode==1,'ANVIL_READY_PROBE_REJECTED')
  if attempt<4:sleep(max(0,min(.3,end-clock())))
 need(False,'ANVIL_READY_TIMEOUT')

def internal_anvil_ready(session,record):
 """One read inside the actual Anvil container. No container creation or transaction."""
 check_stop(session);info=owned(record,'anvil');state=info['State']
 diagnostic(session,'ANVIL_PROCESS',running=state['Running'],exitCode=state.get('ExitCode'),oomKilled=bool(state.get('OOMKilled')),engineErrorPresent=bool(state.get('Error')))
 need(state['Running'],'ANVIL_PROCESS_EXIT')
 began=time.monotonic()
 try:
  r=execute(['docker','exec',info['Id'],'/usr/local/bin/cast','chain-id','--rpc-url','http://127.0.0.1:8545','--rpc-timeout','2'],5)
 except subprocess.TimeoutExpired:
  diagnostic(session,'CONTAINER_RPC_TIMEOUT',elapsedMs=int((time.monotonic()-began)*1000));raise ValueError('MANUAL_ANVIL_INTERNAL_TIMEOUT') from None
 diagnostic(session,'CONTAINER_RPC_EXIT',exitCode=r.returncode,chainMatches=r.stdout.strip()==b'31337',elapsedMs=int((time.monotonic()-began)*1000))
 check_stop(session);need(r.returncode==0 and r.stdout.strip()==b'31337','ANVIL_INTERNAL_RPC')

def fresh_state_volume(record):
 name=record['run']+'-anvil-data'
 need(record['volumes'].get('anvil')==name and not any(r in record['containers'] for r in ['state-init','anvil','seed']),'FRESH_STATE_ONLY')
 volume=inspect('volume',name)
 need(volume.get('Name')==name and volume.get('Driver')=='local' and not volume.get('Options') and volume.get('Labels',{}).get(LABEL)==record['run'],'STATE_VOLUME_OWNER')

def failure_observation(session,record):
 if 'anvil' not in record['containers']:return
 try:
  info=owned(record,'anvil');state=info['State']
  diagnostic(session,'FAILURE_PROCESS',running=state['Running'],exitCode=state.get('ExitCode'),oomKilled=bool(state.get('OOMKilled')),engineErrorPresent=bool(state.get('Error')))
 except Exception:diagnostic(session,'FAILURE_OBSERVATION_UNAVAILABLE');return
 if state['Running'] and not (active_path(session)/'control/STOP').exists():
  try:internal_anvil_ready(session,record)
  except Exception:diagnostic(session,'FAILURE_INTERNAL_CHECK_REJECTED')
 try:
  r=execute(['docker','logs','--tail','10',info['Id']],3)
  diagnostic(session,'FAILURE_LOG_SUMMARY',readSucceeded=r.returncode==0,stateWritableMarker=b'MANUAL_STATE_WRITABLE' in r.stdout,stdoutBytes=len(r.stdout),stderrBytes=len(r.stderr))
 except Exception:diagnostic(session,'FAILURE_LOG_UNAVAILABLE')

def state_init_options(record):
 return ['--memory=64m','--user','0:0','--read-only','--cap-drop=ALL','--cap-add=CHOWN','--security-opt=no-new-privileges','--mount','type=volume,source='+record['volumes']['anvil']+',target=/state','--entrypoint','/bin/sh']

def manual_network_args(run,subnet):
 # Windows Desktop host publishing failed on --internal in the bounded A/B probe.
 # Only manual sessions use this private named bridge; read CI keeps its internal network.
 # Outbound egress is not blocked by this network. All published ports remain loopback-only.
 args=network.create_args(run,subnet);args.remove('--internal')
 args[3:3]=['--driver','bridge'];args[-1:-1]=['--label',LABEL+'='+run];return args

def start(prepared,address):
 address=owner(address);prepared=scoped(prepared)
 p=json.loads((prepared/'prepared.json').read_text());fixture=prepared/'fixture'
 need(p['status']=='PREPARED_NOT_RUNNING' and p['source']==source_map() and p['files']==tree(fixture),'PREPARED_IDENTITY')
 need(not (prepared/'RUN_CLAIM').exists(),'CLAIM_USED')
 observer=admission(prepared/('resources-'+uuid.uuid4().hex[:8]+'.jsonl'));port_check()
 images={r:docker('image','inspect',ref,'--format','{{.Id}}') for r,ref in LOCK['images'].items()}
 plan=network.inventory(execute) if os.name=='nt' else linux.inventory(execute)
 run='ccpg-'+uuid.uuid4().hex[:16];session=RUNTIME/('session-'+run[5:]);session.mkdir(mode=0o700)
 for n in ['config','control','appEvidence','oracleEvidence','oracle-config','fixture-public','private-evidence','bootstrapEvidence']:(session/n).mkdir(mode=0o700)
 record={'run':run,'status':'STARTING_NOT_ACCEPTED','externalOwner':address,'stopPolicy':'EXPLICIT_STOP_OR_FAILURE','prepared':str(prepared.relative_to(ROOT)),'containers':{},'volumes':{},'network':None,'web':'http://127.0.0.1:32175','rpc':'http://127.0.0.1:32176','userAcceptance':'NOT_RUN'}
 persist(session,record);save(prepared/'RUN_CLAIM',{'run':run})
 passwords={k:secrets.token_hex(32) for k in ['OWNER','API','JWT','TEST','ORACLE']}
 db='ccpg_'+run[5:];pgowner='ccowner_'+run[5:]
 pg=session/'pg.private.env';pg.write_text('POSTGRES_USER='+pgowner+'\nPOSTGRES_DB='+db+'\nPOSTGRES_PASSWORD='+passwords['OWNER']+'\nPOSTGRES_INITDB_ARGS=--auth-host=scram-sha-256 --auth-local=trust\n',encoding='utf8')
 env=session/'bootstrap.private.env';env.write_text('\n'.join(['READ_RUN_ID='+run,'DIRECT_ORACLE_TOKEN='+passwords['ORACLE']]+['FULL_READ_'+k+('_SECRET' if k=='JWT' else '_PASSWORD')+'='+v for k,v in passwords.items() if k!='ORACLE'])+'\n',encoding='utf8')
 def launch(role,image,options,args):
  check_stop(session)
  name=run+'-'+role;record['containers'][role]={'name':name,'image':image};persist(session,record)
  common=['run','-d','--pull=never','--name',name,'--label',LABEL+'='+run,'--network',run+'-network','--restart=no','--pids-limit=128','--cpus=1']
  if role in ['pg','anvil','app','oracle']:common+=['--network-alias',role]
  docker(*common,*options,image,*args)
  info=owned(record,role);record['containers'][role]['id']=info['Id'];persist(session,record);return info
 def actor(role,args,bindings,envfile=None,limit='384m'):
  options=['--memory='+limit,'--read-only','--cap-drop=ALL','--security-opt=no-new-privileges','--tmpfs','/tmp:rw,nosuid,size=32m','--mount',mount(fixture,'/app-fixture'),'--entrypoint','java']
  for src,target,ro in bindings:options+=['--mount',mount(src,target,ro)]
  if envfile:options+=['--env-file',str(envfile)]
  return launch(role,images['java'],options,actor_argv(args))
 def wait_exit(role,timeout):
  end=time.monotonic()+timeout
  while time.monotonic()<end:
   check_stop(session);state=owned(record,role)['State']
   if not state['Running']:need(state['ExitCode']==0,role.upper()+'_FAILED');return
   time.sleep(.3)
  need(False,role.upper()+'_TIMEOUT')
 def wait_file(role,path,timeout):
  end=time.monotonic()+timeout
  while time.monotonic()<end:
   check_stop(session)
   if path.is_file():json.loads(path.read_text());return
   need(owned(record,role)['State']['Running'],role.upper()+'_EXIT');time.sleep(.3)
  need(False,role.upper()+'_READY_TIMEOUT')
 try:
  check_stop(session);observer.sample('BEFORE_RESOURCES',True)
  args=manual_network_args(run,plan['subnet']);created,network_diagnostic=network.command(execute,'NETWORK_CREATE',args);save(session/'network-create.json',network_diagnostic)
  need(created is not None and created.returncode==0,'NETWORK_CREATE');net=inspect('network',run+'-network');need(net['Internal'] is False and net.get('Driver')=='bridge' and net['Labels'].get(LABEL)==run,'NETWORK_OWNER');record['network']=net['Id'];persist(session,record)
  for role in ['pg','anvil']:
   check_stop(session)
   name=run+'-'+role+'-data';record['volumes'][role]=name;persist(session,record)
   need(not docker('volume','ls','-q','--filter','name=^'+name+'$').split(),'VOLUME_ALREADY_EXISTS')
   docker('volume','create','--label',LABEL+'='+run,name);need(inspect('volume',name)['Labels'].get(LABEL)==run,'VOLUME_OWNER')
  launch('pg',images['pg'],['--memory=256m','--env-file',str(pg),'--mount','type=volume,source='+record['volumes']['pg']+',target=/var/lib/postgresql/data'],[])
  end=time.monotonic()+35
  while time.monotonic()<end:
   check_stop(session)
   if execute(['docker','exec',record['containers']['pg']['name'],'pg_isready','-h','127.0.0.1','-U',pgowner,'-d',db],5).returncode==0:break
   time.sleep(.5)
  else:need(False,'PG_READY')
  check_stop(session);fresh_state_volume(record)
  launch('state-init',images['anvil'],state_init_options(record),['-ec',STATE_INIT]);wait_exit('state-init',10)
  diagnostic(session,'STATE_INIT_EXIT',exitCode=0,uid=1000,gid=1000,mode='0755')
  launch('anvil',images['anvil'],['--memory=256m','--user','1000:1000','--cap-drop=ALL','--security-opt=no-new-privileges','--publish','127.0.0.1:32176:8545','--mount','type=volume,source='+record['volumes']['anvil']+',target=/state','--entrypoint','/bin/sh'],['-ec',STATE_START,'manual-anvil',*ANVIL_ARGS])
  internal_anvil_ready(session,record)
  check_stop(session)
  launch('rpc-peer',images['anvil'],['--memory=64m','--user','1000:1000','--read-only','--cap-drop=ALL','--security-opt=no-new-privileges','--entrypoint','/bin/sh'],['-ec','test "$(/usr/local/bin/cast chain-id --rpc-url http://anvil:8545 --rpc-timeout 2)" = 31337'])
  wait_exit('rpc-peer',10);diagnostic(session,'DOCKER_NETWORK_RPC_OK',chainId=31337)
  anvil_ready(session)
  actor('seed',['seed',address],[(fixture/'artifacts','/artifacts',True),(session/'private-evidence','/private-evidence',False),(session/'fixture-public','/fixture',False),(session/'oracle-config','/oracle-config',False)])
  wait_exit('seed',300)
  meta=json.loads((session/'fixture-public/test-chain.json').read_text());need(meta['userA']==address and meta['scope']=='NEW_MANUAL_EXTERNAL_OWNER_NOT_ACCEPTED','SEED_OWNER')
  actor('bootstrap',['bootstrap'],[(session/'fixture-public','/fixture',False),(session/'config','/gate-config',False),(session/'oracle-config','/oracle-config',False)],env)
  wait_exit('bootstrap',90)
  # Oracle key never mounts into product Spring, no user signing actor is launched.
  oracle_options=['--memory=256m','--read-only','--cap-drop=ALL','--security-opt=no-new-privileges','--tmpfs','/tmp:rw,nosuid,size=32m','--mount',mount(fixture,'/app-fixture'),'--mount',mount(session/'oracle-config','/oracle-config'),'--mount',mount(session/'control','/gate-control'),'--mount',mount(session/'oracleEvidence','/oracle-evidence',False),'--entrypoint','java']
  launch('oracle',images['java'],oracle_options,['-Xmx128m','-cp','/app-fixture/helpers:/app-fixture/classes:/app-fixture/jars/*','CDirectOracleServer','/oracle-config/identity.json','/oracle-config/oracle-key.private','/oracle-config/auth-token.private','/gate-control','/oracle-evidence','--manual-stop-only','ready.json'])
  wait_file('oracle',session/'oracleEvidence/ready.json',20)
  app_options=['--memory=768m','--publish','127.0.0.1:32175:8080','--read-only','--cap-drop=ALL','--security-opt=no-new-privileges','--tmpfs','/tmp:rw,nosuid,size=32m','--mount',mount(fixture,'/app-fixture'),'--mount',mount(session/'config','/gate-config'),'--mount',mount(session/'control','/gate-control'),'--mount',mount(session/'appEvidence','/gate-evidence',False),'--entrypoint','java']
  launch('app',images['java'],app_options,['-Xms32m','-Xmx256m','-XX:MaxMetaspaceSize=192m','-cp','/app-fixture/helpers:/app-fixture/classes:/app-fixture/jars/*','ProductManualActor','app'])
  wait_file('app',session/'appEvidence/ready.json',120);observer.sample('APP_READY')
  check_stop(session);host_probe(meta);check_stop(session);record['status']='READY_FOR_USER_LOGIN_NOT_METAMASK_ACCEPTED';record['identity']=meta;persist(session,record)
  print(json.dumps({'status':record['status'],'session':str(session.relative_to(ROOT)),'web':record['web'],'rpc':record['rpc'],'stopPolicy':record['stopPolicy'],'userAction':'Create/login to a new TMS test account, connect independent test MetaMask, then prove ownership. User approval required.'}),flush=True)
  # Foreground supervisor: no hidden daemon, no user transaction and no retry loop.
  while not (session/'control/STOP').exists():
   need(owned(record,'app')['State']['Running'] and owned(record,'oracle')['State']['Running'],'SERVICE_EXIT');time.sleep(2)
 except BaseException:
  record['status']='BLOCKED_NO_RETRY';persist(session,record);failure_observation(session,record);raise
 finally:shutdown(session)

def shutdown(path):
 session=scoped(path);record=json.loads((session/'owned.json').read_text());need(session.name=='session-'+record['run'][5:],'SESSION_RUN')
 live=active_path(session,record)
 if (live/'stopped.json').exists():return json.loads((live/'stopped.json').read_text())
 try:save(live/'STOP_CLAIM',{'run':record['run']})
 except FileExistsError:return {'status':'STOP_ALREADY_CLAIMED_CHECK_STATUS_NO_RETRY'}
 (live/'control/STOP').touch(exist_ok=True)
 # Verify *all* present resources before any Docker stop; no wildcard cleanup/delete.
 current={}
 for role,item in record['containers'].items():
  ids=docker('container','ls','-aq','--no-trunc','--filter','name=^/'+item['name']+'$').split()
  need(len(ids)<=1,'STOP_NAME_COLLISION')
  if ids:current[role]=owned(record,role)
 if record.get('network'):
  n=inspect('network',record['run']+'-network');need(n['Id']==record['network'] and n['Labels'].get(LABEL)==record['run'],'STOP_NETWORK_OWNER')
 for v in record['volumes'].values():
  existing=docker('volume','ls','-q','--filter','name=^'+v+'$').split()
  if existing:need(existing==[v] and inspect('volume',v)['Labels'].get(LABEL)==record['run'],'STOP_VOLUME_OWNER')
 states={};snapshots={};errors=[]
 if 'app' in current:
  end=time.monotonic()+20
  while time.monotonic()<end and owned(record,'app')['State']['Running']:time.sleep(.2)
 for role in ['app','oracle','bootstrap','seed','rpc-peer','state-init']:
  if role in current:
   try:docker('stop','--time','20',current[role]['Id'],timeout=25);states[role]=owned(record,role)['State']['ExitCode']
   except Exception:errors.append(role.upper()+'_STOP_UNCONFIRMED')
 if 'pg' in current and current['pg']['State']['Running']:
  try:
   dest=live/'postgres.private.dump';need(not dest.exists(),'SNAPSHOT_EXISTS')
   with dest.open('xb') as f:
    r=subprocess.run(['docker','exec',current['pg']['Id'],'pg_dump','-Fc','-U','ccowner_'+record['run'][5:],'-d','ccpg_'+record['run'][5:]],stdout=f,stderr=subprocess.DEVNULL,timeout=45,env=environment())
   need(r.returncode==0 and dest.stat().st_size>0,'PG_DUMP_UNCONFIRMED');snapshots['pg']={'sha256':linux.sha(dest),'bytes':dest.stat().st_size}
  except Exception:errors.append('PG_DUMP_UNCONFIRMED')
 for role in ['anvil','pg']:
  if role in current:
   try:docker('stop','--time','20',current[role]['Id'],timeout=25);states[role]=owned(record,role)['State']['ExitCode']
   except Exception:errors.append(role.upper()+'_STOP_UNCONFIRMED')
 if 'anvil' in current:
  try:
   dest=live/'anvil-state.private.json';need(not dest.exists(),'SNAPSHOT_EXISTS')
   r=execute(['docker','cp',current['anvil']['Id']+':/state/anvil.json',str(dest)],20)
   need(r.returncode==0 and dest.is_file() and dest.stat().st_size>0,'ANVIL_SNAPSHOT_UNCONFIRMED');snapshots['anvil']={'sha256':linux.sha(dest),'bytes':dest.stat().st_size}
  except Exception:errors.append('ANVIL_SNAPSHOT_UNCONFIRMED')
 normal=(live/'appEvidence/normal-stop.json').exists()
 if 'app' in current and not normal:errors.append('APP_NORMAL_STOP_UNCONFIRMED')
 result={'status':'STOPPED_PRESERVED_NOT_RESTART_VERIFIED' if not errors and all(n==0 for n in states.values()) else 'STOPPED_PRESERVATION_PARTIAL','states':states,'snapshots':snapshots,'errors':errors,'appNormalStop':normal,'userAcceptance':'NOT_ASSESSED'}
 save(live/'stopped.json',result);return result

def request_stop(path):
 """External CLI only requests STOP; foreground start owns all teardown/snapshots."""
 session=scoped(path);record=json.loads((session/'owned.json').read_text());need(session.name=='session-'+record['run'][5:],'SESSION_RUN')
 live=active_path(session,record)
 if (live/'stopped.json').exists():return json.loads((live/'stopped.json').read_text())
 (live/'control/STOP').touch(exist_ok=True)
 return {'status':'STOP_REQUESTED_SUPERVISOR_MUST_CONFIRM','session':str(session.relative_to(ROOT))}

def status(path):
 session=scoped(path);record=json.loads((session/'owned.json').read_text());need(session.name=='session-'+record['run'][5:],'SESSION_RUN')
 live=active_path(session,record)
 if (live/'stopped.json').exists():return json.loads((live/'stopped.json').read_text())
 state='STOPPING' if (live/'STOP_CLAIM').exists() else 'STOP_REQUESTED_SUPERVISOR_MUST_CONFIRM' if (live/'control/STOP').exists() else record['status']
 return {'status':state,'web':record['web'],'rpc':record['rpc'],'externalOwner':record['externalOwner'],'stopPolicy':record.get('stopPolicy','LEGACY_BOUNDED'),'containers':{r:owned(record,r)['State']['Running'] for r in record['containers'] if 'id' in record['containers'][r]},'stopped':False,'userAcceptance':'NOT_ASSESSED'}

def main():
 parser=argparse.ArgumentParser(description=__doc__);sub=parser.add_subparsers(dest='command',required=True)
 sub.add_parser('prepare');s=sub.add_parser('start');s.add_argument('prepared');s.add_argument('--owner',required=True);s.add_argument('--execute',action='store_true',required=True)
 for name in ['status','stop']:sub.add_parser(name).add_argument('session')
 a=parser.parse_args()
 try:
  if a.command=='prepare':result=prepare()
  elif a.command=='start':start(a.prepared,a.owner);return
  elif a.command=='status':result=status(a.session)
  else:result=request_stop(a.session)
  print(json.dumps(result))
 except Exception as e:
  code=str(e);print(json.dumps({'status':'BLOCKED_NO_RETRY','safeError':code if re.fullmatch('MANUAL_[A-Z0-9_]+',code) else type(e).__name__}));raise SystemExit(1)

if __name__=='__main__':
 if sys.argv[1:]==['--anvil-readiness-probe']:
  began=time.monotonic()
  def trace(stage):print(json.dumps({'stage':stage,'elapsedMs':int((time.monotonic()-began)*1000)}),file=sys.stderr,flush=True)
  try:
   trace('CHILD_START');print(json.dumps(anvil_probe(trace=trace)))
  except ConnectionRefusedError:trace('ERROR_CONNECT_REFUSED');raise SystemExit(1)
  except TimeoutError:trace('ERROR_SOCKET_TIMEOUT');raise SystemExit(1)
  except (urllib.error.URLError,ConnectionError):trace('ERROR_CONNECTION');raise SystemExit(1)
  except Exception:trace('ERROR_PROTOCOL');raise SystemExit(2)
 else:main()
