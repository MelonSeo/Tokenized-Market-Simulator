"""Cold copies of an owned UNPAID failure only; original volume is mounted read-only.
No old SQLite/STOP is copied, opened, reset or dispatched. No signing/fixture transaction.
"""
import hashlib,json,re,secrets,shutil,subprocess,time
from ai_pilot_linux import ROOT,LABEL,PG,LOCK,env,cmd,save,restrict_owned_folder,stop_owned_container,stage_source
from product_read_resources import Observer
import product_read_network as network

def cleanup_resources(folder,created,owner,anvil):
    if any(name==anvil for name,img in created):
        try:
            p=subprocess.run(['docker','logs',anvil],env=env(),capture_output=True,timeout=10)
            (folder/'anvil.private.log').write_bytes(p.stdout+p.stderr)
        except Exception:
            try:save(folder/'anvil-log-status.json',{'status':'UNAVAILABLE_CLEANUP_CONTINUES'})
            except Exception:pass
    result={}
    for name,img in reversed(created):
        try:result[name]=stop_owned_container(name,owner,img,20)
        except Exception:result[name]='UNCONFIRMED'
    return result

def fingerprint(pg,db):
    if not re.fullmatch(r'exchange_ai_pilot_trade_[a-f0-9]{16}',db):raise ValueError('RECEIPT_DB_SCOPE')
    tables=['orders','trades','price_quotes','blockchain_transactions','user_balances','futures_positions','futures_operations','futures_journal','futures_transactions']
    expr=','.join("'"+t+"',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text)::text,'[]')) from "+t+' t)' for t in tables)
    return json.loads(cmd(['docker','exec',pg,'psql','-X','-qAt','-v','ON_ERROR_STOP=1','-U','pilot','-d',db,'-c','BEGIN READ ONLY; SET LOCAL statement_timeout=5000; SELECT json_build_object('+expr+'); COMMIT;']))

def run(source):
    if not re.fullmatch(r'pilot-linux-[a-f0-9]{16}',source):raise ValueError('RECEIPT_SOURCE_SCOPE')
    base=ROOT/'deployment/runtime'/source
    if base.is_symlink() or getattr(base.lstat(),'st_file_attributes',0)&0x400:raise ValueError('RECEIPT_SOURCE_LINK')
    meta=json.loads((base/'owned.json').read_text());result=json.loads((base/'result.json').read_text())
    if meta['run']!=source or meta['scope']!='C_LATENCY' or result['status']!='PARTIAL' or meta['paidCalls']!=0 or meta.get('apiKeyReads')!=0:raise ValueError('RECEIPT_UNPAID_FAILURE_REQUIRED')
    if json.loads((base/'v2.private.json').read_text()).get('mode')!='UNPAID_CONTROLLED_ONLY':raise ValueError('RECEIPT_UNPAID_CONFIG_REQUIRED')
    for role,image in [('pg',PG),('anvil',LOCK['images']['anvil'])]:
        item=json.loads(cmd(['docker','inspect',source+'-'+role]))[0]
        if item['Config']['Labels'].get(LABEL)!=source or item['Config']['Image']!=image or item['State']['Running']:raise ValueError('RECEIPT_STOPPED_SOURCE_REQUIRED')
        if role=='pg':
            mounts=[m for m in item['Mounts'] if m['Destination']=='/var/lib/postgresql/data']
            if len(mounts)!=1 or mounts[0]['Name']!=meta['names']['pgdata']:raise ValueError('RECEIPT_SOURCE_VOLUME')
    state=base/'chain/state.json'
    if state.is_symlink() or not 0<state.stat().st_size<=512*1024**2:raise ValueError('RECEIPT_STATE_SCOPE')
    def state_hash():
        with state.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
    original=state_hash();owner='pilot-linux-'+secrets.token_hex(8);folder=ROOT/'deployment/runtime'/owner;folder.mkdir();restrict_owned_folder(folder)
    observer=Observer(folder/'resources.jsonl');observer.sample('CLONE_ADMISSION',True)
    names={r:owner+'-'+r for r in ['network','pgdata','pg','anvil','app','copy']};image=cmd(['docker','image','inspect','tms-ai-pilot-linux:unpaid-v2','--format','{{.Id}}']);created=[];stage='BUILD';done=False
    save(folder/'owned.json',{'run':owner,'scope':'RECEIPT_CLONE','sourceRun':source,'names':names,'image':image,'paidCalls':0,'apiKeyReads':0})
    def mount(path,target,ro=True):return 'type=bind,source='+str(path.resolve())+',target='+target+(',readonly' if ro else '')
    def create(role,img,opts=(),args=(),net=None):
        name=names[role];created.append((name,img));return cmd(['docker','create','--pull=never','--name',name,'--label',LABEL+'='+owner,'--network',net or names['network'],*opts,img,*args])
    def actor(role,limit):
        cmd(['docker','start',names[role]]);code=cmd(['docker','wait',names[role]],limit);p=subprocess.run(['docker','logs',names[role]],env=env(),capture_output=True,timeout=10);(folder/(role+'.private.log')).write_bytes(p.stdout+p.stderr)
        if code!='0':raise ValueError('RECEIPT_CLONE_'+role.upper()+'_FAILED')
    try:
        from product_read_tools import find_jdk
        b=env();b['JAVA_HOME']=str(find_jdk()['java'].parent.parent)
        p=subprocess.run([str(ROOT/'backend/gradlew.bat'),'-p',str(ROOT/'backend'),'-I',str(ROOT/'deployment/product-read-ci.gradle'),'pilotV2TestRuntime','productReadCiClasses','productReadCiRuntime','--offline','--no-daemon','--max-workers=1','-Dorg.gradle.jvmargs=-Xmx256m -XX:MaxMetaspaceSize=192m'],env=b,capture_output=True,timeout=240)
        (folder/'build.private.log').write_bytes(p.stdout+p.stderr)
        if p.returncode:raise ValueError('RECEIPT_CLONE_BUILD_FAILED')
        save(folder/'source-files.json',stage_source(folder/'source'))
        inventory=network.inventory(lambda a,timeout=30:subprocess.run(a,env=env(),capture_output=True,timeout=timeout));save(folder/'network.json',inventory);observer.sample('CLONE_CREATE',True);stage='COPY'
        cmd(['docker','network','create','--internal','--subnet',inventory['subnet'],'--label',LABEL+'='+owner,names['network']]);cmd(['docker','volume','create','--label',LABEL+'='+owner,names['pgdata']])
        if json.loads(cmd(['docker','volume','inspect',names['pgdata']]))[0]['Labels'].get(LABEL)!=owner:raise ValueError('RECEIPT_DESTINATION_OWNER')
        create('copy',PG,['--memory=256m','--entrypoint','/bin/sh','--mount','type=volume,source='+meta['names']['pgdata']+',target=/from,readonly','--mount','type=volume,source='+names['pgdata']+',target=/to'],['-c','test -z "$(find /to -mindepth 1 -print -quit)" && cp -a /from/. /to/'],net='none');actor('copy',120)
        (folder/'chain').mkdir();shutil.copyfile(state,folder/'chain/state.json');shutil.copyfile(base/'pg.private.env',folder/'pg.private.env')
        create('pg',PG,['--memory=384m','--network-alias','pg','--env-file',str(folder/'pg.private.env'),'--mount','type=volume,source='+names['pgdata']+',target=/var/lib/postgresql/data'],['postgres','-c','shared_buffers=16MB','-c','max_connections=40']);cmd(['docker','start',names['pg']]);time.sleep(2)
        db='exchange_ai_pilot_trade_'+source[12:];before=fingerprint(names['pg'],db);save(folder/'before-financial.json',before)
        create('anvil',LOCK['images']['anvil'],['--memory=384m','--network-alias','anvil','--entrypoint','anvil','--mount',mount(folder/'chain','/data',False)],['--host','0.0.0.0','--accounts','0','--chain-id','31337','--block-time','1','--state','/data/state.json','--preserve-historical-states','--quiet']);cmd(['docker','start',names['anvil']]);stage='RPC_READY'
        end=time.monotonic()+90
        while subprocess.run(['docker','exec',names['anvil'],'cast','chain-id','--rpc-url','http://127.0.0.1:8545'],env=env(),capture_output=True,timeout=5).returncode:
            status=json.loads(cmd(['docker','inspect',names['anvil']]))[0]['State']
            if not status['Running']:raise ValueError('RECEIPT_CLONE_ANVIL_OOM' if status['OOMKilled'] else 'RECEIPT_CLONE_ANVIL_EXITED')
            if time.monotonic()>end:raise ValueError('RECEIPT_CLONE_RPC_NOT_READY')
            time.sleep(1)
        props=dict(line.split('=',1) for line in (base/'trade.properties').read_text().splitlines() if '=' in line)
        if props.get('app.ai.api-key')!='' or props.get('app.blockchain.rpc-url')!='http://anvil:8545':raise ValueError('RECEIPT_CLONE_CONFIG')
        for flag in ['app.futures.enabled','app.futures.runtime-enabled','app.conditional.enabled','app.reserve.enabled','app.trade-audit.enabled','app.ai.diagnosis.enabled']:props[flag]='false'
        props['app.blockchain.reconciliation.initial-delay-ms']='2147483647'
        (folder/'trade.properties').write_text('\n'.join(k+'='+v for k,v in props.items())+'\n')
        shutil.copyfile(base/'v2.private.json',folder/'v2.private.json');desc=json.loads((base/'bind-descriptor.private.json').read_text());save(folder/'probe-fixture.private.json',{'account':desc['accounts']['owner'],'orderId':desc['recipe']['actors']['owner']['orderId']})
        observer.sample('CLONE_BEFORE_APP',True);stage='RECEIPT_CLONE'
        create('app',image,['--memory=2560m','--shm-size=128m','--mount',mount(folder/'source','/work'),'--mount',mount(folder,'/run/pilot',False),'-e','AI_PILOT_V2_LINUX_INTERNAL=true','-e','AI_RECEIPT_PROBE=true','-e','AI_RECEIPT_CLONE=true','-e','AI_PILOT_V2_HTTP_SCOPE=RECEIPT_PROBE','-e','AI_PILOT_V2_FOLDER=/run/pilot','-e','AI_PILOT_V2_APP=/run/pilot/trade.properties','-e','AI_PILOT_V2_CONFIG=/run/pilot/v2.private.json','-e','AI_EVAL_TEST_PYTHON=/usr/local/bin/python']);actor('app',600)
        after=fingerprint(names['pg'],db);save(folder/'clone-financial.json',{'before':before,'after':after,'unchanged':before==after});
        if before!=after:raise ValueError('RECEIPT_CLONE_FINANCIAL_CHANGED')
        done=True
    finally:
        cleanup=cleanup_resources(folder,created,owner,names['anvil']);observer.sample('STOPPED');unchanged=state_hash()==original
        save(folder/'result.json',{'status':'MEASURED_CLONE_NOT_ACCEPTANCE' if done and unchanged and all(v in {'STOPPED','NOT_CREATED'} for v in cleanup.values()) else 'PARTIAL','stage':stage,'cleanup':cleanup,'sourceStateUnchanged':unchanged,'originalVolumeReadOnly':True,'paidCalls':0,'apiKeyReads':0})
        print(json.dumps({'run':owner,'source':source,'measured':done,'sourceStateUnchanged':unchanged,'paidCalls':0,'apiKeyReads':0}))
