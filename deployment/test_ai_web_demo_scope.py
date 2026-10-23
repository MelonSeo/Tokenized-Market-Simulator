import json
from pathlib import Path
import tempfile
import unittest
import uuid
from concurrent.futures import ThreadPoolExecutor
from ai_evaluation_guard import EvaluationGuard, GuardError
from ai_web_demo_scope import WebDemoScope

CARRY={'llm_calls':12,'embedding_calls':38,'input_tokens':105629,'output_tokens':8400,'embedding_tokens':73535,'reserved_nusd':366343200}

class WebScopeTest(unittest.TestCase):
    def guard(self,folder,create=True,approved=True,carry=None):
        return EvaluationGuard(Path(folder)/'budget.sqlite',run_id='offline_web_test',source_hash='a'*64,corpus_hash='b'*64,
            create=create,execution_approved=approved,carry=dict(carry or CARRY),validate_lineage=lambda:None)
    def embedding(self):return json.dumps({'model':'text-embedding-3-small','input':['offline'],'dimensions':1536,'encoding_format':'float'}).encode()
    def sender(self,path,raw):return {'usage':{'prompt_tokens':1}}
    def test_unapproved_never_calls_sender_or_claims(self):
        with tempfile.TemporaryDirectory() as d:
            scope=WebDemoScope(d,self.guard(d,approved=False))
            with self.assertRaisesRegex(GuardError,'EXECUTION_NOT_APPROVED'):scope.claim_submission()
            with self.assertRaisesRegex(GuardError,'EXECUTION_NOT_APPROVED'):scope.dispatch('embeddings',self.embedding(),lambda *_:self.fail('sender'),request_id=str(uuid.uuid4()))
            self.assertFalse((Path(d)/'web-submission.claim').exists())
    def test_duplicate_submit_and_endpoint_survive_process_restart(self):
        with tempfile.TemporaryDirectory() as d:
            scope=WebDemoScope(d,self.guard(d));scope.claim_submission()
            scope.dispatch('embeddings',self.embedding(),self.sender,request_id=str(uuid.uuid4()))
            again=WebDemoScope(d,self.guard(d,False))
            with self.assertRaisesRegex(GuardError,'ALREADY_CLAIMED'):again.claim_submission()
            with self.assertRaisesRegex(GuardError,'ALREADY_CLAIMED'):again.dispatch('embeddings',self.embedding(),lambda *_:self.fail('retry'),request_id=str(uuid.uuid4()))
            self.assertEqual(again.guard.summary()['embedding_calls'],39)
    def test_uncertainty_keeps_reservation_and_stops_future_calls(self):
        with tempfile.TemporaryDirectory() as d:
            scope=WebDemoScope(d,self.guard(d));scope.claim_submission()
            def uncertain(*_):raise TimeoutError('private-original-must-not-leak')
            with self.assertRaisesRegex(GuardError,'TRANSPORT_UNCERTAIN'):scope.dispatch('embeddings',self.embedding(),uncertain,request_id=str(uuid.uuid4()))
            self.assertTrue((Path(d)/'STOP').is_file())
            again=WebDemoScope(d,self.guard(d,False))
            self.assertEqual(again.guard.summary()['embedding_calls'],39)
            with self.assertRaisesRegex(GuardError,'EXECUTION_NOT_APPROVED'):again.dispatch('embeddings',self.embedding(),self.sender,request_id=str(uuid.uuid4()))
    def test_global_cost_and_model_rejection_before_sender(self):
        with tempfile.TemporaryDirectory() as d:
            carry={**CARRY,'reserved_nusd':2000000000};scope=WebDemoScope(d,self.guard(d,carry=carry));scope.claim_submission()
            with self.assertRaisesRegex(GuardError,'COST_LIMIT'):scope.dispatch('embeddings',self.embedding(),lambda *_:self.fail('sender'),request_id=str(uuid.uuid4()))
            self.assertEqual(scope.guard.summary()['reserved_nusd'],2000000000)
        with tempfile.TemporaryDirectory() as d:
            scope=WebDemoScope(d,self.guard(d));scope.claim_submission()
            bad=json.loads(self.embedding());bad['model']='unapproved'
            with self.assertRaises(GuardError):scope.dispatch('embeddings',json.dumps(bad).encode(),lambda *_:self.fail('sender'),request_id=str(uuid.uuid4()))
            self.assertTrue((Path(d)/'STOP').is_file())
            again=WebDemoScope(d,self.guard(d,False))
            with self.assertRaisesRegex(GuardError,'EXECUTION_NOT_APPROVED'):
                again.dispatch('embeddings',self.embedding(),lambda *_:self.fail('after invalid model'),request_id=str(uuid.uuid4()))
    def test_new_scope_has_no_extra_global_budget(self):
        self.assertEqual(WebDemoScope.INPUT*2500+WebDemoScope.OUTPUT*12000+WebDemoScope.EMBEDDING*20,WebDemoScope.COST)
        self.assertEqual(CARRY['reserved_nusd']+WebDemoScope.COST,467483840)
    def test_two_workers_cannot_claim_same_submission(self):
        with tempfile.TemporaryDirectory() as d:
            scope=WebDemoScope(d,self.guard(d))
            def attempt(_):
                try:scope.claim_submission();return True
                except GuardError:return False
            with ThreadPoolExecutor(max_workers=2) as pool:self.assertEqual(list(pool.map(attempt,range(2))).count(True),1)
    def test_unknown_or_mismatched_guard_identity_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            self.guard(d)
            with self.assertRaisesRegex(GuardError,'IDENTITY_MISMATCH'):
                EvaluationGuard(Path(d)/'budget.sqlite',run_id='other',source_hash='a'*64,corpus_hash='b'*64,carry=CARRY,validate_lineage=lambda:None)
    def test_exact_one_llm_and_embedding_are_shared_by_all_workers(self):
        from test_ai_evaluation_guard import llm,fake
        with tempfile.TemporaryDirectory() as d:
            scope=WebDemoScope(d,self.guard(d));scope.claim_submission()
            scope.dispatch('embeddings',self.embedding(),fake,request_id=str(uuid.uuid4()))
            scope.dispatch('responses',llm(max_output_tokens=1600),fake,request_id=str(uuid.uuid4()))
            self.assertEqual(scope.guard.summary()['llm_calls'],13)
            self.assertEqual(scope.guard.summary()['embedding_calls'],39)
            with self.assertRaisesRegex(GuardError,'ALREADY_CLAIMED'):
                WebDemoScope(d,self.guard(d,False)).dispatch('responses',llm(),lambda *_:self.fail('extra LLM'),request_id=str(uuid.uuid4()))
    def test_embedding_local_token_limit_does_not_call_sender(self):
        with tempfile.TemporaryDirectory() as d:
            scope=WebDemoScope(d,self.guard(d));scope.claim_submission()
            raw=json.loads(self.embedding());raw['input']=['x'*1030]
            with self.assertRaisesRegex(GuardError,'WEB_TOKEN_LIMIT'):
                scope.dispatch('embeddings',json.dumps(raw).encode(),lambda *_:self.fail('over budget'),request_id=str(uuid.uuid4()))
            self.assertEqual(scope.guard.summary()['embedding_calls'],38)
            self.assertTrue((Path(d)/'STOP').is_file())
            again=WebDemoScope(d,self.guard(d,False))
            with self.assertRaisesRegex(GuardError,'EXECUTION_NOT_APPROVED'):
                again.dispatch('embeddings',self.embedding(),lambda *_:self.fail('after token rejection'),request_id=str(uuid.uuid4()))

if __name__=='__main__':unittest.main()
