"""Exactly the stopped 23-response history; read-only proposal, never approval.

D2's original deadline FAIL is immutable. A NEW, timely offline semantic review
may justify not replaying the captured response, but cannot repair the old gate.
The single inherited B5 UNKNOWN remains fully held. No key/network/refund API.
"""
from pathlib import Path
from ai_pilot_preflight import ROOT,need,FROZEN,V2_FILE_HASHES
from ai_pilot_continuation import read,sha,ro,row_hash,TABLES
from ai_pilot_coordinator import workload,PilotCoordinator
from ai_pilot_held_resume import digest,LIMITS
from ai_pilot_review import assessment_valid

VERSION='tms-pilot-review-carry23-v5'

def history_from(ancestor):
    ancestor=Path(ancestor)
    with ro(ancestor/'pilot-v2.sqlite') as db:
        tables={t:db.execute('SELECT * FROM '+t+' ORDER BY 1').fetchall() for t in TABLES}
    return {'tables':tables,'files':{n:sha(ancestor/n) for n in ('pilot-v2.sqlite','session.json','STOP')}}

def inspect(parent,bundle,pg_rows,termination,history,*,execution=None):
    parent=Path(parent);old=read(parent/'session.json');d=old['continuation']
    need(old['mode']=='PAID_EXPLICIT_V2' and d['version']=='tms-pilot-held-b5-v4'
         and (parent/'STOP').is_file(),'V5_PARENT_NOT_STOPPED')
    need(all(old[k]==d[k] for k in ('allocation','approvalId','budgetAuthority','fixtureHash'))
         and d['limits']==LIMITS and d['frozen']==FROZEN and d['policies']==V2_FILE_HASHES,'V5_AUTHORITY_CHANGED')
    need(termination=={'financialUnchanged':True,'cStateUnchanged':True,'resourcesStopped':True,
         'serverPassed':1,'serverFailed':0,'serverSkipped':0},'V5_TERMINATION_UNPROVEN')
    need(history['files']==d['parentFiles'] and digest(history['tables'])==d['rowsSha256'],'V5_ANCESTRY_CHANGED')
    rows=workload(old['fixture'])[:23];need(len(bundle)==23 and digest(bundle[:16])==d['prefixSha256'],'V5_PREFIX_CHANGED')
    frozen=[read(ROOT/'backend/src/test/resources/ai'/n) for n in ('pilot-acceptance-v1.json','novel-30-v2.json')]
    execution=Path(execution) if execution is not None else parent.parent
    reviews=execution/'reviews';bound={}
    for i,(entry,row) in enumerate(zip(bundle,rows)):
        e=entry['evidence'];need(e['row']==row and e['criteria']==frozen[0]['criteria']
            and e['frozenExpected']==next(c for s in frozen for c in s['cases'] if c['id']==row['case']),'V5_FROZEN_DRIFT')
        if i==15:
            need(row['case']=='B5' and entry['quality']=='NOT_RUN' and 'receipt' not in entry,'V5_B5_CHANGED');continue
        need(entry['quality'] in {'PASS','PARTIAL','FAIL'},'V5_QUALITY_CHANGED')
        if i>=16:
            key=digest(e);rq=reviews/(key+'.request.private.json');decision=reviews/(key+'.decision.json')
            need(read(rq)==e,'V5_RESPONSE_CHANGED');decision_value=read(decision)
            need(decision_value['evidenceSha256']==key,'V5_RESPONSE_CHANGED')
            notes=decision_value['notes'];grade='FAIL' if notes.startswith('SAFE_QUALITY_FAIL:') else notes.split(':',1)[0].split(' ',1)[0]
            need(grade==entry['quality'],'V5_QUALITY_CHANGED')
            for f in (rq,decision,reviews/(key+'.timing.json')):bound[f.relative_to(execution).as_posix()]=sha(f)
        if i<22:
            r=entry['receipt'];need(r['evidenceSha256']==digest(e) and r['deadlineMs']==300000
                and type(r['elapsedMillis']) is int and 0<=r['elapsedMillis']<300000
                and type(r['accepted']) is bool and r['accepted']==(entry['quality']!='FAIL'),'V5_REVIEW_UNPROVEN')
            if i>=16:
                f=reviews/(digest(e)+'.receipt.json');need(read(f)==r,'V5_REVIEW_UNPROVEN');bound[f.relative_to(execution).as_posix()]=sha(f)
        else:
            need(row['case']=='D2' and entry['quality']=='PARTIAL' and 'receipt' not in entry
                 and not (reviews/(digest(e)+'.receipt.json')).exists(),'V5_D2_FAILURE_CHANGED')
            p=entry['offlineReview'];a=assessment_valid(p['assessment'],e);r=p['receipt']
            need(p['status']=='OFFLINE_REVIEW_ONLY_NOT_ORIGINAL_GATE_PASS' and p['providerRequests']==0 and p['keyReads']==0
                 and p['originalResponseReused'] is True and p['originalRequestSha256']==sha(rq)
                 and a['quality']=='PARTIAL' and a['safeToContinue'] is True and r['evidenceSha256']==digest(e)
                 and r['accepted'] is True and r['reviewer']==a['reviewer'] and r['deadlineMs']==300000
                 and type(r['elapsedMillis']) is int and 0<=r['elapsedMillis']<300000
                 and p['reviewCodeSha256']==sha(ROOT/'deployment/ai_pilot_review.py')
                 and p['completion']['evidenceSha256']==digest(e) and p['completion']['deadlineMs']==300000
                 and type(p['completion']['elapsedMillis']) is int and r['elapsedMillis']<=p['completion']['elapsedMillis']<300000,
                 'V5_D2_OFFLINE_REVIEW_REQUIRED')
    need(read(execution/'inner-failure.json')['code']=='V2_SEMANTIC_REVIEW_REQUIRED','V5_STOP_REASON_CHANGED')
    bound['inner-failure.json']=sha(execution/'inner-failure.json')
    with ro(parent/'pilot-v2.sqlite') as db:
        expected={'version':2,'allocation':old['allocation'],'mode':'PAID_V2','source':old['source'],
                  'fixture':old['fixtureHash'],'budget':digest(read(ROOT/'deployment/ai-pilot-budget-v2.json')),
                  'plan':digest(read(ROOT/'deployment/ai-pilot-execution-v2.json'))}
        need(read(parent/'session.json')['fixtureHash']==digest(old['fixture'])
             and __import__('json').loads(db.execute('SELECT value FROM identity').fetchone()[0])==expected,'V5_IDENTITY_CHANGED')
        need(db.execute('SELECT phase,stopped FROM control').fetchall()==[(2,1)],'V5_PHASE_CHANGED')
        for table,values in history['tables'].items():
            actual=db.execute('SELECT * FROM '+table+' ORDER BY 1').fetchall()
            need(all(tuple(v) in actual for v in values),'V5_ANCESTRY_ROWS_LOST')
        subs=db.execute('SELECT ticket,phase,slot,actor,body_hash,admitted,complete,server_request,worker_exited FROM submissions').fetchall()
        by={(s[1],s[2]):s for s in subs if s[1]!='INDEX_LEGACY'}
        ix=[s for s in subs if s[1]=='INDEX_LEGACY']
        need(len(subs)==24 and len(by)==23 and len(ix)==1 and ix[0][5:7]==(1,1) and ix[0][8]==1,'V5_SUBMISSION_CHANGED')
        for i,(row,entry) in enumerate(zip(rows,bundle)):
            s=by.get((row['phase'],row['slot']));need(s is not None and s[3:5]==(row['uid'],row['bodySha256'])
                 and s[5]==1 and s[7]==entry['evidence']['reply']['runId']
                 and (s[6],s[8])==((0,0) if i==15 else (1,1)),'V5_SUBMISSION_CHANGED')
        unknown=db.execute("SELECT * FROM reservations WHERE outcome!='OBSERVED'").fetchall()
        need(unknown==[tuple(d['heldReservation'])] and unknown[0][6]==29060,'V5_UNKNOWN_CHANGED')
        need(db.execute('SELECT ticket,disposition FROM held_terminal').fetchall()==[(d['failedTicket'],'B5_TECHNICAL_FAIL_NO_REPLAY')],'V5_B5_DISPOSITION_CHANGED')
        local={r[0]:(r[3],r[6],r[8]) for r in db.execute('SELECT * FROM reservations')}
        remote={r['id']:(r['kind'],r['reserved'],r['state']) for r in pg_rows}
        need(len(local)==59 and len(remote)==len(pg_rows) and local==remote,'V5_BUDGET_MISMATCH')
        totals=PilotCoordinator.totals(db)
        need((totals['llmCalls'],totals['embeddingCalls'],totals['reservedNusd'])==(31,28,1418256940),'V5_TOTALS_CHANGED')
        need({r[0] for r in db.execute('SELECT name FROM phases WHERE complete=1')}=={'INDEX_LEGACY','SAFETY_LEGACY'},'V5_PHASE_CHANGED')
        rows_hash=row_hash(db)
    return {'version':VERSION,'allocation':old['allocation'],'approvalId':old['approvalId'],'budgetAuthority':old['budgetAuthority'],
        'fixtureHash':old['fixtureHash'],'oldSource':old['source'],'failedTicket':d['failedTicket'],'heldReservation':d['heldReservation'],
        'parentFiles':{n:sha(parent/n) for n in ('pilot-v2.sqlite','session.json','STOP')},'executionFiles':bound,
        'rowsSha256':rows_hash,'prefixSha256':digest(bundle),'pgRowsSha256':digest(pg_rows),'historySha256':digest(history),
        'terminationSha256':digest(termination),'priorReservedNusd':1418256940,'priorLlmCalls':31,'priorEmbeddingCalls':28,
        'priorResponses':23,'originalTimelyReviews':21,'originalD2Gate':'FAIL','offlineD2Content':'PARTIAL',
        'remainingRequests':34,'remainingLlmCalls':61,'remainingEmbeddingCalls':42,'remainingCapNusd':16581743060,
        'limits':LIMITS,'frozen':FROZEN,'policies':V2_FILE_HASHES}

def exception_context(proposal,new_source,child_id):
    import re
    need(proposal['version']==VERSION and re.fullmatch('[0-9a-f]{64}',new_source) is not None
         and re.fullmatch('[0-9a-f]{16}',child_id) is not None,'V5_EXCEPTION_SCOPE')
    return {'version':'tms-review-carry23-exception-v1','proposalSha256':digest(proposal),'newSource':new_source,'childId':child_id,
        'allocation':proposal['allocation'],'approvalId':proposal['approvalId'],'holdUnknownFull':True,'skipB5NoReplay':True,
        'skipTwentyThreeNoReplay':True,'preserveD2OriginalFailure':True,'stopOnAnyNewUnknown':True,'refundAllowed':False,'maximumSuccessors':1}

def verify_anchor(meta,parent):
    parent=Path(parent);d=meta['continuation'];anchor=Path(meta['folder']).parent/'anchor'
    need(set(d['anchorFiles'])=={'prefix-twenty-three.json','pg-rows.json','termination.json','history.json','held-exception.json'},'V5_ANCHOR_SCOPE')
    for n,h in d['anchorFiles'].items():need(sha(anchor/n)==h,'V5_ANCHOR_CHANGED')
    bundle=read(anchor/'prefix-twenty-three.json');observed=inspect(parent,bundle,read(anchor/'pg-rows.json'),read(anchor/'termination.json'),read(anchor/'history.json'),execution=anchor/'parent-execution')
    need(all(d.get(k)==v for k,v in observed.items()),'V5_PARENT_CHANGED')
    need(all(meta[k]==observed[k] for k in ('allocation','approvalId','budgetAuthority','fixtureHash')),'V5_AUTHORITY_CHANGED')
    from ai_pilot_held_resume import validate_exception
    validate_exception(observed,read(anchor/'held-exception.json'),meta['source'],d['childId'])
    need(read(parent/'CONTINUATION_CLAIM.json')=={'childId':d['childId'],'source':meta['source'],'allocation':meta['allocation'],
         'continuationSha256':digest(d)},'V5_SINGLE_SUCCESSOR_REQUIRED')
    return bundle
