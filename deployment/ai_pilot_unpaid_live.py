"""Owned UNPAID v2 integration runner. Never loads .env, prior ledgers, keys or paid suppliers."""
import argparse, json, os, re, secrets, shutil, socket, subprocess, time, uuid
from pathlib import Path
from product_read_resources import Observer

ROOT=Path(__file__).resolve().parents[1]
IMAGE=json.loads((ROOT/'deployment/ai-readiness-plan.json').read_text())['image']
LABEL='tms.ai.pilot-v2.owner'

def env():
    keep={'PATH','SYSTEMROOT','WINDIR','COMSPEC','USERPROFILE','HOME','APPDATA','LOCALAPPDATA','TEMP','TMP','JAVA_HOME','GRADLE_USER_HOME','DOCKER_CONFIG','NUMBER_OF_PROCESSORS','PROGRAMFILES','PROGRAMFILES(X86)','CHROME_PATH','TMS_CHROME_EXPECTED_MAJOR'}
    # Enumerate names only; never materialize excluded provider-key values.
    e={k:os.environ[k] for k in os.environ if k.upper() in keep}
    e.update({'JAVA_TOOL_OPTIONS':'-Djava.net.preferIPv4Stack=true -Djava.net.preferIPv6Addresses=false','AI_ENABLED':'false'})
    return e

def cmd(args,timeout=30):
    p=subprocess.run(list(map(str,args)),cwd=ROOT,env=env(),capture_output=True,timeout=timeout)
    if p.returncode:
        safe=re.sub(r'(?i)(password|secret|private.key)\s*[=:]\s*\S+',r'\1=<redacted>',p.stderr.decode('utf8',errors='replace'))
        safe=re.sub(r'[A-Za-z0-9_\-]{32,}','<opaque-redacted>',safe).replace(str(ROOT),'<worktree>')
        raise RuntimeError('V2_OWN_COMMAND_FAILED_'+str(p.returncode)+': '+safe[:700])
    return p.stdout.decode('utf8',errors='replace').strip()

def save(path,value):
    with path.open('x',encoding='utf8') as f:json.dump(value,f,ensure_ascii=False,indent=2)

def restrict_owned_folder(folder):
    if os.name=='nt':
        user=cmd(['whoami'])
        cmd(['icacls',folder,'/inheritance:r','/grant:r',user+':(OI)(CI)F','SYSTEM:(OI)(CI)F'])
    else:folder.chmod(0o700)

def owned(name,run):
    item=json.loads(cmd(['docker','inspect',name]))[0]
    if item['Name']!='/'+name or item['Config']['Labels'].get(LABEL)!=run or item['Config']['Image']!=IMAGE:raise RuntimeError('V2_OWNERSHIP_MISMATCH')
    return item


def stop_owned_container(name,run,image,seconds=10):
    """Create may have committed even when its CLI timed out. Always inspect exact ownership."""
    try:
        names=cmd(['docker','container','ls','-a','--filter','name=^/'+name+'$','--format','{{.Names}}']).splitlines()
        if not names:return 'NOT_CREATED'
        if names!=[name]:return 'UNCONFIRMED'
        def inspect():
            info=json.loads(cmd(['docker','inspect',name]))[0]
            if info['Name']!='/'+name or info['Config']['Labels'].get(LABEL)!=run or info['Config']['Image']!=image:raise RuntimeError('V2_OWNERSHIP_MISMATCH')
            return info
        info=inspect()
        if info['State']['Running']:cmd(['docker','stop','--time',str(seconds),name])
        return 'STOPPED' if not inspect()['State']['Running'] else 'UNCONFIRMED'
    except Exception:return 'UNCONFIRMED'


def stop_owned_pg(name,run):return stop_owned_container(name,run,IMAGE)


def stop_child(process):
    try:
        if process is None or process.poll() is not None:return 'STOPPED'
        subprocess.run(['taskkill','/PID',str(process.pid),'/T','/F'],capture_output=True,timeout=20,check=True)
        process.wait(timeout=5)
        return 'STOPPED'
    except Exception:return 'UNCONFIRMED'

def run():
    run='pilot-v2-'+secrets.token_hex(8);folder=ROOT/'deployment/runtime'/run;folder.mkdir(mode=0o700)
    restrict_owned_folder(folder)
    observer=Observer(folder/'resources.jsonl');observer.sample('ADMISSION',True)
    name=run+'-pg';created=False;process=None;stage='CREATE_PG';passed=False
    password=secrets.token_urlsafe(32);db='exchange_ai_pilot_'+run[9:]
    secret=folder/'pg.private.env'
    with secret.open('x',encoding='utf8') as f:f.write('POSTGRES_USER=pilot\nPOSTGRES_DB='+db+'\nPOSTGRES_PASSWORD='+password+'\n')
    secret.chmod(0o600)
    save(folder/'owned.json',{'run':run,'container':name,'image':IMAGE,'paidCalls':0,'keyReads':0})
    try:
        observer.sample('BEFORE_CREATE',True)
        cmd(['docker','create','--pull=never','--name',name,'--label',LABEL+'='+run,'--memory=384m','--cpus=1','--env-file',secret,'-p','127.0.0.1::5432','--mount','type=volume,source='+run+'-pgdata,target=/var/lib/postgresql/data',IMAGE,'postgres','-c','shared_buffers=16MB','-c','max_connections=24'])
        created=True;cmd(['docker','start',name]);item=owned(name,run)
        binding=item['NetworkSettings']['Ports']['5432/tcp'][0]
        if binding['HostIp']!='127.0.0.1':raise RuntimeError('V2_LOOPBACK_ONLY')
        stage='PG_READY';end=time.monotonic()+45
        while subprocess.run(['docker','exec',name,'pg_isready','-U','pilot','-d',db],env=env(),capture_output=True,timeout=5).returncode:
            if time.monotonic()>end:raise RuntimeError('V2_PG_READY_TIMEOUT')
            time.sleep(.5)
        props=folder/'run.private.properties'
        with props.open('x',encoding='utf8') as f:f.write('identity='+run+'\ncontainer='+name+'\ndb.url=jdbc:postgresql://127.0.0.1:'+binding['HostPort']+'/'+db+'?connectTimeout=2&socketTimeout=5\ndb.user=pilot\ndb.password='+password+'\n')
        props.chmod(0o600);e=env();e.update({'AI_PILOT_V2_PG_TESTS':'true','AI_PILOT_V2_RUN':str(props)})
        stage='ACTUAL_PG_DUAL_GUARD';observer.sample(stage)
        args=[str(ROOT/'backend/gradlew.bat'),'-p',str(ROOT/'backend'),'-I',str(ROOT/'deployment/ai-readiness.gradle'),'test','--offline','--no-daemon','--max-workers=1','-Dorg.gradle.jvmargs=-Xmx256m -XX:MaxMetaspaceSize=192m','--tests','*PilotV2PostgresTest']
        with (folder/'java.private.log').open('xb') as log:
            process=subprocess.Popen(args,cwd=ROOT,env=e,stdout=log,stderr=subprocess.STDOUT)
            code=process.wait(timeout=240)
        result=ROOT/'backend/build/test-results/test/TEST-com.pricetrack.exchange.ai.pilot.PilotV2PostgresTest.xml'
        if result.exists():shutil.copyfile(result,folder/'postgres.private.xml')
        if code:raise RuntimeError('V2_POSTGRES_ASSERTION_FAILED')
        passed=True
    finally:
        child_cleanup=stop_child(process)
        pg_cleanup=stop_owned_pg(name,run);stopped=pg_cleanup in {'STOPPED','NOT_CREATED'}
        observer.sample('FINISHED')
        save(folder/'result.json',{'status':'PASS_PG_ONLY' if passed and stopped and child_cleanup=='STOPPED' else 'FAIL','stage':stage,'ownedPgStopped':stopped,'pgCleanup':pg_cleanup,'childCleanup':child_cleanup,'volumePreserved':True,'paidCalls':0,'keyReads':0,'realModel':False})
        print(json.dumps({'run':run,'stage':stage,'pgPassed':passed,'ownedPgStopped':stopped,'paidCalls':0}),flush=True)
    return folder

def free_port():
    with socket.socket() as s:s.bind(('127.0.0.1',0));return s.getsockname()[1]

def full(folder,resume=False):
    """Reuse only the PG allocated above, never an arbitrary existing container/volume."""
    import product_read_network as network
    import product_read_linux_support as linux
    requested=Path(folder).resolve()
    base=requested.parent if resume else requested
    if base.parent!=(ROOT/'deployment/runtime').resolve() or not base.name.startswith('pilot-v2-'):raise RuntimeError('V2_SCOPE')
    meta=json.loads((base/'owned.json').read_text());parent_run=meta['run'];name=meta['container'];owned(name,parent_run)
    restrict_owned_folder(base)
    attempt=requested.name.removeprefix('full-') if resume else secrets.token_hex(4)
    if not __import__('re').fullmatch('[a-f0-9]{8}',attempt):raise RuntimeError('V2_ATTEMPT_SCOPE')
    run=parent_run+'-f'+attempt;folder=base/('full-'+attempt)
    if resume:
        prior=json.loads((folder/'owned.json').read_text())
        if prior!={'run':run,'parentPgRun':parent_run,'parentPg':name,'scope':'NEW_ISOLATED_FULL_ATTEMPT'}:raise RuntimeError('V2_ATTEMPT_IDENTITY')
    else:folder.mkdir(mode=0o700)
    observer=Observer(folder/('resources-full-'+uuid.uuid4().hex[:8]+'.jsonl'));observer.sample('FULL_ADMISSION',True)
    settings={line.split('=',1)[0]:line.split('=',1)[1] for line in (base/'run.private.properties').read_text().splitlines() if '=' in line}
    if not resume:save(folder/'owned.json',{'run':run,'parentPgRun':parent_run,'parentPg':name,'scope':'NEW_ISOLATED_FULL_ATTEMPT'})
    lock=json.loads((ROOT/'deployment/product-read-ci-lock.json').read_text())
    tools='tokenized-ai-latest-tools:phase-a-20261029174809-d30067c2'
    # An image is a compiler/recipe input, not an old runtime, DB, snapshot or wallet.
    digest=cmd(['docker','image','inspect',tools,'--format','{{.Id}}'])
    if not resume:save(folder/'recipe-image.json',{'image':tools,'digest':digest,'scope':'CONTRACT_COMPILER_ARTIFACTS_ONLY'})
    network_name=run+'-network';anvil=run+'-anvil';created=[];process=None;stage='BUILD';passed=False
    def docker(*args,timeout=30):return cmd(['docker',*args],timeout)
    def own_stop(n,image):
        exists=docker('container','ls','-a','--filter','name=^/'+n+'$','--format','{{.Names}}').splitlines()
        if not exists:return 'NOT_CREATED'
        if exists!=[n]:raise RuntimeError('V2_CLEANUP_NAME_MISMATCH')
        item=json.loads(docker('inspect',n))[0]
        if item['Config']['Labels'].get(LABEL)!=run or item['Config']['Image']!=image:raise RuntimeError('V2_OWNERSHIP_MISMATCH')
        if item['State']['Running']:docker('stop','--time','15',n)
        if json.loads(docker('inspect',n))[0]['State']['Running']:raise RuntimeError('V2_OWN_STOP_UNCONFIRMED')
        return 'STOPPED_STATE_PRESERVED'
    def mount(path,target,ro=True):return 'type=bind,source='+str(path.resolve())+',target='+target+(',readonly' if ro else '')
    def actor(role,image,args,options=(),timeout=120):
        n=run+'-'+role;created.append((n,image));docker('create','--pull=never','--name',n,'--label',LABEL+'='+run,'--network',network_name,'--memory=384m',*options,image,*args)
        docker('start',n);status=docker('wait',n,timeout=timeout)
        logs=subprocess.run(['docker','logs',n],capture_output=True,env=env(),timeout=10)
        (folder/(role+'.private.log')).write_bytes(logs.stdout+logs.stderr)
        if status!='0':raise RuntimeError('V2_ACTOR_'+role.upper()+'_FAILED')
    try:
        if not resume:
            # Compile before running the full stack; all dependencies are local/offline.
            build=[str(ROOT/'backend/gradlew.bat'),'-p',str(ROOT/'backend'),'-I',str(ROOT/'deployment/product-read-ci.gradle'),'pilotV2TestRuntime','productReadCiClasses','productReadCiRuntime','--offline','--no-daemon','--max-workers=1','-Dorg.gradle.jvmargs=-Xmx256m -XX:MaxMetaspaceSize=192m']
            p=subprocess.run(build,env=env(),cwd=ROOT,capture_output=True,timeout=180);(folder/'build.private.log').write_bytes(p.stdout+p.stderr)
            if p.returncode:raise RuntimeError('V2_BUILD_FAILED')
            inventory=network.inventory(lambda args,timeout=30:subprocess.run(args,env=env(),capture_output=True,timeout=timeout));save(folder/'network.json',inventory)
            observer.sample('BEFORE_FULL_CREATE',True)
            # Windows Docker internal bridges do not publish loopback ports. This owned bridge
            # contains trial services only; every host port is explicitly bound to 127.0.0.1.
            docker('network','create','--subnet',inventory['subnet'],'--label',LABEL+'='+run,network_name)
            stage='ANVIL'
            chain=folder/'chain';chain.mkdir();rpc_port=free_port();web_port=free_port();origin='http://127.0.0.1:'+str(web_port)
            created.append((anvil,lock['images']['anvil']))
            genesis_epoch=int(time.time());save(folder/'chain-start.json',{'genesisEpoch':genesis_epoch,'chainId':31337,'owner':run})
            # Preserve the state on normal exit; avoid serializing full historical state
            # every 20s during a read-only RPC acceptance run. No history is pruned.
            docker('run','--pull=never','-d','--name',anvil,'--label',LABEL+'='+run,'--network',network_name,'--network-alias','anvil','--memory=256m','-p','127.0.0.1:'+str(rpc_port)+':8545','--mount',mount(chain,'/data',False),'--entrypoint','anvil',lock['images']['anvil'],'--host','0.0.0.0','--accounts','0','--chain-id','31337','--timestamp',str(genesis_epoch),'--block-time','2','--state','/data/state.json','--preserve-historical-states','--quiet')
            end=time.monotonic()+30
            while subprocess.run(['docker','exec',anvil,'cast','chain-id','--rpc-url','http://127.0.0.1:8545'],capture_output=True,env=env(),timeout=5).returncode:
                if time.monotonic()>end:raise RuntimeError('V2_ANVIL_READY_TIMEOUT')
                time.sleep(.5)
            stage='VERIFIED_ARTIFACTS'
            extract=run+'-extract';created.append((extract,tools));docker('create','--name',extract,'--label',LABEL+'='+run,tools)
            if not (ROOT/'contracts/out').exists():docker('cp',extract+':/contracts/out',ROOT/'contracts/out')
            compiler_root=folder/'compiler';(compiler_root/'contracts').mkdir(parents=True)
            shutil.copytree(ROOT/'contracts/src',compiler_root/'contracts/src')
            docker('cp',extract+':/contracts/lib',compiler_root/'contracts/lib')
            shutil.copytree(ROOT/'contracts/out',compiler_root/'contracts/out')
            artifacts=folder/'artifacts';artifacts.mkdir();save(folder/'artifact-proof.json',linux.artifact_fixture(compiler_root,artifacts))
            for child in ('fixture','keys','private-evidence','release','secrets'):(folder/child).mkdir()
            stage='C_FIXTURE'
            actor('cseed',lock['images']['java'],['-Xmx192m','-Djdk.httpclient.disableRetryConnect=true','-Djdk.httpclient.enableAllMethodRetry=false','-Djdk.httpclient.redirects.retrylimit=1','--add-opens=java.net.http/jdk.internal.net.http=ALL-UNNAMED','-cp','/classes:/helpers:/jars/*','PilotV2ChainFixture',origin],
                ['--entrypoint','java','--mount',mount(ROOT/'backend/build/classes/java/main','/classes'),'--mount',mount(ROOT/'backend/build/product-read-ci/helpers','/helpers'),'--mount',mount(ROOT/'backend/build/product-read-ci/jars','/jars'),*sum((['--mount',mount(folder/p,t,False)] for p,t in [('artifacts','/artifacts'),('private-evidence','/private-evidence'),('fixture','/fixture'),('keys','/keys')]),[])],300)
            stage='LEGACY_FIXTURE'
            fresh={k:'0x'+secrets.token_hex(32) for k in ['OPERATOR_PRIVATE_KEY','PRICE_SIGNER_PRIVATE_KEY','FUTURES_EXECUTOR_PRIVATE_KEY','FUTURES_PRICE_SIGNER_PRIVATE_KEY']}
            with (folder/'secrets/application.properties').open('x') as f:f.write('\n'.join(k+'='+v for k,v in fresh.items())+'\n')
            for role,script in [('spotdeploy','/init-chain.sh'),('futuresdeploy','/init-futures-fixture.sh')]:actor(role,tools,[script],['--entrypoint','/bin/sh','--mount',mount(folder/'secrets','/run/secrets'),'--mount',mount(folder/'release','/release',False)],180)
        else:
            stage='OWNED_STATE_RESUME'
            created.append((anvil,lock['images']['anvil']))
            own_stop(anvil,lock['images']['anvil'])
            info=json.loads(docker('inspect',anvil))[0]
            primary=json.loads(docker('network','inspect',network_name))[0]
            if primary['Labels'].get(LABEL)!=run:raise RuntimeError('V2_NETWORK_OWNER')
            if primary['Internal']:
                host_network=run+'-host'
                if host_network not in info['NetworkSettings']['Networks']:
                    inventory=network.inventory(lambda args,timeout=30:subprocess.run(args,env=env(),capture_output=True,timeout=timeout))
                    docker('network','create','--subnet',inventory['subnet'],'--label',LABEL+'='+run,host_network)
                    docker('network','connect',host_network,anvil)
            docker('start',anvil)
            info=json.loads(docker('inspect',anvil))[0]
            rpc_port=int(info['NetworkSettings']['Ports']['8545/tcp'][0]['HostPort'])
            previous=max(folder.glob('app-*.private.properties'),key=lambda p:p.stat().st_mtime)
            previous_props=dict(line.split('=',1) for line in previous.read_text().splitlines() if '=' in line)
            origin=previous_props['app.c-futures.origin'];web_port=int(previous_props['server.port'])
            fresh=dict(line.split('=',1) for line in (folder/'secrets/application.properties').read_text().splitlines() if '=' in line)
            # Rebuild only current test code; never redeploy or replay an existing fixture transaction.
            build=[str(ROOT/'backend/gradlew.bat'),'-p',str(ROOT/'backend'),'-I',str(ROOT/'deployment/product-read-ci.gradle'),'pilotV2TestRuntime','--offline','--no-daemon','--max-workers=1','-Dorg.gradle.jvmargs=-Xmx256m -XX:MaxMetaspaceSize=192m']
            b=subprocess.run(build,env=env(),cwd=ROOT,capture_output=True,timeout=180)
            (folder/('resume-build-'+uuid.uuid4().hex[:8]+'.private.log')).write_bytes(b.stdout+b.stderr)
            if b.returncode:raise RuntimeError('V2_RESUME_BUILD_FAILED')
        docker('start',name)
        stage='ACTUAL_SQL_READY';end=time.monotonic()+30
        while subprocess.run(['docker','exec',name,'psql','-X','-U',settings['db.user'],'-d',settings['db.url'].split('/')[3].split('?')[0],'-tAc','SELECT 1'],capture_output=True,env=env(),timeout=5).returncode:
            if time.monotonic()>end:raise RuntimeError('V2_ACTUAL_SQL_READY_TIMEOUT')
            time.sleep(.5)
        binding=owned(name,parent_run)['NetworkSettings']['Ports']['5432/tcp'][0]
        settings['db.url']='jdbc:postgresql://127.0.0.1:'+binding['HostPort']+'/'+settings['db.url'].split('/')[3]
        suffix=parent_run[9:]+'_'+attempt;dbs={role:'exchange_ai_pilot_'+role+'_'+suffix for role in ['legacy','pilot','budget','trade']}
        existing=set(docker('exec',name,'psql','-X','-U',settings['db.user'],'-d',settings['db.url'].split('/')[3].split('?')[0],'-tAc',"SELECT datname FROM pg_database WHERE datname LIKE 'exchange_ai_pilot_%'").splitlines())
        reuse_trial=False
        for d in set(dbs.values()) & existing:
            tables=docker('exec',name,'psql','-X','-U',settings['db.user'],'-d',d,'-tAc',"SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE c.relkind IN ('r','p') AND n.nspname NOT IN ('pg_catalog','information_schema')")
            if tables!='0':
                if d!=dbs['trade'] or (folder/'session').exists():raise RuntimeError('V2_EXISTING_TRIAL_DB_REQUIRES_PREPARED_FIXTURE')
                evidence=docker('exec',name,'psql','-X','-U',settings['db.user'],'-d',d,'-tAc',"SELECT (SELECT count(*) FROM orders WHERE status<>'FAILED')+(SELECT count(*) FROM trades)+(SELECT count(*) FROM blockchain_transactions)+(SELECT count(*) FROM futures_positions)+(SELECT count(*) FROM futures_transactions)+(SELECT count(*) FROM user_balances WHERE locked_amount<>0)")
                if evidence!='0':
                    pending=docker('exec',name,'psql','-X','-U',settings['db.user'],'-d',d,'-tAc',"SELECT (SELECT count(*) FROM blockchain_transactions WHERE status IN ('SIGNED','SUBMITTED'))+(SELECT count(*) FROM futures_transactions WHERE status IN ('SIGNED','SUBMITTED','PREPARED'))+(SELECT count(*) FROM futures_positions WHERE pending_operation IS NOT NULL)")
                    if pending!='0':raise RuntimeError('V2_UNCERTAIN_FINANCIAL_WORK_NO_REPLAY')
                    reuse_trial=True;continue  # Continue the SAME owned users/orders/position. Never resubmit their completed trade.
                # Failed before any submitted financial work. Preserve this DB; never reset it.
                dbs['trade']='exchange_ai_pilot_trade_'+suffix+'_'+secrets.token_hex(3)
                save(folder/('unused-trade-db-'+uuid.uuid4().hex[:8]+'.json'),{'preserved':d,'newEmptyTrial':dbs['trade'],'unresolvedOrSubmittedFinancialWorkRows':0,'failedBeforeSignatureRowsPreserved':True})
        sql='\n'.join('CREATE DATABASE '+d+';' for d in dbs.values() if d not in existing)
        r=subprocess.run(['docker','exec','-i',name,'psql','-X','-v','ON_ERROR_STOP=1','-U',settings['db.user'],'-d',settings['db.url'].split('/')[3].split('?')[0]],input=sql.encode(),capture_output=True,env=env(),timeout=15)
        if r.returncode:
            (folder/('sql-error-'+uuid.uuid4().hex[:8]+'.private.log')).write_bytes(r.stdout+r.stderr)
            raise RuntimeError('V2_AI_DATABASES_FAILED')
        def ai_db(role):return {'jdbcUrl':settings['db.url'].replace('/'+settings['db.url'].split('/')[3].split('?')[0]+'?','/'+dbs[role]+'?'),'dbUser':settings['db.user'],'dbPassword':settings['db.password']}
        config={'mode':'UNPAID_CONTROLLED_ONLY','repo':str(ROOT),'run':str(folder/'session'),'python':shutil.which('python'),'knowledgeRoot':str(ROOT/'docs/ai-knowledge'),'legacyManifest':str(ROOT/'docs/ai/ingest-manifest.json'),'pilotManifest':str(ROOT/'docs/ai/ingest-manifest-pilot.json'),'legacy9':ai_db('legacy'),'pilot10':ai_db('pilot'),'budget':ai_db('budget')}
        revision=uuid.uuid4().hex[:8];config_path=folder/('v2-'+revision+'.private.json');save(config_path,config)
        addresses={}
        for f in ['chain.properties','futures.properties']:addresses.update(line.split('=',1) for line in (folder/'release'/f).read_text().splitlines() if '=' in line)
        identity=json.loads((folder/'fixture/c-identity.json').read_text())
        if reuse_trial:
            previous=max(folder.glob('app-*.private.properties'),key=lambda p:p.stat().st_mtime)
            previous_props=dict(line.split('=',1) for line in previous.read_text().splitlines() if '=' in line)
            origin=previous_props['app.c-futures.origin'];web_port=int(previous_props['server.port'])
        props={'spring.config.import':'','server.address':'127.0.0.1','server.port':str(web_port),'spring.datasource.url':settings['db.url'],'spring.datasource.username':settings['db.user'],'spring.datasource.password':settings['db.password'],'spring.jpa.hibernate.ddl-auto':'none','spring.sql.init.mode':'always','spring.datasource.hikari.maximum-pool-size':'4','app.price.provider':'simulated','app.price.update-interval-ms':'1000','app.price.initial-delay-ms':'1000','app.blockchain.enabled':'true','app.blockchain.rpc-url':'http://127.0.0.1:'+str(rpc_port),'app.blockchain.operator-private-key':fresh['OPERATOR_PRIVATE_KEY'],'app.blockchain.price-report.enabled':'true','app.blockchain.price-report.signer-private-key':fresh['PRICE_SIGNER_PRIVATE_KEY'],'app.blockchain.reconciliation.initial-delay-ms':'2147483647','app.reserve.enabled':'true','app.reserve.execution-id':run,'app.trade-audit.enabled':'true','app.futures.enabled':'true','app.futures.runtime-enabled':'true','app.futures.executor-private-key':fresh['FUTURES_EXECUTOR_PRIVATE_KEY'],'app.futures.signer-private-key':fresh['FUTURES_PRICE_SIGNER_PRIVATE_KEY'],'app.futures.rpc-url':'http://127.0.0.1:'+str(rpc_port),'app.futures.chain-id':'31337','app.futures.fixture-block-time-seconds':'2','app.futures.engine-address':addresses['FUTURES_ENGINE_ADDRESS'],'app.futures.code-hash':addresses['FUTURES_ENGINE_CODE_HASH'],'app.conditional.enabled':'false','app.ai.enabled':'true','app.ai.agent.enabled':'true','app.ai.tools.enabled':'true','app.ai.skills.enabled':'true','app.ai.diagnosis.enabled':'false','app.ai.pilot-v2.unpaid-tests':'true','app.ai.api-key':'','app.ai.jdbc-url':ai_db('legacy')['jdbcUrl'],'app.ai.db-user':settings['db.user'],'app.ai.db-password':settings['db.password'],'app.ai.knowledge-root':str(ROOT/'docs/ai-knowledge').replace('\\','/'),'app.ai.manifest':str(ROOT/'docs/ai/ingest-manifest.json').replace('\\','/'),'app.c-futures.enabled':'true','app.c-futures.direct.enabled':'false','app.c-futures.rpc-uri':'http://127.0.0.1:'+str(rpc_port),'app.c-futures.chain-id':'31337','app.c-futures.genesis-hash':identity['genesisHash'],'app.c-futures.origin':origin,'app.admin.password':'','logging.level.com.pricetrack.exchange.ai.agent':'ERROR'}
        for k,a in [('mock-krw','MOCK_KRW_ADDRESS'),('m-sec','MSEC_ADDRESS'),('price-oracle','PRICE_ORACLE_ADDRESS'),('exchange-vault','EXCHANGE_VAULT_ADDRESS')]:props['app.blockchain.'+k+'-address']=addresses[a]
        for role in ['token','controller','rules','vault']:props['app.c-futures.'+role]=identity[role]['address'];props['app.c-futures.'+role+'-code-hash']=identity[role]['codeHash']
        for field,key in [('relayer','relayer'),('oracle','oracle'),('pauseAdmin','pause-admin'),('symbolHash','symbol-hash'),('policyHash','policy-hash')]:props['app.c-futures.'+key]=identity[field]
        props['spring.datasource.url']=ai_db('trade')['jdbcUrl']
        props['spring.datasource.driver-class-name']='org.postgresql.Driver';props['spring.jpa.database-platform']='org.hibernate.dialect.PostgreSQLDialect';props['app.jwt.secret']=secrets.token_urlsafe(48)
        app_path=folder/('app-'+revision+'.private.properties')
        with app_path.open('x',encoding='utf8') as f:f.write('\n'.join(k+'='+v for k,v in props.items())+'\n')
        static=ROOT/'backend/build/resources/main/static';shutil.copytree(ROOT/'tools/websocket-test-client/dist',static,dirs_exist_ok=True)
        e=env();e.update({'AI_PILOT_V2_HTTP_TESTS':'true','AI_PILOT_V2_FOLDER':str(folder),'AI_PILOT_V2_APP':str(app_path),'AI_PILOT_V2_CONFIG':str(config_path)})
        if reuse_trial:e['AI_PILOT_V2_REUSE_TRIAL']='true'
        # Align only this owned restored test chain BEFORE issuing fresh reports. Never
        # extend a report or alter TTL/contract validation; old canonical receipts remain.
        import product_manual_clock as clock
        def rpc(method,params):return json.loads(docker('exec',anvil,'cast','rpc',method,*[json.dumps(p,separators=(',',':')) for p in params],'--rpc-url','http://127.0.0.1:8545'))
        cmeta=json.loads((folder/'fixture/test-chain.json').read_text());before_clock=clock.snapshot(rpc,cmeta,ROOT)
        clock.configure(rpc);time.sleep(2)
        after_clock=clock.snapshot(rpc,cmeta,ROOT,anchors=before_clock['history']);clock.verify_preserved(before_clock,after_clock)
        ready_clock=clock.require_ready(rpc)
        save(folder/('clock-alignment-'+revision+'.json'),{'scope':'THIS_NEW_OWNED_CHAIN_ONLY','before':before_clock,'after':after_clock,'latestAge':ready_clock['latestAge'],'pendingAge':ready_clock['pendingAge'],'reportTtlChanged':False})
        stage='SPRING_HTTP_CHROME';observer.sample(stage,True)
        # Run the same JUnit selector without a second Gradle daemon while Docker is live.
        # This also prevents a pre-JUnit Gradle failure from copying stale XML as new evidence.
        java=Path(e['JAVA_HOME'])/'bin/java.exe'
        cp=';'.join(str(ROOT/'backend'/p) for p in ['build/classes/java/main','build/classes/java/test','build/resources/main','build/resources/test','build/pilot-v2-test/jars/*'])
        args=[str(java),'-Xmx384m','-XX:MaxMetaspaceSize=256m','-cp',cp,'com.pricetrack.exchange.futures.chain.PilotV2LiveHttpTest']
        started=time.time()
        with (folder/('http-java-'+revision+'.private.log')).open('xb') as log:process=subprocess.Popen(args,env=e,cwd=ROOT/'backend',stdout=log,stderr=subprocess.STDOUT);code=process.wait(timeout=1500)
        if code:raise RuntimeError('V2_HTTP_ASSERTION_FAILED')
        summaries=[p for p in folder.glob('standalone-*.json') if p.stat().st_mtime>=started]
        if len(summaries)!=1:raise RuntimeError('V2_REAL_HTTP_TEST_REQUIRED')
        suite=json.loads(summaries[0].read_text())
        if any(suite[k]!=v for k,v in {'tests':1,'passed':1,'skipped':0,'aborted':0,'failed':0}.items()):raise RuntimeError('V2_REAL_HTTP_TEST_REQUIRED')
        result=json.loads((folder/'http-result.json').read_text())
        if result!={'controlledProvider':True,'realModel':False,'httpRequests':51,'browserRequests':6,'financialUnchanged':True,'paidCalls':0,'keyReads':0}:raise RuntimeError('V2_COMPLETE_EVIDENCE_REQUIRED')
        passed=True
    finally:
        cleanup={'ownedChild':stop_child(process)}
        for n,image in reversed(created):
            try:cleanup[n]=own_stop(n,image)
            except Exception:cleanup[n]='UNCONFIRMED_NOT_MODIFIED_IF_WRONG_OWNER'
        try:
            if owned(name,parent_run)['State']['Running']:cmd(['docker','stop','--time','10',name])
            cleanup[name]='STOPPED' if not owned(name,parent_run)['State']['Running'] else 'UNCONFIRMED'
        except Exception:cleanup[name]='UNCONFIRMED'
        clean=all(v in {'STOPPED','NOT_CREATED','STOPPED_STATE_PRESERVED'} for v in cleanup.values())
        observer.sample('FULL_STOPPED');save(folder/('full-result-'+uuid.uuid4().hex[:8]+'.json'),{'status':'PASS' if passed and clean else 'FAIL','stage':stage,'ownedResourcesStopped':clean,'cleanup':cleanup,'statesPreserved':True,'paidCalls':0,'providerKeyReads':0})
        print(json.dumps({'run':run,'stage':stage,'fullPassed':passed,'paidCalls':0}),flush=True)

def probe(folder):
    folder=Path(folder).resolve();meta=json.loads((folder/'owned.json').read_text());parent=json.loads((folder.parent/'owned.json').read_text())
    if folder.parent.parent!=(ROOT/'deployment/runtime').resolve() or meta['parentPgRun']!=parent['run']:raise RuntimeError('V2_PROBE_SCOPE')
    run=meta['run'];anvil=run+'-anvil';pg=parent['container'];report={}
    observer=Observer(folder/('probe-resources-'+uuid.uuid4().hex[:8]+'.jsonl'));observer.sample('PROBE',True)
    info=json.loads(cmd(['docker','inspect',anvil]))[0]
    if info['Config']['Labels'].get(LABEL)!=run or info['State']['Running']:raise RuntimeError('V2_PROBE_OWNERSHIP')
    owned(pg,parent['run'])
    try:
        cmd(['docker','start',anvil]);time.sleep(1)
        info=json.loads(cmd(['docker','inspect',anvil]))[0]
        report.update({'running':info['State']['Running'],'ports':info['NetworkSettings']['Ports'],'configuredPorts':info['HostConfig']['PortBindings']})
        internal=subprocess.run(['docker','exec',anvil,'cast','chain-id','--rpc-url','http://127.0.0.1:8545'],capture_output=True,env=env(),timeout=5)
        report['internalRpcReady']=internal.returncode==0 and internal.stdout.strip()==b'31337'
        genesis=json.loads(cmd(['docker','exec',anvil,'cast','rpc','eth_getBlockByNumber','0x0','false','--rpc-url','http://127.0.0.1:8545']))
        expected=json.loads((folder/'fixture/test-chain.json').read_text())
        report['genesisMatchesFixture']=genesis['hash']==expected['genesisHash']
        report['genesisEpoch']=int(genesis['timestamp'],16)
        latest=json.loads(cmd(['docker','exec',anvil,'cast','rpc','eth_getBlockByNumber','latest','false','--rpc-url','http://127.0.0.1:8545']))
        report['latestEpoch']=int(latest['timestamp'],16);report['hostEpoch']=int(time.time())
        port=int(info['HostConfig']['PortBindings']['8545/tcp'][0]['HostPort'])
        try:
            with socket.create_connection(('127.0.0.1',port),timeout=3):report['hostTcp']=True
        except OSError:report['hostTcp']=False
        cmd(['docker','start',pg]);time.sleep(1)
        props=dict(line.split('=',1) for line in (folder.parent/'run.private.properties').read_text().splitlines() if '=' in line)
        check=subprocess.run(['docker','exec',pg,'psql','-X','-U',props['db.user'],'-d',props['db.url'].split('/')[3].split('?')[0],'-tAc','SELECT 1'],capture_output=True,env=env(),timeout=5)
        report['actualSqlReady']=check.returncode==0 and check.stdout.strip()==b'1'
        suffix=parent['run'][9:]+'_'+folder.name.removeprefix('full-')
        dbs=cmd(['docker','exec',pg,'psql','-X','-U',props['db.user'],'-d',props['db.url'].split('/')[3].split('?')[0],'-tAc',"SELECT datname FROM pg_database WHERE datname LIKE 'exchange_ai_pilot_trade_"+suffix+"%'"]).splitlines()
        report['legacyRisk']=[]
        for d in dbs:
            table=cmd(['docker','exec',pg,'psql','-X','-U',props['db.user'],'-d',d,'-tAc',"SELECT to_regclass('public.futures_positions') IS NOT NULL"])
            if table=='t':
                rows=cmd(['docker','exec',pg,'psql','-X','-U',props['db.user'],'-d',d,'-tAc',"SELECT json_build_object('status',status,'risk',risk_status,'deadline',deadline,'observed',last_observed_at,'pending',pending_operation IS NOT NULL,'reason',block_reason) FROM futures_positions ORDER BY id"])
                report['legacyRisk'] += [json.loads(x) for x in rows.splitlines() if x]
    finally:
        image=json.loads((ROOT/'deployment/product-read-ci-lock.json').read_text())['images']['anvil']
        report['cleanup']={anvil:stop_owned_container(anvil,run,image),pg:stop_owned_pg(pg,parent['run'])}
        save(folder/('resume-probe-'+uuid.uuid4().hex[:8]+'.json'),report);print(json.dumps(report),flush=True)


def rpc_window(folder,no_periodic=False):
    """Bounded read-only RPC/I/O diagnosis, this task's stopped Anvil only."""
    import http.client
    folder=Path(folder).resolve();meta=json.loads((folder/'owned.json').read_text())
    if folder.parent.parent!=(ROOT/'deployment/runtime').resolve():raise RuntimeError('V2_PROBE_SCOPE')
    run=meta['run'];name=run+'-anvil';info=json.loads(cmd(['docker','inspect',name]))[0]
    image=json.loads((ROOT/'deployment/product-read-ci-lock.json').read_text())['images']['anvil']
    if info['Config']['Labels'].get(LABEL)!=run or info['Config']['Image']!=image or info['State']['Running']:raise RuntimeError('V2_PROBE_OWNERSHIP')
    observer=Observer(folder/('rpc-window-resources-'+uuid.uuid4().hex[:8]+'.jsonl'));observer.sample('RPC_WINDOW',True)
    rows=[];source=folder/'chain/state.json';source_hash=__import__('hashlib').sha256(source.read_bytes()).hexdigest();state=source
    try:
        if no_periodic:
            output=folder/('rpc-no-periodic-'+uuid.uuid4().hex[:8]);output.mkdir();name=run+'-rpc-'+uuid.uuid4().hex[:8]
            state=output/'state.json'
            cmd(['docker','create','--pull=never','--name',name,'--label',LABEL+'='+run,'--network',run+'-network','--memory=256m','-p','127.0.0.1::8545','--mount','type=bind,source='+str(source.parent)+',target=/in,readonly','--mount','type=bind,source='+str(output)+',target=/out','--entrypoint','anvil',image,'--host','0.0.0.0','--accounts','0','--chain-id','31337','--block-time','1','--load-state','/in/state.json','--dump-state','/out/state.json','--preserve-historical-states','--quiet'])
        cmd(['docker','start',name]);time.sleep(2)
        info=json.loads(cmd(['docker','inspect',name]))[0];port=int(info['NetworkSettings']['Ports']['8545/tcp'][0]['HostPort'])
        for i in range(100):
            start=time.monotonic();c=http.client.HTTPConnection('127.0.0.1',port,timeout=3);error=None;ok=False
            before=state.stat() if state.exists() else None
            try:
                c.request('POST','/',json.dumps({'jsonrpc':'2.0','id':i+1,'method':'eth_chainId','params':[]} ),{'Content-Type':'application/json','Connection':'close'})
                r=c.getresponse();body=r.read(4097);ok=r.status==200 and len(body)<=4096 and json.loads(body).get('result')=='0x7a69'
            except Exception as e:error=type(e).__name__+(':'+str(e.winerror) if getattr(e,'winerror',None) else '')
            finally:c.close()
            after=state.stat() if state.exists() else None;rows.append({'sample':i,'ok':ok,'errorType':error,'elapsedMs':round((time.monotonic()-start)*1000,3),'stateBytes':after.st_size if after else 0,'stateWriteDuringCall':bool(after and (not before or before.st_mtime_ns!=after.st_mtime_ns)),'stateModifiedEpoch':after.st_mtime if after else None})
            time.sleep(.5)
    finally:
        cleanup=stop_owned_container(name,run,image,15)
        unchanged=__import__('hashlib').sha256(source.read_bytes()).hexdigest()==source_hash if no_periodic else None
        save(folder/('rpc-window-'+uuid.uuid4().hex[:8]+'.json'),{'samples':rows,'periodicSnapshot':not no_periodic,'sourceStateUnchanged':unchanged,'paidCalls':0,'readOnlyRpc':True,'cleanup':cleanup,'container':name})
        print(json.dumps({'rpcSamples':len(rows),'failures':sum(not r['ok'] for r in rows),'timeouts':sum((r['errorType'] or '').startswith('TimeoutError') for r in rows),'maxMs':max((r['elapsedMs'] for r in rows),default=0),'sourceStateUnchanged':unchanged,'paidCalls':0}),flush=True)

def finish(base):
    """Remove stopped disposable containers only; retain PG/current Anvil and every data file."""
    base=Path(base).resolve();meta=json.loads((base/'owned.json').read_text());run=meta['run']
    if base.parent!=(ROOT/'deployment/runtime').resolve() or base.name!=run:raise RuntimeError('V2_CLEANUP_SCOPE')
    runs={run}|{json.loads(p.read_text())['run'] for p in base.glob('full-*/owned.json')}
    lock=json.loads((ROOT/'deployment/product-read-ci-lock.json').read_text())
    keep={meta['container'],run+'-fcafeaf7f-anvil'};removed=[]
    states=[{'path':str(p.relative_to(base)),'bytes':p.stat().st_size,'sha256':__import__('hashlib').sha256(p.read_bytes()).hexdigest()} for p in base.rglob('state.json')]
    names=cmd(['docker','container','ls','-a','--filter','label='+LABEL,'--format','{{.Names}}']).splitlines()
    if not keep<=set(names):raise RuntimeError('V2_CLEANUP_REQUIRED_STATE_CONTAINER_MISSING')
    checked=[]
    for name in names:
        if not name.startswith(run+'-'):continue
        item=json.loads(cmd(['docker','inspect',name]))[0];owner=item['Config']['Labels'].get(LABEL)
        if owner not in runs or item['Name']!='/'+name or item['State']['Running']:raise RuntimeError('V2_CLEANUP_OWNERSHIP')
        if name in keep:continue
        role=name.removeprefix(owner+'-')
        image=lock['images']['anvil'] if role=='anvil' or re.fullmatch('rpc-[0-9a-f]{8}',role) else lock['images']['java'] if role=='cseed' else 'tokenized-ai-latest-tools:phase-a-20261029174809-d30067c2' if role in {'extract','spotdeploy','futuresdeploy'} else None
        if image is None or item['Config']['Image']!=image:raise RuntimeError('V2_CLEANUP_IMAGE')
        checked.append(name)
    # Validate the full deletion set first. No force, -v, image prune or data deletion.
    for name in checked:cmd(['docker','rm',name]);removed.append(name)
    volume=run+'-pgdata';cmd(['docker','volume','inspect',volume,'--format','{{.Name}}'])
    save(base/('cleanup-'+uuid.uuid4().hex[:8]+'.json'),{'removed':removed,'keptStoppedContainers':sorted(keep),'keptPgVolume':volume,'stateFilesPreserved':states,'dataDeleted':False})
    print(json.dumps({'ownedDisposableContainersRemoved':len(removed),'keptStoppedContainers':len(keep),'stateFilesPreserved':len(states),'pgVolumePreserved':True}),flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser();group=parser.add_mutually_exclusive_group();group.add_argument('--full',type=Path);group.add_argument('--resume',type=Path);group.add_argument('--probe',type=Path);group.add_argument('--rpc-window',type=Path);group.add_argument('--rpc-no-periodic',type=Path);group.add_argument('--finish',type=Path);args=parser.parse_args()
    finish(args.finish) if args.finish else rpc_window(args.rpc_no_periodic,True) if args.rpc_no_periodic else rpc_window(args.rpc_window) if args.rpc_window else probe(args.probe) if args.probe else full(args.resume,True) if args.resume else full(args.full) if args.full else run()
