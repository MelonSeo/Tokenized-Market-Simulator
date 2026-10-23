"""Read-only ancestor carry and exclusive, one-question continuation. No HTTP/key.

Uncertain ancestor reservations stay charged. An uncertain continuation may NOT
spawn a successor. Preparation never creates a paid approval or clears a STOP.
"""
import base64
from contextlib import closing
import hashlib
import os
from pathlib import Path
import re
import sqlite3
import time
import uuid
from ai_evaluation_guard import EvaluationGuard, GuardError, POLICY, _bound, _canonical, _decode
from ai_evaluation_lineage import verify as legacy_verify

REGISTRY = Path(__file__).resolve().parent/'runtime'/'ai-evaluation-continuations-v1'
KEYS = ('llm_calls','embedding_calls','input_tokens','output_tokens','embedding_tokens','reserved_nusd')


def scope(question):
    if type(question) is not int or question not in (12,14,15):
        raise GuardError('QUESTION_SCOPE_INVALID')
    calls, output = (1,1600) if question == 12 else (2,2000)
    return dict(zip(KEYS,(calls,1,32768*calls,output,1032,
        32768*calls*POLICY['input_nusd']+output*POLICY['output_nusd']+1032*POLICY['embedding_nusd'])))


def sha(path):
    p=Path(path)
    if p.is_symlink() or not p.is_file():raise GuardError('ANCESTOR_INVALID')
    return hashlib.sha256(p.read_bytes()).hexdigest()


def context(meta):
    return hashlib.sha256(_canonical(meta)).hexdigest()


def read_meta(folder):
    return _decode((Path(folder)/'run.json').read_bytes())


def snapshot(folder):
    """Validate exact ledger identity and every local reservation, never refund."""
    try:
        original=Path(folder).absolute()
        if original.is_symlink():raise ValueError()
        p=original.resolve(strict=True);m=read_meta(p)
        if not re.fullmatch(r'eval_[0-9a-f]{32}',m['runId']):raise ValueError()
        ledger=p/'budget.sqlite'; before=sha(ledger)
        # Ancestors must be quiescent, not a live SQLite WAL snapshot.
        if any(Path(str(ledger)+suffix).exists() for suffix in ('-wal','-journal')):raise ValueError()
        with closing(sqlite3.connect(ledger.as_uri()+'?mode=ro',uri=True)) as db:
            identity=_decode(db.execute('select value from identity where id=1').fetchone()[0].encode())
            expected={'run':m['runId'],'source':m['sourceHash'],'corpus':m['corpusHash'],
                      'context':context(m),'policy':m['policy']}
            if 'lineage' in m:
                if m['policy']!=POLICY:raise ValueError()
                expected['carry']={('reserved_nusd' if k=='cap_nusd' else k):v for k,v in m['lineage']['spent'].items()}
            if 'continuation' in m:
                expected['carry']=m['continuation']['carry'];expected['local_limits']=scope(m['questions'][0])
            if identity!=expected:raise ValueError()
            totals=dict.fromkeys(KEYS,0);uncertain=0
            for rid,kind,inp,out,emb,cost,outcome,digest in db.execute('select * from reservations'):
                if (str(uuid.UUID(rid))!=rid or kind not in ('llm','embedding')
                        or any(type(v) is not int or v<0 for v in (inp,out,emb,cost))
                        or outcome not in ('OBSERVED','RESERVED') or not re.fullmatch('[0-9a-f]{64}',digest)
                        or (kind=='llm' and (emb!=0 or not 1<=inp<=32768 or not 1<=out<=1600))
                        or (kind=='embedding' and (inp!=0 or out!=0 or not 1<=emb<=16*8191))
                        or cost!=inp*POLICY['input_nusd']+out*POLICY['output_nusd']+emb*POLICY['embedding_nusd']):raise ValueError()
                for key,value in zip(KEYS,(int(kind=='llm'),int(kind=='embedding'),inp,out,emb,cost)):totals[key]+=value
                uncertain+=int(outcome=='RESERVED')
            stopped=db.execute('select stopped from control where id=1').fetchall()
            if stopped not in ([(0,)],[(1,)]):raise ValueError()
        permit=p/'approval.json'; approval=None
        if permit.exists():
            a=_decode(permit.read_bytes());approval=sha(permit)
            if any(a.get(k)!=m[k] for k in ('runId','sourceHash','corpusHash')) or type(a.get('expiresAt')) is not int:raise ValueError()
            active=a['expiresAt']>time.time()
        else:active=False
        stop=p/'STOP';stop_sha=sha(stop) if stop.exists() else None
        # Even STOP is insufficient while an old paid permit is still live:
        # old runners may already be in flight. Require expiry plus quiescence.
        if active:raise GuardError('ANCESTOR_AUTHORIZATION_ACTIVE')
        if uncertain and stop_sha is None:raise GuardError('UNCERTAIN_WITHOUT_STOP')
        if before!=sha(ledger):raise ValueError()
        return {'dir':str(p),'runId':m['runId'],'sourceHash':m['sourceHash'],'corpusHash':m['corpusHash'],
            'runSha256':sha(p/'run.json'),'ledgerSha256':before,'approvalSha256':approval,
            'stopSha256':stop_sha,'controlStopped':stopped[0][0], 'local':totals,'uncertain':uncertain}
    except GuardError:raise
    except Exception:raise GuardError('ANCESTOR_INVALID') from None


def _carry(snapshots):
    result={k:sum(s['local'][k] for s in snapshots) for k in KEYS}
    if any(result[k]>POLICY['cap_nusd' if k=='reserved_nusd' else k] for k in KEYS):
        raise GuardError('LINEAGE_OVER_BUDGET')
    return result


def _review(folder,meta):
    """Operator quality judgement, not model/JUnit-generated PASS."""
    p=Path(folder);a=_decode((p/'quality-review.json').read_bytes())
    result=_decode((p/'question-result.private.json').read_bytes(),max_bytes=2000000)
    completion=_decode((p/'completion.json').read_bytes())
    s=snapshot(p)
    if (result.get('number')!=meta['questions'][0] or result.get('result',{}).get('status')!='ANSWERED'
            or completion.get('status')!='EVALUATION_EXECUTED_NOT_AUTOMATIC_PASS'
            or completion.get('questions')!=1 or s['local']['llm_calls']<1):
        raise GuardError('QUALITY_PASS_REQUIRED')
    if a!={'runId':meta['runId'],'sourceHash':meta['sourceHash'],'question':meta['questions'][0],
            'resultSha256':sha(p/'question-result.private.json'),'verdict':'PASS','reviewedBy':'USER'}:
        raise GuardError('QUALITY_PASS_REQUIRED')
    if s['uncertain']:raise GuardError('PREDECESSOR_UNCERTAIN')
    return sha(p/'quality-review.json')


def verify(folder,meta,registry=REGISTRY):
    try:
        p=Path(folder).resolve(strict=True);line=meta['continuation'];q=meta['questions'][0]
        if meta['questions']!=[q] or meta['policy']!=POLICY or line['scope']!=scope(q):raise ValueError()
        if sha(line['evidence']['path'])!=line['evidence']['sha256']:raise ValueError()
        ancestors=[snapshot(s['dir']) for s in line['ancestors']]
        if ancestors!=line['ancestors'] or len({s['runId'] for s in ancestors})!=len(ancestors):raise ValueError()
        if len(ancestors)<2 or any(s['corpusHash']!=meta['corpusHash'] for s in ancestors):raise ValueError()
        first,child=ancestors[:2]; cm=read_meta(child['dir'])
        legacy_verify(child['dir'],cm,Path(line['legacyRegistry']))
        if cm['lineage']['parentRunId']!=first['runId']:raise ValueError()
        if sha(Path(line['legacyRegistry'])/(first['runId']+'.json'))!=line['legacyClaimSha256']:raise ValueError()
        if q!=({2:12,3:14,4:15}.get(len(ancestors))):raise ValueError()
        for i in range(2,len(ancestors)):
            am=read_meta(ancestors[i]['dir'])
            verify(ancestors[i]['dir'],am,registry)
            if am['continuation']['ancestors']!=ancestors[:i]:raise ValueError()
            if _review(ancestors[i]['dir'],am)!=line['reviews'][i-2]:raise ValueError()
        if len(line['reviews'])!=len(ancestors)-2 or line['carry']!=_carry(ancestors):raise ValueError()
        expected={'childDir':str(p),'childContextHash':context(meta)}
        claim=Path(registry)/(ancestors[-1]['runId']+'.json')
        if _decode(claim.read_bytes())!=expected:raise ValueError()
        return line['carry']
    except Exception:raise GuardError('CONTINUATION_INVALID') from None


def prepare(predecessor,folder,port,source,corpus,*,legacy_registry,evidence,registry=REGISTRY):
    if type(port) is not int or not 49152<=port<=65535 or not re.fullmatch('[0-9a-f]{64}',source):
        raise GuardError('RUN_TARGET_INVALID')
    prev=Path(predecessor).resolve(strict=True);pm=read_meta(prev)
    if 'continuation' in pm:
        verify(prev,pm,registry);review=_review(prev,pm)
        ancestors=pm['continuation']['ancestors']+[snapshot(prev)]
        reviews=pm['continuation']['reviews']+[review]
        legacy_registry=Path(pm['continuation']['legacyRegistry'])
    else:
        legacy_verify(prev,pm,legacy_registry)
        ancestors=[snapshot(pm['lineage']['parentDir']),snapshot(prev)];reviews=[]
    q={2:12,3:14,4:15}.get(len(ancestors))
    limits=scope(q);carry=_carry(ancestors)
    if any(s['corpusHash']!=corpus for s in ancestors):raise GuardError('CORPUS_CHANGED')
    if any(carry[k]+limits[k]>POLICY['cap_nusd' if k=='reserved_nusd' else k] for k in KEYS):
        raise GuardError('CONTINUATION_BUDGET_INSUFFICIENT')
    evidence_meta={'path':str(Path(evidence).resolve(strict=True)),'sha256':sha(evidence)}
    if 'continuation' in pm and evidence_meta!=pm['continuation']['evidence']:raise GuardError('EVIDENCE_CHANGED')
    target=Path(folder).absolute();target.mkdir(exist_ok=False,parents=False)
    rid='eval_'+uuid.uuid4().hex
    line={'ancestors':ancestors,'carry':carry,'scope':limits,'reviews':reviews,'evidence':evidence_meta,
          'legacyRegistry':str(Path(legacy_registry).resolve()),
          'legacyClaimSha256':sha(Path(legacy_registry)/(ancestors[0]['runId']+'.json'))}
    meta={'version':1,'runId':rid,'sourceHash':source,'corpusHash':corpus,'dbPort':port,
          'dbName':'exchange_ai_'+rid,'dbUser':'exchange_ai_'+rid,'mode':'live','fixture':'success',
          'policy':POLICY,'questions':[q],'continuation':line}
    root=Path(registry);root.mkdir(parents=True,exist_ok=True)
    try:fd=os.open(root/(ancestors[-1]['runId']+'.json'),os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    except FileExistsError:raise GuardError('CONTINUATION_ALREADY_CLAIMED') from None
    with os.fdopen(fd,'wb') as f:
        f.write(_canonical({'childDir':str(target.resolve()),'childContextHash':context(meta)}));f.flush();os.fsync(f.fileno())
    with (target/'run.json').open('xb') as f:f.write(_canonical(meta));f.flush();os.fsync(f.fileno())
    EvaluationGuard(target/'budget.sqlite',run_id=rid,source_hash=source,corpus_hash=corpus,
        context_hash=context(meta),create=True,carry=carry,local_limits=limits,
        validate_lineage=lambda:verify(target,meta,root))
    return meta


def assess(meta,capture):
    """Actual Java serialized requests; no sender and no request content output."""
    q=meta['questions'][0];limits=scope(q);results=[]
    if (capture.get('providerCalls')!=0 or capture.get('sourceHash')!=meta['sourceHash']
            or capture.get('evidenceSha256')!=meta['continuation']['evidence']['sha256']
            or capture.get('processedQuestions')!=[q] or capture.get('preservedFuturesEvidence') is not True
            or len(capture.get('profiles',[]))!=2
            or {p.get('profile') for p in capture['profiles']}!={'EXPECTED_STATE','MAXIMUM_MIXED'}):
        raise GuardError('CONTINUATION_DRY_RUN_INVALID')
    for profile in capture['profiles']:
        totals=dict.fromkeys(KEYS,0)
        for row in profile['requests']:
            if row['question']!=q:raise GuardError('QUESTION_SCOPE_INVALID')
            kind,inp,out,emb=_bound(row['path'],base64.b64decode(row['bodyBase64'],validate=True),POLICY)
            cost=inp*POLICY['input_nusd']+out*POLICY['output_nusd']+emb*POLICY['embedding_nusd']
            for k,v in zip(KEYS,(int(kind=='llm'),int(kind=='embedding'),inp,out,emb,cost)):totals[k]+=v
        if not totals['llm_calls'] or totals['embedding_calls']!=1 or any(totals[k]>limits[k] for k in KEYS):
            raise GuardError('CONTINUATION_DRY_RUN_OVER_SCOPE')
        results.append(totals)
    return results


if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser(description='Offline preparation only; never authorizes payment.')
    parser.add_argument('--predecessor',required=True);parser.add_argument('--run-dir',required=True)
    parser.add_argument('--legacy-registry',required=True);parser.add_argument('--evidence',required=True)
    parser.add_argument('--port',required=True,type=int)
    args=parser.parse_args()
    from ai_evaluation_broker import source_hash,corpus_hash
    m=prepare(args.predecessor,args.run_dir,args.port,source_hash(),corpus_hash(),
        legacy_registry=Path(args.legacy_registry),evidence=Path(args.evidence))
    print(_canonical({'prepared':True,'runId':m['runId'],'questions':m['questions'],
        'carry':m['continuation']['carry'],'localLimits':m['continuation']['scope'],'paidApproved':False}).decode())
