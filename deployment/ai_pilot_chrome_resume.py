"""Six DOM requests only, on a cold copy of one unfinished owned UNPAID trial.
Original source/ledger/STOP/PG/chain stay unchanged. No new allocation or phase skip.
"""
import hashlib,json,re,secrets,shutil,sqlite3,subprocess,time
from pathlib import Path
from urllib.parse import urlsplit,parse_qsl
from ai_pilot_unpaid_live import ROOT,cmd,env,save,restrict_owned_folder
from ai_pilot_linux import LABEL,PG,LOCK
from ai_pilot_linux_browser_probe import profile,PROFILE_SHA
from ai_pilot_fixture_audit import validate_source
from ai_receipt_clone import cleanup_resources,fingerprint
from product_read_resources import Observer
from product_read_tools import find_jdk
import product_read_network as network

def digest(path):
    with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()

def local_database(url):
    if not isinstance(url,str) or not url.startswith('jdbc:postgresql://'):return False
    try:
        u=urlsplit(url[5:]);pairs=parse_qsl(u.query,strict_parsing=True)
        return u.hostname=='pg' and u.port==5432 and not u.username and not u.password and not u.fragment and bool(re.fullmatch(r'/exchange_ai_pilot_[a-z0-9_]+',u.path)) and len(pairs)==2 and dict(pairs)=={'connectTimeout':'2','socketTimeout':'5'}
    except ValueError:return False

def validate_web_ledger(base):
    session=base/'session';meta=json.loads((session/'session.json').read_text())
    if meta['mode']!='UNPAID_CONTROLLED_ONLY' or (session/'STOP').exists():raise ValueError('CHROME_UNPAID_UNSTOPPED_REQUIRED')
    c=sqlite3.connect((session/'pilot-v2.sqlite').resolve().as_uri()+'?mode=ro',uri=True)
    try:
        identity=json.loads(c.execute('select value from identity').fetchone()[0])
        if identity['mode']!='UNPAID' or identity['allocation']!=meta['allocation'] or identity['source']!=meta['source']:raise ValueError('CHROME_LEDGER_IDENTITY')
        if c.execute('select phase,stopped from control').fetchall()!=[(6,0)]:raise ValueError('CHROME_WEB_PHASE_REQUIRED')
        if c.execute("select count(*) from submissions where phase='WEB_PILOT'").fetchone()[0]!=0:raise ValueError('CHROME_WEB_ALREADY_ATTEMPTED')
        if c.execute("select count(*) from reservations where outcome!='OBSERVED'").fetchone()[0]:raise ValueError('CHROME_UNKNOWN_RESERVATION')
        phases=dict(c.execute('select name,complete from phases'))
        if any(phases.get(n)!=1 for n in ['INDEX_LEGACY','SAFETY_LEGACY','NOVEL30','INDEX_PILOT','SAFETY_C','LATENCY']):raise ValueError('CHROME_PREFIX_INCOMPLETE')
        return {'source':meta['source'],'reservations':c.execute('select count(*) from reservations').fetchone()[0]}
    finally:c.close()

def run(source):
    base,meta=validate_source(source)
    if meta['scope']!='FULL' or json.loads((base/'final-fingerprint.json').read_text()).get('financialUnchanged') is not True:raise ValueError('CHROME_FROZEN_SOURCE_REQUIRED')
    original_ledger=validate_web_ledger(base);seccomp=profile()
    owner='pilot-linux-'+secrets.token_hex(8);folder=ROOT/'deployment/runtime'/owner;folder.mkdir();restrict_owned_folder(folder)
    observer=Observer(folder/'resources.jsonl');observer.sample('CHROME_RESUME_ADMISSION',True)
    watched=['chain/state.json','session/session.json','session/pilot-v2.sqlite','session/binding.private.json','session/rows.private.json','source-files.json','final-fingerprint.json']
    before_hash={name:digest(base/name) for name in watched}
    names={r:owner+'-'+r for r in ['network','pgdata','pg','anvil','app']};created=[];passed=False
    image=meta['image'];save(folder/'owned.json',{'run':owner,'scope':'CHROME_SIX_RESUME_ONLY','source':source,'names':names,'image':image,'sourceIdentity':original_ledger['source'],'profileSha256':PROFILE_SHA,'paidCalls':0,'apiKeyReads':0})
    def create(role,img,options=(),command=(),net=None):
        name=owner+'-'+role;created.append((name,img));cmd(['docker','create','--pull=never','--name',name,'--label',LABEL+'='+owner,'--network',net or names['network'],*options,img,*command]);return name
    def actor(name,timeout):
        cmd(['docker','start',name]);code=cmd(['docker','wait',name],timeout)
        p=subprocess.run(['docker','logs',name],env=env(),capture_output=True,timeout=10);(folder/(name.removeprefix(owner+'-')+'.private.log')).write_bytes(p.stdout+p.stderr)
        if code!='0':raise ValueError('CHROME_OWN_ACTOR_FAILED')
    def mount(path,target,ro=True):return 'type=bind,source='+str(path.resolve())+',target='+target+(',readonly' if ro else '')
    try:
        # Immutable original runtime source; new driver is separately manifested, never hidden in /work.
        manifest=json.loads((base/'source-files.json').read_text())
        for name,expected in manifest.items():
            p=Path(name)
            if p.is_absolute() or '..' in p.parts or (base/'source'/p).is_symlink() or digest(base/'source'/p)!=expected:raise ValueError('CHROME_ORIGINAL_SOURCE_CHANGED')
        shutil.copytree(base/'source',folder/'source');shutil.copyfile(base/'source-files.json',folder/'source-files.json')
        shutil.copytree(base/'session',folder/'session')
        for name in ['trade.properties','v2.private.json','pg.private.env','browser.private.json']:shutil.copyfile(base/name,folder/name)
        shutil.copyfile(base/'final-fingerprint.json',folder/'source-final-fingerprint.json')
        props=dict(line.split('=',1) for line in (folder/'trade.properties').read_text().splitlines() if '=' in line)
        if props.get('app.ai.api-key')!='' or any(props.get(k)!='http://anvil:8545' for k in ['app.futures.rpc-url','app.blockchain.rpc-url','app.c-futures.rpc-uri']) or props.get('server.address')!='127.0.0.1':raise ValueError('CHROME_SOURCE_CONFIG')
        config=json.loads((folder/'v2.private.json').read_text())
        if config['mode']!='UNPAID_CONTROLLED_ONLY' or any(not local_database(config[k]['jdbcUrl']) for k in ['budget','legacy9','pilot10']):raise ValueError('CHROME_LOCAL_DATABASES')
        if props.get('app.blockchain.reconciliation.initial-delay-ms')!='2147483647' or props.get('app.ai.diagnosis.enabled')!='false' or props.get('app.conditional.enabled')!='false':raise ValueError('CHROME_BACKGROUND_WRITER_CONFIG')
        if validate_web_ledger(folder)!=original_ledger:raise ValueError('CHROME_LEDGER_COPY_CHANGED')
        driver=folder/'driver';driver.mkdir();classes=driver/'classes';classes.mkdir()
        files={'PilotChromeResume.java':ROOT/'deployment/PilotChromeResume.java','ai_pilot_chrome_entry.py':ROOT/'deployment/ai_pilot_chrome_entry.py',**{n:ROOT/'tools/websocket-test-client/scripts'/n for n in ['ai-linux-pilot-browser.mjs','pilot-dom-readiness.mjs','ai-linux-browser-proof.mjs','ai-linux-browser-diagnose.mjs','linux-sandbox-evidence.mjs','cdp-boundary.mjs']},**{n:base/'source/tools/websocket-test-client/scripts'/n for n in ['browser-executable.mjs','ai-pilot-v2-flow.mjs','native-sandbox-evidence.mjs']}}
        for name,p in files.items():shutil.copyfile(p,driver/name)
        cp=';'.join(str(folder/'source/backend'/p) for p in ['build/classes/java/main','build/classes/java/test','build/resources/main','build/resources/test','build/pilot-v2-test/jars/*'])
        jdk=find_jdk();p=subprocess.run([str(jdk['javac']),'-parameters','--release','21','-cp',cp,'-d',str(classes),str(driver/'PilotChromeResume.java')],env=env(),capture_output=True,timeout=60)
        (folder/'compile.private.log').write_bytes(p.stdout+p.stderr)
        if p.returncode:raise ValueError('CHROME_DRIVER_COMPILE')
        save(folder/'driver-files.json',{p.relative_to(driver).as_posix():digest(p) for p in driver.rglob('*') if p.is_file()})
        # No claim on compile failure; after creation this immutable sidecar blocks duplicate resumes.
        claim=ROOT/'deployment/runtime'/('chrome-resume-'+source+'.claim')
        with claim.open('x',encoding='utf8') as f:json.dump({'owner':owner,'source':source,'unpaidOnly':True},f)
        inventory=network.inventory(lambda a,timeout=30:subprocess.run(a,env=env(),capture_output=True,timeout=timeout));save(folder/'network.json',inventory);observer.sample('CHROME_RESUME_CREATE',True)
        cmd(['docker','network','create','--internal','--subnet',inventory['subnet'],'--label',LABEL+'='+owner,names['network']]);cmd(['docker','volume','create','--label',LABEL+'='+owner,names['pgdata']])
        vol=json.loads(cmd(['docker','volume','inspect',names['pgdata']]))[0]
        if vol['Labels'].get(LABEL)!=owner:raise ValueError('CHROME_DESTINATION_OWNER')
        actor(create('copy',PG,['--memory=256m','--entrypoint','/bin/sh','--mount','type=volume,source='+meta['names']['pgdata']+',target=/from,readonly','--mount','type=volume,source='+names['pgdata']+',target=/to'],['-c','test -z "$(find /to -mindepth 1 -print -quit)" && cp -a /from/. /to/'],net='none'),120)
        (folder/'chain').mkdir();shutil.copyfile(base/'chain/state.json',folder/'chain/state.json')
        pg=create('pg',PG,['--memory=384m','--network-alias','pg','--env-file',str(folder/'pg.private.env'),'--mount','type=volume,source='+names['pgdata']+',target=/var/lib/postgresql/data'],['postgres','-c','shared_buffers=16MB','-c','max_connections=40']);cmd(['docker','start',pg])
        end=time.monotonic()+45
        while subprocess.run(['docker','exec',pg,'psql','-X','-U','pilot','-d','postgres','-tAc','SELECT 1'],env=env(),capture_output=True,timeout=5).returncode:
            if time.monotonic()>end:raise ValueError('CHROME_PG_READY')
            time.sleep(.5)
        db='exchange_ai_pilot_trade_'+source[12:];financial_before=fingerprint(pg,db)
        anvil=create('anvil',LOCK['images']['anvil'],['--memory=384m','--network-alias','anvil','--entrypoint','anvil','--mount',mount(folder/'chain','/data',False)],['--host','0.0.0.0','--accounts','0','--chain-id','31337','--block-time','1','--state','/data/state.json','--preserve-historical-states','--quiet']);cmd(['docker','start',anvil])
        end=time.monotonic()+60
        while subprocess.run(['docker','exec',anvil,'cast','chain-id','--rpc-url','http://127.0.0.1:8545'],env=env(),capture_output=True,timeout=5).returncode:
            if time.monotonic()>end:raise ValueError('CHROME_RPC_READY')
            time.sleep(.5)
        cp='/driver/classes:'+':'.join('/work/backend/'+p for p in ['build/classes/java/main','build/classes/java/test','build/resources/main','build/resources/test','build/pilot-v2-test/jars/*','build/product-read-ci/helpers'])
        save(folder/'java-command.json',['java','-Xmx384m','-XX:MaxMetaspaceSize=256m','-Djdk.httpclient.disableRetryConnect=true','-Djdk.httpclient.enableAllMethodRetry=false','-Djdk.httpclient.redirects.retrylimit=1','--add-opens=java.net.http/jdk.internal.net.http=ALL-UNNAMED','-cp',cp,'com.pricetrack.exchange.futures.chain.PilotChromeResume'])
        app=create('app',image,['--memory=2560m','--shm-size=256m','--security-opt','seccomp='+str(seccomp),'--entrypoint','python','--mount',mount(folder,'/run/pilot',False),'--mount',mount(driver,'/driver'),'-e','AI_PILOT_BROWSER_RESUME=true','-e','AI_PILOT_V2_LINUX_INTERNAL=true','-e','AI_PILOT_V2_HTTP_TESTS=true','-e','AI_PILOT_V2_FOLDER=/run/pilot','-e','AI_PILOT_V2_APP=/run/pilot/trade.properties','-e','AI_PILOT_V2_CONFIG=/run/pilot/v2.private.json','-e','AI_EVAL_TEST_PYTHON=/usr/local/bin/python'],['-B','-S','/driver/ai_pilot_chrome_entry.py'])
        cmd(['docker','cp',str((folder/'source').resolve())+'/.',app+':/work'],120)
        config=json.loads(cmd(['docker','inspect',app]))[0]
        if config['Config']['User']!='1000:1000' or config['HostConfig']['Privileged'] or config['HostConfig'].get('CapAdd') or config['HostConfig']['PortBindings']:raise ValueError('CHROME_APP_ISOLATION')
        observer.sample('CHROME_RESUME_BEFORE_APP',True);cmd(['docker','start',app]);end=time.monotonic()+150
        while not (folder/'browser-ready.json').exists():
            if not json.loads(cmd(['docker','inspect',app]))[0]['State']['Running'] or time.monotonic()>end:raise ValueError('CHROME_APP_BOOT')
            time.sleep(.5)
        observer.sample('CHROME_RESUME_BEFORE_BROWSER',True);save(folder/'browser-admission.json',{'allowed':True})
        code=cmd(['docker','wait',app],650)
        if code!='0':raise ValueError('CHROME_SIX_FAILED')
        result=json.loads((folder/'chrome-only-result.json').read_text());financial_after=fingerprint(pg,db)
        if result.get('browserRequests')!=6 or result.get('sandboxVerified') is not True or financial_before!=financial_after:raise ValueError('CHROME_FINAL_EVIDENCE')
        save(folder/'clone-financial.json',{'before':financial_before,'after':financial_after,'unchanged':True});passed=True
    finally:
        if any(n==names['app'] for n,_ in created):
            try:
                logs=subprocess.run(['docker','logs',names['app']],env=env(),capture_output=True,timeout=10);(folder/'app.private.log').write_bytes(logs.stdout+logs.stderr)
            except Exception:pass
        cleanup=cleanup_resources(folder,created,owner,names['anvil']);observer.sample('STOPPED')
        unchanged=all(digest(base/n)==h for n,h in before_hash.items())
        save(folder/'result.json',{'status':'PASS_UNPAID_CHROME_SIX_NOT_MODEL_QUALITY' if passed and unchanged and all(v in {'STOPPED','NOT_CREATED'} for v in cleanup.values()) else 'PARTIAL','cleanup':cleanup,'originalFilesUnchanged':unchanged,'originalVolumeReadOnly':True,'originalLedgerNotReset':True,'paidCalls':0,'apiKeyReads':0})
        print(json.dumps({'run':owner,'passed':passed,'originalFilesUnchanged':unchanged,'paidCalls':0,'apiKeyReads':0}))

if __name__=='__main__':
    import argparse
    p=argparse.ArgumentParser();p.add_argument('source');run(p.parse_args().source)
