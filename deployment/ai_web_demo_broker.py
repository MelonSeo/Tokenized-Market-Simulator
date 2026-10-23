"""One web context, keyless JVM, durable cumulative Guard. Offline by default.

No approval writer. Live sender construction requires a new exact web permit;
offline mode never reads credentials or creates any HTTP connection.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import sys
import time
import threading
import uuid
from ai_evaluation_guard import EvaluationGuard, GuardError, POLICY, _canonical, _decode, _bound
from ai_evaluation_broker import source_hash, corpus_hash
from ai_evaluation_continuation import snapshot, verify as verify_q12, read_meta, _carry, scope
from ai_web_demo_scope import WebDemoScope

QUESTION='내 Long 포지션은 왜 강제청산됐나요? 기록된 근거로 진단해주세요.'
SKILL='futures-liquidation-diagnosis'
REGISTRY=Path(__file__).resolve().parent/'runtime'/'ai-web-lineage-v1'

def digest(path):
    p=Path(path)
    if p.is_symlink() or not p.is_file():raise GuardError('WEB_ARTIFACT_INVALID')
    return hashlib.sha256(p.read_bytes()).hexdigest()

def ancestors(q12,registry):
    m=read_meta(q12);verify_q12(q12,m,registry)
    if m.get('questions')!=[12]:raise GuardError('WEB_PREDECESSOR_INVALID')
    rows=[snapshot(x['dir']) for x in m['continuation']['ancestors']]+[snapshot(q12)]
    if len(rows)!=3 or rows[-1]['uncertain']:raise GuardError('WEB_PREDECESSOR_INVALID')
    return rows

def verify(folder,m):
    if m['sourceHash']!=source_hash() or m['corpusHash']!=corpus_hash():raise GuardError('WEB_SOURCE_CHANGED')
    line=m['web']['lineage']
    if ancestors(line['q12'],Path(line['registry']))!=line['ancestors']:raise GuardError('WEB_LINEAGE_CHANGED')
    if _carry(line['ancestors'])!=m['web']['carry']:raise GuardError('WEB_CARRY_INVALID')
    proof=_decode(Path(m['web']['indexProof']).read_bytes())
    if digest(m['web']['indexProof'])!=m['web']['indexProofSha256'] or proof.get('status')!='PASS' or proof.get('providerCalls')!=0 or proof.get('newOwner')!=m['web']['indexOwner']:
        raise GuardError('WEB_INDEX_NOT_READY')
    claim=_decode(Path(m['web']['claim']).read_bytes())
    if claim!={'run':str(Path(folder).resolve()),'context':hashlib.sha256(_canonical(m)).hexdigest()}:raise GuardError('WEB_CLAIM_INVALID')
    return m['web']['carry']

def prepare(folder,index,q12,registry,user,position,claim_registry=None,mode='offline'):
    if mode not in ('offline','live'):raise GuardError('WEB_MODE_INVALID')
    if type(user) is not int or user<=0 or str(uuid.UUID(position))!=position:raise GuardError('WEB_CONTEXT_INVALID')
    index=Path(index).resolve(strict=True);im=_decode((index/'run.json').read_bytes())
    proof=index/'index-copy-verification.json';pd=_decode(proof.read_bytes())
    if im['sourceHash']!=source_hash() or im['corpusHash']!=corpus_hash() or pd.get('status')!='PASS' or pd.get('newOwner')!=im['runId']:
        raise GuardError('WEB_INDEX_NOT_READY')
    rows=ancestors(q12,registry);carry=_carry(rows);limits=scope(12)
    if any(carry[k]+limits[k]>POLICY['cap_nusd' if k=='reserved_nusd' else k] for k in carry):raise GuardError('WEB_BUDGET_INSUFFICIENT')
    folder=Path(folder).absolute();folder.mkdir(exist_ok=False)
    rid='eval_'+uuid.uuid4().hex
    # Simulations are owned by their new fixture, not claims for future payment.
    # The live branch has ONE global predecessor slot across all web fixtures.
    root=(Path(claim_registry) if claim_registry is not None else folder.parent/'web-simulation-lineage' if mode=='offline' else REGISTRY)/mode
    root.mkdir(parents=True,exist_ok=True)
    # Separate web branch: does not reuse or rewrite Q12/Q14 continuation claims.
    claim=root/(rows[-1]['runId']+'.web.json')
    m={**im,'runId':rid,'dbName':im['dbName'],'dbUser':im['dbUser'],'mode':mode,'fixture':'success','policy':POLICY,
       'web':{'userId':user,'positionId':position,'skillId':SKILL,'question':QUESTION,'carry':carry,'limits':limits,
       'indexProof':str(proof),'indexProofSha256':digest(proof),'indexOwner':im['runId'],
       'lineage':{'q12':str(Path(q12).resolve()),'registry':str(Path(registry).resolve()),'ancestors':rows},'claim':str(claim)}}
    context=hashlib.sha256(_canonical(m)).hexdigest()
    write_new(claim,_canonical({'run':str(folder.resolve()),'context':context}))
    write_new(folder/'run.json',_canonical(m))
    EvaluationGuard(folder/'budget.sqlite',run_id=rid,source_hash=m['sourceHash'],corpus_hash=m['corpusHash'],
        context_hash=context,create=True,carry=carry,local_limits=limits,validate_lineage=lambda:verify(folder,m))
    return m

def write_new(path,raw):
    with os.fdopen(os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'wb') as f:f.write(raw);f.flush();os.fsync(f.fileno())

def live_sender(folder,m,approval):
    # All validation, including READY and lineage, happens BEFORE the old
    # single-HTTPS sender is permitted to read a key. There is no permit writer.
    verify(folder,m)
    p=Path(approval)
    if not p.is_absolute() or p.is_symlink() or p.parent.resolve()!=Path(folder).resolve():raise GuardError('WEB_APPROVAL_REQUIRED')
    a=_decode(p.read_bytes())
    context=hashlib.sha256(_canonical(m)).hexdigest()
    required={'purpose':'ONE_WEB_DEMO','contextHash':context,'localLimits':scope(12),
        'carry':m['web']['carry'],'userId':m['web']['userId'],'positionId':m['web']['positionId'],
        'question':QUESTION,'skillId':SKILL,'retryCount':0,'indexProofSha256':m['web']['indexProofSha256'],
        'llmModel':POLICY['llm_model'],'embeddingModel':POLICY['embedding_model']}
    if m['mode']!='live' or any(a.get(k)!=v for k,v in required.items()) or type(a.get('expiresAt')) is not int or not time.time()<a['expiresAt']<=time.time()+600:
        raise GuardError('WEB_APPROVAL_REQUIRED')
    capture=Path(folder)/'dry-run.private.json'
    if digest(capture)!=a.get('dryRunSha256'):raise GuardError('WEB_DRY_RUN_REQUIRED')
    assess_dry_run(m,_decode(capture.read_bytes(),max_bytes=400000))
    from ai_evaluation_broker import live_sender as single_https_sender
    send=single_https_sender(Path(folder),m,p)
    def authorized(path,raw):
        if time.time()>=a['expiresAt'] or (Path(folder)/'STOP').exists():raise GuardError('WEB_DEADLINE')
        verify(folder,m)
        return send(path,raw,10 if path=='responses' else 5)
    return authorized

def assess_dry_run(m,capture):
    if capture.get('sourceHash')!=m['sourceHash'] or capture.get('corpusHash')!=m['corpusHash'] or capture.get('webContext')!={k:m['web'][k] for k in ('userId','positionId','skillId','question','indexProofSha256')}:
        raise GuardError('WEB_DRY_RUN_IDENTITY')
    totals={k:0 for k in scope(12)}
    for row in capture.get('requests',[]):
        kind,i,o,e=_bound(row['path'],base64.b64decode(row['bodyBase64'],validate=True),POLICY)
        for k,v in zip(totals,(int(kind=='llm'),int(kind=='embedding'),i,o,e,i*2500+o*12000+e*20)):totals[k]+=v
    if totals['llm_calls']!=1 or totals['embedding_calls']!=1 or any(totals[k]>scope(12)[k] for k in totals):raise GuardError('WEB_DRY_RUN_LIMIT')
    return totals

def fake_sender(index):
    # Existing policy vector only. Not a newly generated embedding or LLM answer.
    n=_decode((Path(index)/'source-index.json').read_bytes(),max_bytes=4000000)
    ch=next(c for c in n['chunks'] if c['path']=='onchain-settlement-policy.md' and '선물 FuturesEngine' in c['heading'])
    vector=json.loads(ch['embedding'])
    def send(path,raw):
        if path=='embeddings':return {'usage':{'prompt_tokens':1},'data':[{'index':0,'embedding':vector}]}
        body=_decode(raw);data=json.loads(body['input']);ev=data['evidence'];tool=ev['tools'][0]
        scalar=next((k,v) for k,v in tool['data'].items() if isinstance(v,str))
        answer={'status':'ANSWERED','interpretation':'무과금 fake 모델 응답입니다. 실제 Tool 조회와 정책 인용의 연결만 검증합니다.',
            'citationIds':[tool['evidenceId'],ev['knowledge'][0]['id']],
            'facts':[{'evidenceId':tool['evidenceId'],'pointer':'/'+scalar[0],'value':scalar[1]}],
            'uncertainties':['FAKE_MODEL_NOT_REAL_QUALITY_EVALUATION'],'recommendedNextCheck':[]}
        return {'status':'completed','service_tier':'default','usage':{'input_tokens':1,'output_tokens':1},
            'output':[{'content':[{'type':'output_text','text':json.dumps(answer,ensure_ascii=False)}]}]}
    return send

def arm_deadline(folder,guard,seconds,on_exit=os._exit):
    # Independent of stdin and Java monitors. A killed/uncertain request keeps
    # its already-COMMITTED reservation; no finally block/refund is required.
    def expire():
        try:
            if not (Path(folder)/'STOP').exists():write_new(Path(folder)/'STOP',b'WEB_DEADLINE\n')
        finally:
            guard.approved=False
            on_exit(124)
    timer=threading.Timer(seconds,expire);timer.daemon=True;timer.start();return timer

def broker(folder,index,inp=None,out=None,seconds=600,live=False,approval=None):
    folder=Path(folder).resolve(strict=True);m=_decode((folder/'run.json').read_bytes());carry=verify(folder,m)
    # This offline release has NO key reader and NO HTTP sender. Fake reservations
    # are labelled simulation and must never be claimed as live usage/approval.
    if (not live and approval is not None) or m['mode']!=('live' if live else 'offline'):raise GuardError('WEB_MODE_INVALID')
    if not live and (folder/'approval.json').exists():raise GuardError('WEB_LIVE_NOT_APPROVED')
    guard=EvaluationGuard(folder/'budget.sqlite',run_id=m['runId'],source_hash=m['sourceHash'],corpus_hash=m['corpusHash'],
        context_hash=hashlib.sha256(_canonical(m)).hexdigest(),carry=carry,local_limits=m['web']['limits'],
        execution_approved=True,validate_lineage=lambda:verify(folder,m))
    if (folder/'STOP').exists():raise GuardError('RUN_STOPPED')
    with guard._db() as db:
        if db.execute('select stopped from control where id=1').fetchall()!=[(0,)] or db.execute("select 1 from reservations where outcome!='OBSERVED'").fetchone():raise GuardError('RUN_HALTED')
    sender=live_sender(folder,m,approval) if live else fake_sender(index)
    scoped=WebDemoScope(folder,guard);start=time.monotonic()
    timer=arm_deadline(folder,guard,seconds)
    inp=inp or sys.stdin.buffer;out=out or sys.stdout.buffer
    def emit(x):out.write(_canonical(x)+b'\n');out.flush()
    emit({'ready':True,'mode':'live' if live else 'offline','sourceHash':m['sourceHash'],'corpusHash':m['corpusHash']})
    try:
        for line in iter(lambda:inp.readline(160001),b''):
            try:
                if time.monotonic()-start>seconds:raise GuardError('WEB_DEADLINE')
                r=_decode(line,max_bytes=160000)
                if r=={'summary':True}:emit({'summary':guard.summary()});continue
                if r=={'stop':True}:break
                if r=={'claimSubmission':True}:scoped.claim_submission();emit({'claimed':True});continue
                if set(r)!={'id','path','body','timeoutMs'} or type(r['timeoutMs']) is not int or not 1<=r['timeoutMs']<=60000:raise GuardError('FRAME_INVALID')
                raw=base64.b64decode(r['body'],validate=True)
                # Full serialized private artifact only in new run, no credentials.
                with (folder/'requests.private.jsonl').open('ab') as f:f.write(_canonical({'path':r['path'],'bodyBase64':r['body']})+b'\n')
                result=scoped.dispatch(r['path'],raw,sender,request_id=r['id'])
                usage={k:v for k,v in result.get('usage',{}).items() if k in ('input_tokens','output_tokens','prompt_tokens') and type(v) is int}
                with (folder/'provider-usage.private.jsonl').open('ab') as f:
                    f.write(_canonical({'id':r['id'],'path':r['path'],'simulation':not live,'usage':usage})+b'\n');f.flush();os.fsync(f.fileno())
                emit({'ok':True,'body':base64.b64encode(_canonical(result)).decode()})
            except Exception as e:
                if not (folder/'STOP').exists():write_new(folder/'STOP',b'STOPPED\n')
                guard.stop();emit({'ok':False,'code':str(e) if isinstance(e,GuardError) else 'WEB_BROKER_FAILURE'});break
    finally:
        timer.cancel()
        if not (folder/'STOP').exists():write_new(folder/'STOP',b'STOPPED\n')
        guard.stop()
        write_new(folder/('live-summary.json' if live else 'simulation-summary.json'),_canonical({'simulation':not live,'totals':guard.summary()}))
        if not live and (folder/'requests.private.jsonl').is_file():
            capture={'sourceHash':m['sourceHash'],'corpusHash':m['corpusHash'],'webContext':{k:m['web'][k] for k in ('userId','positionId','skillId','question','indexProofSha256')},
                'requests':[json.loads(line) for line in (folder/'requests.private.jsonl').read_text(encoding='utf-8').splitlines()]}
            capture['bounds']=assess_dry_run(m,capture)
            write_new(folder/'dry-run.private.json',_canonical(capture))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--run-dir',required=True);p.add_argument('--index',required=True)
    p.add_argument('--prepare',action='store_true');p.add_argument('--q12');p.add_argument('--registry');p.add_argument('--user',type=int);p.add_argument('--position')
    p.add_argument('--mode',choices=('offline','live'),default='offline');p.add_argument('--live',action='store_true');p.add_argument('--approval')
    a=p.parse_args()
    try:
        if a.prepare:
            if a.live or a.approval:raise GuardError('WEB_MODE_INVALID')
            prepare(a.run_dir,a.index,a.q12,a.registry,a.user,a.position,mode=a.mode)
        else:broker(a.run_dir,a.index,live=a.live,approval=a.approval)
    except Exception:raise SystemExit('WEB_PREPARATION_OR_TRANSPORT_BLOCKED') from None
