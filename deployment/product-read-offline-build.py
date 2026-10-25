"""Optional low-memory local javac validation using an explicitly supplied, hash-verified JAR manifest.
Not used by CI; never starts Gradle daemons, Docker, RPC or browser processes.
"""
import argparse,hashlib,importlib.util,json,os,shutil,subprocess,uuid
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--dependencies',type=Path,required=True);p.add_argument('--jdk',type=Path,required=True);args=p.parse_args()
 root=Path(__file__).resolve().parents[1];out=root/'deployment/runtime/linux-offline-build'/('javac-'+uuid.uuid4().hex[:12]);out.mkdir(parents=True)
 sha=lambda f:hashlib.sha256(f.read_bytes()).hexdigest()
 dependencies=json.loads(args.dependencies.read_text())['dependencies'];jars=[Path(name) for name in dependencies]
 assert jars and all(f.is_file() and f.suffix=='.jar' and sha(f)==dependencies[str(f)] for f in jars)
 lombok=next(f for f in jars if f.name.startswith('lombok-'));runtime=[f for f in jars if f!=lombok]
 spec=importlib.util.spec_from_file_location('linux_gate',root/'deployment/product-read-linux.py');gate=importlib.util.module_from_spec(spec);spec.loader.exec_module(gate)
 sources=gate.sources();classes=out/'classes';helpers=out/'helpers';classes.mkdir();helpers.mkdir()
 env={k:v for k,v in os.environ.items() if k.upper() in {'PATH','SYSTEMROOT','WINDIR','COMSPEC','TEMP','TMP','USERPROFILE','APPDATA','LOCALAPPDATA','PROGRAMFILES'}}
 def run(label,command):
  r=subprocess.run(list(map(str,command)),env=env,capture_output=True,timeout=120);(out/(label+'.log')).write_bytes(r.stdout+r.stderr)
  if r.returncode:raise RuntimeError('OFFLINE_'+label.upper()+'_FAILED')
 def compile(label,files,cp,dest,processor=False):
  options=['--release','21','-parameters','-encoding','UTF-8','-cp',cp,'-d',str(dest)]
  options+=['-processorpath',str(lombok),'-processor','lombok.launch.AnnotationProcessorHider$AnnotationProcessor'] if processor else ['-proc:none']
  options+=list(map(str,files));argfile=out/(label+'.args');argfile.write_text('\n'.join('"'+s.replace('\\','/')+'"' for s in options),encoding='utf8')
  run(label,[args.jdk/'javac.exe' if os.name=='nt' else args.jdk/'javac','-J-Xmx512m','@'+str(argfile)])
 compile('main',list((root/'backend/src/main/java').rglob('*.java')),os.pathsep.join(map(str,[*runtime,lombok])),classes,True)
 compile('helpers',[root/'deployment'/(name+'.java') for name in gate.HELPERS],os.pathsep.join(map(str,[classes,*runtime])),helpers)
 for name in ['ProductReadGate','ProductReadDriverRpcTest','ProductReadConnectionTest','ProductReadFixture']:
  run(name,[args.jdk/('java.exe' if os.name=='nt' else 'java'),'-Xmx128m','-cp',os.pathsep.join(map(str,[helpers,classes,*runtime])),name,'--self-test'])
 assert gate.sources()==sources
 result={'status':'OFFLINE_JAVAC_AND_PURE_TEST_PASS_NOT_LINUX_OR_EVM','mainSources':len(list((root/'backend/src/main/java').rglob('*.java'))),'helperSources':len(gate.HELPERS),'sourcesUnchanged':True,'sources':sources}
 (out/'result.json').write_text(json.dumps(result),encoding='utf8');print(json.dumps({'result':str(out.relative_to(root)),'status':result['status'],'mainSources':result['mainSources'],'helperSources':result['helperSources']}))
if __name__=='__main__':main()
