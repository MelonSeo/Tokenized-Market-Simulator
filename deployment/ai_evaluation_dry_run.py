"""Apply the exact guard bounds to Java-serialized requests. Never dispatch."""
import argparse
import base64
import json
from pathlib import Path
from ai_evaluation_guard import POLICY,_bound
from ai_reevaluation_plan import derive

def assess(parent,capture):
    budget=derive(parent);data=json.loads(Path(capture).read_text(encoding='utf-8'))
    if data.get('providerCalls')!=0:raise ValueError('NOT_OFFLINE_CAPTURE')
    results=[]
    for profile in data['profiles']:
        totals=dict(llm_calls=0,embedding_calls=0,input_tokens=0,output_tokens=0,embedding_tokens=0,cap_nusd=0);rows=[]
        for row in profile['requests']:
            if row['question'] not in range(2,17):raise ValueError('QUESTION_SCOPE_INVALID')
            raw=base64.b64decode(row['bodyBase64'],validate=True);kind,inp,out,emb=_bound(row['path'],raw,POLICY)
            cost=inp*POLICY['input_nusd']+out*POLICY['output_nusd']+emb*POLICY['embedding_nusd']
            for k,v in dict(llm_calls=int(kind=='llm'),embedding_calls=int(kind=='embedding'),input_tokens=inp,output_tokens=out,embedding_tokens=emb,cap_nusd=cost).items():totals[k]+=v
            rows.append({'question':row['question'],'kind':kind,'serializedBytes':len(raw),'inputBound':inp,'outputBound':out,'embeddingBound':emb})
        over=[k for k,v in totals.items() if v>budget['remaining'][k]]
        results.append({'profile':profile['profile'],'total':totals,'overLimits':over,'fits':not over,'requests':rows})
    return {'providerCalls':0,'paidApproved':False,'parentLedgerSha256':budget['parentLedgerSha256'],'remaining':budget['remaining'],
            'preservedFuturesEvidence':data.get('preservedFuturesEvidence',False),'profiles':results,'allProfilesFit':all(r['fits'] for r in results)}

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--parent',required=True);p.add_argument('--capture',required=True);p.add_argument('--output',required=True);a=p.parse_args()
    result=assess(a.parent,a.capture)
    with Path(a.output).open('x',encoding='utf-8') as f:json.dump(result,f,indent=2)
    print(json.dumps({k:v for k,v in result.items() if k!='profiles'}));print(json.dumps([{'profile':r['profile'],'total':r['total'],'overLimits':r['overLimits']} for r in result['profiles']]))
