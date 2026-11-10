"""Networkless read-only startup audit of this turn's failed owned UNPAID trial.
Never starts its servers, opens its ledger for writes or dispatches a provider.
"""
import json,re,secrets,subprocess
from ai_pilot_linux import ROOT,LABEL,PG,LOCK
from ai_pilot_unpaid_live import env,cmd,save,restrict_owned_folder,stop_owned_container
from product_read_resources import Observer
SCRIPT=r'''
import sys,time,json,sqlite3,pathlib
sys.path.insert(0,'/work/deployment')
n=time.monotonic();from ai_pilot_channel import source_fingerprint
from ai_pilot_coordinator import canonical,digest,workload
from ai_pilot_preflight import POLICY_PATH,PLAN_PATH,preflight,ROOT
rows=[]
def measure(name,fn):
 n=time.monotonic();r=fn();rows.append({'stage':name,'ms':(time.monotonic()-n)*1000});return r
meta=json.loads(pathlib.Path('/audit/session/session.json').read_text())
assert meta['mode']=='UNPAID_CONTROLLED_ONLY'
h=measure('sourceFingerprint',source_fingerprint)
matching=h==meta['source'];measure('preflight',lambda:preflight(ROOT));measure('workload',lambda:workload(meta['fixture']))
p=pathlib.Path('/audit/session/pilot-v2.sqlite');c=sqlite3.connect(p.as_uri()+'?mode=ro',uri=True)
identity=canonical({'version':2,'allocation':meta['allocation'],'mode':'UNPAID','source':meta['source'],'fixture':digest(meta['fixture']),'budget':digest(json.loads((ROOT/POLICY_PATH).read_text())),'plan':digest(json.loads((ROOT/PLAN_PATH).read_text()))}).decode()
print(json.dumps({'sourceMatching':matching,'identityMatching':c.execute('select value from identity where id=1').fetchone()==(identity,),'control':c.execute('select phase,stopped from control').fetchall(),'reservations':c.execute('select count(*) from reservations').fetchone()[0],'STOP':pathlib.Path('/audit/session/STOP').exists(),'timings':rows,'paidCalls':0,'apiKeyReads':0}));c.close()
'''
def validate_source(source):
    if not re.fullmatch(r'pilot-linux-[a-f0-9]{16}',source):raise ValueError('AUDIT_SOURCE_SCOPE')
    base=ROOT/'deployment/runtime'/source
    for p in [base,base/'source',base/'owned.json',base/'result.json',base/'v2.private.json',base/'session',base/'session/session.json',base/'session/pilot-v2.sqlite']:
        if p.is_symlink() or getattr(p.lstat(),'st_file_attributes',0)&0x400:raise ValueError('AUDIT_SOURCE_LINK')
    meta=json.loads((base/'owned.json').read_text());result=json.loads((base/'result.json').read_text())
    if meta['run']!=source or meta['scope']!='C_LATENCY' or meta['paidCalls']!=0 or meta.get('apiKeyReads')!=0 or result['status']!='PARTIAL':raise ValueError('AUDIT_UNPAID_FAILURE_REQUIRED')
    if any(json.loads(p.read_text()).get('mode')!='UNPAID_CONTROLLED_ONLY' for p in [base/'v2.private.json',base/'session/session.json']):raise ValueError('AUDIT_UNPAID_REQUIRED')
    for role,image in [('app',meta['image']),('pg',PG),('anvil',LOCK['images']['anvil'])]:
        item=json.loads(cmd(['docker','inspect',source+'-'+role]))[0]
        if item['State']['Running'] or item['Config']['Image']!=image or item['Config']['Labels'].get(LABEL)!=source:raise ValueError('AUDIT_STOPPED_OWNED_SOURCE_REQUIRED')
    return base,meta
def run(source,local_source=False):
    base,meta=validate_source(source)
    owner='pilot-linux-'+secrets.token_hex(8);folder=ROOT/'deployment/runtime'/owner;folder.mkdir();restrict_owned_folder(folder)
    observer=Observer(folder/'resources.jsonl');observer.sample('BROKER_AUDIT',True)
    image=meta['image'];name=owner+'-app';save(folder/'owned.json',{'run':owner,'scope':'BROKER_RO_AUDIT','source':source,'paidCalls':0,'apiKeyReads':0})
    try:
        source_options=[] if local_source else ['--mount','type=bind,source='+str((base/'source').resolve())+',target=/work,readonly']
        cmd(['docker','create','--pull=never','--name',name,'--label',LABEL+'='+owner,'--network','none','--memory=256m','--entrypoint','python',*source_options,'--mount','type=bind,source='+str(base.resolve())+',target=/audit,readonly',image,'-B','-S','-c',SCRIPT])
        if local_source:cmd(['docker','cp',str((base/'source').resolve())+'/.',name+':/work'],120)
        cmd(['docker','start',name]);code=cmd(['docker','wait',name],60)
        p=subprocess.run(['docker','logs',name],env=env(),capture_output=True,timeout=10);(folder/'audit.private.log').write_bytes(p.stdout+p.stderr)
        if code!='0':raise RuntimeError('BROKER_AUDIT_FAILED')
        result=json.loads(p.stdout);result['sourceTransport']='LINUX_LOCAL_COPY' if local_source else 'READONLY_BIND';save(folder/'audit-result.json',result);print(json.dumps({'run':owner,**result}))
    finally:
        state=stop_owned_container(name,owner,image,10);save(folder/'cleanup.json',{'state':state});observer.sample('STOPPED')
if __name__=='__main__':
    import argparse
    p=argparse.ArgumentParser();p.add_argument('source');p.add_argument('--local-source',action='store_true');a=p.parse_args();run(a.source,a.local_source)
