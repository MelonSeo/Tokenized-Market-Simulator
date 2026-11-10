"""Explicit user-authorized carry-forward of the same US$18 allocation.

Original STOP/SQLite/source remain intact. No generic retry/reset operation.
"""
import argparse,json,secrets,shutil,subprocess,time,re,sys
from pathlib import Path
from ai_pilot_unpaid_live import ROOT,save,cmd,env,restrict_owned_folder
from ai_pilot_paid_launcher import get_owner,owned,stop,app,execution_claim,create,mount,start_data,cleanup_owned,provider_check,record_paid_anvil_termination
from ai_pilot_linux import stage_source
from ai_pilot_linux_inner import verify_source
from ai_pilot_continuation import read,sha,inspect_prefix,inspect_five,inspect_twelve
from ai_pilot_paid_environment import LABEL
from ai_pilot_preflight import need,canonical
from product_read_resources import Observer
import product_read_network as network
import hashlib

OWNER='pilot-paid-56ce1e0e1611a222'
KEY='71e135c80c95ef1166c5408acf929fd966c493ac0506dc040f6e73ea91c4d974'
FIVE='continuation-dcefa791d03e57e2'
TWELVE='continuation-1dd8aa3b6d0eb93e'
HELD='continuation-111a94ae5a0bd9c0/recovery-e7a47fc5a1ab'
REVIEWED='continuation-3e9742bfa5d17456'
OOM='continuation-b3ebc4e68f260ec7'


def prepare_held(package,exception_file,*,reviewed=False,oom=False):
    """AFTER a separate historical-UNKNOWN exception approval, never implicit in prepare."""
    from ai_pilot_held_resume import inspect as inspect_held,validate_exception,digest,runtime_fingerprint
    from ai_pilot_channel import source_fingerprint
    need(not (reviewed and oom),'V2_CARRY_PREFIX_SCOPE')
    base,unused=parent();previous=base/(OOM if oom else REVIEWED if reviewed else HELD);package=Path(package)
    request=read(package/'request.json');old=read(previous/'session/session.json')
    # Validate approval before any PG start, child folder, claim or activation.
    prefix='prefix-thirty-two.json' if oom else 'prefix-twenty-three.json' if reviewed else 'prefix-sixteen.json'
    if oom:
        from ai_pilot_oom_resume import inspect as inspect_oom
        expected=inspect_oom(previous/'session',read(package/prefix),read(package/'pg-rows.json'),read(package/'termination.json'),read(package/'history.json'),execution=package/'parent-execution')
    elif reviewed:
        from ai_pilot_review_resume import inspect as inspect_reviewed
        expected=inspect_reviewed(previous/'session',read(package/prefix),read(package/'pg-rows.json'),read(package/'termination.json'),read(package/'history.json'))
    else:expected=inspect_held(previous/'session',read(package/prefix),read(package/'pg-rows.json'),read(package/'termination.json'))
    m=read(previous/'owned.json')
    need(request['proposal']==expected and request['hostSource']==source_fingerprint()
         and request['newSource']==runtime_fingerprint(ROOT,m['images']),'V2_HELD_PROPOSAL_DRIFT')
    exception=read(exception_file,16384);validate_exception(expected,exception,request['newSource'],request['childId'])
    with execution_claim(base,'HELD_B5_PREPARE_AFTER_EXCEPTION'):
        m=read(previous/'owned.json');need(not (previous/'session/CONTINUATION_CLAIM.json').exists(),'V2_CARRY_ALREADY_CLAIMED')
        obs=Observer(base/('held-admission-'+secrets.token_hex(4)+'.jsonl'));obs.sample('HELD_PREPARE_ADMISSION',True)
        live=pg_rows(m);need({r['id']:(r['kind'],r['reserved'],r['state']) for r in live}==
            {r['id']:(r['kind'],r['reserved'],r['state']) for r in read(package/'pg-rows.json')},'V2_HELD_PG_DRIFT')
        child_id=request['childId'];child=base/('continuation-'+child_id);child.mkdir();restrict_owned_folder(child);anchor=child/'anchor';anchor.mkdir()
        for n in (prefix,'pg-rows.json','termination.json')+(('history.json',) if reviewed or oom else ()):shutil.copyfile(package/n,anchor/n)
        if reviewed or oom:
            for name,expected_sha in expected['executionFiles'].items():
                source=(package/'parent-execution' if oom else previous)/name
                need(sha(source)==expected_sha,'V2_REVIEWED_EVIDENCE_CHANGED')
                if oom and not name.startswith('resume-evidence/'):
                    need(sha(previous/name)==expected_sha,'V2_REVIEWED_EVIDENCE_CHANGED')
                destination=anchor/'parent-execution'/name;destination.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,destination)
        save(anchor/'held-exception.json',exception)
        d={**expected,'childId':child_id,'anchorFiles':{p.name:sha(p) for p in anchor.iterdir() if p.is_file()}}
        save(child/'continuation.json',d)
        m={**m,'parentSession':str((previous/'session').resolve()),'childId':child_id};m.pop('rootSession',None);m.pop('middleSession',None)
        if oom:
            from ai_pilot_paid_launcher import frozen_anvil_resources
            baseline=base/'baseline'
            proof=read(package/'parent-execution/resume-evidence/frozen-copy-proof.json')
            need(sha(baseline/'state.json')==proof['snapshot']['sourceSha256'],'V2_OOM_BASELINE_CHANGED')
            volume=OWNER+'-oom-state-'+child_id
            cmd(['docker','volume','create','--label',LABEL+'='+OWNER,volume])
            need(json.loads(cmd(['docker','volume','inspect',volume]))[0]['Labels'].get(LABEL)==OWNER,'V2_PAID_VOLUME_OWNER')
            init=create(OWNER,'oom-state-init-'+child_id,m['images']['anvil'],'none',[
                '--user','0:0','--memory=64m','--entrypoint','sh','--mount','type=volume,source='+volume+',target=/output'],
                ['-c','test -z "$(find /output -mindepth 1 -print -quit)" && chown 1000:1000 /output && chmod 700 /output'])
            try:
                cmd(['docker','start',init]);need(cmd(['docker','wait',init],20)=='0','V2_PAID_NEW_STATE_PERMISSION')
            finally:stop(init,OWNER,m['images']['anvil'],10)
            node=create(OWNER,'oom-anvil-'+child_id,m['images']['anvil'],m['names']['internal'],[
                *frozen_anvil_resources(),'--network-alias','anvil','--entrypoint','anvil','--mount',mount(baseline,'/baseline'),
                '--mount','type=volume,source='+volume+',target=/output'],
                ['--host','0.0.0.0','--accounts','0','--chain-id','31337','--no-mining','--load-state','/baseline/state.json',
                 '--dump-state','/output/state.json','--preserve-historical-states','--quiet'])
            m={**m,'names':{**m['names'],'anvil':node,'state':volume}}
        save(child/'owned.json',m)
        for n in ('prepared-descriptor.private.json','prepared-binding.private.json','baseline-fingerprint.json','trade.properties','v2.private.json'):
            shutil.copyfile(previous/n,child/n)
        manifest=read(package/'source-files.json');verify_source(package/'source',manifest)
        need(source_fingerprint(package/'source')==request['newSource'],'V2_HELD_STAGED_SOURCE_DRIFT')
        shutil.copytree(package/'source',child/'source');verify_source(child/'source',manifest);save(child/'source-files.json',manifest)
        app(child,child,m,'held-plan-'+child_id,'CONTINUATION_PLAN',obs)
        need(read(child/'approval-request.private.json')['context']['sourceHash']==request['newSource'],'V2_HELD_STAGED_SOURCE_DRIFT')
        count=32 if oom else 23 if reviewed else 16
        save(child/'prepared.json',{'status':'CARRY_PREPARED_NOT_ACTIVATED','priorRequests':count,'remainingRequests':57-count,'newBudget':False,'keyReads':0,'providerRequests':0})
        print(json.dumps({'child':child.name,'status':'PREPARED_NOT_ACTIVATED','newBudget':False}))

def parent():
    base,meta=get_owner(OWNER,require_current_source=False)
    verify_source(base/'source',read(base/'source-files.json'))
    need(read(base/'paid-execution/inner-failure.json')['code']=='V2_SEMANTIC_REVIEW_REQUIRED','V2_CARRY_STOP_REASON')
    need(not cmd(['docker','ps','-q','--filter','label='+LABEL+'='+OWNER]),'V2_CARRY_PARENT_RUNNING')
    return base,meta

def pg_rows(meta):
    pg=meta['names']['pg'];owned(pg,OWNER,meta['images']['pg']);cmd(['docker','start',pg])
    database=read(ROOT/'deployment/runtime'/OWNER/'paid-config.private.json')['budget']['jdbcUrl'].split('/')[3].split('?')[0]
    need(re.fullmatch(r'exchange_ai_pilot_budget_paid_[0-9a-f]{16}',database),'V2_CARRY_DATABASE')
    end=time.monotonic()+30
    try:
        while True:
            try:
                raw=cmd(['docker','exec','-e','PGOPTIONS=-c default_transaction_read_only=on',pg,'psql','-XqAt','-v','ON_ERROR_STOP=1','-U','pilot','-d',database,'-c',
                    "SELECT coalesce(json_agg(json_build_object('id',id::text,'kind',kind,'reserved',reserved,'state',state)),'[]'::json) FROM ai_provider_budget.reservation"],8)
                return json.loads(raw)
            except Exception:
                need(time.monotonic()<end,'V2_CARRY_PG_AUDIT_DEADLINE');time.sleep(.2)
    finally:stop(pg,OWNER,meta['images']['pg'],15)

def prepare(from_five=False,from_twelve=False):
    base,meta=parent()
    with execution_claim(base,'EXPLICIT_CARRY_PREPARE'):
        need(not (from_five and from_twelve),'V2_CARRY_PREFIX_SCOPE')
        previous=TWELVE if from_twelve else FIVE
        session=base/previous/'session' if from_five or from_twelve else base/'session'
        need(not (session/'CONTINUATION_CLAIM.json').exists(),'V2_CARRY_ALREADY_CLAIMED')
        child_id=secrets.token_hex(8);child=base/('continuation-'+child_id);child.mkdir();restrict_owned_folder(child)
        obs=Observer(child/'resources.jsonl');obs.sample('CARRY_PREPARE_ADMISSION',True)
        request=base/'paid-execution/reviews'/(KEY+'.request.private.json')
        replay=base/'first-response-revalidation';receipt=replay/(KEY+'.receipt.json');proof=replay/'revalidation-proof.json'
        rows=pg_rows(meta)
        if from_five or from_twelve:
            verify_source(base/previous/'source',read(base/previous/'source-files.json'))
            bundle=read(base/previous/('twelve-prefix-reviewed.json' if from_twelve else 'five-prefix-reviewed.json'))
            old,desc=(inspect_twelve if from_twelve else inspect_five)(session,bundle,rows)
        else:old,desc=inspect_prefix(session,read(request),read(receipt),read(proof),rows)
        anchor=child/'anchor';anchor.mkdir()
        if from_five or from_twelve:save(anchor/('prefix-twelve.json' if from_twelve else 'prefix-five.json'),bundle)
        else:
            for source,name in ((request,'prefix-request.json'),(receipt,'receipt.json'),(proof,'revalidation-proof.json')):shutil.copyfile(source,anchor/name)
        save(anchor/'pg-rows.json',rows)
        desc.update({'version':'tms-pilot-carry-twelve-v3' if from_twelve else 'tms-pilot-carry-five-v2' if from_five else 'tms-pilot-carry-pf1a-v1','childId':child_id,'anchorFiles':{p.name:sha(p) for p in anchor.iterdir()}})
        if from_five or from_twelve:desc['rootFiles']={n:sha(base/'session'/n) for n in ('pilot-v2.sqlite','session.json','STOP','CONTINUATION_CLAIM.json')}
        if from_twelve:desc['middleFiles']={n:sha(base/FIVE/'session'/n) for n in ('pilot-v2.sqlite','session.json','STOP','CONTINUATION_CLAIM.json')}
        save(child/'continuation.json',desc)
        meta={**meta,'parentSession':str(session.resolve()),'childId':child_id}
        if from_five or from_twelve:meta['rootSession']=str((base/'session').resolve())
        if from_twelve:meta['middleSession']=str((base/FIVE/'session').resolve())
        save(child/'owned.json',meta)
        for name in ('prepared-descriptor.private.json','prepared-binding.private.json','baseline-fingerprint.json','trade.properties'):
            shutil.copyfile(base/name,child/name)
        shutil.copyfile(base/'paid-config.private.json',child/'v2.private.json')
        manifest=stage_source(child/'source');artifact=child/'source/deployment/paid-build-identity.json'
        identity=read(artifact);identity['images']=meta['images'];artifact.write_text(json.dumps(identity,sort_keys=True,separators=(',',':')),encoding='utf8')
        manifest['deployment/paid-build-identity.json']=sha(artifact);save(child/'source-files.json',manifest)
        app(child,child,meta,'carry-plan-'+child_id,'CONTINUATION_PLAN',obs)
        count=12 if from_twelve else 5 if from_five else 1
        save(child/'prepared.json',{'status':'CARRY_PREPARED_NOT_ACTIVATED','parentReservedNusd':desc['priorReservedNusd'],'priorRequests':count,'remainingRequests':57-count,
             'priorLlmCalls':desc['priorLlmCalls'],'priorEmbeddingCalls':desc['priorEmbeddingCalls'],'newBudget':False,'keyReads':0,'providerRequests':0})
        print(json.dumps({'child':child.name,'status':'PREPARED_NOT_ACTIVATED','newBudget':False}))

def child_state(name):
    need(re.fullmatch(r'continuation-[0-9a-f]{16}',name),'V2_CARRY_CHILD_SCOPE')
    base=ROOT/'deployment/runtime'/OWNER;child=base/name
    meta=read(child/'owned.json');need(meta['owner']==OWNER and meta['childId']==name[13:],'V2_CARRY_CHILD_SCOPE')
    need(read(child/'prepared.json')['status']=='CARRY_PREPARED_NOT_ACTIVATED','V2_CARRY_NOT_PREPARED')
    manifest=read(child/'source-files.json')
    verify_source(ROOT,{n:h for n,h in manifest.items() if not n.startswith('backend/build/') and n!='deployment/paid-build-identity.json'})
    return base,child,meta

def activate(name):
    base,child,meta=child_state(name)
    desc=read(child/'continuation.json')
    if desc['version'] in {'tms-pilot-held-b5-v4','tms-pilot-review-carry23-v5','tms-pilot-oom-carry32-v6'}:
        from ai_pilot_held_resume import inspect as inspect_held,validate_exception,runtime_fingerprint
        from ai_pilot_channel import source_fingerprint
        p=Path(meta['parentSession']);a=child/'anchor'
        if desc['version']=='tms-pilot-oom-carry32-v6':
            from ai_pilot_oom_resume import inspect as inspect_oom
            proposal=inspect_oom(p,read(a/'prefix-thirty-two.json'),read(a/'pg-rows.json'),read(a/'termination.json'),read(a/'history.json'),execution=a/'parent-execution')
        elif desc['version']=='tms-pilot-review-carry23-v5':
            from ai_pilot_review_resume import inspect as inspect_reviewed
            proposal=inspect_reviewed(p,read(a/'prefix-twenty-three.json'),read(a/'pg-rows.json'),read(a/'termination.json'),read(a/'history.json'),execution=a/'parent-execution')
        else:proposal=inspect_held(p,read(a/'prefix-sixteen.json'),read(a/'pg-rows.json'),read(a/'termination.json'))
        need(all(desc.get(k)==v for k,v in proposal.items()),'V2_HELD_PROPOSAL_DRIFT')
        validate_exception(proposal,read(a/'held-exception.json'),runtime_fingerprint(ROOT,meta['images']),meta['childId'])
    with execution_claim(base,'EXPLICIT_CARRY_ACTIVATION'):
        need(not (child/'session').exists(),'V2_CARRY_NO_RESTART')
        obs=Observer(child/('activation-resources-'+secrets.token_hex(4)+'.jsonl'));obs.sample('CARRY_ACTIVATION_ADMISSION',True)
        plan=read(child/'approval-request.private.json');now=int(time.time())
        permit={**plan['context'],'approvalId':plan['approvalId'],'paidApproved':True,'approvedAt':now,'expiresAt':now+10800,'keyFile':'/run/approved-provider-key'}
        prices={'llmModel':'gpt-5.6-terra','embeddingModel':'text-embedding-3-small','inputNusdPerToken':2500,'outputNusdPerToken':12000,
                'embeddingNusdPerToken':20,'serviceTier':'default','regionalEndpoint':False,'verifiedAt':now,'expiresAt':now+10800,
                'reviewer':'Codex official-docs carry-forward verification','officialSources':['https://developers.openai.com/api/docs/models/gpt-5.6-terra','https://developers.openai.com/api/docs/models/text-embedding-3-small']}
        provider_check(prices,permit);save(child/'approved-permit.json',permit);save(child/'provider-check.json',prices)
        claim={'childId':meta['childId'],'source':permit['sourceHash'],'allocation':permit['allocation'],'continuationSha256':permit['continuationSha256']}
        save(Path(meta['parentSession'])/'CONTINUATION_CLAIM.json',claim) # Exclusive sibling barrier; original files untouched.
        app(child,child,meta,'carry-authorize-'+meta['childId'],'AUTHORIZE_CONTINUATION',obs)
        need(read(child/'authorized.json')['sameBudget'] is True,'V2_CARRY_AUTHORIZATION')
        print(json.dumps({'child':name,'status':'AUTHORIZED_SAME_ALLOCATION','newBudget':False,'apiRequests':0,'keyReads':0}))

def run(name,key_reference):
    base,child,meta=child_state(name)
    with execution_claim(base,'EXPLICIT_CARRY_RUN'):
        need(read(child/'authorized.json')['sameBudget'] is True,'V2_CARRY_AUTHORIZATION')
        provider_check(read(child/'provider-check.json'),read(child/'approved-permit.json'))
        obs=Observer(child/'run-resources.jsonl');obs.sample('CARRY_RUN_ADMISSION',True)
        need(not (child/'inner-start.json').exists(),'V2_CARRY_NO_RESTART')
        need(Path(key_reference).is_file() and not Path(key_reference).is_symlink(),'V2_PAID_KEY_REFERENCE')
        subnet=network.inventory(lambda a,timeout=30:subprocess.run(a,env=env(),capture_output=True,timeout=timeout))['subnet']
        net=OWNER+'-carry-wan-'+meta['childId'];role='carry-relay-'+meta['childId'];relay=OWNER+'-'+role
        try:
            cmd(['docker','network','create','--subnet',subnet,'--label',LABEL+'='+OWNER,net])
            create(OWNER,role,meta['images']['app'],meta['names']['internal'],['--user','1000:1000','--memory=64m','--pids-limit=32','--cap-drop=ALL',
               '--security-opt=no-new-privileges:true','--read-only','--network-alias','pilot-egress','--mount',mount(child/'source/deployment','/relay'),'--entrypoint','python'],
               ['-B','-S','/relay/ai_pilot_egress.py','--approved-egress'])
            cmd(['docker','network','connect',net,relay]);cmd(['docker','start',relay])
            start_data(child,meta);app(child,child,meta,'carry-run-'+meta['childId'],'PAID',obs,key_reference=key_reference)
        finally:
            primary=sys.exception() is not None
            finish_run(child,meta,obs,relay,primary=primary)


def finish_run(child,meta,observer,relay,*,primary):
    """Keep the first failure; secondary collection failures never authorize success."""
    errors=[]
    try:
        cleanup=cleanup_owned(meta,[(relay,meta['images']['app'],15),(meta['names']['pg'],meta['images']['pg'],15),(meta['names']['anvil'],meta['images']['anvil'],120)])
        if not cleanup or not all(v=='STOPPED' for v in cleanup.values()):errors.append('V2_PAID_CLEANUP_UNVERIFIED')
        save(child/'carry-cleanup.json',cleanup)
    except Exception:errors.append('V2_PAID_CLEANUP_UNVERIFIED')
    try:observer.sample('CARRY_STOPPED')
    except Exception:errors.append('V2_PAID_FINAL_RESOURCE_OBSERVATION_FAILED')
    try:
        terminal=record_paid_anvil_termination(child,meta)
        if terminal['running'] or terminal['oomKilled'] or terminal['exitCode']!=0:errors.append('V2_PAID_ANVIL_TERMINATION_FAILED')
    except Exception:errors.append('V2_PAID_ANVIL_TERMINATION_UNVERIFIED')
    if errors:
        try:save(child/'carry-terminal-failure.json',{'codes':errors,'primaryFailurePreserved':primary})
        except Exception:
            if not primary:raise ValueError('V2_PAID_TERMINAL_EVIDENCE_WRITE_FAILED') from None
            print('{"code":"V2_PAID_TERMINAL_EVIDENCE_WRITE_FAILED","primaryFailurePreserved":true}',file=sys.stderr)
        if not primary:raise ValueError(errors[0])

if __name__=='__main__':
    p=argparse.ArgumentParser();s=p.add_subparsers(dest='op',required=True);q=s.add_parser('prepare');q.add_argument('--from-five',action='store_true');q.add_argument('--from-twelve',action='store_true')
    a=s.add_parser('activate');a.add_argument('--child',required=True)
    a=s.add_parser('run');a.add_argument('--child',required=True);a.add_argument('--key-reference',required=True)
    h=s.add_parser('prepare-held');h.add_argument('--package',required=True);h.add_argument('--exception-file',required=True)
    h=s.add_parser('prepare-reviewed');h.add_argument('--package',required=True);h.add_argument('--exception-file',required=True)
    h=s.add_parser('prepare-oom');h.add_argument('--package',required=True);h.add_argument('--exception-file',required=True)
    a=p.parse_args()
    try:
        if a.op=='prepare':prepare(a.from_five,a.from_twelve)
        elif a.op=='prepare-held':prepare_held(a.package,a.exception_file)
        elif a.op=='prepare-reviewed':prepare_held(a.package,a.exception_file,reviewed=True)
        elif a.op=='prepare-oom':prepare_held(a.package,a.exception_file,oom=True)
        elif a.op=='activate':activate(a.child)
        else:run(a.child,a.key_reference)
    except Exception as error:
        code=str(error);code=code if re.fullmatch(r'V2_[A-Z0-9_]+',code) else 'V2_CARRY_FAILED'
        print(json.dumps({'status':'PARTIAL','code':code,'type':type(error).__name__}));raise SystemExit(1)
