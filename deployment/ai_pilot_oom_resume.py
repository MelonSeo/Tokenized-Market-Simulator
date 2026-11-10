"""Exact stopped 32-response OOM carry; proposal is NOT approval or a new budget.

Original C finality remains UNKNOWN. Only a separately verified frozen baseline
may be used for subsequent read-only requests. Old F1 is never replayed or passed.
"""
from pathlib import Path
from ai_pilot_preflight import ROOT,need,FROZEN,V2_FILE_HASHES
from ai_pilot_continuation import read,sha,ro,row_hash
from ai_pilot_coordinator import workload,PilotCoordinator
from ai_pilot_held_resume import digest,LIMITS
from ai_pilot_review import assessment_valid

VERSION='tms-pilot-oom-carry32-v6'
PREFIX='prefix-thirty-two.json'


def inspect(parent,bundle,pg_rows,termination,history,*,execution=None):
    parent=Path(parent);execution=Path(execution) if execution is not None else parent.parent
    old=read(parent/'session.json');d=old['continuation']
    need(old['mode']=='PAID_EXPLICIT_V2' and d['version']=='tms-pilot-review-carry23-v5'
         and (parent/'STOP').is_file(),'V6_PARENT_NOT_STOPPED')
    need(all(old[k]==d[k] for k in ('allocation','approvalId','budgetAuthority','fixtureHash'))
         and d['limits']==LIMITS and d['frozen']==FROZEN and d['policies']==V2_FILE_HASHES,'V6_AUTHORITY_CHANGED')
    need(history['files']==d['parentFiles'] and digest(history['tables'])==d['rowsSha256'],'V6_ANCESTRY_CHANGED')
    need(len(bundle)==32 and digest(bundle[:23])==d['prefixSha256'],'V6_PREFIX_CHANGED')
    bound={}
    def capture(name):
        p=Path(name);need(not p.is_absolute() and '..' not in p.parts,'V6_EVIDENCE_SCOPE')
        path=execution/p;value=read(path);bound[p.as_posix()]=sha(path);return value
    # Keep the original late D2, all earlier review evidence and the held B5 intact.
    for name,h in d['executionFiles'].items():
        path='anchor/parent-execution/'+name
        capture(path);need(bound[path]==h,'V6_ANCESTRY_CHANGED')
    need(capture('anchor/prefix-twenty-three.json')==bundle[:23],'V6_ANCESTRY_CHANGED')
    need(capture('final-fingerprint.json')['cStateUnchanged']=='UNKNOWN','V6_ORIGINAL_UNKNOWN_CHANGED')
    need(capture('post-stop-db-linux-audit-r3/financial-db.json')['baselineMatch'] is True,'V6_DB_UNPROVEN')
    audit=capture('post-stop-pg-only-audit/result.json')
    need(audit['pgSqliteMatching'] is True and audit['unknownHeldUnchanged'] is True
         and audit['pgStopped']=='STOPPED','V6_BUDGET_UNPROVEN')
    need(all(x=='STOPPED' for x in capture('carry-cleanup.json').values()),'V6_RESOURCES_NOT_STOPPED')
    need(termination=={'originalCFinal':'UNKNOWN','originalFinalFingerprintSha256':bound['final-fingerprint.json'],
         'dbBaselineMatch':True,'resourcesStopped':True,'frozenCopyBaselineMatch':True,
         'frozenCopyCProofSha256':termination.get('frozenCopyCProofSha256')}
         and isinstance(termination['frozenCopyCProofSha256'],str) and len(termination['frozenCopyCProofSha256'])==64,
         'V6_TERMINATION_UNPROVEN')
    copy=capture('resume-evidence/frozen-copy-proof.json')
    need(bound['resume-evidence/frozen-copy-proof.json']==termination['frozenCopyCProofSha256']
         and copy['status']=='PASS_FROZEN_COPY_NOT_ORIGINAL_FINALITY' and copy['originalCFinal']=='UNKNOWN_PRESERVED'
         and copy['sourceUnchanged'] is True and copy['restoredExit']==0 and copy['providerCalls']==copy['keyReads']==0
         and copy['restoredC']['baselineMatch'] is True and copy['restoredC']['sourceGenesisAndHeadHeadersMatch'] is True,
         'V6_COPY_BASELINE_UNPROVEN')
    frozen=[read(ROOT/'backend/src/test/resources/ai'/n) for n in ('pilot-acceptance-v1.json','novel-30-v2.json')]
    rows=workload(old['fixture'])[:32]
    for i,(entry,row) in enumerate(zip(bundle,rows)):
        e=entry['evidence'];need(e['row']==row and e['criteria']==frozen[0]['criteria']
            and e['frozenExpected']==next(c for s in frozen for c in s['cases'] if c['id']==row['case']),'V6_FROZEN_DRIFT')
        if i<23:continue
        key=digest(e);http=capture(entry['httpCapture'])
        need(http['kind']=='HTTP_REPLY' and http['value']==[row['expectedHttp'],e['reply']],'V6_HTTP_CHANGED')
        if i==31:
            need(row['case']=='F1' and entry['quality']=='NOT_REVIEWED' and entry.get('serverVerification')=='FAIL'
                 and 'receipt' not in entry and e['reply']['status']=='PARTIAL'
                 and 'SYNTHESIS_UNAVAILABLE' in e['reply']['uncertainties'],'V6_F1_FAILURE_CHANGED')
            continue
        need(capture('reviews/'+key+'.request.private.json')==e,'V6_HTTP_CHANGED')
        a=assessment_valid(capture('reviews/'+key+'.assessment.private.json'),e)
        r=capture('reviews/'+key+'.receipt.json');completion=capture('reviews/'+key+'.completion.json')
        need(a['quality']==entry['quality'] and a['safeToContinue'] is True and entry['receipt']==r
             and r['evidenceSha256']==key and completion['evidenceSha256']==key
             and r['reviewer']==a['reviewer'] and r['accepted']==(entry['quality']!='FAIL')
             and all(x['deadlineMs']==300000 and type(x['elapsedMillis']) is int and 0<=x['elapsedMillis']<300000 for x in (r,completion))
             and r['elapsedMillis']<=completion['elapsedMillis']
             and not (execution/'reviews'/(key+'.failure.json')).exists(),'V6_REVIEW_UNPROVEN')
    with ro(parent/'pilot-v2.sqlite') as db:
        expected={'version':2,'allocation':old['allocation'],'mode':'PAID_V2','source':old['source'],
            'fixture':old['fixtureHash'],'budget':digest(read(ROOT/'deployment/ai-pilot-budget-v2.json')),
            'plan':digest(read(ROOT/'deployment/ai-pilot-execution-v2.json'))}
        import json
        need(json.loads(db.execute('SELECT value FROM identity').fetchone()[0])==expected
             and digest(old['fixture'])==old['fixtureHash'] and db.execute('SELECT phase,stopped FROM control').fetchall()==[(2,1)],'V6_IDENTITY_CHANGED')
        for table,values in history['tables'].items():
            actual=db.execute('SELECT * FROM '+table+' ORDER BY 1').fetchall()
            need(all(tuple(v) in actual for v in values),'V6_ANCESTRY_ROWS_LOST')
        subs=db.execute('SELECT ticket,phase,slot,actor,body_hash,admitted,complete,server_request,worker_exited FROM submissions').fetchall()
        by={(s[1],s[2]):s for s in subs if s[1]!='INDEX_LEGACY'};ix=[s for s in subs if s[1]=='INDEX_LEGACY']
        need(len(subs)==33 and len(by)==32 and len(ix)==1 and ix[0][5:7]==(1,1) and ix[0][8]==1,'V6_SUBMISSION_CHANGED')
        for i,(row,entry) in enumerate(zip(rows,bundle)):
            s=by.get((row['phase'],row['slot']));need(s is not None and s[3:5]==(row['uid'],row['bodySha256'])
                and s[5]==1 and s[7]==entry['evidence']['reply']['runId']
                and (s[6],s[8])==((0,0) if i==15 else (1,1)),'V6_SUBMISSION_CHANGED')
        need(db.execute("SELECT * FROM reservations WHERE outcome!='OBSERVED'").fetchall()==[tuple(d['heldReservation'])]
             and d['heldReservation'][6]==29060,'V6_UNKNOWN_CHANGED')
        need(db.execute('SELECT ticket,disposition FROM held_terminal').fetchall()==[(d['failedTicket'],'B5_TECHNICAL_FAIL_NO_REPLAY')],'V6_B5_CHANGED')
        local={r[0]:(r[3],r[6],r[8]) for r in db.execute('SELECT * FROM reservations')}
        remote={r['id']:(r['kind'],r['reserved'],r['state']) for r in pg_rows}
        need(len(local)==79 and len(remote)==len(pg_rows) and local==remote,'V6_BUDGET_MISMATCH')
        totals=PilotCoordinator.totals(db)
        need((totals['llmCalls'],totals['embeddingCalls'],totals['reservedNusd'])==(45,34,1911453860),'V6_TOTALS_CHANGED')
        need({r[0] for r in db.execute('SELECT name FROM phases WHERE complete=1')}=={'INDEX_LEGACY','SAFETY_LEGACY'},'V6_PHASE_CHANGED')
        rows_hash=row_hash(db)
    return {'version':VERSION,'allocation':old['allocation'],'approvalId':old['approvalId'],'budgetAuthority':old['budgetAuthority'],
        'fixtureHash':old['fixtureHash'],'oldSource':old['source'],'failedTicket':d['failedTicket'],'heldReservation':d['heldReservation'],
        'parentFiles':{n:sha(parent/n) for n in ('pilot-v2.sqlite','session.json','STOP')},'executionFiles':bound,
        'rowsSha256':rows_hash,'prefixSha256':digest(bundle),'pgRowsSha256':digest(pg_rows),'historySha256':digest(history),
        'terminationSha256':digest(termination),'priorReservedNusd':1911453860,'priorLlmCalls':45,'priorEmbeddingCalls':34,
        'priorResponses':32,'remainingRequests':25,'remainingLlmCalls':47,'remainingEmbeddingCalls':36,'remainingCapNusd':16088546140,
        'originalCFinal':'UNKNOWN','originalF1Quality':'NOT_REVIEWED','limits':LIMITS,'frozen':FROZEN,'policies':V2_FILE_HASHES}


def exception_context(proposal,new_source,child_id):
    import re
    need(proposal['version']==VERSION and re.fullmatch('[0-9a-f]{64}',new_source) is not None
         and re.fullmatch('[0-9a-f]{16}',child_id) is not None,'V6_EXCEPTION_SCOPE')
    return {'version':'tms-oom-carry32-exception-v1','proposalSha256':digest(proposal),'newSource':new_source,'childId':child_id,
        'allocation':proposal['allocation'],'approvalId':proposal['approvalId'],'holdUnknownFull':True,'skipB5NoReplay':True,
        'skipThirtyTwoNoReplay':True,'preserveOriginalCUnknown':True,'preserveOriginalF1Failure':True,
        'stopOnAnyNewUnknown':True,'refundAllowed':False,'maximumSuccessors':1}


def verify_anchor(meta,parent):
    d=meta['continuation'];a=Path(meta['folder']).parent/'anchor'
    need(set(d['anchorFiles'])=={PREFIX,'pg-rows.json','termination.json','history.json','held-exception.json'},'V6_ANCHOR_SCOPE')
    for n,h in d['anchorFiles'].items():need(sha(a/n)==h,'V6_ANCHOR_CHANGED')
    bundle=read(a/PREFIX);observed=inspect(parent,bundle,read(a/'pg-rows.json'),read(a/'termination.json'),read(a/'history.json'),execution=a/'parent-execution')
    need(all(d.get(k)==v for k,v in observed.items()) and all(meta[k]==observed[k] for k in ('allocation','approvalId','budgetAuthority','fixtureHash')),'V6_PARENT_CHANGED')
    from ai_pilot_held_resume import validate_exception
    validate_exception(observed,read(a/'held-exception.json'),meta['source'],d['childId'])
    need(read(Path(parent)/'CONTINUATION_CLAIM.json')=={'childId':d['childId'],'source':meta['source'],
         'allocation':meta['allocation'],'continuationSha256':digest(d)},'V6_SINGLE_SUCCESSOR_REQUIRED')
    return bundle
