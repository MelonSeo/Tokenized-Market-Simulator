"""One explicitly approved historical B5 UNKNOWN, never a general UNKNOWN bypass.

Inspection/proposal are read-only. This module issues no permit, key access,
network call, refund or retry. Import still requires the ordinary paid permit,
an additional exact exception and an exclusive parent continuation claim.
"""
import hashlib,json,re,time
from pathlib import Path
from ai_pilot_preflight import ROOT,canonical,need,preflight,FROZEN,V2_FILE_HASHES
from ai_pilot_continuation import read,sha,ro,row_hash
from ai_pilot_coordinator import PilotCoordinator,workload

VERSION='tms-pilot-held-b5-v4'
LIMITS={'capNusd':18000000000,'llmCalls':92,'embeddingCalls':70}

def digest(v):return hashlib.sha256(canonical(v)).hexdigest()

def compiled_identity(root,images):
    """Same staged class/JAR byte set as stage_source, including pinned image IDs."""
    root=Path(root);files={}
    for tree in ('classes/java/main','classes/java/test','resources/main','resources/test/ai','pilot-v2-test/jars','product-read-ci/helpers'):
        folder=root/'backend/build'/tree;need(folder.is_dir(),'V4_BUILD_TREE_MISSING')
        for p in folder.rglob('*'):
            need(not p.is_symlink(),'V4_BUILD_LINK')
            if p.is_file() and p.suffix in {'.class','.jar'}:files[p.relative_to(root).as_posix()]=sha(p)
    return {'files':files,'images':images}

def runtime_fingerprint(root,images):
    from ai_pilot_channel import source_fingerprint
    raw=json.dumps(compiled_identity(root,images),sort_keys=True,separators=(',',':')).encode('utf8')
    return source_fingerprint(Path(root),artifact_raw=raw)

def inspect(parent,bundle,pg_rows,termination):
    """Exactly 15 reviewed + one failed HTTP response, all 46 reservations held."""
    parent=Path(parent);old=read(parent/'session.json');preflight()
    need(old['mode']=='PAID_EXPLICIT_V2' and (parent/'STOP').is_file(),'V4_PARENT_NOT_STOPPED')
    need(termination=={'financialUnchanged':True,'cStateUnchanged':True,'resourcesStopped':True,
                      'serverPassed':1,'serverFailed':0,'serverSkipped':0},'V4_TERMINATION_UNPROVEN')
    rows=workload(old['fixture'])[:16];need(len(bundle)==16,'V4_PREFIX_CHANGED')
    sets=[read(ROOT/'backend/src/test/resources/ai'/n) for n in ('pilot-acceptance-v1.json','novel-30-v2.json')]
    for i,(entry,row) in enumerate(zip(bundle,rows)):
        e=entry['evidence'];need(e['row']==row and e['criteria']==sets[0]['criteria']
            and e['frozenExpected']==next(c for s in sets for c in s['cases'] if c['id']==row['case']),'V4_FROZEN_DRIFT')
        if i<15:
            r=entry['receipt'];need(entry['quality'] in {'PASS','PARTIAL','FAIL'} and r['evidenceSha256']==digest(e)
                and r['deadlineMs']==300000 and type(r['elapsedMillis']) is int and 0<=r['elapsedMillis']<300000
                and type(r['accepted']) is bool,'V4_REVIEW_UNPROVEN')
            need(i!=4 or (row['case']=='PC4a' and entry['quality']=='FAIL' and r['accepted'] is False),'V4_OLD_FAIL_CHANGED')
        else:
            reply=e['reply'];need(row['case']=='B5' and entry['quality']=='NOT_RUN' and 'receipt' not in entry
                and reply['httpStatus']==503 and reply['error']=='AGENT_UNAVAILABLE','V4_B5_FAILURE_CHANGED')
    with ro(parent/'pilot-v2.sqlite') as db:
        ident=json.loads(db.execute('SELECT value FROM identity').fetchone()[0])
        need(ident=={'version':2,'allocation':old['allocation'],'mode':'PAID_V2','source':old['source'],
             'fixture':old['fixtureHash'],'budget':digest(read(ROOT/'deployment/ai-pilot-budget-v2.json')),
             'plan':digest(read(ROOT/'deployment/ai-pilot-execution-v2.json'))},'V4_IDENTITY_DRIFT')
        need(db.execute('SELECT phase,stopped FROM control').fetchone()==(2,1),'V4_STOP_STATE_CHANGED')
        subs=db.execute('SELECT ticket,phase,slot,actor,body_hash,admitted,complete,server_request,worker_exited FROM submissions').fetchall()
        index=[s for s in subs if s[1]=='INDEX_LEGACY'];by={(s[1],s[2]):s for s in subs if s[1]!='INDEX_LEGACY'}
        need(len(subs)==17 and len(index)==1 and index[0][5:7]==(1,1) and index[0][8]==1 and len(by)==16,'V4_SUBMISSION_DRIFT')
        for i,(row,e) in enumerate(zip(rows,bundle)):
            s=by.get((row['phase'],row['slot']));need(s is not None and s[3:5]==(row['uid'],row['bodySha256'])
                and s[5]==1 and s[7]==e['evidence']['reply']['runId'],'V4_SUBMISSION_DRIFT')
            need(s[6:9:2]==((1,1) if i<15 else (0,0)),'V4_SUBMISSION_DRIFT')
        unknown=db.execute("SELECT * FROM reservations WHERE outcome!='OBSERVED'").fetchall()
        need(len(unknown)==1,'V4_UNKNOWN_SCOPE_CHANGED');u=unknown[0];failed=by[('NOVEL30',9)]
        need(u[1]==failed[0] and u[2:4]==('NOVEL30','EMBEDDING') and u[5]==0 and u[6]==29060
             and u[8:]==('UNKNOWN',None,None),'V4_UNKNOWN_SCOPE_CHANGED')
        local={r[0]:(r[3],r[6],r[8]) for r in db.execute('SELECT * FROM reservations')}
        remote={r['id']:(r['kind'],r['reserved'],r['state']) for r in pg_rows}
        need(len(local)==46 and len(remote)==len(pg_rows) and local==remote,'V4_BUDGET_MISMATCH')
        totals=PilotCoordinator.totals(db)
        need(totals['llmCalls']==24 and totals['embeddingCalls']==22 and totals['reservedNusd']==1072998300,'V4_TOTALS_CHANGED')
        need({r[0] for r in db.execute('SELECT name FROM phases WHERE complete=1')}=={'INDEX_LEGACY','SAFETY_LEGACY'},'V4_PHASE_DRIFT')
        rows_hash=row_hash(db)
    return {'version':VERSION,'allocation':old['allocation'],'approvalId':old['approvalId'],
        'budgetAuthority':old['budgetAuthority'],'fixtureHash':old['fixtureHash'],'oldSource':old['source'],
        'parentFiles':{n:sha(parent/n) for n in ('pilot-v2.sqlite','session.json','STOP')},'rowsSha256':rows_hash,
        'prefixSha256':digest(bundle),'pgRowsSha256':digest(pg_rows),'terminationSha256':digest(termination),
        'failedTicket':failed[0],'heldReservation':list(u),'priorReservedNusd':totals['reservedNusd'],
        'priorLlmCalls':24,'priorEmbeddingCalls':22,'priorResponses':16,'reviewedResponses':15,
        'remainingRequests':41,'remainingLlmCalls':68,'remainingEmbeddingCalls':48,
        'remainingCapNusd':16927001700,'limits':LIMITS,'frozen':FROZEN,'policies':V2_FILE_HASHES}

def exception_context(proposal,new_source,child_id):
    if proposal.get('version')=='tms-pilot-oom-carry32-v6':
        from ai_pilot_oom_resume import exception_context as oom_context
        return oom_context(proposal,new_source,child_id)
    if proposal.get('version')=='tms-pilot-review-carry23-v5':
        from ai_pilot_review_resume import exception_context as review_context
        return review_context(proposal,new_source,child_id)
    need(re.fullmatch('[0-9a-f]{64}',new_source) is not None and re.fullmatch('[0-9a-f]{16}',child_id) is not None,'V4_EXCEPTION_SCOPE')
    return {'version':'tms-held-unknown-exception-v1','proposalSha256':digest(proposal),'newSource':new_source,'childId':child_id,
        'allocation':proposal['allocation'],'approvalId':proposal['approvalId'],'holdUnknownFull':True,'skipB5NoReplay':True,
        'stopOnAnyNewUnknown':True,'refundAllowed':False,'maximumSuccessors':1}

def validate_exception(proposal,exception,new_source,child_id,now=None):
    expected=exception_context(proposal,new_source,child_id);now=time.time() if now is None else now
    need(type(exception) is dict and set(exception)==set(expected)|{'explicitExceptionApproved','approvedAt','expiresAt'},'V4_EXCEPTION_SCHEMA')
    need(all(type(exception[k]) is type(v) and exception[k]==v for k,v in expected.items())
         and exception['explicitExceptionApproved'] is True,'V4_EXPLICIT_EXCEPTION_REQUIRED')
    need(type(exception['approvedAt']) is int and type(exception['expiresAt']) is int
         and exception['approvedAt']<=now<exception['expiresAt']<=exception['approvedAt']+14400,'V4_EXCEPTION_EXPIRED')

def verify_held_anchor(meta,parent):
    d=meta['continuation'];anchor=Path(meta['folder']).parent/'anchor'
    need(set(d['anchorFiles'])=={'prefix-sixteen.json','pg-rows.json','termination.json','held-exception.json'},'V4_ANCHOR_SCOPE')
    for n,h in d['anchorFiles'].items():need(sha(anchor/n)==h,'V4_ANCHOR_CHANGED')
    bundle=read(anchor/'prefix-sixteen.json');observed=inspect(parent,bundle,read(anchor/'pg-rows.json'),read(anchor/'termination.json'))
    need(all(d.get(k)==v for k,v in observed.items()),'V4_PARENT_CHANGED')
    need(all(meta[k]==observed[k] for k in ('allocation','approvalId','budgetAuthority','fixtureHash')),'V4_AUTHORITY_CHANGED')
    validate_exception(observed,read(anchor/'held-exception.json'),meta['source'],d['childId'])
    need(read(Path(parent)/'CONTINUATION_CLAIM.json')=={'childId':d['childId'],'source':meta['source'],'allocation':meta['allocation'],
         'continuationSha256':digest(d)},'V4_SINGLE_SUCCESSOR_REQUIRED')
    return bundle

class HeldCoordinator(PilotCoordinator):
    """Only constructed after ordinary paid authorization AND exact exception verification.

    B5 remains complete=0/worker_exited=0 and UNKNOWN stays UNKNOWN. A separate
    disposition counts it as processed, not successful, for later phase scheduling.
    """
    def __init__(self,path,*,meta,clock=time.time):
        self.held=meta['continuation'];self.meta=meta
        need(self.held['version'] in {VERSION,'tms-pilot-review-carry23-v5','tms-pilot-oom-carry32-v6'},'V4_VERSION')
        super().__init__(path,allocation=meta['allocation'],source_hash=meta['source'],fixture=meta['fixture'],mode='PAID_V2',clock=clock)
    def verify(self,c):
        super().verify(c)
        self.verify_history(c)
    def verify_history(self,c):
        need(c.execute('SELECT ticket,disposition FROM held_terminal').fetchall()==[(self.held['failedTicket'],'B5_TECHNICAL_FAIL_NO_REPLAY')],'V4_DISPOSITION_CHANGED')
        row=c.execute('SELECT * FROM reservations WHERE pg_id=?',(self.held['heldReservation'][0],)).fetchone()
        need(row==tuple(self.held['heldReservation']),'V4_HELD_RESERVATION_CHANGED')
        failed=c.execute('SELECT phase,slot,complete,worker_exited FROM submissions WHERE ticket=?',(self.held['failedTicket'],)).fetchone()
        need(failed==('NOVEL30',9,0,0),'V4_B5_COMPLETION_CHANGED')
    def pending_sql(self):return 'complete=0 AND ticket NOT IN (SELECT ticket FROM held_terminal)'
    def phase_progress(self,c,phase):
        return c.execute('SELECT count(*),coalesce(sum(CASE WHEN complete=1 OR ticket IN (SELECT ticket FROM held_terminal) THEN 1 ELSE 0 END),0) FROM submissions WHERE phase=?',(phase,)).fetchone()
    def assert_no_unresolved(self,c,exclude=None):
        need(exclude!=self.held['heldReservation'][0],'V4_B5_REPLAY_FORBIDDEN')
        rows=c.execute("SELECT * FROM reservations WHERE outcome!='OBSERVED'"+(' AND pg_id<>?' if exclude else ''),(exclude,) if exclude else ()).fetchall()
        need(rows==[tuple(self.held['heldReservation'])],'V2_UNKNOWN_USAGE_HALTED')
    def reconcile(self,pg_rows):
        with self.db() as c:
            need(c.execute('SELECT value FROM identity WHERE id=1').fetchall()==[(self.identity,)],'V2_IDENTITY_MISMATCH')
            self.verify_history(c);self.assert_no_unresolved(c)
            local={r[0]:(r[1],r[2],r[3]) for r in c.execute('SELECT pg_id,kind,cost,outcome FROM reservations')}
            remote={r['id']:(r['kind'],r['reserved'],r['state']) for r in pg_rows}
            match=len(remote)==len(pg_rows) and local==remote
            if not match:c.execute('UPDATE control SET stopped=1 WHERE id=1')
            return {'matching':match,'pgReservedNusd':sum(v[1] for v in remote.values()),'evaluationReservedNusd':sum(v[1] for v in local.values()),
                'historicalUnknownHeld':1,'automaticRefundOrReplay':False}
