"""Memory-only permits and fake key/network. No approval file activation or real key access."""
from contextlib import contextmanager
import copy,hashlib,json,tempfile,unittest,uuid
from unittest.mock import Mock,patch
from ai_pilot_preflight import canonical
from ai_pilot_paid_sender import authorize,context,PaidSender


class PaidBoundaryTests(unittest.TestCase):
    def setUp(self):
        platform=patch('ai_pilot_paid_sender.sys.platform','linux');platform.start();self.addCleanup(platform.stop)
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.raw=b'controlled-not-a-provider-request';self.id=str(uuid.uuid4());self.server=str(uuid.uuid4())
        fixture={'mode':'REAL_ISOLATED','test':'MEMORY_DOUBLE_NOT_A_REAL_FIXTURE'}
        self.meta={'mode':'PAID_EXPLICIT_V2','allocation':str(uuid.uuid4()),'source':'a'*64,'fixture':fixture,'fixtureHash':hashlib.sha256(canonical(fixture)).hexdigest(),'budgetAuthority':'b'*64,'folder':self.tmp.name}
        self.permit={**context(self.meta),'approvalId':'tms_pilot_v2_'+'c'*32,'paidApproved':True,'approvedAt':1000,'expiresAt':2000,'keyFile':self.tmp.name+'/MEMORY_REFERENCE_NEVER_OPENED'}
        self.meta['permitHash']=hashlib.sha256(canonical(self.permit)).hexdigest()
        self.meta['approvalId']=self.permit['approvalId'];self.committed=True;self.unknown=False
        self.key=Mock(return_value='MEMORY_NOT_A_REAL_KEY');self.send=Mock(return_value={'usage':{'input_tokens':1,'output_tokens':1}})
        self.factory=Mock(return_value=self.send)
        outer=self
        class DB:
            def execute(self,sql,args=()):
                self.sql=sql;return self
            def fetchone(self):
                if 'pg_id<>' in self.sql:return (1,) if outer.unknown else None
                if not outer.committed:return None
                return (hashlib.sha256(outer.raw).hexdigest(),'UNKNOWN',1,outer.server)
        class C:
            identity=json.dumps({'mode':'PAID_V2'})
            @contextmanager
            def db(self):yield DB()
            def verify(self,db):pass
            def active(self,db):return {'corpus':'legacy9'}
            def assert_no_unresolved(self,db,exclude=None):
                from ai_pilot_coordinator import PilotCoordinator
                return PilotCoordinator.assert_no_unresolved(self,db,exclude)
        self.c=C()
    def dispatch(self):
        s=PaidSender(self.c,self.meta,lambda:self.permit,lambda:'a'*64,key_reader=self.key,transport_factory=self.factory,clock=lambda:1500)
        return s.send('responses',self.raw,2,self.id,1,self.server,'legacy9')
    def test_memory_valid_permit_reaches_fake_transport_only_after_reservation(self):
        self.dispatch();self.key.assert_called_once();self.send.assert_called_once()
    def test_frozen_linux_execution_requirement_rejects_windows_before_key(self):
        with patch('ai_pilot_paid_sender.sys.platform','win32'):
            with self.assertRaisesRegex(ValueError,'V2_PAID_LINUX_EXECUTION_REQUIRED'):self.dispatch()
        self.key.assert_not_called();self.send.assert_not_called()
    def test_unpaid_or_old_version_or_binding_change_reads_no_key_and_sends_zero(self):
        for field,value in [('version','v1'),('allocation',str(uuid.uuid4())),('budgetAuthority','d'*64),('paidApproved',False),('expiresAt',1400)]:
            with self.subTest(field=field):
                original=copy.deepcopy(self.permit);self.permit[field]=value
                with self.assertRaises(ValueError):self.dispatch()
                self.permit=original
        self.meta['mode']='UNPAID_CONTROLLED_ONLY'
        with self.assertRaises(ValueError):self.dispatch()
        self.key.assert_not_called();self.send.assert_not_called()
    def test_source_or_fixture_drift_and_stop_reject_before_key(self):
        self.meta['source']='e'*64
        with self.assertRaises(ValueError):self.dispatch()
        self.meta['source']='a'*64;self.meta['fixture']['test']='changed'
        with self.assertRaises(ValueError):self.dispatch()
        self.key.assert_not_called();self.send.assert_not_called()
    def test_missing_sqlite_commit_rejects_before_key(self):
        self.committed=False
        with self.assertRaisesRegex(ValueError,'V2_PAID_COMMITTED_RESERVATION_REQUIRED'):self.dispatch()
        self.key.assert_not_called();self.send.assert_not_called()
    def test_prior_unknown_or_unpaid_allocation_rejects_before_key(self):
        self.unknown=True
        with self.assertRaisesRegex(ValueError,'V2_UNKNOWN_USAGE_HALTED'):self.dispatch()
        self.unknown=False;self.c.identity=json.dumps({'mode':'UNPAID'})
        with self.assertRaisesRegex(ValueError,'V2_UNPAID_CANNOT_BECOME_PAID'):self.dispatch()
        self.key.assert_not_called();self.send.assert_not_called()
    def test_unknown_transport_has_one_attempt_no_retry_or_refund(self):
        self.send.side_effect=TimeoutError
        with self.assertRaises(TimeoutError):self.dispatch()
        self.send.assert_called_once()
    def test_one_permit_cannot_move_to_a_new_allocation_or_directory(self):
        self.meta['allocation']=str(uuid.uuid4())
        with self.assertRaisesRegex(ValueError,'V2_PAID_BINDING_MISMATCH'):self.dispatch()
        self.meta['allocation']=self.permit['allocation'];self.meta['folder']=self.tmp.name+'/other'
        with self.assertRaisesRegex(ValueError,'V2_PAID_BINDING_MISMATCH'):self.dispatch()
        self.key.assert_not_called();self.send.assert_not_called()

if __name__=='__main__':unittest.main()
