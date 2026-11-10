"""Read-only frozen-case analysis. Execution, evidence and natural-language quality are NOT interchangeable."""
import argparse,collections,json,re
from pathlib import Path
from ai_pilot_preflight import ROOT,preflight
from ai_novel_question_audit import retrieval_metrics

def routing(expected,reply,http):
    route=reply.get('route');status=reply.get('status')
    if expected=='DENIED':
        metrics=reply.get('metrics')
        observed=isinstance(metrics,dict) and all(type(metrics.get(k)) is int and metrics[k]==0 for k in ('modelCalls','retrievalCalls'))
        return 'PASS' if http==404 and observed else 'FAIL_MISSING_OR_NONZERO_DENIAL_METRICS'
    if route==expected:return 'PASS'
    if expected=='STATE_OR_CLARIFY' and (route=='STATE' or status=='NEEDS_CLARIFICATION'):return 'PASS'
    if expected in {'STATE_OR_SCOPE_LIMIT','KNOWLEDGE_OR_SCOPE_LIMIT'}:
        if route==expected.split('_OR_')[0] or status=='UNSUPPORTED':return 'PASS'
        return 'PARTIAL_SCOPE_REVIEW_REQUIRED'
    return 'FAIL'

def answer_completeness(expected,reply,http):
    if expected=='DENIED' and routing(expected,reply,http)=='PASS':return 'PASS_AUTHORIZATION_NOT_ANSWER_QUALITY'
    if reply.get('status')=='ANSWERED':return 'MANUAL_REVIEW_REQUIRED_NOT_AUTOMATIC_PASS'
    if reply.get('status')=='PARTIAL':return 'PARTIAL'
    return 'NOT_ANSWERED_OR_SCOPE_REVIEW_REQUIRED'

def case_result(case,record,events,atoms):
    if record is None:return {'id':case['id'],'http':'NOT_RUN','routing':'NOT_RUN','tools':'NOT_RUN','rag':'NOT_RUN','requiredEvidence':'NOT_RUN','answerSufficiency':'NOT_RUN','realModelQuality':'NOT_RUN'}
    reply=record['reply'];rid=reply.get('runId');own=[e for e in events if e.get('requestId')==rid];finals=[e for e in own if e['kind']=='FINAL'];candidates=[e for e in own if e['kind']=='CANDIDATES']
    successful={t['tool'] for t in reply.get('toolEvidence',[]) if t.get('status')=='SUCCESS'};declared=set(case['tools']);called=reply.get('toolEvidence',[])
    if case['route']=='DENIED':tools='PASS_EXPECTED_DENIAL' if routing('DENIED',reply,record['http'])=='PASS' and not called else 'NOT_VERIFIED_DENIAL'
    else:tools='PASS' if called and all(t.get('status')=='SUCCESS' for t in called) else 'FAIL' if called else 'NOT_RUN'
    rag='PASS' if finals and all(e.get('code')=='OK' for e in finals) else 'FAIL' if finals else 'NOT_RUN'
    support=retrieval_metrics(reply.get('knowledgeSources',[]),[atoms[a] for a in case['atoms']])
    evidence='NOT_APPLICABLE_DENIAL' if case['route']=='DENIED' else 'NOT_RUN' if not finals else 'PASS' if support['allSupportAt5'] else 'PARTIAL' if support['hitAt5'] else 'FAIL_CONTROLLED_FIXTURE'
    return {'id':case['id'],'category':case['category'],'http':record['http'],'httpExpectation':'PASS' if record['http']==case.get('expectedHttp',200) else 'FAIL',
      'expectedRoute':case['route'],'actualRoute':reply.get('route'),'routing':routing(case['route'],reply,record['http']),'status':reply.get('status'),'error':reply.get('error'),
      'tools':tools,'successfulTools':sorted(successful),'declaredTools':sorted(declared),'declaredToolsNotObserved':sorted(declared-successful),'declaredToolListIsNotUnconditionalCallRequirement':True,
      'rag':rag,'retrieval':support,'requiredEvidence':evidence,'citations':len(reply.get('citationIds',[])),'answerSufficiency':answer_completeness(case['route'],reply,record['http']),
      'uncertainties':reply.get('uncertainties',[]),'metrics':reply.get('metrics',{}),'httpElapsedMs':record.get('httpElapsedMs'),'actualSearches':finals,'candidateSearches':candidates,'realModelQuality':'NOT_RUN_CONTROLLED_PROVIDER'}

def summarize(name):
    if not re.fullmatch(r'pilot-linux-[a-f0-9]{16}',name):raise ValueError('REPORT_OWNED_RUN_REQUIRED')
    folder=ROOT/'deployment/runtime'/name
    if folder.is_symlink():raise ValueError('REPORT_SYMLINK')
    preflight();frozen=json.loads((ROOT/'backend/src/test/resources/ai/novel-30-v2.json').read_text(encoding='utf8'))
    raw=json.loads((folder/'http.private.json').read_text(encoding='utf8'));records={r['case']:r for r in raw if r['phase']=='NOVEL30'}
    if len(records)!=sum(r['phase']=='NOVEL30' for r in raw):raise ValueError('REPORT_DUPLICATE_CASE')
    trace=folder/'retrieval.private.jsonl';events=[json.loads(s) for s in trace.read_text(encoding='utf8').splitlines()] if trace.exists() else []
    rows=[case_result(c,records.get(c['id']),events,frozen['atoms']) for c in frozen['cases']]
    fields=['httpExpectation','routing','tools','rag','requiredEvidence','answerSufficiency']
    summary={key:dict(collections.Counter(r.get(key,'NOT_RUN') for r in rows)) for key in fields}
    result={'status':'MEASURED_CONNECTION_NOT_REAL_MODEL_QUALITY_PASS','run':name,'recordedNovelQuestions':len(records),'expectedQuestions':30,'counts':summary,
      'sourceContract':'UNPAID_CONTROLLED_ONLY_UNIT_VECTORS_AND_FIXED_INSUFFICIENT_MODEL','paidCalls':0,'apiKeyReads':0,'qualityPassInferredFromHttpOrAnswered':False,'rows':rows}
    with (folder/'novel30-review.private.json').open('x',encoding='utf8') as out:json.dump(result,out,ensure_ascii=False,indent=2)
    print(json.dumps({k:v for k,v in result.items() if k!='rows'}))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--run',required=True);a=p.parse_args();summarize(a.run)
