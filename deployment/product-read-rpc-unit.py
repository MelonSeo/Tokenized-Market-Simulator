"""Compile/run only changed Driver + pure tests, no prepare identity or live claim."""
import datetime,hashlib,json,os,subprocess,uuid
from pathlib import Path
from product_read_tools import find_jdk,ToolError
ROOT=Path(__file__).resolve().parents[1]
old=ROOT/'deployment/runtime/product-read/prepare-20261024T151017Z-002a9b3b'
manifest=json.loads((old/'prepared.json').read_text())
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
assert all(sha(ROOT/p)==v for p,v in manifest['sources'].items() if p.replace('\\','/').startswith('backend/src/main/'))
assert all(sha(old/'fixture'/p)==v for p,v in manifest['fixture'].items())
out=ROOT/'deployment/runtime/product-read'/('rpc-unit-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')+'-'+uuid.uuid4().hex[:8]);out.mkdir(parents=True)
classes=out/'classes';classes.mkdir()
sources=[ROOT/'deployment'/n for n in ['ProductReadDriver.java','ProductReadDriverRpcTest.java','ProductReadConnection.java','ProductReadConnectionTest.java','ProductReadGate.java']]
cp=os.pathsep.join(map(str,[old/'fixture/helpers',old/'fixture/classes',*sorted((old/'fixture/jars').glob('*.jar'))]))
args=['--release','21','-parameters','-proc:none','-encoding','UTF-8','-cp',cp,'-d',str(classes),*map(str,sources)]
arg=out/'compile.args';arg.write_text('\n'.join('"'+v.replace('\\','/')+'"' for v in args),encoding='utf8')
try:jdk=find_jdk()
except ToolError as e:raise SystemExit(str(e)) from None
env={k:v for k,v in os.environ.items() if k.upper() in {'PATH','PATHEXT','SYSTEMROOT','WINDIR','COMSPEC','TEMP','TMP','USERPROFILE','APPDATA','LOCALAPPDATA','PROGRAMFILES'}}
before={p.name:sha(p) for p in sources}
result={'status':'NOT_PASS','sources':before}
try:
 for label,command in [('compile',[jdk['javac'],'@'+str(arg)]),*[(name,[jdk['java'],'-Xmx128m','-cp',str(classes)+os.pathsep+cp,cls,'--self-test']) for name,cls in [('unit','ProductReadDriverRpcTest'),('connection','ProductReadConnectionTest'),('boundary','ProductReadGate')]]]:
  r=subprocess.run(list(map(str,command)),env=env,capture_output=True,timeout=60)
  (out/(label+'.log')).write_bytes(r.stdout+r.stderr);assert r.returncode==0,label
 result['status']='PURE_RPC_UNIT_PASS_NOT_LIVE';print((out/'unit.log').read_text())
finally:
 result['sourcesUnchanged']=before=={p.name:sha(p) for p in sources};(out/'result.json').write_text(json.dumps(result,indent=2),encoding='utf8');print(json.dumps({'unit':out.name,**result}))
