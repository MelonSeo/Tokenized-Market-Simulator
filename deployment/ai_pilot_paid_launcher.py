"""Owned PAID v2 supervisor: prepare -> targeted controlled probe -> explicit paid permit.

No key is accepted by prepare/probe. PAID first authorizes in a network-none Linux
gate without a key mount; only then may an owned relay get outbound access.
Existing USER data, original trial ledgers/STOP and prior snapshots stay readonly.
"""
import argparse
import hashlib
import json
import re
import secrets
import shutil
import subprocess
import sys
import time
from contextlib import contextmanager
from pathlib import Path

from ai_pilot_unpaid_live import ROOT,cmd,env,save,restrict_owned_folder
from ai_pilot_linux import PG,LOCK,stage_source
from ai_pilot_paid_environment import LABEL
from ai_pilot_fixture_audit import validate_source
from ai_pilot_chrome_resume import local_database,digest
from ai_pilot_linux_browser_probe import profile,PROFILE_SHA
from ai_pilot_preflight import need
from ai_pilot_linux_inner import verify_source
from product_read_resources import Observer,ResourceError
import product_read_network as network

# Only frozen read-only evaluation copies: fixture-writing and user chains retain
# their own mining settings. Preserve every generated historical snapshot.
READ_ONLY_ANVIL_MIB=1024


def frozen_anvil_resources():
    # No swap expansion to hide an undersized cap or consume the user's C drive.
    return ['--memory='+str(READ_ONLY_ANVIL_MIB)+'m','--memory-swap='+str(READ_ONLY_ANVIL_MIB)+'m']


def require_frozen_anvil_profile(item):
    cfg=item.get('HostConfig',{});args=item.get('Config',{}).get('Cmd',[])
    if args and args[0]=='-c':
        command=args[1] if len(args)>1 else ''
        mining=bool(re.search(r'(?:^|\s)--no-mining(?:\s|$)',command)) and '--block-time' not in command
        history='--preserve-historical-states' in command and '--prune-history' not in command
    else:
        mining='--no-mining' in args and '--block-time' not in args
        history='--preserve-historical-states' in args and '--prune-history' not in args
    need(cfg.get('Memory')==READ_ONLY_ANVIL_MIB*1024**2 and cfg.get('MemorySwap')==READ_ONLY_ANVIL_MIB*1024**2
         and mining and history,'V2_PAID_FROZEN_ANVIL_PROFILE_REQUIRED')


def observe_paid_anvil(folder,meta,*,sample=False):
    """Host supervisor only; no RPC, key, raw logs, policy changes or retries."""
    item=owned(meta['names']['anvil'],meta['owner'],meta['images']['anvil'])
    require_frozen_anvil_profile(item)
    state=item['State'];event={'monotonic':time.monotonic(),'utcNs':time.time_ns(),
        'running':state['Running'],'oomKilled':state['OOMKilled'],'exitCode':state['ExitCode'],
        'memoryLimitBytes':item['HostConfig']['Memory']}
    if not state['Running'] or state['OOMKilled']:
        save(Path(folder)/'anvil-health-failure.json',event)
        raise ValueError('V2_PAID_ANVIL_UNAVAILABLE')
    if sample:
        stats=json.loads(cmd(['docker','stats','--no-stream','--format','{{json .}}',meta['names']['anvil']],8))
        event['memoryUsage']=stats['MemUsage'];event['memoryPercent']=stats['MemPerc']
        with (Path(folder)/'anvil-memory.jsonl').open('a',encoding='utf8') as f:
            f.write(json.dumps(event)+'\n');f.flush()
    return event


def record_paid_anvil_termination(folder,meta):
    item=owned(meta['names']['anvil'],meta['owner'],meta['images']['anvil'])
    state=item['State'];value={'running':state['Running'],'oomKilled':state['OOMKilled'],
        'exitCode':state['ExitCode'],'memoryLimitBytes':item['HostConfig']['Memory'],
        'startedAt':state.get('StartedAt'),'finishedAt':state.get('FinishedAt')}
    save(Path(folder)/'anvil-termination.json',value)
    return value


def owned(name,owner,image):
    item=json.loads(cmd(['docker','inspect',name]))[0]
    need(item['Name']=='/'+name and item['Config']['Labels'].get(LABEL)==owner and item['Image']==image,'V2_PAID_OWNER_MISMATCH')
    need(not item['HostConfig']['Privileged'] and not item['HostConfig'].get('CapAdd') and not item['HostConfig']['PortBindings'],'V2_PAID_CONTAINER_ISOLATION')
    return item


def stop(name,owner,image,grace=15):
    item=owned(name,owner,image)
    if item['State']['Running']:cmd(['docker','stop','--time',str(grace),name],grace+15)
    need(not owned(name,owner,image)['State']['Running'],'V2_PAID_STOP_UNCONFIRMED')
    return 'STOPPED'


def cleanup_owned(meta,resources):
    result={}
    for name,image,grace in reversed(resources):
        try:result[name]=stop(name,meta['owner'],image,grace)
        except Exception:result[name]='STOP_UNVERIFIED'
    return result


@contextmanager
def execution_claim(base,kind):
    claim=base/'execution.lock';token=secrets.token_hex(8)
    save(claim,{'kind':kind,'token':token})
    try:yield
    finally:
        # Retain the claim if *any* same-owner process may still be running.
        owner=base.name
        running=cmd(['docker','ps','-q','--filter','label='+LABEL+'='+owner])
        if not running:claim.rename(base/('execution-closed-'+token+'.json'))


def local_url(role,owner):
    need(re.fullmatch(r'pilot-paid-[0-9a-f]{16}',owner),'V2_PAID_OWNER')
    need(re.fullmatch(r'[a-z0-9_]+',role),'V2_PAID_DATABASE_ROLE')
    database='exchange_ai_pilot_'+role+'_'+owner[11:]
    need(len(database.encode('ascii'))<=63,'V2_PAID_DATABASE_IDENTIFIER_LENGTH')
    return 'jdbc:postgresql://127.0.0.1:5432/'+database+'?connectTimeout=2&socketTimeout=5'


def provider_check(value,permit,now=None):
    """Attest the maximum effective per-token price (including cache writes).

    This is the frozen conservative envelope, not an assertion that the listed
    uncached price equals that envelope. Never lowers the reservation rates.
    """
    now=time.time() if now is None else now
    expected={'llmModel':'gpt-5.6-terra','embeddingModel':'text-embedding-3-small',
              'inputNusdPerToken':2500,'outputNusdPerToken':12000,'embeddingNusdPerToken':20,
              'serviceTier':'default','regionalEndpoint':False}
    need(type(value) is dict and set(value)==set(expected)|{'verifiedAt','expiresAt','officialSources','reviewer'},'V2_PROVIDER_PRICE_CHECK_REQUIRED')
    need(all(type(value[k]) is type(v) and value[k]==v for k,v in expected.items()),'V2_PROVIDER_PRICE_OR_MODEL_MISMATCH')
    need(type(value['verifiedAt']) is int and type(value['expiresAt']) is int and value['verifiedAt']<=now<value['expiresAt']<=value['verifiedAt']+14400
         and type(permit.get('expiresAt')) is int and permit['expiresAt']<=value['expiresAt'],'V2_PROVIDER_PRICE_CHECK_EXPIRED')
    need(isinstance(value['reviewer'],str) and bool(value['reviewer'].strip()) and type(value['officialSources']) is list and value['officialSources']
         and all(isinstance(u,str) and u.startswith(('https://openai.com/','https://platform.openai.com/docs/','https://developers.openai.com/')) for u in value['officialSources']),
         'V2_PROVIDER_PRICE_SOURCE_REQUIRED')
    return True


def settings(owner,password,mode,session,roles):
    p={'mode':mode,'repo':'/work','run':session,'python':'/usr/local/bin/python','knowledgeRoot':'/work/docs/ai-knowledge',
       'legacyManifest':'/work/docs/ai/ingest-manifest.json','pilotManifest':'/work/docs/ai/ingest-manifest-pilot.json'}
    for key,role in roles.items():p[key]={'jdbcUrl':local_url(role,owner),'dbUser':'pilot','dbPassword':password}
    return p


def create(owner,role,image,net,options=(),command=()):
    need(re.fullmatch(r'[a-z0-9-]+',role),'V2_PAID_RESOURCE_ROLE')
    name=owner+'-'+role
    cmd(['docker','create','--pull=never','--name',name,'--label',LABEL+'='+owner,'--network',net,*options,image,*command])
    owned(name,owner,image);return name


def mount(path,target,readonly=True):
    return 'type=bind,source='+str(Path(path).resolve())+',target='+target+(',readonly' if readonly else '')


def sql(pg,statement):
    return cmd(['docker','exec',pg,'psql','-X','-v','ON_ERROR_STOP=1','-U','pilot','-d','postgres','-tAc',statement])


def start_data(folder,meta):
    # Never silently restart or docker-update the preserved 384MiB failed node.
    # A new owned copy with the validated profile must be bound by the operator.
    require_frozen_anvil_profile(owned(meta['names']['anvil'],meta['owner'],meta['images']['anvil']))
    for role in ('pg','anvil'):
        name=meta['names'][role];owned(name,meta['owner'],meta['images'][role]);cmd(['docker','start',name])
    end=time.monotonic()+45
    while True:
        try:
            need(sql(meta['names']['pg'],'SELECT 1')=='1','V2_PAID_PG_READY')
            chain=cmd(['docker','exec',meta['names']['anvil'],'cast','chain-id','--rpc-url','http://127.0.0.1:8545'],5)
            need(chain=='31337','V2_PAID_CHAIN_ID');return
        except Exception:
            need(time.monotonic()<end,'V2_PAID_DATA_READY_DEADLINE');time.sleep(.3)


def browser_admission_reply(folder,observer):
    """A refusal must reach the child so it can STOP and preserve final evidence.

    Never translate a missing resource measurement into permission. The parent
    still waits only a bounded shutdown interval after a denial.
    """
    allowed=False
    try:
        observer.sample('BEFORE_BROWSER',True);allowed=True
    except ResourceError:
        pass  # The small resource record preserves the exact failed criterion.
    save(folder/'browser-host-admission.json',{'allowed':allowed})
    return allowed


def app(folder,base,meta,role,mode,observer,*,key_reference=None):
    # Source is copied to Linux-local storage; only new owned runtime is writable.
    observer.sample('BEFORE_'+mode,True)
    if mode=='PAID':observe_paid_anvil(folder,meta,sample=True)
    # 1920MiB required headroom + 384MiB heap + 256MiB metaspace already
    # exhaust 2560MiB before native/Python/source-cache overhead. This is a cap,
    # not preallocation; Windows physical and Linux available guards are unchanged.
    opts=['--user','1000:1000','--memory=3072m','--shm-size=256m','--security-opt','seccomp='+str(profile()),
          '--mount',mount(folder,'/run/pilot',False),'--mount',mount(base,'/work/deployment/runtime/'+meta['owner'],False),
          '-e','AI_PILOT_EXECUTION='+mode,'-e','AI_PILOT_FROZEN_SERVER=true','-e','AI_PILOT_V2_HTTP_TESTS=true',
          '-e','AI_PILOT_IMAGES='+json.dumps(meta['images'],sort_keys=True,separators=(',',':')),
          '-e','AI_PILOT_V2_FOLDER=/run/pilot','-e','AI_PILOT_V2_APP=/run/pilot/trade.properties',
          '-e','AI_PILOT_V2_CONFIG=/run/pilot/v2.private.json','--entrypoint','python']
    if key_reference is not None:
        need(mode=='PAID' and (folder/'authorized.json').is_file(),'V2_PAID_GATE_REQUIRED_BEFORE_KEY_MOUNT')
        opts+=['--mount',mount(key_reference,'/run/approved-provider-key')]
    if 'parentSession' in meta:
        parent=Path(meta['parentSession']);scope=ROOT/'deployment/runtime'/meta['owner']
        need(parent.resolve() in {scope/'session',scope/'continuation-dcefa791d03e57e2/session',scope/'continuation-1dd8aa3b6d0eb93e/session',scope/'continuation-111a94ae5a0bd9c0/recovery-e7a47fc5a1ab/session',scope/'continuation-3e9742bfa5d17456/session',scope/'continuation-b3ebc4e68f260ec7/session',scope/'continuation-60c4912e2e9417d9/session'} and not parent.is_symlink(),'V2_CARRY_PARENT_SCOPE')
        opts+=['--mount',mount(parent,'/run/pilot-parent')]
    if 'rootSession' in meta:
        root=Path(meta['rootSession']);need(root.resolve()==ROOT/'deployment/runtime'/meta['owner']/'session' and not root.is_symlink(),'V2_CARRY_ROOT_SCOPE')
        opts+=['--mount',mount(root,'/run/pilot-root')]
    if 'middleSession' in meta:
        middle=Path(meta['middleSession']);need(middle.resolve()==ROOT/'deployment/runtime'/meta['owner']/'continuation-dcefa791d03e57e2/session' and not middle.is_symlink(),'V2_CARRY_MIDDLE_SCOPE')
        opts+=['--mount',mount(middle,'/run/pilot-middle')]
    net='none' if mode.startswith('AUTHORIZE') or mode in {'CONTINUATION_PLAN','TARGET_PLAN'} else 'container:'+meta['names']['pg']
    name=create(meta['owner'],role,meta['images']['app'],net,opts,['-B','-S','/work/deployment/ai_pilot_paid_inner.py'])
    try:
        cmd(['docker','cp',str((base/'source').resolve())+'/.',name+':/work'],120)
        cmd(['docker','start',name]);end=time.monotonic()+(120 if mode=='AUTHORIZE' else 7200);next_memory_sample=0.
        while owned(name,meta['owner'],meta['images']['app'])['State']['Running']:
            if mode=='PAID':
                now=time.monotonic();observe_paid_anvil(folder,meta,sample=now>=next_memory_sample)
                if now>=next_memory_sample:next_memory_sample=now+10
            if (folder/'browser-host-request.json').exists() and not (folder/'browser-host-admission.json').exists():
                if not browser_admission_reply(folder,observer):
                    end=min(end,time.monotonic()+75)
            need(time.monotonic()<end,'V2_PAID_OWN_SUPERVISOR_DEADLINE');time.sleep(.5)
        item=owned(name,meta['owner'],meta['images']['app'])
        if mode=='PAID':observe_paid_anvil(folder,meta,sample=True)
        logs=subprocess.run(['docker','logs',name],env=env(),capture_output=True,timeout=10)
        with (folder/(role+'.docker.private.log')).open('xb') as out:out.write(logs.stdout+logs.stderr)
        need(item['State']['ExitCode']==0,'V2_PAID_INNER_FAILED')
        return name
    finally:
        primary=sys.exception() is not None
        try:state=stop(name,meta['owner'],meta['images']['app'],30)
        except Exception:state='STOP_UNVERIFIED'
        try:save(folder/(role+'-stop.json'),{'state':state,'primaryFailurePreserved':primary})
        except Exception:
            if not primary:raise ValueError('V2_PAID_STOP_EVIDENCE_WRITE_FAILED') from None
            print('{"code":"V2_PAID_STOP_EVIDENCE_WRITE_FAILED","primaryFailurePreserved":true}',file=sys.stderr)
        if not primary:need(state=='STOPPED','V2_PAID_CLEANUP_UNVERIFIED')


def prepare(source):
    original,old=validate_source(source) # exact stopped owned UNPAID fixture only
    baseline=json.loads((original/'final-fingerprint.json').read_text())
    need(baseline.get('financialUnchanged') is True and baseline.get('cStateUnchanged') is True,'V2_PAID_FROZEN_FIXTURE_REQUIRED')
    owner='pilot-paid-'+secrets.token_hex(8);base=ROOT/'deployment/runtime'/owner;base.mkdir();restrict_owned_folder(base)
    observer=Observer(base/'resources.jsonl');observer.sample('PREPARE_ADMISSION',True)
    images={'app':cmd(['docker','image','inspect','tms-ai-pilot-linux:unpaid-v2','--format','{{.Id}}']),
            'pg':cmd(['docker','image','inspect',PG,'--format','{{.Id}}']),
            'anvil':cmd(['docker','image','inspect',LOCK['images']['anvil'],'--format','{{.Id}}'])}
    names={r:owner+'-'+r for r in ('internal','pgdata','state','pg','anvil')}
    meta={'owner':owner,'source':source,'images':images,'names':names,'profileSha256':PROFILE_SHA,'scope':'FROZEN_TRIAL_COPY_NEW_PAID_BUDGET_ONLY'}
    save(base/'owned.json',meta)
    watched=['session/pilot-v2.sqlite','session/session.json','chain/state.json','final-fingerprint.json']
    source_hash={p:digest(original/p) for p in watched};save(base/'source-preservation.json',source_hash)
    current=[];success=False
    try:
        observer.sample('BEFORE_PREPARE_CREATE',True)
        inv=network.inventory(lambda a,timeout=30:subprocess.run(a,env=env(),capture_output=True,timeout=timeout));save(base/'network.json',inv)
        cmd(['docker','network','create','--internal','--subnet',inv['subnet'],'--label',LABEL+'='+owner,names['internal']])
        for v in ('pgdata','state'):cmd(['docker','volume','create','--label',LABEL+'='+owner,names[v]])
        for v in ('pgdata','state'):need(json.loads(cmd(['docker','volume','inspect',names[v]]))[0]['Labels'].get(LABEL)==owner,'V2_PAID_VOLUME_OWNER')
        copier=create(owner,'copy',images['pg'],'none',['--memory=256m','--entrypoint','sh',
                    '--mount','type=volume,source='+old['names']['pgdata']+',target=/from,readonly',
                    '--mount','type=volume,source='+names['pgdata']+',target=/to'],
                    ['-c','test -z "$(find /to -mindepth 1 -print -quit)" && cp -a /from/. /to/'])
        current.append((copier,images['pg'],15));cmd(['docker','start',copier]);need(cmd(['docker','wait',copier],120)=='0','V2_PAID_COPY_FAILED')
        init=create(owner,'state-init',images['anvil'],'none',['--user','0:0','--memory=64m','--entrypoint','sh',
             '--mount','type=volume,source='+names['state']+',target=/output'],
             ['-c','test -z "$(find /output -mindepth 1 -print -quit)" && chown 1000:1000 /output && chmod 700 /output'])
        current.append((init,images['anvil'],15));cmd(['docker','start',init]);need(cmd(['docker','wait',init],20)=='0','V2_PAID_NEW_STATE_PERMISSION')
        shutil.copyfile(original/'pg.private.env',base/'pg.private.env')
        props=dict(line.split('=',1) for line in (original/'trade.properties').read_text().splitlines() if '=' in line)
        need(props.get('app.ai.api-key')=='' and local_database(props['spring.datasource.url']),'V2_PAID_SOURCE_CONFIGURATION')
        need(props.get('server.address')=='127.0.0.1' and props.get('app.price.provider')=='simulated' and props.get('app.blockchain.reconciliation.initial-delay-ms')=='2147483647'
             and props.get('app.ai.diagnosis.enabled')=='false' and props.get('app.conditional.enabled')=='false'
             and props.get('app.c-futures.direct.enabled')=='false'
             and all(props.get(k)=='http://anvil:8545' for k in ('app.blockchain.rpc-url','app.futures.rpc-url','app.c-futures.rpc-uri')),
             'V2_PAID_BACKGROUND_WRITER_OR_RPC_CONFIGURATION')
        props['spring.datasource.url']=props['spring.datasource.url'].replace('://pg:5432/','://127.0.0.1:5432/')
        props['app.jwt.secret']=secrets.token_urlsafe(48)
        password=next(line.split('=',1)[1] for line in (base/'pg.private.env').read_text().splitlines() if line.startswith('POSTGRES_PASSWORD='))
        config=settings(owner,password,'UNPAID_CONTROLLED_ONLY','/work/deployment/runtime/'+owner+'/unused-unpaid',
                        {'budget':'budget_prepare','legacy9':'legacy_prepare','pilot10':'pilot_prepare'})
        paid=settings(owner,password,'PAID_EXPLICIT_V2','/work/deployment/runtime/'+owner+'/session',
                        {'budget':'budget_paid','legacy9':'legacy_paid','pilot10':'pilot_paid'})
        props['app.ai.jdbc-url']=config['legacy9']['jdbcUrl']
        (base/'trade.properties').write_text('\n'.join(k+'='+v for k,v in props.items())+'\n')
        save(base/'v2.private.json',config);save(base/'paid-config.private.json',paid)
        save(base/'baseline-fingerprint.json',baseline)
        descriptor=json.loads((original/'bind-descriptor.private.json').read_text());save(base/'prepared-descriptor.private.json',descriptor)
        (base/'baseline').mkdir();shutil.copyfile(original/'chain/state.json',base/'baseline/state.json')
        source_dir=base/'source';manifest=stage_source(source_dir)
        artifact=source_dir/'deployment/paid-build-identity.json';identity=json.loads(artifact.read_text());identity['images']=images
        artifact.write_text(json.dumps(identity,sort_keys=True,separators=(',',':')))
        manifest['deployment/paid-build-identity.json']=digest(artifact);save(base/'source-files.json',manifest)
        pg=create(owner,'pg',images['pg'],names['internal'],['--memory=384m','--network-alias','pg','--env-file',str(base/'pg.private.env'),
                  '--mount','type=volume,source='+names['pgdata']+',target=/var/lib/postgresql/data'],['postgres','-c','shared_buffers=16MB','-c','max_connections=40'])
        current.append((pg,images['pg'],15))
        shell='if test -s /output/state.json; then source=/output/state.json; else source=/baseline/state.json; fi; exec anvil --host 0.0.0.0 --accounts 0 --chain-id 31337 --no-mining --load-state "$source" --dump-state /output/state.json --preserve-historical-states --quiet'
        anvil=create(owner,'anvil',images['anvil'],names['internal'],[*frozen_anvil_resources(),'--network-alias','anvil','--entrypoint','sh',
                 '--mount',mount(base/'baseline','/baseline'),'--mount','type=volume,source='+names['state']+',target=/output'],['-c',shell])
        current.append((anvil,images['anvil'],120));start_data(base,meta)
        for cfg in (config,paid):
            for key in ('budget','legacy9','pilot10'):
                db=cfg[key]['jdbcUrl'].split('/')[3].split('?')[0];sql(pg,'CREATE DATABASE '+db)
        app(base,base,meta,'prepare','PREPARE',observer)
        need(json.loads((base/'inner-result.json').read_text())['status']=='PREPARED_NOT_APPROVED','V2_PAID_PREPARED_EVIDENCE')
        success=True
    finally:
        primary=sys.exception() is not None
        cleanup=cleanup_owned(meta,current)
        same=all(digest(original/p)==value for p,value in source_hash.items())
        save(base/'prepare-result.json',{'status':'PREPARED_NOT_APPROVED' if success and same and all(x=='STOPPED' for x in cleanup.values()) else 'PARTIAL',
                  'owner':owner,'sourcePreserved':same,'cleanup':cleanup,'apiRequests':0,'keyReads':0,'activationAllowed':False})
        observer.sample('PREPARE_STOPPED')
        if not primary:need(success and same and all(x=='STOPPED' for x in cleanup.values()),'V2_PAID_PREPARE_OR_CLEANUP_UNVERIFIED')
    print(json.dumps({'owner':owner,'prepared':success,'apiRequests':0,'keyReads':0}));return owner


def get_owner(owner,*,require_current_source=True):
    need(re.fullmatch(r'pilot-paid-[0-9a-f]{16}',owner),'V2_PAID_OWNER')
    base=ROOT/'deployment/runtime'/owner;need(base.is_dir() and not base.is_symlink(),'V2_PAID_OWNER_FOLDER')
    meta=json.loads((base/'owned.json').read_text());need(meta['owner']==owner,'V2_PAID_OWNER')
    need(json.loads((base/'prepare-result.json').read_text())['status']=='PREPARED_NOT_APPROVED','V2_PAID_PREPARE_REQUIRED')
    if require_current_source:
        manifest=json.loads((base/'source-files.json').read_text())
        try:verify_source(ROOT,{n:h for n,h in manifest.items() if not n.startswith('backend/build/') and n!='deployment/paid-build-identity.json'})
        except Exception:raise ValueError('V2_PAID_HOST_SOURCE_DRIFT') from None
    return base,meta


def audit_names(owner):
    # READ ONLY on a failed owned copy, allowed even when its old source is retired.
    base,meta=get_owner(owner,require_current_source=False)
    observer=Observer(base/'db-name-audit-resources.jsonl');observer.sample('NAME_AUDIT_ADMISSION',True)
    pg=meta['names']['pg'];owned(pg,owner,meta['images']['pg'])
    try:
        cmd(['docker','start',pg]);end=time.monotonic()+20
        while True:
            try:
                raw=cmd(['docker','exec',pg,'psql','-X','-qAt','-v','ON_ERROR_STOP=1','-U','pilot','-d','postgres','-c',
                         "BEGIN READ ONLY; SELECT datname,length(datname) FROM pg_database WHERE datname LIKE 'exchange_ai_pilot_budget_probe_%'; COMMIT;"])
                break
            except Exception:
                need(time.monotonic()<end,'V2_PAID_NAME_AUDIT_READY');time.sleep(.25)
        rows=[{'database':r.split('|')[0],'length':int(r.split('|')[1])} for r in raw.splitlines()]
        save(base/'db-name-audit.json',{'actual':rows,'requestedLength':64,'readOnly':True})
        print(json.dumps({'actual':rows,'requestedLength':64,'readOnly':True}))
    finally:
        stop(pg,owner,meta['images']['pg']);observer.sample('NAME_AUDIT_STOPPED')


def probe(owner):
    base,meta=get_owner(owner)
    with execution_claim(base,'PROBE'):return _probe(base,meta)


def _probe(base,meta):
    owner=meta['owner']
    folder=base/('probe-'+secrets.token_hex(8));folder.mkdir();restrict_owned_folder(folder)
    observer=Observer(folder/'resources.jsonl');observer.sample('PROBE_ADMISSION',True)
    for name in ('prepared-descriptor.private.json','baseline-fingerprint.json','trade.properties','source-files.json'):shutil.copyfile(base/name,folder/name)
    config=json.loads((base/'v2.private.json').read_text());suffix='p_'+folder.name[6:]
    config['run']='/work/deployment/runtime/'+owner+'/'+folder.name+'/session'
    for key,role in (('budget','budget_'+suffix),('legacy9','legacy_'+suffix),('pilot10','pilot_'+suffix)):config[key]['jdbcUrl']=local_url(role,owner)
    save(folder/'v2.private.json',config)
    success=False
    try:
        start_data(folder,meta)
        for key in ('budget','legacy9','pilot10'):sql(meta['names']['pg'],'CREATE DATABASE '+config[key]['jdbcUrl'].split('/')[3].split('?')[0])
        app(folder,base,meta,folder.name,'PROBE',observer)
        result=json.loads((folder/'inner-result.json').read_text());need(result['status']=='PASS_PAID_ADAPTER_CONTROLLED_PROBE_NOT_QUALITY','V2_PAID_PROBE_EVIDENCE')
        paid_config=json.loads((base/'paid-config.private.json').read_text());empty={}
        for key in ('budget','legacy9','pilot10'):
            database=paid_config[key]['jdbcUrl'].split('/')[3].split('?')[0]
            value=cmd(['docker','exec',meta['names']['pg'],'psql','-X','-qAt','-v','ON_ERROR_STOP=1','-U','pilot','-d',database,
                       '-c',"BEGIN READ ONLY; SELECT count(*) FROM pg_tables WHERE schemaname NOT IN ('pg_catalog','information_schema'); COMMIT;"])
            empty[key]=value=='0'
        save(folder/'paid-databases-unactivated.json',empty);need(all(empty.values()),'V2_PAID_DATABASE_WAS_ACTIVATED')
        success=True
    finally:
        primary=sys.exception() is not None
        cleanup=cleanup_owned(meta,[(meta['names']['pg'],meta['images']['pg'],15),(meta['names']['anvil'],meta['images']['anvil'],120)])
        save(folder/'probe-result.json',{'status':'PASS' if success and all(x=='STOPPED' for x in cleanup.values()) else 'PARTIAL',
             'source':json.loads((base/'approval-request.private.json').read_text())['context']['sourceHash'],
             'cleanup':cleanup,'apiRequests':0,'keyReads':0,'activationAllowed':False})
        observer.sample('PROBE_STOPPED')
        if not primary:need(success and all(x=='STOPPED' for x in cleanup.values()),'V2_PAID_PROBE_OR_CLEANUP_UNVERIFIED')
    print(json.dumps({'owner':owner,'attempt':folder.name,'passed':success,'apiRequests':0,'keyReads':0}))


def readiness(owner):
    base,meta=get_owner(owner)
    expected=json.loads((base/'approval-request.private.json').read_text())['context']['sourceHash']
    candidates=sorted(base.glob('probe-*/probe-result.json'))
    need(bool(candidates),'V2_PAID_CONTROLLED_PROBE_REQUIRED')
    proof=next((p for p in reversed(candidates) if json.loads(p.read_text()).get('status')=='PASS'),None)
    need(proof is not None,'V2_PAID_CONTROLLED_PROBE_REQUIRED');folder=proof.parent
    p=json.loads(proof.read_text());need(p['source']==expected and p['apiRequests']==0 and p['keyReads']==0 and p['activationAllowed'] is False,'V2_PAID_PROBE_IDENTITY')
    inactive=json.loads((folder/'paid-databases-unactivated.json').read_text())
    browser_network=json.loads((folder/'browser/network-summary.private.json').read_text())['networkSummary']
    checks={
        'sourceAndArtifactVerified':json.loads((folder/'inner-start.json').read_text())['source']==expected,
        'server':json.loads((folder/'server-junit.json').read_text())=={'tests':1,'passed':1,'skipped':0,'failed':0,'causes':[]},
        'sandbox':json.loads((folder/'browser/linux-sandbox.private.json').read_text()).get('verified') is True,
        'oneRealDomProbe':json.loads((folder/'browser/six-dom-submissions.json').read_text()).get('requests')==1,
        'linuxPipe':json.loads((folder/'linux-frame-proof.json').read_text())=={'framing':True,'deadline':True,'apiRequests':0},
        'paidDatabasesInactive':set(inactive)=={'budget','legacy9','pilot10'} and all(v is True for v in inactive.values()),
        'actualAgentHttp':len([r for r in browser_network if r.get('path')=='/api/ai/agent/answers' and r.get('status')==200])==1,
        'noPaidAllocation':not (base/'session').exists(),
        'ownedProcessesStopped':not bool(cmd(['docker','ps','-q','--filter','label='+LABEL+'='+owner])),
    }
    state=json.loads((folder/'final-fingerprint.json').read_text())
    checks['financialAndCUnchanged']=state.get('financialUnchanged') is True and state.get('cStateUnchanged') is True
    checks['finalizersCompleted']=all(v=='DONE' for v in json.loads((folder/'inner-cleanup.json').read_text())['steps'].values())
    budget=json.loads((folder/'probe-budget.json').read_text())
    checks['shadowOnlyKnownUsage']=budget.get('mode')=='UNPAID' and budget.get('unknown')==0 and budget.get('stopped') is True
    result={'status':'READY_FOR_APPROVAL' if all(checks.values()) else 'PARTIAL','checks':checks,
            'owner':owner,'source':expected,'apiRequests':0,'keyReads':0,'activationAllowed':False,
            'realModelQuality':'NOT_RUN','providerAccessAndCurrentPrice':'NOT_RUN_REQUIRES_APPROVAL_AND_CHECK',
            'capUsd':'18.00','llmMaximum':92,'embeddingMaximum':70}
    print(json.dumps(result));return result


def paid(owner,permit,key_reference,prices=None):
    base,meta=get_owner(owner)
    with execution_claim(base,'PAID'):return _paid(base,meta,permit,key_reference,prices)


def _paid(base,meta,permit,key_reference,prices=None):
    owner=meta['owner'];folder=base/'paid-execution'
    # Explicit branch only; no prepare/probe accepts a key reference or approval.
    need(permit is not None and key_reference is not None,'V2_PAID_APPROVAL_REQUIRED')
    need(not (base/'session').exists() and not (base/'authorized.json').exists(),'V2_PAID_NO_AUTOMATIC_RESTART')
    expected_source=json.loads((base/'approval-request.private.json').read_text())['context']['sourceHash']
    proofs=[json.loads(p.read_text()) for p in base.glob('probe-*/probe-result.json') if not p.is_symlink() and p.stat().st_size<=16384]
    need(any(p.get('status')=='PASS' and p.get('source')==expected_source and p.get('apiRequests')==0 and p.get('keyReads')==0
             and p.get('activationAllowed') is False for p in proofs),'V2_PAID_CONTROLLED_PROBE_REQUIRED')
    observer=Observer(base/'paid-resources.jsonl');observer.sample('PAID_ADMISSION',True)
    permit=Path(permit);need(permit.is_file() and not permit.is_symlink() and permit.stat().st_size<=16384,'V2_PAID_APPROVAL_REQUIRED')
    need(prices is not None,'V2_PROVIDER_PRICE_CHECK_REQUIRED')
    prices=Path(prices);need(prices.is_file() and not prices.is_symlink() and prices.stat().st_size<=16384,'V2_PROVIDER_PRICE_CHECK_REQUIRED')
    provider_check(json.loads(prices.read_text()),json.loads(permit.read_text()))
    folder.mkdir();restrict_owned_folder(folder)
    for name in ('prepared-binding.private.json','prepared-descriptor.private.json','baseline-fingerprint.json','source-files.json','approval-request.private.json','trade.properties'):
        shutil.copyfile(base/name,folder/name)
    shutil.copyfile(permit,folder/'approved-permit.json')
    shutil.copyfile(prices,folder/'provider-check.json')
    # Gate has no network and no key mount. This command is never called in unpaid preparation.
    shutil.copyfile(base/'paid-config.private.json',folder/'v2.private.json')
    app(folder,base,meta,'authorize','AUTHORIZE',observer)
    need(json.loads((folder/'authorized.json').read_text())['authorized'] is True,'V2_PAID_APPROVAL_REQUIRED')
    # Key metadata is not inspected until the Linux gate has accepted the bound permit.
    key_reference=Path(key_reference);need(key_reference.is_file() and not key_reference.is_symlink(),'V2_PAID_KEY_REFERENCE')
    inv=network.inventory(lambda a,timeout=30:subprocess.run(a,env=env(),capture_output=True,timeout=timeout))
    run_paid_infrastructure(folder,base,meta,observer,key_reference,inv['subnet'])


def run_paid_infrastructure(folder,base,meta,observer,key_reference,subnet):
    """Called only AFTER the no-key Linux approval gate; separately failure-testable."""
    owner=meta['owner'];outbound=owner+'-outbound';relay=owner+'-egress'
    try:
        cmd(['docker','network','create','--subnet',subnet,'--label',LABEL+'='+owner,outbound])
        create(owner,'egress',meta['images']['app'],meta['names']['internal'],['--user','1000:1000','--memory=64m','--pids-limit=32','--cap-drop=ALL',
               '--security-opt=no-new-privileges:true','--read-only','--network-alias','pilot-egress','--mount',mount(base/'source/deployment','/relay'),'--entrypoint','python'],
               ['-B','-S','/relay/ai_pilot_egress.py','--approved-egress'])
        cmd(['docker','network','connect',outbound,relay]);cmd(['docker','start',relay])
        start_data(folder,meta);app(folder,base,meta,'paid','PAID',observer,key_reference=key_reference)
    finally:
        primary=sys.exception() is not None
        result=cleanup_owned(meta,[(relay,meta['images']['app'],15),(meta['names']['pg'],meta['images']['pg'],15),(meta['names']['anvil'],meta['images']['anvil'],120)])
        save(folder/'cleanup.json',result);observer.sample('PAID_STOPPED')
        if not primary:need(all(x=='STOPPED' for x in result.values()),'V2_PAID_CLEANUP_UNVERIFIED')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);sub=parser.add_subparsers(dest='op',required=True)
    p=sub.add_parser('prepare');p.add_argument('--source',required=True)
    p=sub.add_parser('probe');p.add_argument('--owner',required=True)
    p=sub.add_parser('readiness');p.add_argument('--owner',required=True)
    p=sub.add_parser('audit-names');p.add_argument('--owner',required=True)
    p=sub.add_parser('paid');p.add_argument('--owner',required=True);p.add_argument('--permit',required=True);p.add_argument('--key-reference',required=True);p.add_argument('--provider-check',required=True)
    a=parser.parse_args()
    try:
        if a.op=='prepare':prepare(a.source)
        elif a.op=='probe':probe(a.owner)
        elif a.op=='readiness':readiness(a.owner)
        elif a.op=='audit-names':audit_names(a.owner)
        else:paid(a.owner,a.permit,a.key_reference,a.provider_check)
    except Exception as error:
        code=str(error)
        if not re.fullmatch(r'V2_[A-Z0-9_]+',code):code='V2_PAID_LAUNCH_FAILED'
        print(json.dumps({'status':'PARTIAL','code':code,'type':type(error).__name__}));raise SystemExit(1)
