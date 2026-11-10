"""No paid calls: observed-prefix memory fixtures and strict review clocks."""
import json,hashlib,sqlite3,tempfile,unittest,uuid,copy
from pathlib import Path
from unittest.mock import patch
import ai_pilot_continuation as carry
from ai_pilot_preflight import ROOT,canonical
from ai_pilot_coordinator import PilotCoordinator
from ai_pilot_http_plan import PilotHttpPlan
from ai_pilot_paid_sender import context,authorize
from ai_pilot_paid_adapter import private_write
from ai_pilot_review import ReviewGate
from ai_pilot_unpaid_live import save
from test_ai_pilot_coordinator import fixture
from test_ai_pilot_http_plan import ControlledAdapter

class ReviewTests(unittest.TestCase):
    def test_safe_quality_failure_is_explicit_not_inferred_from_rejection(self):
        for note,expected in [('insufficient answer',False),('SAFE_QUALITY_FAIL:',False),('SAFE_QUALITY_FAIL: missing noncritical explanation; safety checked',True)]:
            with tempfile.TemporaryDirectory() as tmp:
                g=ReviewGate(tmp,{'case':'controlled'})
                private_write(g.decision,{'evidenceSha256':g.key,'accepted':False,'reviewer':'test','notes':note})
                self.assertFalse(g.wait());self.assertIs(g.safe_quality_failure,expected)
    def test_utf8_evidence_and_timed_decision_are_roundtripped(self):
        with tempfile.TemporaryDirectory() as tmp:
            g=ReviewGate(tmp,{'question':'수수료 포함인가요?','answer':'입력액에 포함됩니다.'})
            self.assertEqual(json.loads((Path(tmp)/(g.key+'.request.private.json')).read_text(encoding='utf8'))['question'],'수수료 포함인가요?')
            private_write(g.decision,{'evidenceSha256':g.key,'accepted':True,'reviewer':'검토자','notes':'숫자·근거를 직접 확인함'})
            self.assertTrue(g.wait());self.assertLess(carry.read(Path(tmp)/(g.key+'.receipt.json'))['elapsedMillis'],300000)
    def test_deadline_and_preexisting_or_mismatched_decisions_reject(self):
        with tempfile.TemporaryDirectory() as tmp:
            clock=[10.];g=ReviewGate(tmp,{'x':1},clock=lambda:clock[0])
            private_write(g.decision,{'evidenceSha256':g.key,'accepted':True,'reviewer':'test','notes':'test'})
            clock[0]=310.
            with self.assertRaisesRegex(ValueError,'V2_SEMANTIC_REVIEW_REQUIRED'):g.wait()
        with tempfile.TemporaryDirectory() as tmp:
            key=hashlib.sha256(canonical({'x':1})).hexdigest();private_write(Path(tmp)/(key+'.decision.json'),{})
            with self.assertRaisesRegex(ValueError,'V2_PREEXISTING_REVIEW_DECISION'):ReviewGate(tmp,{'x':1})

class CarryTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name)
        self.parent=self.root/'parent';self.parent.mkdir();binding=fixture();binding['mode']='REAL_ISOLATED'
        self.old={'allocation':str(uuid.uuid4()),'mode':'PAID_EXPLICIT_V2','source':'a'*64,'fixture':binding,
                  'fixtureHash':hashlib.sha256(canonical(binding)).hexdigest(),'budgetAuthority':'b'*64,
                  'approvalId':'tms_pilot_v2_'+'c'*32,'folder':str(self.parent)}
        class Clock:
            value=1000000.
            def __call__(self):return self.value
        c=PilotCoordinator(self.parent/'pilot-v2.sqlite',allocation=self.old['allocation'],source_hash='a'*64,fixture=binding,mode='PAID_V2',create=True,clock=Clock())
        a=ControlledAdapter(c,binding);plan=PilotHttpPlan(c,binding);plan.run_index('INDEX_LEGACY',a)
        row=next(r for r in c.rows if r['phase']=='SAFETY_LEGACY' and r['slot']==0)
        ticket=c.claim(row['phase'],0);status,reply=a.request('owner','POST','/api/ai/agent/answers',row['body']);c.complete_request(ticket,reply['runId'])
        with c.db() as db:
            ids=db.execute('SELECT pg_id,kind FROM reservations ORDER BY pg_id').fetchall();seen={'LLM':0,'EMBEDDING':0}
            for uid,kind in ids:
                cost=80952500//2 if kind=='LLM' else 1595260//7
                if seen[kind]==0:cost+=80952500%2 if kind=='LLM' else 1595260%7
                seen[kind]+=1;db.execute('UPDATE reservations SET cost=? WHERE pg_id=?',(cost,uid))
        c.stop();private_write(self.parent/'STOP',{'reason':'TEST_SEMANTIC_TIMEOUT'})
        private_write(self.parent/'session.json',self.old)
        with carry.ro(self.parent/'pilot-v2.sqlite') as db:
            self.pg=[{'id':r[0],'kind':r[1],'reserved':r[2],'state':r[3]} for r in db.execute('SELECT pg_id,kind,cost,outcome FROM reservations')]
        expected=next(x for x in carry.read(ROOT/'backend/src/test/resources/ai/pilot-acceptance-v1.json')['cases'] if x['id']=='PF1a')
        self.request={'row':row,'reply':reply,'frozenExpected':expected,'criteria':carry.read(ROOT/'backend/src/test/resources/ai/pilot-acceptance-v1.json')['criteria'],'kind':'CONTROLLED_TEST_ONLY'}
        self.key=hashlib.sha256(canonical(self.request)).hexdigest()
        self.receipt={'evidenceSha256':self.key,'accepted':True,'reviewer':'test','elapsedMillis':100,'deadlineMs':300000}
        self.proof={'status':'PASS','providerCalls':0,'originalResponseReused':True,'evidenceSha256':self.key}
    def inspect(self):return carry.inspect_prefix(self.parent,self.request,self.receipt,self.proof,self.pg)
    def make_child(self):
        old,desc=self.inspect();child=self.root/'child';child.mkdir();anchor=child/'anchor';anchor.mkdir()
        for name,value in [('prefix-request.json',self.request),('receipt.json',self.receipt),('pg-rows.json',self.pg)]:private_write(anchor/name,value)
        proof={**self.proof,'originalRequestSha256':carry.sha(anchor/'prefix-request.json'),'receiptSha256':carry.sha(anchor/'receipt.json')}
        private_write(anchor/'revalidation-proof.json',proof)
        desc.update({'version':'tms-pilot-carry-pf1a-v1','childId':'d'*16,'anchorFiles':{p.name:carry.sha(p) for p in anchor.iterdir()}})
        meta={**self.old,'source':'e'*64,'folder':str(child/'session'),'continuation':desc}
        private_write(self.parent/'CONTINUATION_CLAIM.json',{'childId':desc['childId'],'source':meta['source'],'allocation':meta['allocation'],
                      'continuationSha256':hashlib.sha256(canonical(desc)).hexdigest()})
        return meta,child
    def test_only_exact_observed_prefix_can_be_carried(self):
        _,d=self.inspect();self.assertEqual(d['priorReservedNusd'],82547760);self.assertEqual(d['priorLlmCalls'],2)
    def test_unknown_pg_mismatch_and_late_review_block_before_transfer(self):
        for change in ('unknown','pg','late','case'):
            with self.subTest(change=change):
                pg=copy.deepcopy(self.pg);receipt=copy.deepcopy(self.receipt);request=copy.deepcopy(self.request)
                if change=='unknown':pg[0]['state']='UNKNOWN'
                elif change=='pg':pg.pop()
                elif change=='late':receipt['elapsedMillis']=300000
                else:request['row']['case']='PF1b'
                with self.assertRaises(ValueError):carry.inspect_prefix(self.parent,request,receipt,self.proof,pg)
    def test_import_keeps_old_stop_and_all_rows_while_only_new_control_is_opened(self):
        meta,child=self.make_child();before={n:carry.sha(self.parent/n) for n in ('pilot-v2.sqlite','STOP','session.json')}
        now=1000000000
        permit={**context(meta),'approvalId':meta['approvalId'],'paidApproved':True,'approvedAt':now,'expiresAt':now+3600,
                'keyFile':str((child/'NEVER_OPENED').resolve())}
        private_write(child/'permit.json',permit)
        original=carry.verify_anchor
        with patch('ai_pilot_paid_sender.sys.platform','linux'),patch('ai_pilot_paid_sender.time.time',return_value=now+1),patch('ai_pilot_channel.source_fingerprint',return_value='e'*64),patch.object(carry,'verify_anchor',side_effect=lambda m,*args:original(m,self.parent)):
            imported=carry.import_ledger(meta,child/'permit.json',self.parent)
            with carry.ro(child/'session/pilot-v2.sqlite') as db:
                self.assertEqual(db.execute('SELECT phase,stopped FROM control').fetchone(),(1,0))
                self.assertEqual(carry.row_hash(db),meta['continuation']['rowsSha256'])
            self.assertEqual(before,{n:carry.sha(self.parent/n) for n in before})
            with self.assertRaisesRegex(ValueError,'V2_CARRY_NO_RESTART'):carry.import_ledger(meta,child/'permit.json',self.parent)
    def test_changed_authority_or_allocation_and_second_child_are_rejected(self):
        meta,_=self.make_child()
        for field in ('allocation','approvalId','budgetAuthority'):
            bad=copy.deepcopy(meta);bad[field]='changed'
            with self.assertRaisesRegex(ValueError,'V2_CARRY_BUDGET_AUTHORITY_CHANGED'):carry.verify_anchor(bad,self.parent)
        with self.assertRaises(FileExistsError):save(self.parent/'CONTINUATION_CLAIM.json',{})

    def test_full_mock_continuation_sends_only_56_and_preserves_cumulative_usage(self):
        from ai_pilot_continuation import ContinuationSchedule
        meta,child=self.make_child();now=1000000000
        permit={**context(meta),'approvalId':meta['approvalId'],'paidApproved':True,'approvedAt':now,'expiresAt':now+3600,
                'keyFile':str((child/'NEVER_OPENED').resolve())}
        private_write(child/'permit.json',permit);original=carry.verify_anchor
        with patch('ai_pilot_paid_sender.sys.platform','linux'),patch('ai_pilot_paid_sender.time.time',return_value=now+1),patch('ai_pilot_channel.source_fingerprint',return_value='e'*64),patch.object(carry,'verify_anchor',side_effect=lambda m,*args:original(m,self.parent)):
            imported=carry.import_ledger(meta,child/'permit.json',self.parent)
            class Clock:
                value=1000000.
                def __call__(self):return self.value
            c=PilotCoordinator(child/'session/pilot-v2.sqlite',allocation=meta['allocation'],source_hash='e'*64,fixture=meta['fixture'],mode='PAID_V2',clock=Clock())
            a=ControlledAdapter(c,meta['fixture']);a.pg=copy.deepcopy(self.pg);bodies=[];request=a.request
            def capture(actor,method,path,body):bodies.append(body);return request(actor,method,path,body)
            a.request=capture
            result=ContinuationSchedule(c,imported,permit,lambda:'e'*64).run(a)
            self.assertEqual(len(a.requests),56);self.assertEqual(len(a.web),6)
            self.assertNotIn(self.request['row']['body'],bodies)
            self.assertEqual(len(result['results']),59);self.assertEqual(result['reusedRequests'],1)
            self.assertTrue(c.reconcile(a.pg)['matching']);self.assertTrue(c.summary()['stopped'])
            self.assertEqual(c.summary()['embeddingCalls'],68)
            self.assertGreaterEqual(c.summary()['reservedNusd'],82547760)

if __name__=='__main__':unittest.main()
