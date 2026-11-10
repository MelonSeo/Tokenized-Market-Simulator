"""Fresh UNPAID Linux-only data path. Windows controls Docker, never HTTP/RPC or SQL sockets.

No old runtime/volume/ledger is read. All model senders remain controlled and the
runtime network is internal with no published ports. Existing Windows runner stays unchanged.
"""
import argparse,hashlib,json,os,re,secrets,shutil,subprocess,time,uuid
from pathlib import Path
from ai_pilot_unpaid_live import ROOT,env,cmd,save,restrict_owned_folder,stop_owned_container
from product_read_resources import Observer
from product_read_tools import find_jdk
import product_read_network as network
import product_read_linux_support as support

LABEL='tms.ai.pilot-v2.owner'

def fresh_anvil_command():
    # Choose genesis at process launch, not before Docker create/start round trips.
    # Only the new empty owned chain uses this; never adjusts a running chain clock.
    return ['-c','exec anvil --timestamp "$(date +%s)" "$@"','anvil',
            '--host','0.0.0.0','--accounts','0','--chain-id','31337',
            '--block-time','1','--state','/data/state.json','--preserve-historical-states','--quiet']
TOOLS='tokenized-ai-latest-tools:phase-a-20261029174809-d30067c2'
LOCK=json.loads((ROOT/'deployment/product-read-ci-lock.json').read_text())
PG=json.loads((ROOT/'deployment/ai-readiness-plan.json').read_text())['image']

def topology(run):
    if not re.fullmatch(r'pilot-linux-[a-f0-9]{16}',run):raise ValueError('LINUX_OWNER')
    return {r:run+'-'+r for r in ['network','pg','anvil','app','pgdata']}

def container_args(run,name,image,net,alias,options=(),command=()):
    if name not in {run+'-'+r for r in ['pg','anvil','app','extract','cseed','spotdeploy','futuresdeploy']}:raise ValueError('LINUX_RESOURCE_NAME')
    if any(x in options for x in ['-p','--publish','--privileged','--cap-add','--security-opt']):raise ValueError('LINUX_UNSAFE_OPTIONS')
    return ['docker','create','--pull=never','--name',name,'--label',LABEL+'='+run,'--network',net,'--network-alias',alias,*options,image,*command]

def stage_source(destination):
    """Explicit public source/build allowlist. Never recursively copy deployment/runtime or .env."""
    from ai_pilot_channel import CARRY_SOURCES
    trees=['backend/src/main','backend/src/test/java/com/pricetrack/exchange/ai/pilot','backend/src/test/java/com/pricetrack/exchange/futures/chain','backend/src/test/resources/ai','docs/ai-knowledge','contracts/src','tools/websocket-test-client/src','backend/build/classes/java/main','backend/build/classes/java/test','backend/build/resources/main','backend/build/resources/test/ai','backend/build/pilot-v2-test/jars','backend/build/product-read-ci/helpers']
    files=['backend/build.gradle','docs/ai/ingest-manifest.json','docs/ai/ingest-manifest-pilot.json','deployment/ai-readiness-plan.json','deployment/product-read-ci.gradle','deployment/pilot-linux-image/Dockerfile','backend/src/test/java/com/pricetrack/exchange/ai/evaluation/ApprovedAiModelEvaluationTest.java','backend/src/test/java/com/pricetrack/exchange/ai/LatestRetrievalBenchmark.java']
    names=['ai_pilot_channel','ai_pilot_coordinator','ai_pilot_fixture','ai_pilot_http_plan','ai_pilot_http_adapter','ai_pilot_browser_host','ai_pilot_preflight','ai_novel_question_audit','ai_evaluation_broker','ai_evaluation_guard','ai_pilot_unpaid_live','ai_pilot_bind_live','ai_pilot_paid_sender','ai_pilot_linux','ai_pilot_linux_inner','ai_receipt_clone','ai_pilot_native_bridge','ai_pilot_native_probe','product_read_resources','product_read_network','product_read_linux_support','c_gate_network','product_manual_clock']
    files+=['deployment/'+n+'.py' for n in names]+['deployment/'+n for n in ['ai-pilot-budget-v2.json','ai-pilot-execution-v2.json','PilotV2ChainFixture.java','PilotV2LinuxSmoke.java']]
    files+=['deployment/'+n+'.py' for n in ['ai_pilot_paid_transport','ai_pilot_egress','ai_pilot_paid_environment','ai_pilot_paid_schedule']]
    files+=['deployment/'+n+'.py' for n in ['ai_pilot_paid_launcher','ai_pilot_paid_inner','ai_pilot_paid_adapter']]
    files+=['deployment/'+n+'.py' for n in ['ai_pilot_chrome_resume','ai_pilot_fixture_audit','ai_pilot_linux_browser_probe']]
    files+=['tools/websocket-test-client/scripts/'+n for n in ['ai-linux-pilot-browser.mjs','ai-linux-browser-proof.mjs','linux-sandbox-evidence.mjs','pilot-dom-readiness.mjs']]
    files+=['deployment/pilot-linux-image/chromium-seccomp.json','deployment/ai-pilot-driver-bounds.json']
    files+=['deployment/'+n for n in CARRY_SOURCES]
    files+=['tools/websocket-test-client/scripts/'+n for n in ['ai-pilot-v2-flow.mjs','ai-pilot-v2-browser.mjs','cdp-boundary.mjs','browser-executable.mjs','native-sandbox-evidence.mjs','ai-native-sandbox-probe.mjs']]
    for tree in trees:
        source=ROOT/tree
        if not source.is_dir():raise RuntimeError('LINUX_SOURCE_TREE_MISSING')
        for p in source.rglob('*'):
            if p.is_symlink() or p.name=='.env':raise RuntimeError('LINUX_SOURCE_LINK_OR_ENV')
        shutil.copytree(source,destination/tree)
    for file in files:
        p=ROOT/file
        if not p.is_file() or p.is_symlink():raise RuntimeError('LINUX_SOURCE_FILE_MISSING')
        target=destination/file;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,target)
    # Test property imports are unnecessary: DynamicPropertySource supplies exact owned configuration.
    (destination/'backend/build/resources/test').mkdir(exist_ok=True)
    manifest={p.relative_to(destination).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in destination.rglob('*') if p.is_file()}
    # Bind the actual compiled runtime to the permit without rehashing every JAR
    # in the broker's short startup deadline. Inner admission verifies all bytes.
    identity={'files':{n:h for n,h in manifest.items() if n.startswith('backend/build/') and n.endswith(('.class','.jar'))}}
    artifact=destination/'deployment/paid-build-identity.json';artifact.write_text(json.dumps(identity,sort_keys=True,separators=(',',':')))
    manifest['deployment/paid-build-identity.json']=hashlib.sha256(artifact.read_bytes()).hexdigest()
    return manifest

def properties(folder,run,password,addresses,identity,keys,kind='trade'):
    def url(role):return 'jdbc:postgresql://pg:5432/exchange_ai_pilot_'+role+'_'+run[12:]+'?connectTimeout=2&socketTimeout=5'
    p={'spring.config.import':'','server.address':'127.0.0.1','server.port':'8080','spring.datasource.url':url(kind),'spring.datasource.username':'pilot','spring.datasource.password':password,'spring.datasource.driver-class-name':'org.postgresql.Driver','spring.jpa.database-platform':'org.hibernate.dialect.PostgreSQLDialect','spring.jpa.hibernate.ddl-auto':'none','spring.sql.init.mode':'always','spring.datasource.hikari.maximum-pool-size':'4','app.jwt.secret':secrets.token_urlsafe(48),'app.admin.password':'','app.price.provider':'simulated','app.price.update-interval-ms':'1000','app.price.initial-delay-ms':'1000','app.blockchain.enabled':'true','app.blockchain.rpc-url':'http://anvil:8545','app.blockchain.operator-private-key':keys['OPERATOR_PRIVATE_KEY'],'app.blockchain.price-report.enabled':'true','app.blockchain.price-report.signer-private-key':keys['PRICE_SIGNER_PRIVATE_KEY'],'app.blockchain.reconciliation.initial-delay-ms':'2147483647','app.reserve.enabled':'true','app.reserve.execution-id':run,'app.trade-audit.enabled':'true','app.futures.enabled':'true','app.futures.runtime-enabled':'true','app.futures.executor-private-key':keys['FUTURES_EXECUTOR_PRIVATE_KEY'],'app.futures.signer-private-key':keys['FUTURES_PRICE_SIGNER_PRIVATE_KEY'],'app.futures.rpc-url':'http://anvil:8545','app.futures.chain-id':'31337','app.futures.fixture-block-time-seconds':'2','app.futures.engine-address':addresses['FUTURES_ENGINE_ADDRESS'],'app.futures.code-hash':addresses['FUTURES_ENGINE_CODE_HASH'],'app.conditional.enabled':'false','app.ai.enabled':'true','app.ai.agent.enabled':'true','app.ai.tools.enabled':'true','app.ai.skills.enabled':'true','app.ai.diagnosis.enabled':'false','app.ai.pilot-v2.unpaid-tests':'true','app.ai.api-key':'','app.ai.jdbc-url':url('legacy'),'app.ai.db-user':'pilot','app.ai.db-password':password,'app.ai.knowledge-root':'/work/docs/ai-knowledge','app.ai.manifest':'/work/docs/ai/ingest-manifest.json','app.c-futures.enabled':'true','app.c-futures.direct.enabled':'false','app.c-futures.rpc-uri':'http://anvil:8545','app.c-futures.chain-id':'31337','app.c-futures.genesis-hash':identity['genesisHash'],'app.c-futures.origin':'http://127.0.0.1:8080'}
    for k,a in [('mock-krw','MOCK_KRW_ADDRESS'),('m-sec','MSEC_ADDRESS'),('price-oracle','PRICE_ORACLE_ADDRESS'),('exchange-vault','EXCHANGE_VAULT_ADDRESS')]:p['app.blockchain.'+k+'-address']=addresses[a]
    for role in ['token','controller','rules','vault']:p['app.c-futures.'+role]=identity[role]['address'];p['app.c-futures.'+role+'-code-hash']=identity[role]['codeHash']
    for field,k in [('relayer','relayer'),('oracle','oracle'),('pauseAdmin','pause-admin'),('symbolHash','symbol-hash'),('policyHash','policy-hash')]:p['app.c-futures.'+k]=identity[field]
    if kind=='smoke':
        for flag in ['app.blockchain.enabled','app.blockchain.price-report.enabled','app.reserve.enabled','app.trade-audit.enabled','app.futures.enabled','app.futures.runtime-enabled','app.ai.pilot-v2.unpaid-tests','app.ai.skills.enabled']:p[flag]='false'
        for k in list(p):
            if 'private-key' in k:del p[k]
    return p,url

def run(a2_http=False,novel30_http=False,c_latency=False,receipt_probe=False,receipt_source=None,receipt_agent=False,local_source=False,native_browser=False):
    if native_browser and (not local_source or any((a2_http,novel30_http,c_latency,receipt_probe,receipt_agent,receipt_source))):raise ValueError('NATIVE_BROWSER_REQUIRES_FULL_LOCAL_SOURCE')
    if sum((a2_http,novel30_http,c_latency,receipt_probe,receipt_agent))>1:raise ValueError('LINUX_HTTP_SCOPE_CONFLICT')
    if receipt_source is not None:
        if not receipt_probe:raise ValueError('RECEIPT_SOURCE_REQUIRES_PROBE')
        import ai_receipt_clone
        return ai_receipt_clone.run(receipt_source)
    scope='RECEIPT_AGENT' if receipt_agent else 'RECEIPT_PROBE' if receipt_probe else 'C_LATENCY' if c_latency else 'NOVEL30' if novel30_http else 'A2_PREFIX' if a2_http else 'FULL'
    run='pilot-linux-'+secrets.token_hex(8);names=topology(run);folder=ROOT/'deployment/runtime'/run;folder.mkdir();restrict_owned_folder(folder)
    observer=Observer(folder/'resources.jsonl');observer.sample('ADMISSION',True)
    image=cmd(['docker','image','inspect','tms-ai-pilot-linux:unpaid-v2','--format','{{.Id}}'])
    save(folder/'owned.json',{'run':run,'image':image,'names':names,'paidCalls':0,'apiKeyReads':0,'scope':scope,'sourceTransport':'LINUX_LOCAL_COPY' if local_source else 'READONLY_BIND','nativeBrowser':native_browser})
    created=[];stage='BUILD';passed=False
    def create(role,img,options=(),command=(),alias=None):
        name=run+'-'+role;created.append((name,img));cmd(container_args(run,name,img,names['network'],alias or role,options,command));return name
    def start_actor(name,timeout=300):
        cmd(['docker','start',name]);status=cmd(['docker','wait',name],timeout)
        r=subprocess.run(['docker','logs',name],env=env(),capture_output=True,timeout=10);(folder/(name.removeprefix(run+'-')+'.private.log')).write_bytes(r.stdout+r.stderr)
        if status!='0':raise RuntimeError('LINUX_ACTOR_FAILED_'+name.removeprefix(run+'-').upper())
    def mount(path,target,ro=True):return 'type=bind,source='+str(path.resolve())+',target='+target+(',readonly' if ro else '')
    try:
        from product_read_tools import find_jdk
        jdk=find_jdk();build_env=env();build_env['JAVA_HOME']=str(jdk['java'].parent.parent)
        args=[str(ROOT/'backend/gradlew.bat'),'-p',str(ROOT/'backend'),'-I',str(ROOT/'deployment/product-read-ci.gradle'),'pilotV2TestRuntime','productReadCiClasses','productReadCiRuntime','--offline','--no-daemon','--max-workers=1','-Dorg.gradle.jvmargs=-Xmx256m -XX:MaxMetaspaceSize=192m']
        b=subprocess.run(args,env=build_env,cwd=ROOT,capture_output=True,timeout=240);(folder/'build.private.log').write_bytes(b.stdout+b.stderr)
        if b.returncode:raise RuntimeError('LINUX_BUILD_FAILED')
        static=ROOT/'backend/build/resources/main/static';shutil.copytree(ROOT/'tools/websocket-test-client/dist',static,dirs_exist_ok=True)
        source=folder/'source';save(folder/'source-files.json',stage_source(source))
        inventory=network.inventory(lambda args,timeout=30:subprocess.run(args,env=env(),capture_output=True,timeout=timeout));save(folder/'network.json',inventory)
        observer.sample('BEFORE_CREATE',True);stage='CREATE'
        cmd(['docker','network','create','--internal','--subnet',inventory['subnet'],'--label',LABEL+'='+run,names['network']])
        cmd(['docker','volume','create','--label',LABEL+'='+run,names['pgdata']])
        password=secrets.token_urlsafe(32);secret=folder/'pg.private.env';secret.write_text('POSTGRES_USER=pilot\nPOSTGRES_PASSWORD='+password+'\nPOSTGRES_DB=postgres\n')
        pg=create('pg',PG,['--memory=384m','--env-file',str(secret),'--mount','type=volume,source='+names['pgdata']+',target=/var/lib/postgresql/data'],['postgres','-c','shared_buffers=16MB','-c','max_connections=40']);cmd(['docker','start',pg])
        end=time.monotonic()+45
        while subprocess.run(['docker','exec',pg,'psql','-X','-U','pilot','-d','postgres','-tAc','SELECT 1'],env=env(),capture_output=True,timeout=5).returncode:
            if time.monotonic()>end:raise RuntimeError('LINUX_PG_READINESS')
            time.sleep(.5)
        for role in ['smoke','trade','legacy','pilot','budget']:cmd(['docker','exec',pg,'psql','-X','-v','ON_ERROR_STOP=1','-U','pilot','-d','postgres','-c','CREATE DATABASE exchange_ai_pilot_'+role+'_'+run[12:]])
        for child in ['chain','fixture','keys','artifacts','private-evidence','secrets','release','home']:(folder/child).mkdir()
        anvil=create('anvil',LOCK['images']['anvil'],['--memory=384m','--mount',mount(folder/'chain','/data',False),'--entrypoint','/bin/sh'],fresh_anvil_command());cmd(['docker','start',anvil])
        stage='RPC_READY';end=time.monotonic()+30
        while subprocess.run(['docker','exec',anvil,'cast','chain-id','--rpc-url','http://127.0.0.1:8545'],env=env(),capture_output=True,timeout=5).returncode:
            if time.monotonic()>end:raise RuntimeError('LINUX_ANVIL_READINESS')
            time.sleep(.5)
        stage='C_FIXTURE';extract=create('extract',TOOLS)
        compiler=folder/'compiler';(compiler/'contracts').mkdir(parents=True);shutil.copytree(ROOT/'contracts/src',compiler/'contracts/src');cmd(['docker','cp',extract+':/contracts/lib',compiler/'contracts/lib']);cmd(['docker','cp',extract+':/contracts/out',compiler/'contracts/out']);save(folder/'artifact-proof.json',support.artifact_fixture(compiler,folder/'artifacts'))
        cseed=create('cseed',LOCK['images']['java'],['--memory=384m','--entrypoint','java','--mount',mount(ROOT/'backend/build/classes/java/main','/classes'),'--mount',mount(ROOT/'backend/build/product-read-ci/helpers','/helpers'),'--mount',mount(ROOT/'backend/build/product-read-ci/jars','/jars'),*sum((['--mount',mount(folder/p,t,False)] for p,t in [('artifacts','/artifacts'),('private-evidence','/private-evidence'),('fixture','/fixture'),('keys','/keys')]),[])],['-Xmx192m','-Djdk.httpclient.disableRetryConnect=true','-Djdk.httpclient.enableAllMethodRetry=false','-Djdk.httpclient.redirects.retrylimit=1','--add-opens=java.net.http/jdk.internal.net.http=ALL-UNNAMED','-cp','/classes:/helpers:/jars/*','PilotV2ChainFixture','http://127.0.0.1:8080']);start_actor(cseed)
        stage='LEGACY_FIXTURE';keys={k:'0x'+secrets.token_hex(32) for k in ['OPERATOR_PRIVATE_KEY','PRICE_SIGNER_PRIVATE_KEY','FUTURES_EXECUTOR_PRIVATE_KEY','FUTURES_PRICE_SIGNER_PRIVATE_KEY']};(folder/'secrets/application.properties').write_text('\n'.join(k+'='+v for k,v in keys.items())+'\n')
        for role,script in [('spotdeploy','/init-chain.sh'),('futuresdeploy','/init-futures-fixture.sh')]:start_actor(create(role,TOOLS,['--memory=384m','--entrypoint','/bin/sh','--mount',mount(folder/'secrets','/run/secrets'),'--mount',mount(folder/'release','/release',False)],[script]),180)
        addresses={}
        for file in ['chain.properties','futures.properties']:addresses.update(line.split('=',1) for line in (folder/'release'/file).read_text().splitlines() if '=' in line)
        identity=json.loads((folder/'fixture/c-identity.json').read_text());p,url=properties(folder,run,password,addresses,identity,keys)
        for kind in ['trade','smoke']:
            props,_=properties(folder,run,password,addresses,identity,keys,kind);(folder/(kind+'.properties')).write_text('\n'.join(k+'='+v for k,v in props.items())+'\n')
        config={'mode':'UNPAID_CONTROLLED_ONLY','repo':'/work','run':'/run/pilot/session','python':'/usr/local/bin/python','knowledgeRoot':'/work/docs/ai-knowledge','legacyManifest':'/work/docs/ai/ingest-manifest.json','pilotManifest':'/work/docs/ai/ingest-manifest-pilot.json'}
        for role,key in [('legacy9','legacy'),('pilot10','pilot'),('budget','budget')]:config[role]={'jdbcUrl':url(key),'dbUser':'pilot','dbPassword':password}
        save(folder/'v2.private.json',config)
        # Fresh genesis and interval=1 were configured inside Anvil at launch.
        # Do not reset its clock using a host timestamp delayed by Docker exec.
        import product_manual_clock as clock
        def rpc(method,params):return json.loads(cmd(['docker','exec',anvil,'cast','rpc',method,*[json.dumps(x,separators=(',',':')) for x in params],'--rpc-url','http://127.0.0.1:8545']))
        time.sleep(2);save(folder/'clock-ready.json',clock.require_ready(rpc))
        stage=scope+'_HTTP' if scope!='FULL' else 'LINUX_C_AND_FULL';observer.sample('BEFORE_LINUX_APP',True)
        source_options=[] if local_source else ['--mount',mount(source,'/work')]
        app=create('app',image,['--memory=2560m','--shm-size=256m',*source_options,'--mount',mount(folder,'/run/pilot',False),'-e','AI_PILOT_LOCAL_SOURCE='+str(local_source).lower(),'-e','AI_PILOT_NATIVE_BROWSER='+str(native_browser).lower(),'-e','AI_PILOT_V2_LINUX_INTERNAL=true','-e','AI_PILOT_V2_HTTP_TESTS=true','-e','AI_RECEIPT_PROBE='+str(receipt_probe or receipt_agent or c_latency or native_browser).lower(),'-e','AI_PILOT_V2_HTTP_SCOPE='+scope,'-e','AI_PILOT_V2_FOLDER=/run/pilot','-e','AI_PILOT_V2_APP=/run/pilot/trade.properties','-e','AI_PILOT_V2_CONFIG=/run/pilot/v2.private.json','-e','AI_EVAL_TEST_PYTHON=/usr/local/bin/python'])
        if local_source:cmd(['docker','cp',str(source.resolve())+'/.',app+':/work'],120)
        for name,img in created:
            item=json.loads(cmd(['docker','inspect',name]))[0]
            if item['HostConfig']['PortBindings'] or item['Config']['Labels'].get(LABEL)!=run:raise RuntimeError('LINUX_ISOLATION')
        if native_browser:
            from ai_pilot_native_bridge import NativeProcessHost
            with NativeProcessHost(folder,app,image,run,observer) as browser:
                start_actor(app,1750);browser.check()
        else:start_actor(app,1750)
        expected='PASS_RECEIPT_AGENT_PREFIX_NOT_ACCEPTANCE' if receipt_agent else 'MEASURED_RECEIPT_NOT_ACCEPTANCE' if receipt_probe else 'PASS_C_LATENCY_TRANSPORT_NOT_QUALITY' if c_latency else 'PASS_NOVEL30_HTTP_TRANSPORT_NOT_QUALITY' if novel30_http else 'PASS_A2_HTTP_TRANSPORT_NOT_QUALITY' if a2_http else 'PASS'
        if json.loads((folder/'linux-result.json').read_text())['status']!=expected:raise RuntimeError('LINUX_RESULT_MISSING')
        passed=True
    finally:
        cleanup={name:stop_owned_container(name,run,img,20) for name,img in reversed(created)}
        observer.sample('STOPPED');save(folder/'result.json',{'status':('PASS_RECEIPT_AGENT_PREFIX_NOT_ACCEPTANCE' if receipt_agent else 'MEASURED_RECEIPT_NOT_ACCEPTANCE' if receipt_probe else 'PASS_C_LATENCY_TRANSPORT_NOT_QUALITY' if c_latency else 'PASS_NOVEL30_HTTP_TRANSPORT_NOT_QUALITY' if novel30_http else 'PASS_A2_HTTP_TRANSPORT_NOT_QUALITY' if a2_http else 'PASS') if passed and all(x in {'STOPPED','NOT_CREATED'} for x in cleanup.values()) else 'PARTIAL','stage':stage,'cleanup':cleanup,'dataPreserved':True,'paidCalls':0,'apiKeyReads':0,'windowsPortRelay':False})
        print(json.dumps({'run':run,'stage':stage,'passed':passed,'paidCalls':0,'apiKeyReads':0}),flush=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser();group=parser.add_mutually_exclusive_group();group.add_argument('--a2-http',action='store_true');group.add_argument('--novel30-http',action='store_true');group.add_argument('--c-latency',action='store_true');group.add_argument('--receipt-probe',action='store_true');group.add_argument('--receipt-agent',action='store_true');parser.add_argument('--receipt-source');parser.add_argument('--local-source',action='store_true');parser.add_argument('--native-browser',action='store_true');args=parser.parse_args();run(args.a2_http,args.novel30_http,args.c_latency,args.receipt_probe,args.receipt_source,args.receipt_agent,args.local_source,args.native_browser)
