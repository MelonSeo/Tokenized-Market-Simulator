"""Owned nonroot default-sandbox probe, no service ports, network, key or existing data."""
import json,secrets,subprocess
from ai_pilot_linux import ROOT,LABEL
from ai_pilot_unpaid_live import cmd,env,save,restrict_owned_folder,stop_owned_container
from product_read_resources import Observer

def run():
    owner='pilot-linux-'+secrets.token_hex(8);folder=ROOT/'deployment/runtime'/owner
    folder.mkdir();restrict_owned_folder(folder);observer=Observer(folder/'resources.jsonl');observer.sample('SANDBOX_ADMISSION',True)
    image=cmd(['docker','image','inspect','tms-ai-pilot-linux:unpaid-v2','--format','{{.Id}}']);name=owner+'-app'
    save(folder/'owned.json',{'run':owner,'image':image,'scope':'SANDBOX_ONLY','paidCalls':0,'apiKeyReads':0})
    try:
        cmd(['docker','create','--pull=never','--name',name,'--label',LABEL+'='+owner,'--network','none','--memory=384m','--shm-size=256m','--entrypoint','timeout',image,'15s','chromium','--headless=new','--disable-gpu','--no-first-run','--no-default-browser-check','--user-data-dir=/tmp/pilot-sandbox','--dump-dom','about:blank'])
        item=json.loads(cmd(['docker','inspect',name]))[0]
        if item['Config']['User']!='1000:1000' or item['HostConfig']['Privileged'] or item['HostConfig'].get('CapAdd') or item['HostConfig'].get('SecurityOpt') or item['HostConfig']['PortBindings']:raise ValueError('SANDBOX_ISOLATION_REQUIRED')
        cmd(['docker','start',name]);exit_code=cmd(['docker','wait',name],25)
        logs=subprocess.run(['docker','logs',name],env=env(),capture_output=True,timeout=10)
        (folder/'sandbox.private.log').write_bytes(logs.stdout+logs.stderr)
        ok=exit_code=='0' and b'<html' in logs.stdout
        result={'run':owner,'status':'PASS_DEFAULT_SANDBOX' if ok else 'ENVIRONMENT_UNAVAILABLE','exitCode':exit_code,'namespaceDenied':b'Operation not permitted' in logs.stderr,'sandboxDisabled':False,'privileged':False,'network':'none','realBrowserSixRequests':'NOT_RUN','paidCalls':0,'apiKeyReads':0}
        save(folder/'sandbox-result.json',result)
    finally:
        state=stop_owned_container(name,owner,image,10);save(folder/'cleanup.json',{'container':name,'state':state});observer.sample('STOPPED')
    if state!='STOPPED':raise ValueError('SANDBOX_CLEANUP_UNCONFIRMED')
    print(json.dumps(result))

if __name__=='__main__':run()
