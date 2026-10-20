import concurrent.futures
from contextlib import closing
import hashlib
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
import uuid
import subprocess
import sys
import io
import time
from unittest.mock import patch
from ai_evaluation_guard import EvaluationGuard,GuardError,_canonical
from ai_evaluation_broker import prepare,source_hash,corpus_hash,live_sender
from ai_evaluation_lineage import prepare_child,verify
from test_ai_evaluation_guard import llm,fake

class LineageTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name)
        self.parent=self.root/'parent';self.old=prepare(self.parent,59990,mode='live');self.registry=self.root/'registry'
        with closing(sqlite3.connect(self.parent/'budget.sqlite',isolation_level=None)) as c:
            for _ in range(5):c.execute('insert into reservations values(?,?,?,?,?,?,?,?)',(str(uuid.uuid4()),'llm',7000,640,0,25180000,'OBSERVED','a'*64))
        self.before=(self.parent/'budget.sqlite').read_bytes()
    def child(self,name='child'):
        folder=self.root/name;meta=prepare_child(self.parent,folder,59991,source_hash(),corpus_hash(),self.registry)
        return folder,meta
    def guard(self,folder,meta):
        return EvaluationGuard(folder/'budget.sqlite',run_id=meta['runId'],source_hash=meta['sourceHash'],corpus_hash=meta['corpusHash'],context_hash=hashlib.sha256(_canonical(meta)).hexdigest(),
            carry=verify(folder,meta,self.registry),validate_lineage=lambda:verify(folder,meta,self.registry),execution_approved=True)
    def test_carry_survives_reopen_without_parent_mutation(self):
        f,m=self.child();g=self.guard(f,m);self.assertEqual(g.summary()['llm_calls'],5)
        g.dispatch('responses',llm(),fake,request_id=str(uuid.uuid4()))
        self.assertEqual(self.guard(f,m).summary()['llm_calls'],6);self.assertEqual(self.before,(self.parent/'budget.sqlite').read_bytes())
    def test_second_child_concurrent_claim_only_one(self):
        def claim(i):
            try:self.child('child'+str(i));return 'ok'
            except GuardError:return 'blocked'
        with concurrent.futures.ThreadPoolExecutor(2) as pool:self.assertEqual(sorted(pool.map(claim,[1,2])),['blocked','ok'])
    def test_plain_guard_cannot_omit_carry(self):
        f,m=self.child()
        with self.assertRaisesRegex(GuardError,'IDENTITY_MISMATCH'):EvaluationGuard(f/'budget.sqlite',run_id=m['runId'],source_hash=m['sourceHash'],corpus_hash=m['corpusHash'],context_hash=hashlib.sha256(_canonical(m)).hexdigest())
    def test_parent_changed_blocks_existing_child_before_sender(self):
        f,m=self.child();g=self.guard(f,m)
        with closing(sqlite3.connect(self.parent/'budget.sqlite',isolation_level=None)) as c:c.execute('update control set stopped=1')
        with self.assertRaisesRegex(GuardError,'LINEAGE_INVALID'):g.dispatch('responses',llm(),lambda *x:self.fail('must not send'),request_id=str(uuid.uuid4()))
    def test_residual_call_limit_and_uncertainty_stop(self):
        f,m=self.child();g=self.guard(f,m)
        for _ in range(27):g.dispatch('responses',llm(max_output_tokens=1),fake,request_id=str(uuid.uuid4()))
        with self.assertRaisesRegex(GuardError,'RUN_LIMIT'):g.dispatch('responses',llm(),fake,request_id=str(uuid.uuid4()))
    def test_uncertain_stays_reserved_on_restart_and_duplicate_is_blocked(self):
        f,m=self.child();g=self.guard(f,m);rid=str(uuid.uuid4())
        with self.assertRaisesRegex(GuardError,'TRANSPORT_UNCERTAIN'):g.dispatch('responses',llm(),lambda *x:(_ for _ in ()).throw(TimeoutError()),request_id=rid)
        with self.assertRaisesRegex(GuardError,'REQUEST_ALREADY_RESERVED'):self.guard(f,m).dispatch('responses',llm(),fake,request_id=rid)
        with self.assertRaisesRegex(GuardError,'RUN_HALTED'):self.guard(f,m).dispatch('responses',llm(),fake,request_id=str(uuid.uuid4()))
    def test_no_paid_approval_created(self):
        f,m=self.child();self.assertFalse((f/'approval.json').exists());self.assertFalse((f/'openai.key').exists())
        with self.assertRaisesRegex(GuardError,'PAID_APPROVAL_REQUIRED'):live_sender(f,m,f/'approval.json')
    def test_stop_persists(self):
        f,m=self.child();self.guard(f,m).stop()
        with self.assertRaisesRegex(GuardError,'RUN_STOPPED'):self.guard(f,m).dispatch('responses',llm(),fake,request_id=str(uuid.uuid4()))
    def test_parent_output_token_reservations_cannot_be_refunded(self):
        with closing(sqlite3.connect(self.parent/'budget.sqlite',isolation_level=None)) as c:c.execute('update reservations set output=6400,cost=input*2500+6400*12000')
        f,m=self.child()
        with self.assertRaisesRegex(GuardError,'RUN_LIMIT'):self.guard(f,m).dispatch('responses',llm(),fake,request_id=str(uuid.uuid4()))
    def test_different_process_reopens_same_cumulative_budget(self):
        f,m=self.child();self.guard(f,m).dispatch('responses',llm(),fake,request_id=str(uuid.uuid4()))
        code="from pathlib import Path; import json,sys,hashlib; from ai_evaluation_guard import EvaluationGuard,_canonical; from ai_evaluation_lineage import verify; f=Path(sys.argv[1]); m=json.loads((f/'run.json').read_text()); r=Path(sys.argv[2]); g=EvaluationGuard(f/'budget.sqlite',run_id=m['runId'],source_hash=m['sourceHash'],corpus_hash=m['corpusHash'],context_hash=hashlib.sha256(_canonical(m)).hexdigest(),carry=verify(f,m,r),validate_lineage=lambda:verify(f,m,r)); print(g.summary()['llm_calls'])"
        result=subprocess.run([sys.executable,'-B','-S','-c',code,str(f),str(self.registry)],capture_output=True,timeout=10)
        self.assertEqual(result.returncode,0);self.assertEqual(result.stdout.strip(),b'6')
    def test_broker_uses_cumulative_guard_at_transmission_boundary(self):
        import ai_evaluation_broker as b
        import ai_evaluation_lineage as lineage
        f,m=self.child();original=lineage.verify;out=io.BytesIO()
        frame={'id':str(uuid.uuid4()),'path':'responses','body':__import__('base64').b64encode(llm()).decode(),'timeoutMs':1000}
        with patch.object(lineage,'verify',side_effect=lambda folder,meta:original(folder,meta,self.registry)),patch.object(b,'live_sender',return_value=lambda path,raw,timeout:fake(path,raw)):
            b.broker(f,live=True,approval=f/'NONEXISTENT_FAKE_ONLY',inp=io.BytesIO(_canonical(frame)+b'\n'+_canonical({'summary':True})+b'\n'),out=out)
        self.assertEqual(json.loads(out.getvalue().splitlines()[-1])['summary']['llm_calls'],6)
    def test_parent_active_authorization_blocks_before_claim(self):
        permit={k:self.old[k] for k in ['runId','sourceHash','corpusHash']};permit['expiresAt']=int(time.time())+300
        (self.parent/'approval.json').write_text(json.dumps(permit))
        with self.assertRaisesRegex(GuardError,'PARENT_AUTHORIZATION_STILL_ACTIVE'):self.child()
        self.assertFalse(self.registry.exists())
    def test_expired_authorization_bound_to_child_then_changes_block(self):
        permit={k:self.old[k] for k in ['runId','sourceHash','corpusHash']};permit['expiresAt']=int(time.time())-10
        (self.parent/'approval.json').write_text(json.dumps(permit));f,m=self.child()
        self.assertEqual(m['lineage']['parentClosure'],'EXPIRED')
        permit['expiresAt']=int(time.time())+300;(self.parent/'approval.json').write_text(json.dumps(permit))
        with self.assertRaisesRegex(GuardError,'LINEAGE_INVALID'):self.guard(f,m)

if __name__=='__main__':unittest.main()
