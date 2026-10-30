import io
import json
import tempfile
import unittest
import time
from pathlib import Path
from unittest.mock import patch
import ai_web_demo_broker as w
from test_ai_evaluation_guard import llm
from ai_evaluation_guard import GuardError,POLICY,_canonical
from test_ai_web_demo_scope import CARRY

class WebBrokerTest(unittest.TestCase):
    def test_preparation_requires_ready_proof_before_new_ledger(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);(p/'run.json').write_bytes(_canonical({'sourceHash':'a','corpusHash':'b','runId':'old'}))
            (p/'index-copy-verification.json').write_bytes(_canonical({'status':'INDEXING'}))
            with patch.object(w,'source_hash',return_value='a'),patch.object(w,'corpus_hash',return_value='b'):
                with self.assertRaisesRegex(GuardError,'WEB_INDEX_NOT_READY'):w.prepare(p/'new',p,'unused','unused',2,'11111111-1111-1111-1111-111111111111')
            self.assertFalse((p/'new').exists())
    def test_claim_is_single_and_carry_is_not_reset(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);idx=p/'idx';idx.mkdir();registry=p/'registry'
            (idx/'run.json').write_bytes(_canonical({'sourceHash':'a'*64,'corpusHash':'b'*64,'runId':'old-index','dbName':'exchange_ai_eval_'+'a'*32,'dbUser':'exchange_ai_eval_'+'a'*32,'dbPort':60000}))
            (idx/'index-copy-verification.json').write_bytes(_canonical({'status':'PASS','newOwner':'old-index','providerCalls':0}))
            rows=[{'runId':'parent','local':{k:0 for k in CARRY}},{'runId':'child','local':{k:0 for k in CARRY}},{'runId':'q12','local':CARRY}]
            with patch.object(w,'source_hash',return_value='a'*64),patch.object(w,'corpus_hash',return_value='b'*64),patch.object(w,'ancestors',return_value=rows):
                m=w.prepare(p/'new',idx,p/'q12',p/'old-registry',2,'11111111-1111-1111-1111-111111111111',registry)
                self.assertEqual(w.verify(p/'new',m),CARRY)
                self.assertFalse((p/'new'/'approval.json').exists())
                with self.assertRaises(FileExistsError):w.prepare(p/'another',idx,p/'q12',p/'old-registry',2,'11111111-1111-1111-1111-111111111111',registry)
                (idx/'index-copy-verification.json').write_bytes(b'{}')
                with self.assertRaises(GuardError):w.verify(p/'new',m)
    def test_fake_sender_reuses_vector_and_labels_answer(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);(p/'source-index.json').write_text(json.dumps({'chunks':[{'path':'onchain-settlement-policy.md','heading':'선물 FuturesEngine','embedding':'[1,0]'}]}),encoding='utf-8')
            send=w.fake_sender(p)
            self.assertEqual(send('embeddings',b'{}')['data'][0]['embedding'],[1,0])
            raw=_canonical({'input':json.dumps({'evidence':{'tools':[{'evidenceId':'t','data':{'status':'LIQUIDATED'}}],'knowledge':[{'id':'k'}]}})})
            result=send('responses',raw);answer=json.loads(result['output'][0]['content'][0]['text'])
            self.assertEqual(answer['citationIds'],['t','k']);self.assertIn('FAKE_MODEL_NOT_REAL_QUALITY_EVALUATION',answer['uncertainties'])
    def test_actual_request_shape_dry_run_is_context_bound(self):
        import base64
        context={'userId':2,'positionId':'p','skillId':w.SKILL,'question':w.QUESTION,'indexProofSha256':'a'*64}
        m={'sourceHash':'a','corpusHash':'b','web':context}
        emb=_canonical({'model':'text-embedding-3-small','input':['offline'],'dimensions':1536,'encoding_format':'float'})
        capture={'sourceHash':'a','corpusHash':'b','webContext':context,'requests':[{'path':p,'bodyBase64':base64.b64encode(raw).decode()} for p,raw in [('responses',llm()),('embeddings',emb)]]}
        bounds=w.assess_dry_run(m,capture);self.assertLessEqual(bounds['reserved_nusd'],101140640)
        capture['webContext']={**context,'userId':3}
        with self.assertRaisesRegex(GuardError,'WEB_DRY_RUN_IDENTITY'):w.assess_dry_run(m,capture)
    def test_unapproved_live_never_constructs_https_sender(self):
        with tempfile.TemporaryDirectory() as d,patch.object(w,'verify'),patch('ai_evaluation_broker.live_sender') as send:
            with self.assertRaises(Exception):w.live_sender(Path(d),{'mode':'live'},Path(d)/'absent-approval.json')
            send.assert_not_called()
    def test_exact_live_permit_validates_before_key_sender_and_never_writes_approval(self):
        import hashlib,base64
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);context={'userId':2,'positionId':'p','skillId':w.SKILL,'question':w.QUESTION,'indexProofSha256':'a'*64,'carry':CARRY}
            m={'mode':'live','sourceHash':'a','corpusHash':'b','web':context}
            emb=_canonical({'model':POLICY['embedding_model'],'input':['offline'],'dimensions':1536,'encoding_format':'float'})
            cap={'sourceHash':'a','corpusHash':'b','webContext':{k:context[k] for k in ('userId','positionId','skillId','question','indexProofSha256')},'requests':[{'path':x,'bodyBase64':base64.b64encode(raw).decode()} for x,raw in [('responses',llm()),('embeddings',emb)]]}
            (p/'dry-run.private.json').write_bytes(_canonical(cap))
            a={'purpose':'ONE_WEB_DEMO','contextHash':hashlib.sha256(_canonical(m)).hexdigest(),'localLimits':w.scope(12),'carry':CARRY,'userId':2,'positionId':'p','question':w.QUESTION,'skillId':w.SKILL,'retryCount':0,'indexProofSha256':'a'*64,'llmModel':POLICY['llm_model'],'embeddingModel':POLICY['embedding_model'],'expiresAt':int(time.time())+590,'dryRunSha256':w.digest(p/'dry-run.private.json')}
            permit=p/'unit-test-permit.json';permit.write_bytes(_canonical(a));before=permit.read_bytes()
            with patch.object(w,'verify'),patch('ai_evaluation_broker.live_sender',return_value=lambda *_:{'usage':{}}) as send:
                w.live_sender(p,m,permit);self.assertEqual(send.call_count,1)
                for key,bad in [('userId',3),('retryCount',1),('expiresAt',0),('dryRunSha256','bad')]:
                    permit.write_bytes(_canonical({**a,key:bad}));send.reset_mock()
                    with self.assertRaises(GuardError):w.live_sender(p,m,permit)
                    send.assert_not_called()
                permit.write_bytes(before)
                self.assertEqual(permit.read_bytes(),before)
    def test_deadline_does_not_wait_for_stdin_or_guard_dispatch(self):
        import threading
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory() as d:
            stopped=threading.Event();guard=SimpleNamespace(approved=True)
            timer=w.arm_deadline(d,guard,.02,lambda code:stopped.set())
            try:
                self.assertTrue(stopped.wait(1));self.assertTrue((Path(d)/'STOP').is_file());self.assertFalse(guard.approved)
            finally:timer.cancel()
    def test_real_child_deadline_exits_while_main_thread_is_idle(self):
        import subprocess,sys
        with tempfile.TemporaryDirectory() as d:
            code="from types import SimpleNamespace;from ai_web_demo_broker import arm_deadline;import time,sys;arm_deadline(sys.argv[1],SimpleNamespace(approved=True),.05);time.sleep(4)"
            r=subprocess.run([sys.executable,'-B','-S','-c',code,d],capture_output=True,timeout=3,cwd=Path(__file__).resolve().parent)
            self.assertEqual(r.returncode,124);self.assertEqual((Path(d)/'STOP').read_text().strip(),'WEB_DEADLINE')

if __name__=='__main__':unittest.main()
