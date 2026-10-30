"""Separate approved $5 study; reuses bounded sender and durable guard. No retries."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import sqlite3
import sys
import time
import uuid
from ai_deadline import FrameDeadline
from contextlib import closing
from ai_evaluation_guard import EvaluationGuard, GuardError, LATEST_POLICY, LATEST_POLICY_V2, NOVEL_POLICY, _canonical, _decode
from ai_evaluation_broker import REPO, source_hash, corpus_hash, _https_sender
from ai_model_access_preflight import read_key
from ai_db_fixture import compose, write_new

PURPOSE = 'LATEST_AI_QUALITY_20261030'
INPUT_EXTENSION_APPROVAL='USER_20261030_INPUT_1500000_KEEP_OTHER_LIMITS'
NOVEL_APPROVAL='USER_20261030_NOVEL30_SEPARATE_570'


def policy_for(meta):
    policy=meta.get('policy')
    if not isinstance(policy,dict) or _canonical(policy) not in (_canonical(dict(LATEST_POLICY)),_canonical(dict(LATEST_POLICY_V2)),_canonical(dict(NOVEL_POLICY))):raise GuardError('UNAPPROVED_POLICY')
    return policy
# Explicit user waiver, one historical reservation only. It is never refunded/relabelled/replayed.
UNCERTAIN_CARRY={'runId':'eval_f3ba83e805ed46da8dff89164eadb28e',
    'requestId':'be42227d-b606-40d4-9681-fc134f874e8b','kind':'llm','input':6612,'output':400,'embedding':0,
    'cost':21330000,'outcome':'RESERVED','approval':'USER_20261030_FULL_BOUND_KEEP_WITHIN_ORIGINAL_5_USD'}

def study_source():
    paths = [Path(__file__), REPO/'backend/src/test/resources/ai/latest-acceptance-supplement.json']
    paths += [REPO/'deployment'/name for name in ('phase-a.py','ai_latest_fixture_init.py','Dockerfile.ai-latest-tools')]
    paths += [REPO/'backend/src/test/java/com/pricetrack/exchange/support/PhaseAFixture.java']
    paths += [REPO/'backend/src/test/java/com/pricetrack/exchange/futures/chain/FuturesAutoPostgresAnvilE2ETest.java']
    paths += [REPO/'deployment'/name for name in ('init-chain.sh','init-futures-fixture.sh','foundry.toml')]
    paths += [REPO/'contracts/script'/name for name in ('DeployPublic.s.sol','DeployFuturesFixture.s.sol')]
    paths += [REPO/'deployment'/n for n in ('ai_latest_compare_run.py','ai_deadline.py','ai_latest_final_run.py','ai_verified_fixture_copy.py','ai_rpc_readiness.py')]
    paths += [REPO/'backend/src/test/resources/ai/latest-quality-prompts.json']
    paths += [REPO/'backend/src/test/java/com/pricetrack/exchange/ai/LatestRetrievalBenchmark.java']
    paths += [REPO/'backend/src/test/java/com/pricetrack/exchange/ai/KeywordExperiment.java']
    paths += [REPO/'backend/src/test/resources/ai'/n for n in ('retrieval-golden.json','retrieval-supplement.json')]
    paths += [REPO/'backend/src/test/resources/ai'/n for n in ('novel-30-v2.json','novel-30-v2.lock.json')]
    paths += [REPO/'backend/src/test/resources/ai/novel-followup-v1.json']
    paths += [REPO/'deployment'/n for n in ('ai_novel_question_audit.py',)]
    paths += list((REPO/'backend/src/test/java/com/pricetrack/exchange/futures/chain').glob('LatestAi*.java'))
    h=hashlib.sha256(source_hash().encode())
    for p in sorted(paths):
        h.update(p.relative_to(REPO).as_posix().encode()+b'\0'+p.read_bytes().replace(b'\r\n',b'\n'))
    return h.hexdigest()

def prepare(folder, port, credentials, image, *, approved=False, predecessor=None,uncertain_approved=False,input_extension=False,residual=False,scope_followup=False,novel_reference=None,novel_followup=False):
    if not approved: raise GuardError('PAID_APPROVAL_REQUIRED')
    folder=Path(folder).resolve(strict=True)
    if any(folder.iterdir()) or not 49152<=port<=65535 or not re.fullmatch('sha256:[0-9a-f]{64}',image):
        raise GuardError('NEW_EMPTY_STUDY_REQUIRED')
    if input_extension and predecessor is None:raise GuardError('INPUT_EXTENSION_REQUIRES_CARRY')
    if scope_followup and not residual:raise GuardError('RESIDUAL_SCOPE_REQUIRED')
    if novel_reference is not None and (input_extension or residual or scope_followup):raise GuardError('SEPARATE_NOVEL_BUDGET_REQUIRED')
    if novel_followup and (novel_reference is None or predecessor is None):raise GuardError('NOVEL_CLOSED_PREDECESSOR_REQUIRED')
    policy=dict(NOVEL_POLICY if novel_reference is not None else LATEST_POLICY_V2 if input_extension else LATEST_POLICY)
    key=read_key(credentials);token=uuid.uuid4().hex
    meta={'version':1,'purpose':PURPOSE,'runId':'eval_'+token,'sourceHash':study_source(),
          'corpusHash':corpus_hash(),'dbPort':port,'dbName':'exchange_ai_eval_'+token,
          'dbUser':'exchange_ai_eval_'+token,'dbImage':image,'mode':'live','policy':policy}
    if residual:meta['residualAcceptance']=True
    if scope_followup:meta['scopeFollowup']=True
    if novel_followup:meta['novelFollowup']=True
    carry=None
    if novel_reference is not None:
        from ai_novel_question_audit import audit
        audit()
        old,prior,_=closed_previous(novel_reference,approve_uncertain=True)
        if prior!={'llm_calls':96,'embedding_calls':115,'input_tokens':1366806,'output_tokens':112800,'embedding_tokens':367419,'reserved_nusd':4777963380}:
            raise GuardError('HISTORICAL_NOVEL_REFERENCE_INVALID')
        parent=Path(novel_reference).resolve(strict=True)
        meta.update({'deadlineProtocol':2,'finalAcceptance':True,'novelAcceptance':True,
            'evaluationProfile':{'planningSeconds':15,'synthesisSeconds':30,'retrievalSeconds':15,'totalSeconds':90},
            'datasetSha256':hashlib.sha256((REPO/'backend/src/test/resources/ai/novel-30-v2.json').read_bytes()).hexdigest(),
            'separateApproval':{'id':NOVEL_APPROVAL,'historicalReferenceOnly':str(parent),
                'historicalTotalsNotCharged':prior,'historicalUnknownReservationNusd':21330000,
                'hashes':{n:hashlib.sha256((parent/n).read_bytes()).hexdigest() for n in ('run.json','budget.sqlite','STOP')}}})
        write_new(folder/'NOVEL_RUN_CLAIM',json.dumps({'approval':NOVEL_APPROVAL,'runId':meta['runId'],'sourceHash':meta['sourceHash']}))
        # One approved allocation, not a new $5.70 for every new environment.
        if predecessor is None:write_new(REPO/'deployment/runtime/novel30-approved-570.claim',json.dumps(novel_claim(folder,meta)))
        else:
            prior_meta,_,_=closed_previous(predecessor)
            if policy_for(prior_meta)!=dict(NOVEL_POLICY):raise GuardError('NOVEL_PREDECESSOR_POLICY_MISMATCH')
            meta['novelAllocationRoot']=prior_meta.get('novelAllocationRoot',str(Path(predecessor).resolve(strict=True)))
    if predecessor is not None:
        previous,carry,hashes=closed_previous(predecessor,approve_uncertain=uncertain_approved)
        if previous['corpusHash']!=meta['corpusHash']:raise GuardError('LINEAGE_CORPUS_CHANGED')
        if policy_for(previous)!=policy:
            if policy_for(previous)!=dict(LATEST_POLICY) or policy!=dict(LATEST_POLICY_V2):raise GuardError('POLICY_TRANSITION_INVALID')
            meta['policyTransition']={'approval':INPUT_EXTENSION_APPROVAL,'from':dict(LATEST_POLICY),'to':policy}
        meta.update({'deadlineProtocol':2,'finalAcceptance':True,'verificationLineage':{
            'parent':str(Path(predecessor).resolve(strict=True)),'carry':carry,'hashes':hashes,'successor':str(folder)}})
        if uncertain_approved:meta['verificationLineage']['uncertainCarryApproval']=dict(UNCERTAIN_CARRY)
        meta['evaluationProfile']={'planningSeconds':15,'synthesisSeconds':30,'retrievalSeconds':15,'totalSeconds':90}
        # One successor for this closed approval lineage, even across processes/new folders.
        # This new claim never overwrites the predecessor's STOP, ledger or evidence.
        write_new(Path(predecessor).resolve(strict=True).parent/'latest-final-successor.claim',json.dumps(successor_claim(meta)))
    write_new(folder/'run.json',json.dumps(meta))
    write_new(folder/'db.env','POSTGRES_USER='+meta['dbUser']+'\nPOSTGRES_DB='+meta['dbName']+'\nPOSTGRES_PASSWORD='+secrets.token_urlsafe(36)+'\nPOSTGRES_INITDB_ARGS=--auth-host=scram-sha-256\n')
    write_new(folder/'compose.json',json.dumps(compose(meta)))
    write_new(folder/'relay.json',json.dumps(meta))
    write_new(folder/'openai.key',key)
    permit={'purpose':PURPOSE,'runId':meta['runId'],'sourceHash':meta['sourceHash'],
            'corpusHash':meta['corpusHash'],'policy':policy,'paidApproved':True,
            'expiresAt':int(time.time())+14340,'keyFile':str(folder/'openai.key')}
    write_new(folder/'approval.json',json.dumps(permit))
    EvaluationGuard(folder/'budget.sqlite',run_id=meta['runId'],source_hash=meta['sourceHash'],
                    corpus_hash=meta['corpusHash'],context_hash=hashlib.sha256(_canonical(meta)).hexdigest(),
                    create=True,policy=policy,carry=carry,
                    validate_lineage=(lambda:verify_successor(meta)) if predecessor is not None else None)
    with closing(sqlite3.connect(folder/'budget.sqlite')) as c:
        c.execute('CREATE TABLE profiles(id TEXT PRIMARY KEY, sha TEXT NOT NULL)')
        c.commit()
    return meta

def load(folder):
    folder=Path(folder).resolve(strict=True)
    if folder.is_symlink() or (folder/'run.json').is_symlink():raise GuardError('RUN_INVALID')
    meta=_decode((folder/'run.json').read_bytes())
    policy_for(meta)
    if meta.get('policy')==dict(NOVEL_POLICY):verify_novel_reference(folder,meta)
    elif meta.get('novelAcceptance') or meta.get('separateApproval'):raise GuardError('NOVEL_POLICY_REQUIRED')
    if (meta.get('purpose')!=PURPOSE
        or meta.get('sourceHash')!=study_source() or meta.get('corpusHash')!=corpus_hash()
        or not re.fullmatch('eval_[0-9a-f]{32}',meta.get('runId',''))
        or (not meta.get('continuation') and meta.get('dbName')!='exchange_ai_'+meta['runId']) or meta.get('dbUser')!=meta['dbName']
        or type(meta.get('dbPort')) is not int or not 49152<=meta['dbPort']<=65535
        or not re.fullmatch('sha256:[0-9a-f]{64}',meta.get('dbImage',''))
        or (folder/'budget.sqlite').is_symlink()):
        raise GuardError('RUN_IDENTITY_MISMATCH')
    if meta.get('continuation'):verify_parent(folder,meta)
    if meta.get('verificationLineage'):
        if meta['verificationLineage']['successor']!=str(folder):raise GuardError('SUCCESSOR_SCOPE_INVALID')
        verify_successor(meta)
    return folder,meta


def verify_novel_reference(folder,meta):
    ref=meta.get('separateApproval',{})
    if (ref.get('id')!=NOVEL_APPROVAL or not meta.get('novelAcceptance') or not meta.get('finalAcceptance')
        or meta.get('deadlineProtocol')!=2 or 'continuation' in meta
        or ref.get('historicalTotalsNotCharged',{}).get('reserved_nusd')!=4777963380
        or ref.get('historicalUnknownReservationNusd')!=21330000):raise GuardError('NOVEL_APPROVAL_INVALID')
    parent=Path(ref['historicalReferenceOnly']).resolve(strict=True)
    if parent==folder or not (parent/'STOP').is_file():raise GuardError('HISTORICAL_REFERENCE_NOT_CLOSED')
    if set(ref.get('hashes',{}))!={'run.json','budget.sqlite','STOP'}:raise GuardError('HISTORICAL_REFERENCE_INVALID')
    for leaf,sha in ref['hashes'].items():
        if (parent/leaf).is_symlink() or hashlib.sha256((parent/leaf).read_bytes()).hexdigest()!=sha:raise GuardError('HISTORICAL_REFERENCE_CHANGED')
    claim=_decode((folder/'NOVEL_RUN_CLAIM').read_bytes())
    if claim!={'approval':NOVEL_APPROVAL,'runId':meta['runId'],'sourceHash':meta['sourceHash']}:raise GuardError('NOVEL_CLAIM_INVALID')
    if hashlib.sha256((REPO/'backend/src/test/resources/ai/novel-30-v2.json').read_bytes()).hexdigest()!=meta.get('datasetSha256'):raise GuardError('NOVEL_DATASET_CHANGED')
    root_claim=REPO/'deployment/runtime/novel30-approved-570.claim'
    root=Path(meta.get('novelAllocationRoot',str(folder))).resolve(strict=True)
    allocation_meta=meta if root==folder else _decode((root/'run.json').read_bytes())
    if root!=folder and ('verificationLineage' not in meta or allocation_meta.get('separateApproval')!=ref
            or allocation_meta.get('policy')!=dict(NOVEL_POLICY) or not (root/'STOP').is_file()):raise GuardError('NOVEL_ALLOCATION_ROOT_INVALID')
    if root_claim.is_symlink() or _decode(root_claim.read_bytes())!=novel_claim(root,allocation_meta):raise GuardError('NOVEL_ALLOCATION_ALREADY_BOUND')


def novel_claim(folder,meta):
    return {'approval':NOVEL_APPROVAL,'runId':meta['runId'],'sourceHash':meta['sourceHash'],
            'folder':str(folder),'policy':dict(NOVEL_POLICY)}


def closed_comparison(folder):
    """Carry the original reservation bounds once, plus the closed comparison's own rows.

    Historical source is immutable evidence, not required to equal the revised source.
    No unknown reservation, mutable parent, missing STOP or alternate policy is accepted.
    """
    folder=Path(folder).resolve(strict=True)
    m=_decode((folder/'run.json').read_bytes())
    if (folder.is_symlink() or folder.name!='latest-comparison' or not m.get('continuation')
        or m.get('purpose')!=PURPOSE or m.get('policy')!=dict(LATEST_POLICY)
        or not (folder/'STOP').is_file()):raise GuardError('CLOSED_COMPARISON_REQUIRED')
    verify_parent(folder,m)
    carry=m['continuation']['carry']
    with closing(sqlite3.connect((folder/'budget.sqlite').as_uri()+'?mode=ro',uri=True)) as c:
        identity={'run':m['runId'],'source':m['sourceHash'],'corpus':m['corpusHash'],
            'context':hashlib.sha256(_canonical(m)).hexdigest(),'policy':dict(LATEST_POLICY),'carry':carry}
        if c.execute('SELECT value FROM identity WHERE id=1').fetchall()!=[(_canonical(identity).decode(),)]:raise GuardError('PARENT_IDENTITY_INVALID')
        if c.execute("SELECT 1 FROM reservations WHERE outcome!='OBSERVED'").fetchone():raise GuardError('PARENT_USAGE_UNCERTAIN')
        row=c.execute("SELECT sum(kind='llm'),sum(kind='embedding'),coalesce(sum(input),0),coalesce(sum(output),0),coalesce(sum(embedding),0),coalesce(sum(cost),0) FROM reservations").fetchone()
        totals=dict(carry)
        for k,v in zip(('llm_calls','embedding_calls','input_tokens','output_tokens','embedding_tokens','reserved_nusd'),row):totals[k]+=v or 0
    names=('run.json','budget.sqlite','STOP','comparison.private.json','completion.private.json','fresh-fixture.private.json','fixture-binding.json')
    hashes={name:hashlib.sha256((folder/name).read_bytes()).hexdigest() for name in names}
    return m,totals,hashes


def successor_claim(meta):
    return {'runId':meta['runId'],'folder':meta['verificationLineage']['successor'],'sourceHash':meta['sourceHash'],
            'corpusHash':meta['corpusHash'],'dbName':meta['dbName']}


def closed_previous(folder,*,approve_uncertain=False):
    """A closed successor may carry on the same immutable chain, never as a sibling/reset.

    In particular an environment failure before dispatch consumes no paid allowance,
    but its STOP/claim/evidence remain immutable and the next run has a new own claim.
    """
    folder=Path(folder).resolve(strict=True)
    if folder.name=='latest-comparison':return closed_comparison(folder)
    m=_decode((folder/'run.json').read_bytes())
    if m.get('novelAcceptance'):return closed_novel(folder,m)
    if (folder.name!='latest-study' or not m.get('verificationLineage') or not (folder/'STOP').is_file()
        or m.get('purpose')!=PURPOSE):raise GuardError('CLOSED_SUCCESSOR_REQUIRED')
    policy=policy_for(m)
    verify_successor(m)
    carry=m['verificationLineage']['carry']
    with closing(sqlite3.connect((folder/'budget.sqlite').as_uri()+'?mode=ro',uri=True)) as c:
        identity={'run':m['runId'],'source':m['sourceHash'],'corpus':m['corpusHash'],
            'context':hashlib.sha256(_canonical(m)).hexdigest(),'policy':policy,'carry':carry}
        if c.execute('SELECT value FROM identity WHERE id=1').fetchall()!=[(_canonical(identity).decode(),)]:raise GuardError('PARENT_IDENTITY_INVALID')
        uncertain=c.execute("SELECT id,kind,input,output,embedding,cost,outcome FROM reservations WHERE outcome!='OBSERVED'").fetchall()
        expected=tuple(UNCERTAIN_CARRY[k] for k in ('requestId','kind','input','output','embedding','cost','outcome'))
        if uncertain and not (approve_uncertain and m['runId']==UNCERTAIN_CARRY['runId'] and uncertain==[expected]):raise GuardError('PARENT_USAGE_UNCERTAIN')
        row=c.execute("SELECT sum(kind='llm'),sum(kind='embedding'),coalesce(sum(input),0),coalesce(sum(output),0),coalesce(sum(embedding),0),coalesce(sum(cost),0) FROM reservations").fetchone()
        totals=dict(carry)
        for k,v in zip(('llm_calls','embedding_calls','input_tokens','output_tokens','embedding_tokens','reserved_nusd'),row):totals[k]+=v or 0
    names=['run.json','budget.sqlite','STOP']
    if totals['llm_calls']+totals['embedding_calls']>carry['llm_calls']+carry['embedding_calls']:
        verify_fixture(folder);names+=['fresh-fixture.private.json','fixture-binding.json']
    for name in ('questions.private.json','completion.private.json','financial-after.private.json'):
        if (folder/name).exists():names.append(name)
    return m,totals,{name:hashlib.sha256((folder/name).read_bytes()).hexdigest() for name in names}


def closed_novel(folder,meta):
    if not (folder/'STOP').is_file() or meta.get('policy')!=dict(NOVEL_POLICY):raise GuardError('CLOSED_NOVEL_REQUIRED')
    verify_novel_reference(folder,meta)
    if 'verificationLineage' in meta:verify_successor(meta)
    carry=meta.get('verificationLineage',{}).get('carry')
    identity={'run':meta['runId'],'source':meta['sourceHash'],'corpus':meta['corpusHash'],
        'context':hashlib.sha256(_canonical(meta)).hexdigest(),'policy':dict(NOVEL_POLICY)}
    if carry is not None:identity['carry']=carry
    with closing(sqlite3.connect((folder/'budget.sqlite').as_uri()+'?mode=ro',uri=True)) as db:
        if db.execute('SELECT value FROM identity WHERE id=1').fetchall()!=[(_canonical(identity).decode(),)]:raise GuardError('PARENT_IDENTITY_INVALID')
        if db.execute("SELECT 1 FROM reservations WHERE outcome!='OBSERVED'").fetchone():raise GuardError('PARENT_USAGE_UNCERTAIN')
        own=EvaluationGuard._totals(db)
    totals={k:v+(carry or {}).get(k,0) for k,v in own.items()}
    names=['run.json','budget.sqlite','STOP']
    if own['llm_calls']+own['embedding_calls']:
        verify_fixture(folder);names+=['fresh-fixture.private.json','fixture-binding.json']
        if not _decode((folder/'financial-after.private.json').read_bytes()).get('unchanged'):raise GuardError('PARENT_FINANCIAL_CHANGED')
    for name in ('questions.private.json','completion.private.json','financial-after.private.json'):
        if (folder/name).exists():names.append(name)
    return meta,totals,{n:hashlib.sha256((folder/n).read_bytes()).hexdigest() for n in names}


def verify_successor(meta):
    link=meta['verificationLineage'];waiver=link.get('uncertainCarryApproval')
    if waiver is not None and waiver!=UNCERTAIN_CARRY:raise GuardError('UNCERTAIN_APPROVAL_INVALID')
    old,carry,hashes=closed_previous(link['parent'],approve_uncertain=waiver is not None)
    changed=policy_for(old)!=policy_for(meta)
    expected={'approval':INPUT_EXTENSION_APPROVAL,'from':dict(LATEST_POLICY),'to':dict(LATEST_POLICY_V2)}
    if changed:
        if policy_for(old)!=dict(LATEST_POLICY) or policy_for(meta)!=dict(LATEST_POLICY_V2) or meta.get('policyTransition')!=expected:raise GuardError('POLICY_TRANSITION_INVALID')
    elif 'policyTransition' in meta:raise GuardError('POLICY_TRANSITION_INVALID')
    if (carry!=link['carry'] or hashes!=link['hashes'] or meta['dbName']==old['dbName']
        or meta['corpusHash']!=old['corpusHash'] or meta.get('deadlineProtocol')!=2 or meta.get('finalAcceptance') is not True):
        raise GuardError('PARENT_CHANGED')
    claim=Path(link['parent']).parent/'latest-final-successor.claim'
    if claim.is_symlink() or _decode(claim.read_bytes())!=successor_claim(meta):raise GuardError('SUCCESSOR_CLAIM_INVALID')


def parent_state(folder):
    """Only this study's stopped, fully OBSERVED parent may fund a continuation."""
    folder=Path(folder).resolve(strict=True)
    meta=_decode((folder/'run.json').read_bytes())
    if meta.get('purpose')!=PURPOSE or meta.get('policy')!=dict(LATEST_POLICY) or meta.get('continuation') or not (folder/'STOP').is_file():
        raise GuardError('PARENT_NOT_CLOSED')
    with closing(sqlite3.connect((folder/'budget.sqlite').as_uri()+'?mode=ro',uri=True)) as c:
        expected={'run':meta['runId'],'source':meta['sourceHash'],'corpus':meta['corpusHash'],
                  'context':hashlib.sha256(_canonical(meta)).hexdigest(),'policy':dict(LATEST_POLICY)}
        if c.execute('SELECT value FROM identity WHERE id=1').fetchall()!=[(_canonical(expected).decode(),)]:raise GuardError('PARENT_IDENTITY_INVALID')
        if c.execute("SELECT 1 FROM reservations WHERE outcome!='OBSERVED'").fetchone():raise GuardError('PARENT_USAGE_UNCERTAIN')
        row=c.execute("SELECT sum(kind='llm'),sum(kind='embedding'),coalesce(sum(input),0),coalesce(sum(output),0),coalesce(sum(embedding),0),coalesce(sum(cost),0) FROM reservations").fetchone()
        carry=dict(zip(('llm_calls','embedding_calls','input_tokens','output_tokens','embedding_tokens','reserved_nusd'),(v or 0 for v in row)))
        if c.execute("SELECT 1 FROM profiles WHERE id!='baseline'").fetchone():raise GuardError('PARENT_EXPERIMENTS_EXIST')
    names=('run.json','budget.sqlite','STOP','questions.private.json','fresh-fixture.private.json','fixture-binding.json')
    hashes={name:hashlib.sha256((folder/name).read_bytes()).hexdigest() for name in names}
    return meta,carry,hashes


def verify_parent(folder,meta):
    link=meta['continuation'];parent=Path(link['parent']).resolve(strict=True)
    if folder.parent!=parent.parent or parent.name!='latest-study' or folder.name!='latest-comparison':raise GuardError('PARENT_SCOPE_INVALID')
    p,carry,hashes=parent_state(parent)
    if carry!=link['carry'] or hashes!=link['hashes'] or meta['dbName']!=p['dbName'] or meta['dbUser']!=p['dbUser'] or meta['dbImage']!=p['dbImage'] or meta['corpusHash']!=p['corpusHash']:
        raise GuardError('PARENT_CHANGED')


def prepare_comparison(folder,parent,port):
    """No retry of failed Q14. Frozen inputs compare two synthesis prompts only."""
    folder=Path(folder).resolve(strict=True);parent=Path(parent).resolve(strict=True)
    if any(folder.iterdir()) or folder.parent!=parent.parent or folder.name!='latest-comparison' or parent.name!='latest-study':raise GuardError('NEW_COMPARISON_SCOPE_REQUIRED')
    p,carry,hashes=parent_state(parent)
    meta={**p,'runId':'eval_'+uuid.uuid4().hex,'dbPort':port,'sourceHash':study_source(),
          'continuation':{'parent':str(parent),'carry':carry,'hashes':hashes,'scope':'CAPTURED_INPUT_SYNTHESIS_COMPARISON_NOT_HTTP_RETRY'}}
    if type(port) is not int or not 49152<=port<=65535:raise GuardError('PORT_INVALID')
    verify_parent(folder,meta)
    write_new(folder/'run.json',json.dumps(meta))
    write_new(folder/'openai.key',(parent/'openai.key').read_text(encoding='utf-8'))
    permit={'purpose':PURPOSE,'runId':meta['runId'],'sourceHash':meta['sourceHash'],'corpusHash':meta['corpusHash'],
            'policy':dict(LATEST_POLICY),'paidApproved':True,'expiresAt':int(time.time())+14340,'keyFile':str(folder/'openai.key')}
    write_new(folder/'approval.json',json.dumps(permit))
    for name in ('db.env','fresh-fixture.private.json','fixture-binding.json'):write_new(folder/name,(parent/name).read_text(encoding='utf-8'))
    EvaluationGuard(folder/'budget.sqlite',run_id=meta['runId'],source_hash=meta['sourceHash'],corpus_hash=meta['corpusHash'],
        context_hash=hashlib.sha256(_canonical(meta)).hexdigest(),create=True,policy=LATEST_POLICY,
        carry=carry,validate_lineage=lambda:verify_parent(folder,meta))
    with closing(sqlite3.connect(folder/'budget.sqlite')) as c:c.execute('CREATE TABLE profiles(id TEXT PRIMARY KEY, sha TEXT NOT NULL)');c.commit()
    bind_fixture(folder)  # child inputs already exist; summary must be safe before first dispatch
    return meta,p


def fixture_hash(folder):
    """Freeze deployment and actor/target evidence before the first paid reservation."""
    paths=[folder/'fixture-binding.json',folder/'fresh-fixture.private.json']
    if any(not p.is_file() or p.is_symlink() for p in paths):raise GuardError('FIXTURE_BINDING_REQUIRED')
    return hashlib.sha256(b''.join(p.read_bytes() for p in paths)).hexdigest()


def bind_fixture(folder):
    digest=fixture_hash(folder)
    with closing(sqlite3.connect(folder/'budget.sqlite')) as c:
        c.execute('CREATE TABLE IF NOT EXISTS fixture_binding(sha TEXT PRIMARY KEY)')
        rows=c.execute('SELECT sha FROM fixture_binding').fetchall()
        if not rows:c.execute('INSERT INTO fixture_binding VALUES(?)',(digest,));c.commit()
        elif rows!=[(digest,)]:raise GuardError('FIXTURE_BINDING_CHANGED')


def verify_fixture(folder):
    digest=fixture_hash(folder)
    with closing(sqlite3.connect((folder/'budget.sqlite').as_uri()+'?mode=ro',uri=True)) as c:
        if c.execute('SELECT sha FROM fixture_binding').fetchall()!=[(digest,)]:raise GuardError('FIXTURE_BINDING_CHANGED')

def permit_sender(folder,meta,approval):
    try:
        a=_decode(Path(approval).read_bytes())
        if any(a.get(k)!=v for k,v in {'purpose':PURPOSE,'runId':meta['runId'],'sourceHash':meta['sourceHash'],
                 'corpusHash':meta['corpusHash'],'policy':policy_for(meta),'paidApproved':True}.items()):raise ValueError()
        if type(a.get('expiresAt')) is not int or not time.time()<a['expiresAt']<=time.time()+14400:raise ValueError()
        key_path=Path(a['keyFile'])
        if key_path!=folder/'openai.key' or key_path.is_symlink():raise ValueError()
        key=key_path.read_text(encoding='utf-8').strip()
        if not key or any(c.isspace() for c in key):raise ValueError()
    except Exception:raise GuardError('PAID_APPROVAL_REQUIRED') from None
    return _https_sender(folder,a,key)

def profile_request(folder, raw, path):
    file=folder/'profile.json'
    profile=_decode(file.read_bytes()) if file.exists() else {'id':'baseline','instructionsSuffix':''}
    if (set(profile)!={'id','instructionsSuffix'} or profile['id'] not in {'baseline','experiment1','experiment2'}
        or not isinstance(profile['instructionsSuffix'],str) or len(profile['instructionsSuffix'].encode())>6000
        or profile['id']=='baseline' and profile['instructionsSuffix']):raise GuardError('PROFILE_INVALID')
    sha=hashlib.sha256(_canonical(profile)).hexdigest()
    with closing(sqlite3.connect(folder/'budget.sqlite')) as c:
        c.execute('INSERT OR IGNORE INTO profiles VALUES(?,?)',(profile['id'],sha))
        if c.execute('SELECT sha FROM profiles WHERE id=?',(profile['id'],)).fetchall()!=[(sha,)]:raise GuardError('PROFILE_CHANGED')
        c.commit()
    body=_decode(raw)
    if path=='responses' and body.get('text',{}).get('format',{}).get('name')=='agent_interpretation':
        body['instructions']+='\n'+profile['instructionsSuffix'] if profile['instructionsSuffix'] else ''
        raw=_canonical(body)
    return raw,profile['id'],sha

def broker(folder,*,live=False,approval=None,inp=None,out=None):
    folder,meta=load(folder)
    if not live or approval is None:raise GuardError('PAID_APPROVAL_REQUIRED')
    inp=sys.stdin.buffer if inp is None else inp;out=sys.stdout.buffer if out is None else out
    guard=EvaluationGuard(folder/'budget.sqlite',run_id=meta['runId'],source_hash=meta['sourceHash'],
          corpus_hash=meta['corpusHash'],context_hash=hashlib.sha256(_canonical(meta)).hexdigest(),
          policy=policy_for(meta),execution_approved=True,validate_lineage=lambda:load(folder),
          carry=meta.get('verificationLineage',meta.get('continuation',{})).get('carry'))
    # Constructor verifies existing identity before Spring creates the fresh fixture.
    # Every subsequent identity check/reservation requires its now-frozen evidence.
    guard.validate_lineage=lambda:(load(folder),verify_fixture(folder),permit_sender(folder,meta,approval))
    sender=permit_sender(folder,meta,approval)
    def emit(value):out.write(_canonical(value)+b'\n');out.flush()
    emit({'ready':True,'mode':'live','sourceHash':meta['sourceHash'],'corpusHash':meta['corpusHash']})
    while True:
        line=inp.readline(160001)
        if not line:return
        try:
            if len(line)>160000 or not line.endswith(b'\n'):raise GuardError('FRAME_INVALID')
            r=_decode(line,max_bytes=160000)
            if r=={'summary':True}:
                if (folder/'fixture-binding.json').is_file() and (folder/'fresh-fixture.private.json').is_file():bind_fixture(folder)
                emit({'summary':guard.summary()});continue
            if r=={'stop':True}:
                guard.stop();(folder/'STOP').touch(exist_ok=True);emit({'stopped':True});return
            if (folder/'STOP').exists():raise GuardError('RUN_STOPPED')
            protocol2=meta.get('deadlineProtocol')==2
            expected={'id','path','body','timeoutMs'}|({'deadlineEpochMs'} if protocol2 else set())
            if set(r)!=expected or type(r['timeoutMs']) is not int or not 1<=r['timeoutMs']<=60000:raise GuardError('FRAME_INVALID')
            deadline=FrameDeadline(r['timeoutMs'],r['deadlineEpochMs']) if protocol2 else None
            frame_started=time.monotonic();timing={}
            raw,profile,profile_sha=profile_request(folder,base64.b64decode(r['body'],validate=True),r['path'])
            bind_fixture(folder)  # before BEGIN IMMEDIATE; nested identity validation is read-only
            start=time.monotonic()
            if deadline:deadline.sender_budget()
            def send_bounded(path,body):
                budget=deadline.sender_budget() if deadline else r['timeoutMs']/1000
                timing.update({'preDispatchMs':round((time.monotonic()-frame_started)*1000),'httpsBudgetMs':int(budget*1000)})
                return sender(path,body,budget)
            response=guard.dispatch(r['path'],raw,send_bounded,request_id=r['id'])
            # Usage records contain counts/hashes, never input, headers or API keys.
            usage=response['usage'];kind='llm' if r['path']=='responses' else 'embedding'
            input_count=usage['input_tokens' if kind=='llm' else 'prompt_tokens'];output_count=usage.get('output_tokens',0) if kind=='llm' else 0
            row={'requestId':r['id'],'profile':profile,'profileSha256':profile_sha,'kind':kind,
                 'inputTokens':input_count,'outputTokens':output_count,'elapsedMs':round((time.monotonic()-start)*1000),
                 'estimatedStandardNanoUsd':input_count*(2000 if kind=='llm' else 20)+output_count*12000,
                 'reservedTotals':guard.summary()}
            if protocol2:row.update({'timing':timing,'frameElapsedMs':round((time.monotonic()-frame_started)*1000)})
            write_new(folder/('usage-'+r['id']+'.json'),json.dumps(row))
            encoded=_canonical(response)
            if len(encoded)>2000000:raise GuardError('RESPONSE_LIMIT')
            if deadline and deadline.expired():
                emit({'ok':False,'code':'DEADLINE_EXCEEDED_OBSERVED','requestId':r['id'],'observed':True})
            else:
                reply={'ok':True,'body':base64.b64encode(encoded).decode()}
                if protocol2:reply['requestId']=r['id']
                emit(reply)
        except Exception as error:
            (folder/'STOP').touch(exist_ok=True)
            try:guard.stop()
            except Exception:pass
            code=str(error) if isinstance(error,GuardError) and re.fullmatch('[A-Z_]{1,60}',str(error)) else 'LATEST_BROKER_FAILURE'
            emit({'ok':False,'code':code});return

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--prepare',action='store_true');p.add_argument('--run-dir',required=True)
    p.add_argument('--port',type=int);p.add_argument('--credentials-file');p.add_argument('--image');p.add_argument('--paid-study-approved',action='store_true')
    a=p.parse_args()
    if not a.prepare:raise SystemExit('Use the existing stdio runner for dispatch')
    m=prepare(a.run_dir,a.port,a.credentials_file,a.image,approved=a.paid_study_approved)
    print(json.dumps({'prepared':True,'runId':m['runId'],'sourceHash':m['sourceHash'],'corpusHash':m['corpusHash'],'paidPosts':0}))
