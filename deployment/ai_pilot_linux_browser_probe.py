"""Owned network-none Chromium sandbox capability probe; never runs an Agent/provider."""
import hashlib,json,secrets,subprocess
from pathlib import Path
from ai_pilot_unpaid_live import ROOT,cmd,env,save,restrict_owned_folder,stop_owned_container
from ai_pilot_linux import LABEL
from product_read_resources import Observer

PROFILE=ROOT/'deployment/pilot-linux-image/chromium-seccomp.json'
PROFILE_SHA='d4906af343fd3bdf044a774ff6969bc506fcd141dab5acc8d02d8323e9991c44'

def profile():
    if PROFILE.is_symlink() or hashlib.sha256(PROFILE.read_bytes()).hexdigest()!=PROFILE_SHA:raise ValueError('CHROME_SECCOMP_IDENTITY')
    p=json.loads(PROFILE.read_text())
    if p.get('defaultAction')!='SCMP_ACT_ERRNO' or p['syscalls'][0]['names']!=['clone','setns','unshare']:raise ValueError('CHROME_SECCOMP_SCOPE')
    return PROFILE

def run():
    seccomp=profile();owner='pilot-linux-'+secrets.token_hex(8);folder=ROOT/'deployment/runtime'/owner;folder.mkdir();restrict_owned_folder(folder)
    observer=Observer(folder/'resources.jsonl');observer.sample('LINUX_SANDBOX_ADMISSION',True)
    image=cmd(['docker','image','inspect','tms-ai-pilot-linux:unpaid-v2','--format','{{.Id}}']);name=owner+'-app'
    save(folder/'owned.json',{'run':owner,'scope':'LINUX_SANDBOX_CAPABILITY','image':image,'profileSha256':PROFILE_SHA,'paidCalls':0,'apiKeyReads':0})
    result=None
    try:
        scripts=ROOT/'tools/websocket-test-client/scripts'
        cmd(['docker','create','--pull=never','--name',name,'--label',LABEL+'='+owner,'--network','none','--memory=512m','--shm-size=256m','--security-opt','seccomp='+str(seccomp),'--mount','type=bind,source='+str(scripts.resolve())+',target=/probe,readonly','--mount','type=bind,source='+str(folder.resolve())+',target=/run/probe','--entrypoint','timeout',image,'40s','node','/probe/ai-linux-sandbox-probe.mjs','/run/probe'])
        item=json.loads(cmd(['docker','inspect',name]))[0];h=item['HostConfig']
        if item['Config']['User']!='1000:1000' or h['Privileged'] or h.get('CapAdd') or h['PortBindings'] or h['NetworkMode']!='none' or len(h.get('SecurityOpt',[]))!=1 or not h['SecurityOpt'][0].startswith('seccomp='):raise ValueError('CHROME_PROBE_ISOLATION')
        cmd(['docker','start',name]);code=cmd(['docker','wait',name],45)
        logs=subprocess.run(['docker','logs',name],env=env(),capture_output=True,timeout=10)
        (folder/'sandbox.private.log').write_bytes(logs.stdout+logs.stderr)
        evidence=folder/'linux-sandbox.private.json';proof=json.loads(evidence.read_text()) if evidence.exists() else {};text=proof.get('body','')
        import re
        checks={label:bool(re.search(re.escape(label)+r'\s+Yes\b',text,re.I)) for label in ['Namespace sandbox','PID namespaces','Network namespaces','Seccomp-BPF sandbox']}
        checks['Namespace sandbox']=checks['Namespace sandbox'] or bool(re.search(r'Layer 1 Sandbox\s+Namespace\b',text,re.I))
        passed=code=='0' and proof.get('verified') is True and all(checks.values())
        result={'run':owner,'status':'PASS_SANDBOX_CAPABILITY_NOT_SIX_REQUESTS' if passed else 'ENVIRONMENT_UNAVAILABLE','exitCode':code,'checks':checks,'namespaceDenied':b'Operation not permitted' in logs.stderr,'sandboxDisabled':False,'capabilitiesAdded':False,'profileSha256':PROFILE_SHA,'chromeSix':'NOT_RUN','paidCalls':0,'apiKeyReads':0}
        save(folder/'sandbox-result.json',result)
    finally:
        state=stop_owned_container(name,owner,image,10);save(folder/'cleanup.json',{'state':state});observer.sample('STOPPED')
    if state!='STOPPED':raise ValueError('CHROME_PROBE_CLEANUP')
    print(json.dumps(result))

if __name__=='__main__':run()
