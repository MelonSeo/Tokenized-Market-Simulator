"""Linux-only adapters. Windows resource/network implementations remain unchanged."""
import hashlib,ipaddress,json,os,re,shutil,socket,socketserver,select,subprocess,threading,time
from pathlib import Path
import product_read_network as network
from product_read_resources import ResourceError,utc

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def need(ok,code):
 if not ok:raise RuntimeError('PRODUCT_READ_LINUX_'+code)

def memory(meminfo=Path('/proc/meminfo'),maximum=Path('/sys/fs/cgroup/memory.max'),current=Path('/sys/fs/cgroup/memory.current')):
 start=utc();clock=time.perf_counter_ns()
 rows={line.split(':',1)[0]:line.split(':',1)[1].strip() for line in meminfo.read_text().splitlines() if ':' in line}
 def value(key):
  text=rows[key];need(bool(re.fullmatch(r'[0-9]+ kB',text)),'MEMORY_FORMAT');return int(text.split()[0])*1024
 total=value('MemTotal');available=value('MemAvailable');need(0<=available<=total and total>0,'MEMORY_VALUES')
 physical=available;bound=False
 if maximum.exists():
  raw=maximum.read_text().strip()
  if raw!='max':
   need(bool(re.fullmatch('[0-9]+',raw)),'CGROUP_LIMIT');used=current.read_text().strip();need(bool(re.fullmatch('[0-9]+',used)),'CGROUP_CURRENT')
   available=min(available,max(0,int(raw)-int(used)));bound=True
 return {'memoryStartedAt':start,'memoryCompletedAt':utc(),'memoryElapsedNs':time.perf_counter_ns()-clock,
  'availableBytes':available,'totalPhysicalBytes':total,'hostMemAvailableBytes':physical,'cgroupBoundApplied':bound,'measurement':'LINUX_MEMAVAILABLE_AND_FINITE_CGROUP_HEADROOM'}

def inventory(execute,routes=Path('/proc/net/route')):
 end=time.monotonic()+30
 def read(args):
  left=end-time.monotonic();need(left>0,'INVENTORY_DEADLINE');r=execute(args,timeout=min(10,left))
  need(r.returncode==0 and len(r.stdout)<=4*1024**2,'INVENTORY_RESPONSE');return r.stdout.decode('utf8')
 ids=read(['docker','network','ls','-q']).split();need(0<len(ids)<=4096 and all(re.fullmatch('[0-9a-f]{12,64}',v) for v in ids),'NETWORK_IDS')
 nets=[]
 for index in range(0,len(ids),64):
  batch=ids[index:index+64];items=json.loads(read(['docker','network','inspect',*batch]));need(isinstance(items,list) and len(items)==len(batch),'NETWORK_SHAPE')
  for item in items:
   for cfg in item.get('IPAM',{}).get('Config') or []:
    if cfg.get('Subnet'):
     net=ipaddress.ip_network(cfg['Subnet'],strict=False)
     if net.version==4:nets.append(str(net))
 host=network.proc_routes(routes.read_text());selected=network.select_subnet(nets,host)
 return {'subnet':selected,'networkCount':len(ids),'dockerSubnets':sorted(set(nets)),'linuxRoutes':host,'collisionFree':True}

def artifact_fixture(root,output):
 """Compiler metadata links actual staged source bytes to the deployable artifacts."""
 rows={};compiler=None
 for name in ['CMockCollateral','CCollateralizedFuturesController','CMarginVault','FuturesEngine']:
  path=root/'contracts/out'/ (name+'.sol')/(name+'.json');a=json.loads(path.read_text());meta=json.loads(a['rawMetadata'])
  need(meta['compiler']['version'].startswith('0.8.24+') and meta['settings']['optimizer']=={'enabled':True,'runs':200},'COMPILER')
  need(meta['settings']['compilationTarget']=={'src/'+name+'.sol':name},'COMPILATION_TARGET')
  compiler=meta['compiler']['version']
  for source,description in meta['sources'].items():
   p=(root/'contracts'/source).resolve();need(p.is_relative_to((root/'contracts').resolve()) and p.is_file() and p.stat().st_size<=512*1024,'COMPILER_SOURCE_SCOPE')
   need(source.startswith(('src/','lib/openzeppelin-contracts/')),'COMPILER_DEPENDENCY')
   if source in rows:need(rows[source]['keccak256']==description['keccak256'],'SOURCE_CONFLICT');continue
   entry={'source':source,'file':'source-'+str(len(rows)).zfill(3)+'.bin','sha256':sha(p),'keccak256':description['keccak256']}
   need(len(rows)<256,'SOURCE_COUNT');shutil.copyfile(p,output/entry['file']);rows[source]=entry
  (output/(name+'.json')).write_text(json.dumps(a),encoding='utf8')
 (output/'source-map.json').write_text(json.dumps(list(rows.values())),encoding='utf8')
 return {'compiler':compiler,'sourceCount':len(rows),'artifacts':{n:sha(output/(n+'.json')) for n in ['CMockCollateral','CCollateralizedFuturesController','CMarginVault','FuturesEngine']}}

class Bridge:
 """Opaque localhost bridge to one verified owned container IP; no HTTP parsing or extra JVMs."""
 def __init__(self,address,subnet,port=32171):
  need(ipaddress.ip_address(address) in ipaddress.ip_network(subnet),'BRIDGE_DESTINATION')
  need(type(port) is int and port in (32171,32173),'BRIDGE_PORT')
  self.port=port;self.address=address;self.end=time.monotonic()+110;self.slots=threading.BoundedSemaphore(8);self.sockets=[];self.lock=threading.Lock()
 def start(self):
  owner=self
  class Handler(socketserver.BaseRequestHandler):
   def handle(self):
    if not owner.slots.acquire(timeout=2):return
    remote=None
    try:
     remote=socket.create_connection((owner.address,8080),timeout=3)
     with owner.lock:owner.sockets.extend([self.request,remote])
     self.request.settimeout(10);remote.settimeout(10)
     while time.monotonic()<owner.end:
      ready,_,_=select.select([self.request,remote],[],[],1)
      for source in ready:
       data=source.recv(65536)
       if not data:return
       (remote if source is self.request else self.request).sendall(data)
    except OSError:pass
    finally:
     with owner.lock:
      for s in [self.request,remote]:
       if s in owner.sockets:owner.sockets.remove(s)
     if remote:remote.close()
     owner.slots.release()
  class Server(socketserver.ThreadingTCPServer):daemon_threads=False;allow_reuse_address=False;block_on_close=True
  self.server=Server(('127.0.0.1',self.port),Handler);self.thread=threading.Thread(target=self.server.serve_forever);self.thread.start()
 def close(self):
  self.end=0;self.server.shutdown()
  with self.lock:
   for s in self.sockets:
    try:s.shutdown(socket.SHUT_RDWR)
    except OSError:pass
  self.server.server_close();self.thread.join(3);need(not self.thread.is_alive(),'BRIDGE_EXIT')

def safe_artifacts(live,destination,secrets):
 """Explicit public allowlist, never recursively upload runtime or browser profiles."""
 destination.mkdir(exist_ok=False)
 allowed=['result.json','fixture-public/test-chain.json','verifyEvidence/api-result.json','browser/result.json',
  'browser/01-market.png','browser/02-user-a.png','browser/03-user-b.png','browser/04-logged-out.png',
  'browser/00-login.png','browser/05-account.png','browser/06-position.png']
 allowed+=['fixture-public/public-trade-'+role+'-'+str(action)+'.json' for role in ['userA','userB'] for action in [0,4]]
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
