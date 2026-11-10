"""New owned Linux PG-namespace/deny-relay diagnostic ONLY. No WAN network or key mounts.

Does not run Spring, fixtures, LLM or prior accepted scenarios. Retains private
evidence and stopped owned containers/volume; no deletion or global reconfiguration.
"""
import json
import secrets
import subprocess
import time
from pathlib import Path

from ai_pilot_unpaid_live import ROOT, cmd, env, save, restrict_owned_folder, stop_owned_container
from ai_pilot_paid_environment import LABEL
from ai_pilot_linux import PG
from product_read_resources import Observer
import product_read_network as network

PROBE = '''import json,socket,struct
s=socket.create_connection(("127.0.0.1",5432),2);s.sendall(struct.pack("!II",8,80877103));p=s.recv(1);s.close()
assert p in (b"S",b"N")
s=socket.create_connection(("pilot-egress",8443),2);s.sendall(b"CONNECT api.openai.com:443 HTTP/1.1\\r\\nHost: api.openai.com:443\\r\\n\\r\\n");r=s.recv(4096);s.close()
assert r.startswith(b"HTTP/1.1 403 ")
print(json.dumps({"localhostPgProtocol":True,"providerTunnelDenied":True,"externalDialCapability":False,"apiRequests":0,"keyReads":0}))
'''


def run():
    owner = 'pilot-paid-'+secrets.token_hex(8)
    folder = ROOT/'deployment/runtime'/owner
    folder.mkdir(); restrict_owned_folder(folder)
    observer = Observer(folder/'resources.jsonl'); observer.sample('PAID_TOPOLOGY_ADMISSION', True)
    tools = cmd(['docker','image','inspect','tms-ai-pilot-linux:unpaid-v2','--format','{{.Id}}'])
    pg_image = cmd(['docker','image','inspect',PG,'--format','{{.Id}}'])
    names = {r:owner+'-'+r for r in ('internal','pg','pgdata','egress','app')}
    created=[]; passed=False; stage='CREATE'
    def create(role, image, net, options, command):
        name=names[role]; created.append((name,image))
        cmd(['docker','create','--pull=never','--name',name,'--label',LABEL+'='+owner,'--network',net,*options,image,*command])
        return name
    def owned_stop(name,image):
        # Existing cleanup helper uses another label: validate this milestone's identity explicitly.
        item=json.loads(cmd(['docker','inspect',name]))[0]
        if item['Config']['Labels'].get(LABEL)!=owner or item['Image']!=image:
            return 'OWNERSHIP_UNVERIFIED'
        if item['State']['Running']:
            cmd(['docker','stop','--time','10',name],25)
        again=json.loads(cmd(['docker','inspect',name]))[0]
        return 'STOPPED' if not again['State']['Running'] else 'STILL_RUNNING'
    try:
        observer.sample('PAID_TOPOLOGY_BEFORE_CREATE',True)
        inventory=network.inventory(lambda a,timeout=30:subprocess.run(a,env=env(),capture_output=True,timeout=timeout))
        save(folder/'network.json',inventory)
        stage='CREATE_INTERNAL_NETWORK'
        cmd(['docker','network','create','--internal','--subnet',inventory['subnet'],'--label',LABEL+'='+owner,names['internal']])
        stage='CREATE_PG_VOLUME'
        cmd(['docker','volume','create','--label',LABEL+'='+owner,names['pgdata']])
        (folder/'pg.private.env').write_text('POSTGRES_USER=pilot\nPOSTGRES_PASSWORD='+secrets.token_urlsafe(32)+'\nPOSTGRES_DB=postgres\n')
        pg=create('pg',pg_image,names['internal'],['--memory=384m','--network-alias','pg','--env-file',str(folder/'pg.private.env'),
                '--mount','type=volume,source='+names['pgdata']+',target=/var/lib/postgresql/data'],['postgres','-c','shared_buffers=16MB'])
        cmd(['docker','start',pg]); end=time.monotonic()+40
        while subprocess.run(['docker','exec',pg,'psql','-X','-U','pilot','-d','postgres','-tAc','SELECT 1'],env=env(),capture_output=True,timeout=5).returncode:
            if time.monotonic()>end: raise ValueError('PAID_PROBE_PG_NOT_READY')
            time.sleep(.25)
        (folder/'relay').mkdir()
        # Copy exactly one public script, not private runtime or old evidence.
        (folder/'relay/ai_pilot_egress.py').write_bytes((ROOT/'deployment/ai_pilot_egress.py').read_bytes())
        relay=create('egress',tools,names['internal'],['--memory=64m','--user','1000:1000','--read-only','--cap-drop=ALL',
            '--security-opt=no-new-privileges:true','--pids-limit=32','--network-alias','pilot-egress','--mount',
            'type=bind,source='+str((folder/'relay').resolve())+',target=/relay,readonly','--entrypoint','python'],
            ['-B','-S','/relay/ai_pilot_egress.py']) # Default deny, no approved-egress and no outbound network.
        cmd(['docker','start',relay]); stage='LINUX_NAMESPACE_PROBE'
        app=create('app',tools,'container:'+pg,['--memory=2560m','--user','1000:1000','--entrypoint','python'],['-B','-S','-c',PROBE])
        # Relay readiness is internal-only and does not dial the target.
        ready="import socket;s=socket.create_connection(('127.0.0.1',8443),1);s.close()"
        end=time.monotonic()+15
        while subprocess.run(['docker','exec',relay,'python','-B','-S','-c',ready],env=env(),capture_output=True,timeout=3).returncode:
            if time.monotonic()>end:raise ValueError('PAID_PROBE_RELAY_NOT_READY')
            time.sleep(.2)
        cmd(['docker','start',app]); code=cmd(['docker','wait',app],20)
        # Whitelist probe output, never forward Docker exception text or env/config.
        output=cmd(['docker','logs',app])
        if len(output)>2048 or code!='0':raise ValueError('PAID_PROBE_FAILED')
        proof=json.loads(output)
        if proof!={'localhostPgProtocol':True,'providerTunnelDenied':True,'externalDialCapability':False,'apiRequests':0,'keyReads':0}:
            raise ValueError('PAID_PROBE_EVIDENCE')
        for name,image in created:
            item=json.loads(cmd(['docker','inspect',name]))[0]
            if item['Image']!=image or item['Config']['Labels'].get(LABEL)!=owner or item['HostConfig']['PortBindings']:
                raise ValueError('PAID_PROBE_ISOLATION')
        if json.loads(cmd(['docker','network','inspect',names['internal']]))[0]['Internal'] is not True:
            raise ValueError('PAID_PROBE_NETWORK')
        save(folder/'probe.json',proof); passed=True
    except Exception as error:
        # No arbitrary stderr, exception message, path, properties or request body.
        code=str(error)
        safe=code if __import__('re').fullmatch(r'PAID_[A-Z0-9_]+',code) else 'DOCKER_OR_PROBE_FAILURE'
        save(folder/'failure.json',{'stage':stage,'code':safe,'errorType':type(error).__name__,'apiRequests':0,'keyReads':0})
    finally:
        cleanup={}
        for name,image in reversed(created):
            try:cleanup[name]=owned_stop(name,image)
            except Exception:cleanup[name]='STOP_UNVERIFIED'
        observer.sample('PAID_TOPOLOGY_STOPPED')
        result={'owner':owner,'stage':stage,'status':'PASS' if passed and all(x=='STOPPED' for x in cleanup.values()) else 'PARTIAL',
                'cleanup':cleanup,'apiRequests':0,'keyReads':0,'realProviderConnectivity':'NOT_RUN','paidActivation':False}
        save(folder/'result.json',result)
        print(json.dumps(result))
    return result


if __name__=='__main__':run()
