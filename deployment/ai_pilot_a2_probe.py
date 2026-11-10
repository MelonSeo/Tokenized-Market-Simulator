"""Fresh Linux PG quota-only probe. No Anvil, browser, keys, paid activation or old ledger mutation."""
import hashlib,json,secrets,subprocess,time
from pathlib import Path
from ai_pilot_unpaid_live import ROOT,env,cmd,save,restrict_owned_folder
from product_read_resources import Observer
import product_read_network as network

LABEL='tms.ai.a2-probe.owner'

def run():
    owner='a2-probe-'+secrets.token_hex(8);folder=ROOT/'deployment/runtime'/owner
    folder.mkdir();restrict_owned_folder(folder);observer=Observer(folder/'resources.jsonl');observer.sample('ADMISSION',True)
    pg_image=json.loads((ROOT/'deployment/ai-readiness-plan.json').read_text())['image']
    java_image=json.loads((ROOT/'deployment/product-read-ci-lock.json').read_text())['images']['java']
    names={role:owner+'-'+role for role in ['pg','java','network','pgdata']};created=[];stage='PREPARE';passed=False
    def mount(path,target,ro=True):return 'type=bind,source='+str(path.resolve())+',target='+target+(',readonly' if ro else '')
    def create(role,image,options,command):
        name=names[role];created.append((name,image))
        cmd(['docker','create','--pull=never','--name',name,'--label',LABEL+'='+owner,'--network',names['network'],'--network-alias',role,*options,image,*command]);return name
    def stop(name,image):
        # The common stopper uses the v2 label; this separate owner must never be confused with old pilot resources.
        try:
            a=json.loads(cmd(['docker','inspect',name]))[0]
            if a['Name']!='/'+name or a['Config']['Labels'].get(LABEL)!=owner or a['Config']['Image']!=image:return 'UNCONFIRMED'
            if a['State']['Running']:cmd(['docker','stop','--time','15',name])
            return 'STOPPED' if not json.loads(cmd(['docker','inspect',name]))[0]['State']['Running'] else 'UNCONFIRMED'
        except Exception:return 'UNCONFIRMED'
    try:
        for p in ['backend/build/classes/java/main','backend/build/classes/java/test','backend/build/resources/main','backend/build/pilot-v2-test/jars']:
            if not (ROOT/p).is_dir():raise RuntimeError('A2_BUILD_REQUIRED')
        sources=['deployment/ai_pilot_a2_probe.py','backend/src/test/java/com/pricetrack/exchange/ai/pilot/PilotV2A2Probe.java','backend/src/test/java/com/pricetrack/exchange/ai/pilot/PilotV2Pacing.java','backend/src/test/java/com/pricetrack/exchange/futures/chain/PilotV2LiveHttpTest.java','backend/src/main/java/com/pricetrack/exchange/ai/provider/PgProviderBudget.java']
        classes=['backend/build/classes/java/test/com/pricetrack/exchange/ai/pilot/'+n+'.class' for n in ['PilotV2A2Probe','PilotV2Pacing']]
        save(folder/'source-identity.json',{p:hashlib.sha256((ROOT/p).read_bytes()).hexdigest() for p in sources+classes})
        inventory=network.inventory(lambda args,timeout=30:subprocess.run(args,env=env(),capture_output=True,timeout=timeout));save(folder/'network.json',inventory)
        observer.sample('BEFORE_CREATE',True);stage='CREATE'
        cmd(['docker','network','create','--internal','--subnet',inventory['subnet'],'--label',LABEL+'='+owner,names['network']])
        cmd(['docker','volume','create','--label',LABEL+'='+owner,names['pgdata']])
        password=secrets.token_urlsafe(32);database='exchange_ai_a2_'+owner[9:]
        (folder/'pg.private.env').write_text('POSTGRES_USER=probe\nPOSTGRES_PASSWORD='+password+'\nPOSTGRES_DB='+database+'\n')
        (folder/'probe.private.properties').write_text('jdbc=jdbc:postgresql://pg:5432/'+database+'?connectTimeout=2&socketTimeout=5\nuser=probe\npassword='+password+'\n')
        pg=create('pg',pg_image,['--memory=384m','--env-file',str(folder/'pg.private.env'),'--mount','type=volume,source='+names['pgdata']+',target=/var/lib/postgresql/data'],['postgres','-c','shared_buffers=16MB','-c','max_connections=16'])
        cmd(['docker','start',pg]);end=time.monotonic()+45
        while subprocess.run(['docker','exec',pg,'psql','-X','-U','probe','-d',database,'-tAc','SELECT 1'],env=env(),capture_output=True,timeout=5).returncode:
            if time.monotonic()>end:raise RuntimeError('A2_PG_NOT_READY')
            time.sleep(.5)
        observer.sample('BEFORE_JAVA',True);stage='A2_QUOTA_COMPONENT'
        options=['--memory=512m','--user','1000:1000','--entrypoint','java','--mount',mount(folder,'/run/probe',False),'-e','AI_A2_UNPAID_PROBE=true']
        for p,target in [('classes/java/main','/classes'),('classes/java/test','/testclasses'),('resources/main','/resources'),('pilot-v2-test/jars','/jars')]:options+=['--mount',mount(ROOT/'backend/build'/p,target)]
        java=create('java',java_image,options,['-Xmx192m','-XX:MaxMetaspaceSize=128m','-cp','/classes:/testclasses:/resources:/jars/*','com.pricetrack.exchange.ai.pilot.PilotV2A2Probe'])
        save(folder/'owned.json',{'owner':owner,'names':names,'images':{'pg':pg_image,'java':java_image},'paidCalls':0,'apiKeyReads':0})
        cmd(['docker','start',java]);code=cmd(['docker','wait',java],180)
        logs=subprocess.run(['docker','logs',java],env=env(),capture_output=True,timeout=10);(folder/'probe.private.log').write_bytes(logs.stdout+logs.stderr)
        if code!='0':raise RuntimeError('A2_QUOTA_PROBE_FAILED')
        result=json.loads((folder/'probe-result.json').read_text());passed=result['status']=='PASS_PG_QUOTA_COMPONENT'
        if not passed:raise RuntimeError('A2_RESULT_REQUIRED')
    finally:
        cleanup={name:stop(name,image) for name,image in reversed(created)};observer.sample('STOPPED')
        save(folder/'result.json',{'status':'PASS_PG_QUOTA_COMPONENT' if passed and all(v=='STOPPED' for v in cleanup.values()) else 'PARTIAL','stage':stage,'cleanup':cleanup,'paidCalls':0,'apiKeyReads':0,'volumePreserved':True,'oldDataAccessed':False,'agentHttp':'NOT_RUN'})
        print(json.dumps({'owner':owner,'passed':passed,'stage':stage,'paidCalls':0,'apiKeyReads':0}),flush=True)

if __name__=='__main__':run()
