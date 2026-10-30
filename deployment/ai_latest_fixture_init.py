"""New source-built Phase A environment; existing fixture/deploy/cleanup reused."""
import datetime as dt
import importlib.util
import json
from pathlib import Path
import secrets
import subprocess
import sys
import argparse
import ctypes
import shutil

ROOT=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('phase_a_latest',ROOT/'phase-a.py')
phase=importlib.util.module_from_spec(spec);spec.loader.exec_module(phase)

def resources():
    class Memory(ctypes.Structure):
        _fields_=[('length',ctypes.c_ulong),('load',ctypes.c_ulong)]+[(n,ctypes.c_ulonglong) for n in ('totalPhys','availPhys','totalPage','availPage','totalVirtual','availVirtual','extended')]
    m=Memory();m.length=ctypes.sizeof(m)
    if not ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(m)):raise RuntimeError('MEMORY_API_FAILED')
    ram=m.availPhys/1024**2;disk=shutil.disk_usage(ROOT).free/1024**3
    print(json.dumps({'availableRamMiB':round(ram,2),'freeDiskGiB':round(disk,2)}),flush=True)
    if ram<1920 or disk<2:raise RuntimeError('RESOURCE_GUARD_BLOCKED')


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--execute-session');parser.add_argument('--credentials-file')
    args=parser.parse_args();resources()
    if args.execute_session:
        from ai_latest_evaluation_broker import prepare
        session,meta=phase.load_session(args.execute_session)
        study=session/'latest-study';study.mkdir(exist_ok=False)
        who=subprocess.run(['whoami'],check=True,capture_output=True,text=True).stdout.strip()
        subprocess.run(['icacls',str(study),'/inheritance:r','/grant:r',who+':(OI)(CI)F','SYSTEM:(OI)(CI)F'],check=True,capture_output=True)
        port=next(p for p in secrets.SystemRandom().sample(range(49152,65536),100) if phase._port_available(p))
        prepare(study,port,args.credentials_file,'sha256:eac621400b7b7ff52493883e41e930e3d104695fea5b68cc0c42370cf7880067',approved=True)
        print('LATEST_STUDY='+str(study),flush=True)
        phase.fixture(session,meta,'futures-ai-latest',latest_study=study)
        return
    name='phase-a-'+dt.datetime.now(dt.timezone.utc).strftime('%Y%m%d%H%M%S')+'-'+secrets.token_hex(4)
    session=ROOT/'runtime'/name;session.mkdir(parents=True,exist_ok=False)
    who=subprocess.run(['whoami'],check=True,capture_output=True,text=True).stdout.strip()
    subprocess.run(['icacls',str(session),'/inheritance:r','/grant:r',who+':(OI)(CI)F','SYSTEM:(OI)(CI)F'],check=True,capture_output=True)
    for child in ('logs','fixtures','evidence'):(session/child).mkdir()
    phase.execute(session,['docker','version','--format','{{.Server.Version}}'],'server-health',timeout=15)
    for image in ('postgres:16',phase.FOUNDRY):phase.execute(session,['docker','image','inspect',image],'base-image')
    image='tokenized-ai-latest-tools:'+name
    phase.execute(session,['docker','build','--pull=false','--network=default','-f',str(ROOT/'Dockerfile.ai-latest-tools'),'-t',image,str(phase.REPO)],'tools-build',timeout=600)
    digest=phase.execute(session,['docker','image','inspect',image,'--format','{{.Id}}'],'tools-digest')
    head=phase.execute(session,['git','rev-parse','HEAD'],'source-head')
    phase.private(session/'session.json',json.dumps({'session':name,'sourceHead':head,'productHash':phase.product_hash(),
        'externalProviders':False,'toolsImage':image,'toolsDigest':digest,'toolsRecipe':'Dockerfile.ai-latest-tools'},indent=2))
    print('PHASE_A_SESSION='+str(session))

if __name__=='__main__':main()
