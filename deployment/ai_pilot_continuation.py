"""Explicit one-parent continuation, never a new budget or a STOP reset.

Only the observed one-response or explicitly approved five-response prefix is importable. The old SQLite,
STOP and evidence stay readonly. All reservation/activity rows are carried.
"""
import hashlib,json,shutil,sqlite3
from contextlib import contextmanager,closing
from pathlib import Path
from ai_pilot_preflight import ROOT,canonical,need,preflight
from ai_pilot_coordinator import PilotCoordinator,workload

TABLES=('phases','submissions','reservations','activity')

def sha(path):
    p=Path(path);need(p.is_file() and not p.is_symlink(),'V2_CARRY_FILE_REQUIRED')
    with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()

def read(path,limit=2100000):
    p=Path(path);need(p.is_file() and not p.is_symlink() and p.stat().st_size<=limit,'V2_CARRY_FILE_REQUIRED')
    return json.loads(p.read_text(encoding='utf8'))

@contextmanager
def ro(path):
    need(Path(path).stat().st_size<=2_000_000,'V2_CARRY_LEDGER_LIMIT')
    db=sqlite3.connect(Path(path).resolve().as_uri()+'?mode=ro',uri=True)
    try:yield db
    finally:db.close()

def row_hash(db):
    rows={t:db.execute('SELECT * FROM '+t+' ORDER BY 1').fetchall() for t in TABLES}
    return hashlib.sha256(canonical(rows)).hexdigest()

def inspect_prefix(parent,request,receipt,proof,pg_rows):
    parent=Path(parent);meta=read(parent/'session.json');preflight()
    need(meta.get('mode')=='PAID_EXPLICIT_V2' and (parent/'STOP').is_file(),'V2_CARRY_PARENT_NOT_STOPPED')
    # Do not depend on list ordering for the import identity.
    row=next(r for r in workload(meta['fixture']) if r['phase']=='SAFETY_LEGACY' and r['slot']==0)
    need(request['row']==row and row['case']=='PF1a','V2_CARRY_PREFIX_CHANGED')
    key=hashlib.sha256(canonical(request)).hexdigest()
    frozen=read(ROOT/'backend/src/test/resources/ai/pilot-acceptance-v1.json')
    expected=next(c for c in frozen['cases'] if c['id']=='PF1a')
    need(request['frozenExpected']==expected and proof['status']=='PASS' and proof['providerCalls']==0
         and request['criteria']==frozen['criteria']
         and proof['originalResponseReused'] is True and proof['evidenceSha256']==key
         and receipt['evidenceSha256']==key and receipt['accepted'] is True
         and receipt['deadlineMs']==300000 and type(receipt['elapsedMillis']) is int and 0<=receipt['elapsedMillis']<300000,
         'V2_CARRY_REVALIDATION_REQUIRED')
    with ro(parent/'pilot-v2.sqlite') as db:
        identity=json.loads(db.execute('SELECT value FROM identity WHERE id=1').fetchone()[0])
        need(identity['mode']=='PAID_V2' and identity['allocation']==meta['allocation'] and identity['source']==meta['source']
             and identity['fixture']==meta['fixtureHash'],'V2_CARRY_PARENT_IDENTITY')
        expected_identity={'version':2,'allocation':meta['allocation'],'mode':'PAID_V2','source':meta['source'],
                           'fixture':meta['fixtureHash'],'budget':hashlib.sha256(canonical(read(ROOT/'deployment/ai-pilot-budget-v2.json'))).hexdigest(),
                           'plan':hashlib.sha256(canonical(read(ROOT/'deployment/ai-pilot-execution-v2.json'))).hexdigest()}
        need(identity==expected_identity,'V2_CARRY_PARENT_POLICY')
        need(db.execute('SELECT phase,stopped FROM control WHERE id=1').fetchone()==(1,1),'V2_CARRY_PREFIX_CHANGED')
        subs=db.execute('SELECT phase,slot,actor,body_hash,admitted,complete,server_request,worker_exited FROM submissions ORDER BY phase').fetchall()
        need(len(subs)==2 and subs[0][0]=='INDEX_LEGACY' and subs[1][:4]==('SAFETY_LEGACY',0,row['uid'],row['bodySha256'])
             and all(s[4:6]==(1,1) and s[7]==1 for s in subs)
             and subs[1][6]==request['reply']['runId'],'V2_CARRY_PREFIX_CHANGED')
        need(db.execute("SELECT count(*) FROM phases WHERE complete=1 AND name='INDEX_LEGACY'").fetchone()[0]==1
             and db.execute('SELECT sum(complete) FROM phases').fetchone()[0]==1,'V2_CARRY_PREFIX_CHANGED')
        local={r[0]:(r[1],r[2],r[3]) for r in db.execute('SELECT pg_id,kind,cost,outcome FROM reservations')}
        remote={r['id']:(r['kind'],r['reserved'],r['state']) for r in pg_rows}
        need(len(local)==9 and len(remote)==len(pg_rows) and local==remote
             and all(v[2]=='OBSERVED' for v in local.values()),'V2_CARRY_UNKNOWN_OR_BUDGET_MISMATCH')
        need(sum(v[1] for v in local.values())==82547760
             and sum(v[0]=='LLM' for v in local.values())==2,'V2_CARRY_TOTALS_CHANGED')
        digest=row_hash(db)
    return meta,{'parentFiles':{n:sha(parent/n) for n in ('pilot-v2.sqlite','session.json','STOP')},
                 'rowsSha256':digest,'prefixEvidenceSha256':key,'priorReservedNusd':82547760,'priorLlmCalls':2,'priorEmbeddingCalls':7}

def verify_anchor(meta,parent=Path('/run/pilot-parent')):
    desc=meta['continuation']
    if desc['version']=='tms-pilot-oom-carry32-v6':
        from ai_pilot_oom_resume import verify_anchor as verify_oom_anchor
        return verify_oom_anchor(meta,parent)
    if desc['version']=='tms-pilot-review-carry23-v5':
        from ai_pilot_review_resume import verify_anchor as verify_review_anchor
        return verify_review_anchor(meta,parent)
    if desc['version']=='tms-pilot-held-b5-v4':
        from ai_pilot_held_resume import verify_held_anchor
        return verify_held_anchor(meta,parent)
    if desc['version']=='tms-pilot-carry-twelve-v3':return verify_twelve_anchor(meta,parent)
    if desc['version']=='tms-pilot-carry-five-v2':return verify_five_anchor(meta,parent)
    need(desc['version']=='tms-pilot-carry-pf1a-v1','V2_CARRY_VERSION')
    need(set(desc['parentFiles'])=={'pilot-v2.sqlite','session.json','STOP'}
         and set(desc['anchorFiles'])=={'prefix-request.json','receipt.json','revalidation-proof.json','pg-rows.json'},'V2_CARRY_FILE_SCOPE')
    for n,h in desc['parentFiles'].items():need(sha(parent/n)==h,'V2_CARRY_PARENT_CHANGED')
    old=read(parent/'session.json')
    need(meta['allocation']==old['allocation'] and meta['approvalId']==old['approvalId'] and meta['budgetAuthority']==old['budgetAuthority']
         and meta['fixture']==old['fixture'] and meta['fixtureHash']==old['fixtureHash'],'V2_CARRY_BUDGET_AUTHORITY_CHANGED')
    anchor=Path(meta['folder']).parent/'anchor'
    for n,h in desc['anchorFiles'].items():need(sha(anchor/n)==h,'V2_CARRY_ANCHOR_CHANGED')
    request=read(anchor/'prefix-request.json');receipt=read(anchor/'receipt.json');proof=read(anchor/'revalidation-proof.json')
    need(proof['originalRequestSha256']==sha(anchor/'prefix-request.json') and proof['receiptSha256']==sha(anchor/'receipt.json'),'V2_CARRY_REVALIDATION_REQUIRED')
    _,observed=inspect_prefix(parent,request,receipt,proof,read(anchor/'pg-rows.json'))
    need(all(desc[k]==v for k,v in observed.items()),'V2_CARRY_PARENT_CHANGED')
    claim=read(parent/'CONTINUATION_CLAIM.json',16384)
    need(claim=={'childId':desc['childId'],'source':meta['source'],'allocation':meta['allocation'],
                 'continuationSha256':hashlib.sha256(canonical(desc)).hexdigest()},'V2_CARRY_CLAIM_MISMATCH')
    return request

def make_meta(config,folder):
    from ai_pilot_channel import source_fingerprint
    folder=Path(folder);old=read('/run/pilot-parent/session.json')
    authority=hashlib.sha256((config['budget']['jdbcUrl']+'\n'+config['budget']['dbUser']).encode()).hexdigest()
    need(config['mode']=='PAID_EXPLICIT_V2' and config['run']==old['folder'] and authority==old['budgetAuthority'],'V2_CARRY_BUDGET_AUTHORITY_CHANGED')
    return {'allocation':old['allocation'],'mode':'PAID_EXPLICIT_V2','source':source_fingerprint(),'fixture':old['fixture'],
            'fixtureHash':old['fixtureHash'],'folder':config['run'],'budgetAuthority':old['budgetAuthority'],
            'approvalId':old['approvalId'],'continuation':read(folder/'continuation.json')}

def import_ledger(meta,permit_path,parent=Path('/run/pilot-parent')):
    from ai_pilot_paid_sender import authorize
    from ai_pilot_paid_adapter import private_write
    from ai_pilot_channel import source_fingerprint
    folder=Path(meta['folder']);need(not folder.exists(),'V2_CARRY_NO_RESTART')
    permit=read(permit_path,16384);meta={**meta,'approvalFile':str(permit_path),'permitHash':hashlib.sha256(canonical(permit)).hexdigest()}
    authorize(meta,permit,current_source=source_fingerprint())
    verify_anchor(meta,parent)
    folder.mkdir();shutil.copyfile(parent/'pilot-v2.sqlite',folder/'pilot-v2.sqlite')
    expected=canonical({'version':2,'allocation':meta['allocation'],'mode':'PAID_V2','source':meta['source'],
                        'fixture':meta['fixtureHash'],'budget':hashlib.sha256(canonical(read(ROOT/'deployment/ai-pilot-budget-v2.json'))).hexdigest(),
                        'plan':hashlib.sha256(canonical(read(ROOT/'deployment/ai-pilot-execution-v2.json'))).hexdigest()}).decode()
    with closing(sqlite3.connect(folder/'pilot-v2.sqlite')) as db:
        with db:
            need(row_hash(db)==meta['continuation']['rowsSha256'],'V2_CARRY_ROW_LOSS')
            db.execute('UPDATE identity SET value=? WHERE id=1',(expected,))
            db.execute('UPDATE control SET stopped=0 WHERE id=1') # NEW derivative only; original STOP/SQLite untouched.
            if meta['continuation']['version']=='tms-pilot-held-b5-v4':
                db.execute('CREATE TABLE held_terminal(ticket TEXT PRIMARY KEY, disposition TEXT NOT NULL)')
                db.execute('INSERT INTO held_terminal VALUES(?,?)',(meta['continuation']['failedTicket'],'B5_TECHNICAL_FAIL_NO_REPLAY'))
            need(row_hash(db)==meta['continuation']['rowsSha256'],'V2_CARRY_ROW_LOSS')
    private_write(folder/'session.json',meta)
    verify_anchor(meta,parent)
    d=meta['continuation']
    private_write(folder/'import-proof.json',{'sameAllocation':True,'priorReservedNusd':d['priorReservedNusd'],'llmCarried':d['priorLlmCalls'],'embeddingCarried':d['priorEmbeddingCalls'],'rowsPreserved':True})
    return meta

class ContinuationSchedule:
    def __init__(self,c,meta,permit,current_source):
        from ai_pilot_paid_schedule import PaidSchedule
        self.base=PaidSchedule(c,meta,permit,current_source)
        self.c,self.meta,self.permit,self.current_source=c,meta,permit,current_source
    def run(self,adapter):
        from ai_pilot_paid_schedule import EvidenceAdapter
        from ai_pilot_paid_sender import authorize
        from ai_pilot_http_plan import PilotHttpPlan
        prefix=verify_anchor(self.meta)
        with self.c.db() as db:
            self.c.verify(db)
            need(row_hash(db)==self.meta['continuation']['rowsSha256'],'V2_CARRY_NO_RESTART')
        need(self.c.reconcile(adapter.budget_snapshot())['matching'],'V2_CARRY_BUDGET_MISMATCH')
        plan=PilotHttpPlan(self.c,self.meta['fixture'])
        multiple=self.meta['continuation']['version'] in {'tms-pilot-carry-five-v2','tms-pilot-carry-twelve-v3','tms-pilot-held-b5-v4','tms-pilot-review-carry23-v5','tms-pilot-oom-carry32-v6'}
        reused=prefix if multiple else [{'evidence':prefix,'quality':'PASS'}]
        count=len(reused);done={(p['evidence']['row']['phase'],p['evidence']['row']['slot']) for p in reused}
        plan.rows=[r for r in plan.rows if (r['phase'],r['slot']) not in done]
        adapter=EvidenceAdapter(adapter,self.meta['folder'])
        results=[{'phase':'INDEX_LEGACY','reused':True}]+[{'phase':p['evidence']['row']['phase'],'case':p['evidence']['row']['case'],
                 'actor':p['evidence']['row']['actor'],'http':p['evidence']['reply'].get('httpStatus',200),'reply':p['evidence']['reply'],'reused':True,'quality':p['quality']} for p in reused]
        for result,entry in zip(results[1:],reused):
            if 'offlineReview' in entry:
                result.update(originalReviewGate='FAIL',reviewSource='OFFLINE_REVALIDATION_NOT_ORIGINAL_GATE_PASS')
            if entry.get('serverVerification')=='FAIL':
                result.update(originalReviewGate='NOT_RUN',serverVerification='FAIL')
        try:
            with self.c.db() as db:start=db.execute('SELECT phase FROM control').fetchone()[0]
            for phase in self.c.plan['phases'][start:]:
                authorize(self.meta,self.permit,current_source=self.current_source())
                if phase['id']=='EMBEDDING_HEADROOM':self.c.advance(evidence_reviewed=True);break
                if phase['id'].startswith('INDEX_'):results.append(plan.run_index(phase['id'],adapter))
                else:results.extend(plan.run_requests(phase['id'],adapter))
            return {'transport':'COMPLETED_REQUIRES_FINAL_QUALITY_REVIEW','results':results,'reusedRequests':count,
                    'newRequests':57-count,'humanSatisfaction':'NOT_RUN','operatingSla':'NOT_RUN'}
        except BaseException:self.c.stop();raise


def inspect_five(parent,bundle,pg_rows):
    """Only the explicit five observed responses. FAIL is preserved, never converted to PASS."""
    parent=Path(parent);meta=read(parent/'session.json');preflight()
    need(meta['mode']=='PAID_EXPLICIT_V2' and (parent/'STOP').is_file(),'V2_CARRY_PARENT_NOT_STOPPED')
    rows=[r for r in workload(meta['fixture']) if r['phase']=='SAFETY_LEGACY'][:5]
    need(len(bundle)==5,'V2_CARRY_PREFIX_CHANGED')
    frozen=read(ROOT/'backend/src/test/resources/ai/pilot-acceptance-v1.json')
    for i,(entry,row) in enumerate(zip(bundle,rows)):
        e=entry['evidence'];receipt=entry['receipt'];key=hashlib.sha256(canonical(e)).hexdigest()
        need(e['row']==row and e['criteria']==frozen['criteria'] and e['frozenExpected']==next(c for c in frozen['cases'] if c['id']==row['case']), 'V2_CARRY_PREFIX_CHANGED')
        need(receipt['evidenceSha256']==key and receipt['deadlineMs']==300000 and type(receipt['elapsedMillis']) is int
             and 0<=receipt['elapsedMillis']<300000,'V2_CARRY_REVALIDATION_REQUIRED')
        need(entry['quality']==('FAIL' if i==4 else 'PASS') and receipt['accepted'] is (i!=4),'V2_CARRY_REVIEW_CHANGED')
        if i==4:
            need(row['case']=='PC4a' and e['reply']['status']=='PARTIAL' and e['reply']['uncertainties']==['SYNTHESIS_UNAVAILABLE']
                 and any(t['tool']=='listAbnormalOrders' and t['status']=='SUCCESS' for t in e['reply']['toolEvidence']), 'V2_CARRY_FAILURE_CHANGED')
    with ro(parent/'pilot-v2.sqlite') as db:
        identity=json.loads(db.execute('SELECT value FROM identity').fetchone()[0])
        need(identity=={'version':2,'allocation':meta['allocation'],'mode':'PAID_V2','source':meta['source'],'fixture':meta['fixtureHash'],
             'budget':hashlib.sha256(canonical(read(ROOT/'deployment/ai-pilot-budget-v2.json'))).hexdigest(),
             'plan':hashlib.sha256(canonical(read(ROOT/'deployment/ai-pilot-execution-v2.json'))).hexdigest()},'V2_CARRY_PARENT_POLICY')
        need(db.execute('SELECT phase,stopped FROM control').fetchone()==(1,1),'V2_CARRY_PREFIX_CHANGED')
        subs=db.execute('SELECT phase,slot,actor,body_hash,admitted,complete,server_request,worker_exited FROM submissions ORDER BY phase,slot').fetchall()
        need(len(subs)==6 and subs[0][0]=='INDEX_LEGACY' and all(s[4:6]==(1,1) and s[7]==1 for s in subs),'V2_CARRY_PREFIX_CHANGED')
        for s,row,entry in zip(subs[1:],rows,bundle):
            need(s[:4]==('SAFETY_LEGACY',row['slot'],row['uid'],row['bodySha256']) and s[6]==entry['evidence']['reply']['runId'],'V2_CARRY_PREFIX_CHANGED')
        need(db.execute('SELECT sum(complete) FROM phases').fetchone()[0]==1,'V2_CARRY_PREFIX_CHANGED')
        local={r[0]:(r[1],r[2],r[3]) for r in db.execute('SELECT pg_id,kind,cost,outcome FROM reservations')}
        remote={r['id']:(r['kind'],r['reserved'],r['state']) for r in pg_rows}
        need(len(local)==17 and len(remote)==len(pg_rows) and local==remote and all(v[2]=='OBSERVED' for v in local.values()),'V2_CARRY_UNKNOWN_OR_BUDGET_MISMATCH')
        need(sum(v[1] for v in local.values())==343083460 and sum(v[0]=='LLM' for v in local.values())==6,'V2_CARRY_TOTALS_CHANGED')
        digest=row_hash(db)
    return meta,{'parentFiles':{n:sha(parent/n) for n in ('pilot-v2.sqlite','session.json','STOP')},'rowsSha256':digest,
        'priorReservedNusd':343083460,'priorLlmCalls':6,'priorEmbeddingCalls':11}


def verify_five_anchor(meta,parent,root=Path('/run/pilot-root')):
    desc=meta['continuation'];need(set(desc['anchorFiles'])=={'prefix-five.json','pg-rows.json'},'V2_CARRY_FILE_SCOPE')
    need(set(desc['parentFiles'])=={'pilot-v2.sqlite','session.json','STOP'},'V2_CARRY_FILE_SCOPE')
    for n,h in desc['parentFiles'].items():need(sha(parent/n)==h,'V2_CARRY_PARENT_CHANGED')
    old=read(parent/'session.json');need(all(meta[k]==old[k] for k in ('allocation','approvalId','budgetAuthority','fixture','fixtureHash')),'V2_CARRY_BUDGET_AUTHORITY_CHANGED')
    need(set(desc['rootFiles'])=={'pilot-v2.sqlite','session.json','STOP','CONTINUATION_CLAIM.json'},'V2_CARRY_FILE_SCOPE')
    for n,h in desc['rootFiles'].items():need(sha(root/n)==h,'V2_CARRY_ROOT_CHANGED')
    need(all(desc['rootFiles'][n]==h for n,h in old['continuation']['parentFiles'].items()),'V2_CARRY_ROOT_CHANGED')
    claim0=read(root/'CONTINUATION_CLAIM.json')
    need(claim0=={'childId':old['continuation']['childId'],'source':old['source'],'allocation':old['allocation'],
        'continuationSha256':hashlib.sha256(canonical(old['continuation'])).hexdigest()},'V2_CARRY_CLAIM_MISMATCH')
    anchor=Path(meta['folder']).parent/'anchor'
    for n,h in desc['anchorFiles'].items():need(sha(anchor/n)==h,'V2_CARRY_ANCHOR_CHANGED')
    bundle=read(anchor/'prefix-five.json');_,observed=inspect_five(parent,bundle,read(anchor/'pg-rows.json'))
    need(all(desc[k]==v for k,v in observed.items()),'V2_CARRY_PARENT_CHANGED')
    need(read(parent/'CONTINUATION_CLAIM.json')=={'childId':desc['childId'],'source':meta['source'],'allocation':meta['allocation'],
        'continuationSha256':hashlib.sha256(canonical(desc)).hexdigest()},'V2_CARRY_CLAIM_MISMATCH')
    return bundle


def inspect_twelve(parent,bundle,pg_rows):
    """Observed second STOP. Same frozen workload, no retransmission or quality upgrade."""
    parent=Path(parent);meta=read(parent/'session.json');preflight()
    need(meta['mode']=='PAID_EXPLICIT_V2' and (parent/'STOP').is_file(),'V2_CARRY_PARENT_NOT_STOPPED')
    rows=workload(meta['fixture'])[:12];need(len(bundle)==12,'V2_CARRY_PREFIX_CHANGED')
    sets=[read(ROOT/'backend/src/test/resources/ai'/n) for n in ('pilot-acceptance-v1.json','novel-30-v2.json')]
    for i,(entry,row) in enumerate(zip(bundle,rows)):
        e=entry['evidence'];r=entry['receipt'];key=hashlib.sha256(canonical(e)).hexdigest()
        need(e['row']==row and e['criteria']==sets[0]['criteria'] and e['frozenExpected']==next(c for s in sets for c in s['cases'] if c['id']==row['case']),'V2_CARRY_PREFIX_CHANGED')
        need(r['evidenceSha256']==key and r['deadlineMs']==300000 and type(r['elapsedMillis']) is int and 0<=r['elapsedMillis']<300000,'V2_CARRY_REVALIDATION_REQUIRED')
        need(entry['quality'] in {'PASS','PARTIAL','FAIL'} and r['accepted'] is (i!=4),'V2_CARRY_REVIEW_CHANGED')
        if i==4:need(entry['quality']=='FAIL' and row['case']=='PC4a','V2_CARRY_REVIEW_CHANGED')
    with ro(parent/'pilot-v2.sqlite') as db:
        ident=json.loads(db.execute('SELECT value FROM identity').fetchone()[0])
        need(ident=={'version':2,'allocation':meta['allocation'],'mode':'PAID_V2','source':meta['source'],'fixture':meta['fixtureHash'],
          'budget':hashlib.sha256(canonical(read(ROOT/'deployment/ai-pilot-budget-v2.json'))).hexdigest(),
          'plan':hashlib.sha256(canonical(read(ROOT/'deployment/ai-pilot-execution-v2.json'))).hexdigest()},'V2_CARRY_PARENT_POLICY')
        need(db.execute('SELECT phase,stopped FROM control').fetchone()==(2,1),'V2_CARRY_PREFIX_CHANGED')
        subs=db.execute('SELECT phase,slot,actor,body_hash,admitted,complete,server_request,worker_exited FROM submissions').fetchall()
        index=[s for s in subs if s[0]=='INDEX_LEGACY'];by={(s[0],s[1]):s for s in subs if s[0]!='INDEX_LEGACY'}
        need(len(index)==1 and len(subs)==13 and len(by)==12 and all(s[4:6]==(1,1) and s[7]==1 for s in subs),'V2_CARRY_PREFIX_CHANGED')
        for row,e in zip(rows,bundle):
            s=by.get((row['phase'],row['slot']));need(s is not None and s[2:4]==(row['uid'],row['bodySha256']) and s[6]==e['evidence']['reply']['runId'],'V2_CARRY_PREFIX_CHANGED')
        need({r[0] for r in db.execute('SELECT name FROM phases WHERE complete=1')}=={'INDEX_LEGACY','SAFETY_LEGACY'},'V2_CARRY_PREFIX_CHANGED')
        local={r[0]:(r[1],r[2],r[3]) for r in db.execute('SELECT pg_id,kind,cost,outcome FROM reservations')}
        remote={r['id']:(r['kind'],r['reserved'],r['state']) for r in pg_rows}
        need(len(local)==36 and len(remote)==len(pg_rows) and local==remote and all(v[2]=='OBSERVED' for v in local.values()),'V2_CARRY_UNKNOWN_OR_BUDGET_MISMATCH')
        need(sum(v[1] for v in local.values())==844583340 and sum(v[0]=='LLM' for v in local.values())==18,'V2_CARRY_TOTALS_CHANGED')
        digest=row_hash(db)
    return meta,{'parentFiles':{n:sha(parent/n) for n in ('pilot-v2.sqlite','session.json','STOP')},'rowsSha256':digest,
       'priorReservedNusd':844583340,'priorLlmCalls':18,'priorEmbeddingCalls':18}


def verify_twelve_anchor(meta,parent,root=Path('/run/pilot-root'),middle=Path('/run/pilot-middle')):
    d=meta['continuation'];need(set(d['anchorFiles'])=={'prefix-twelve.json','pg-rows.json'},'V2_CARRY_FILE_SCOPE')
    need(set(d['parentFiles'])=={'pilot-v2.sqlite','session.json','STOP'},'V2_CARRY_FILE_SCOPE')
    old=read(parent/'session.json');mid=read(middle/'session.json')
    for s in (old,mid,read(root/'session.json')):need(all(meta[k]==s[k] for k in ('allocation','approvalId','budgetAuthority','fixture','fixtureHash')),'V2_CARRY_BUDGET_AUTHORITY_CHANGED')
    for path,files in ((parent,d['parentFiles']),(root,d['rootFiles']),(middle,d['middleFiles'])):
        need(set(files)==({'pilot-v2.sqlite','session.json','STOP'} if path==parent else {'pilot-v2.sqlite','session.json','STOP','CONTINUATION_CLAIM.json'}),'V2_CARRY_FILE_SCOPE')
        for n,h in files.items():need(sha(path/n)==h,'V2_CARRY_PARENT_CHANGED')
    need(d['rootFiles']==old['continuation']['rootFiles'] and all(d['middleFiles'][n]==h for n,h in old['continuation']['parentFiles'].items()),'V2_CARRY_ROOT_CHANGED')
    for path,node in ((root,mid),(middle,old)):
        need(read(path/'CONTINUATION_CLAIM.json')=={'childId':node['continuation']['childId'],'source':node['source'],'allocation':node['allocation'],
           'continuationSha256':hashlib.sha256(canonical(node['continuation'])).hexdigest()},'V2_CARRY_CLAIM_MISMATCH')
    anchor=Path(meta['folder']).parent/'anchor'
    for n,h in d['anchorFiles'].items():need(sha(anchor/n)==h,'V2_CARRY_ANCHOR_CHANGED')
    bundle=read(anchor/'prefix-twelve.json');_,observed=inspect_twelve(parent,bundle,read(anchor/'pg-rows.json'))
    need(all(d[k]==v for k,v in observed.items()),'V2_CARRY_PARENT_CHANGED')
    need(read(parent/'CONTINUATION_CLAIM.json')=={'childId':d['childId'],'source':meta['source'],'allocation':meta['allocation'],
      'continuationSha256':hashlib.sha256(canonical(d)).hexdigest()},'V2_CARRY_CLAIM_MISMATCH')
    return bundle
