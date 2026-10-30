"""Read-only local measurement summary. Does not score semantic answer truth.

No providers, subprocesses, DB mutations or automatic claim of quality PASS.
"""
import argparse
from collections import Counter, defaultdict
from contextlib import closing
import hashlib
import json
from pathlib import Path
import sqlite3
import statistics


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def percentile(values,p):
    import math
    return sorted(values)[max(0,math.ceil(len(values)*p)-1)] if values else None


def grounding(row):
    draft=row.get('generated') or {}
    if draft.get('status')!='ANSWERED':return {'status':'NO_ANSWERED_DRAFT_TO_VALIDATE','facts':0}
    model=row.get('modelEvidence') or {};known={t['evidenceId']:t['data'] for t in model.get('tools',[])}
    ids=set(known)|{k['id'] for k in model.get('knowledge',[])}
    assert set(draft.get('citationIds',[]))<=ids
    for fact in draft.get('facts',[]):
        value=known[fact['evidenceId']]
        for field in fact['pointer'].split('/')[1:]:
            field=field.replace('~1','/').replace('~0','~')
            value=value[int(field)] if isinstance(value,list) else value[field]
        rendered='null' if value is None else str(value).lower() if isinstance(value,bool) else str(value)
        assert rendered==fact['value']
    return {'status':'CITATIONS_AND_EXACT_SCALARS_VALID_NOT_NL_TRUTH_PROOF','facts':len(draft.get('facts',[]))}


def summarize(folder):
    folder=folder.resolve(strict=True);meta=read(folder/'run.json')
    if not meta.get('novelAcceptance') or meta['policy']['version']!='novel30-quality-v1':raise ValueError('NOVEL_RUN_REQUIRED')
    with closing(sqlite3.connect((folder/'budget.sqlite').as_uri()+'?mode=ro',uri=True)) as db:
        totals=db.execute("SELECT sum(kind='llm'),sum(kind='embedding'),coalesce(sum(input),0),coalesce(sum(output),0),coalesce(sum(embedding),0),coalesce(sum(cost),0) FROM reservations").fetchone()
        unknown=db.execute("SELECT count(*) FROM reservations WHERE outcome!='OBSERVED'").fetchone()[0]
    own=dict(zip(('llm_calls','embedding_calls','input_tokens','output_tokens','embedding_tokens','reserved_nusd'),(v or 0 for v in totals)))
    carry=meta.get('verificationLineage',{}).get('carry',{})
    charged={k:v+carry.get(k,0) for k,v in own.items()}
    assert all(charged[k]<=meta['policy']['cap_nusd' if k=='reserved_nusd' else k] for k in charged)
    usage=[read(p) for p in folder.glob('usage-*.json')]
    rows=read(folder/'questions.private.json') if (folder/'questions.private.json').exists() else []
    base=[r for r in rows if r['questionId']!='D5-missing']
    by_group=defaultdict(list)
    for r in base:by_group[r['questionId'][0]].append(r)
    groups={}
    for group in 'ABCDEF':
        values=by_group[group];ranked=[r['actualRetrievalMetrics'] for r in values if 'actualRetrievalMetrics' in r]
        times=[r['elapsedMs'] for r in values if 'elapsedMs' in r]
        groups[group]={'recorded':len(values),'http':dict(Counter(str(r.get('httpStatus','NOT_RUN')) for r in values)),
            'answerStatuses':dict(Counter(r.get('answer',{}).get('status',r.get('status','NOT_RUN')) for r in values)),
            'rankedRows':len(ranked),'hitAt5':sum(r['hitAt5'] for r in ranked)/len(ranked) if ranked else None,
            'mrrAt5':sum(r['mrrAt5'] for r in ranked)/len(ranked) if ranked else None,
            'allSupportAt5':sum(r['allSupportAt5'] for r in ranked)/len(ranked) if ranked else None,
            'supportRecallAt5':sum(r['supportRecallAt5'] for r in ranked)/len(ranked) if ranked else None,
            'medianMs':statistics.median(times) if times else None,'p95MsDescriptiveMixedCases':percentile(times,.95)}
    compact=[]
    for row in rows:
        answer=row.get('answer',{});validation=grounding(row)
        if row.get('actorRole')!='ADMIN':
            assert all(k.get('role')=='USER' for k in answer.get('knowledgeSources',[]))
            assert all(k.get('role')=='USER' for k in (row.get('modelEvidence') or {}).get('knowledge',[]))
        compact.append({'id':row['questionId'],'httpStatus':row.get('httpStatus'),'status':answer.get('status',row.get('status')),
            'elapsedMs':row.get('elapsedMs'),'route':answer.get('route'),'metrics':answer.get('metrics'),
            'uncertainties':answer.get('uncertainties'),'retrieval':row.get('actualRetrievalMetrics'),'grounding':validation,
            'semanticQuality':'MANUAL_REVIEW_REQUIRED'})
    result={'status':'MEASURED_NOT_AUTOMATIC_QUALITY_PASS','runId':meta['runId'],'stopped':(folder/'STOP').exists(),
        'datasetSha256':meta['datasetSha256'],'newBudgetOnly':charged,'thisSessionOnly':own,
        'newUnknownReservationsAfterStop':unknown if (folder/'STOP').exists() else None,
        'inFlightOrUnobservedReservations':unknown,
        'historicalReservedNotCharged':meta['separateApproval']['historicalTotalsNotCharged']['reserved_nusd'],
        'usageObservedFiles':len(usage),'standardActualEstimateNusd':sum(r['estimatedStandardNanoUsd'] for r in usage),
        'actualInputTokens':sum(r['inputTokens'] for r in usage if r['kind']=='llm'),
        'actualOutputTokens':sum(r['outputTokens'] for r in usage if r['kind']=='llm'),
        'actualEmbeddingTokens':sum(r['inputTokens'] for r in usage if r['kind']=='embedding'),
        'groups':groups,'rows':compact}
    if (folder/'financial-after.private.json').exists():result['financialAfter']=read(folder/'financial-after.private.json')
    return result


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--run',type=Path,required=True);p.add_argument('--save',action='store_true');p.add_argument('--progress',action='store_true');a=p.parse_args()
    result=summarize(a.run)
    if a.save:
        if not result['stopped']:raise ValueError('CLOSED_RUN_REQUIRED_FOR_FINAL_SAVE')
        with (a.run/'novel-measurements.private.json').open('x',encoding='utf-8') as f:json.dump(result,f,ensure_ascii=False,indent=2)
    if a.progress:result={k:v for k,v in result.items() if k not in ('rows','financialAfter')}
    print(json.dumps(result,ensure_ascii=False,indent=2))


if __name__=='__main__':main()
