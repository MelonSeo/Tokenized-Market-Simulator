"""Read-only API/DB behavior; create-new derived evaluation files, never call providers."""
import argparse
import json
from pathlib import Path
import sqlite3


def analyze(folder, repo):
    p=Path(folder);repo=Path(repo)
    reads=lambda name:json.loads((p/name).read_text(encoding='utf-8'))
    search=reads('retrieval.json')
    sup=json.loads((repo/'backend/src/test/resources/ai/retrieval-supplement.json').read_text(encoding='utf-8'))
    metrics={}
    for group in ['golden','negative','identifiers']:
        rows=[r for r in search if r['group']==group]
        if group=='negative':
            metrics[group]={'questions':len(rows),'retrievalFalsePositives':sum(bool(r['hits']) for r in rows),
                            'rows':[{'question':r['question'],'hits':len(r['hits']),'paths':[h['path'] for h in r['hits']]} for r in rows]}
        else:
            anchors=sup['goldenEvidence'] if group=='golden' else [[r['needle']] for r in sup['identifiers']]
            direct=[]
            for row,expected in zip(rows,anchors):
                found=any(any(needle in h['content'] for needle in expected) for h in row['hits'])
                direct.append({'question':row['question'],'rank':row['rank'],'directEvidence':found,'paths':[h['path'] for h in row['hits']]})
            metrics[group]={'questions':len(rows),'hits':sum(r['rank']>0 for r in rows),
                'mrr5':sum(r['reciprocalRank'] for r in rows)/len(rows),'directEvidence':sum(r['directEvidence'] for r in direct),'rows':direct}
    usage=[]
    for f in sorted(p.glob('usage-*.json')):
        r=json.loads(f.read_text(encoding='utf-8'));ins=r['inputTokens'];outs=r['outputTokens']
        if ins is None or outs is None:raise ValueError('USAGE_UNCERTAIN')
        # Standard rate, no cached-input discount; not an invoice.
        cost=(ins*2000+outs*12000) if r['stage']=='LLM' else ins*20
        usage.append({'ordinal':r['ordinal'],'stage':r['stage'],'inputTokens':ins,'outputTokens':outs,'success':r['success'],'estimatedCostNanoUsd':cost})
    c=sqlite3.connect((p/'budget.sqlite').resolve().as_uri()+'?mode=ro',uri=True)
    ledger=[dict(zip(['kind','outcome','calls','reservedNanoUsd'],row)) for row in c.execute('select kind,outcome,count(*),sum(cost) from reservations group by kind,outcome')]
    c.close()
    summary={'retrieval':metrics,'requests':usage,'ledger':ledger,'actualLlmInputTokens':sum(r['inputTokens'] for r in usage if r['stage']=='LLM'),
             'actualLlmOutputTokens':sum(r['outputTokens'] for r in usage if r['stage']=='LLM'),'actualEmbeddingTokens':sum(r['inputTokens'] for r in usage if r['stage']=='EMBEDDING'),
             'estimatedStandardNanoUsd':sum(r['estimatedCostNanoUsd'] for r in usage),'invoiceVerified':False}
    with (p/'derived-evaluation.json').open('x',encoding='utf-8') as f:json.dump(summary,f,ensure_ascii=False,indent=2)
    print(json.dumps({k:v for k,v in summary.items() if k not in ['requests','retrieval']},ensure_ascii=True))
    print(json.dumps({'retrieval':metrics},ensure_ascii=True))


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--run-dir',required=True);p.add_argument('--repo',default='.')
    a=p.parse_args();analyze(a.run_dir,a.repo)
