"""V2 explicit permit + final lazy transport boundary. Import never reads a key or calls a provider.

This is not an approval issuer. An UNPAID allocation can never enter this sender.
The operator must separately approve and bind a NEW allocation/PG authority.
"""
import hashlib,json,re,stat,sys,time,uuid
from pathlib import Path
from ai_pilot_preflight import ROOT,POLICY_PATH,PLAN_PATH,FROZEN,V2_FILE_HASHES,canonical,need,preflight


def context(meta):
    value={'version':'tms-pilot-permit-v2','allocation':meta['allocation'],'sourceHash':meta['source'],
        'fixtureHash':meta['fixtureHash'],'budgetAuthority':meta['budgetAuthority'],
        'allocationPathSha256':hashlib.sha256(str(Path(meta['folder']).resolve()).encode()).hexdigest(),
        'budgetSha256':V2_FILE_HASHES[POLICY_PATH.as_posix()],'planSha256':V2_FILE_HASHES[PLAN_PATH.as_posix()],
        'frozen':dict(FROZEN),'termsSha256':hashlib.sha256(canonical(json.loads((ROOT/POLICY_PATH).read_text()))).hexdigest()}
    if 'continuation' in meta:value['continuationSha256']=hashlib.sha256(canonical(meta['continuation'])).hexdigest()
    return value


def authorize(meta,permit,*,now=None,current_source=None):
    preflight();now=time.time() if now is None else now
    need(sys.platform=='linux','V2_PAID_LINUX_EXECUTION_REQUIRED')
    need(meta.get('mode')=='PAID_EXPLICIT_V2','V2_PAID_APPROVAL_REQUIRED')
    need(meta.get('fixture',{}).get('mode')=='REAL_ISOLATED','V2_PAID_REAL_FIXTURE_REQUIRED')
    need(str(uuid.UUID(meta['allocation']))==meta['allocation'],'V2_PAID_ALLOCATION')
    need(hashlib.sha256(canonical(meta['fixture'])).hexdigest()==meta['fixtureHash'],'V2_PAID_FIXTURE_DRIFT')
    need(meta['source']==current_source,'V2_PAID_SOURCE_DRIFT')
    expected=context(meta)
    need(type(permit) is dict and set(permit)==set(expected)|{'approvalId','paidApproved','approvedAt','expiresAt','keyFile'},'V2_PAID_PERMIT_SCHEMA')
    need(all(type(permit[k]) is type(v) and permit[k]==v for k,v in expected.items()),'V2_PAID_BINDING_MISMATCH')
    need(permit['paidApproved'] is True and type(permit['approvalId']) is str and re.fullmatch(r'tms_pilot_v2_[0-9a-f]{32}',permit['approvalId']) is not None,'V2_PAID_APPROVAL_REQUIRED')
    need(meta.get('approvalId')==permit['approvalId'],'V2_PAID_APPROVAL_ID_MISMATCH')
    need(type(permit['approvedAt']) is int and type(permit['expiresAt']) is int and
         permit['approvedAt']<=now<permit['expiresAt']<=permit['approvedAt']+14400,'V2_PAID_PERMIT_EXPIRED')
    need(type(permit['keyFile']) is str and permit['keyFile'] and len(permit['keyFile'])<=1024 and Path(permit['keyFile']).is_absolute(),'V2_PAID_KEY_REFERENCE')
    need(hashlib.sha256(canonical(permit)).hexdigest()==meta['permitHash'],'V2_PAID_PERMIT_DRIFT')
    need(type(meta['budgetAuthority']) is str and re.fullmatch(r'[0-9a-f]{64}',meta['budgetAuthority']) is not None,'V2_PAID_BUDGET_AUTHORITY')
    need(not (Path(meta['folder'])/'STOP').exists(),'V2_PAID_STOPPED')
    if 'continuation' in meta:
        from ai_pilot_continuation import verify_anchor
        verify_anchor(meta)
    return permit['approvalId']


def load_permit(meta):
    p=Path(meta['approvalFile'])
    need(p.is_absolute() and not p.is_symlink() and p.stat().st_size<=16384,'V2_PAID_APPROVAL_REQUIRED')
    return json.loads(p.read_text(encoding='utf8'))


def read_key(reference):
    p=Path(reference)
    need(p.is_absolute() and not p.is_symlink() and stat.S_ISREG(p.stat().st_mode) and p.stat().st_size<=512,'V2_PAID_KEY_REFERENCE')
    key=p.read_text(encoding='utf8').strip()
    need(bool(key) and '\n' not in key and '\r' not in key,'V2_PAID_KEY_REFERENCE')
    return key


class PaidSender:
    """Called ONLY inside PilotCoordinator.dispatch's post-COMMIT supplier closure.

    The PG receipt was supplied by the normal concrete PgProviderBudget path.
    Recheck its matching SQLite reservation before key read or HTTPS construction.
    """
    def __init__(self,c,meta,permit_loader,current_source,*,key_reader=read_key,transport_factory=None,clock=time.time):
        self.c,self.meta,self.permit_loader,self.current_source=c,meta,permit_loader,current_source
        self.key_reader,self.clock=key_reader,clock;self.transport=None
        if transport_factory is None:
            from ai_pilot_paid_transport import sender
            transport_factory=sender
        self.factory=transport_factory

    def send(self,path,raw,timeout,pg_id,actor,server_request,corpus):
        start=time.monotonic();permit=self.permit_loader()
        authorize(self.meta,permit,now=self.clock(),current_source=self.current_source())
        need(json.loads(self.c.identity)['mode']=='PAID_V2','V2_UNPAID_CANNOT_BECOME_PAID')
        with self.c.db() as db:
            self.c.verify(db);phase=self.c.active(db)
            need(phase['corpus']==corpus,'V2_PAID_CORPUS_MISMATCH')
            row=db.execute('SELECT r.body_hash,r.outcome,s.actor,s.server_request FROM reservations r JOIN submissions s ON s.ticket=r.ticket WHERE r.pg_id=?',(pg_id,)).fetchone()
            need(row==(hashlib.sha256(raw).hexdigest(),'UNKNOWN',actor,server_request),'V2_PAID_COMMITTED_RESERVATION_REQUIRED')
            self.c.assert_no_unresolved(db,exclude=pg_id)
        left=min(timeout-(time.monotonic()-start),permit['expiresAt']-self.clock())
        need(0<left<=60,'V2_CALL_DEADLINE')
        if self.transport is None:
            key=self.key_reader(permit['keyFile'])  # First possible key access, after BOTH guards.
            self.transport=self.factory(Path(self.meta['folder']),permit,key)
        left=min(timeout-(time.monotonic()-start),permit['expiresAt']-self.clock())
        need(left>0,'V2_CALL_DEADLINE')
        return self.transport(path,raw,left)  # Existing fixed-host, one-attempt bounded HTTPS implementation.
