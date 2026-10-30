"""Pure new-study scope/identity tests. No credentials, Docker or provider access."""
import json
import io
import base64
from pathlib import Path
import sqlite3
import tempfile
import unittest
from contextlib import closing
from unittest.mock import patch
import ai_latest_evaluation_broker as b
from ai_evaluation_guard import GuardError, _canonical


class LatestBrokerTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.folder=Path(self.tmp.name)
        self.addCleanup(patch.stopall)
        patch.object(b,'study_source',return_value='a'*64).start()
        patch.object(b,'corpus_hash',return_value='b'*64).start()
        patch.object(b,'read_key',return_value='fake-no-provider-key').start()
        self.meta=b.prepare(self.folder,55000,'not-read','sha256:'+'c'*64,approved=True)
    def test_exact_new_scope_and_no_reuse(self):
        self.assertEqual(b.load(self.folder)[1],self.meta)
        with self.assertRaisesRegex(GuardError,'NEW_EMPTY_STUDY_REQUIRED'):
            b.prepare(self.folder,55000,'not-read','sha256:'+'c'*64,approved=True)
    def test_port_mutation_denied(self):
        self.meta['dbPort']=8080
        (self.folder/'run.json').write_text(json.dumps(self.meta))
        with self.assertRaisesRegex(GuardError,'RUN_IDENTITY_MISMATCH'):b.load(self.folder)
    def test_fixture_required_and_immutable(self):
        with self.assertRaisesRegex(GuardError,'FIXTURE_BINDING_REQUIRED'):b.bind_fixture(self.folder)
        for name in ('fixture-binding.json','fresh-fixture.private.json'):(self.folder/name).write_text('{}')
        b.bind_fixture(self.folder);b.bind_fixture(self.folder)
        (self.folder/'fresh-fixture.private.json').write_text('{"changed":true}')
        with self.assertRaisesRegex(GuardError,'FIXTURE_BINDING_CHANGED'):b.bind_fixture(self.folder)
    def test_baseline_unchanged_and_two_frozen_candidates_only(self):
        raw=_canonical({'instructions':'original','text':{'format':{'name':'agent_interpretation'}}})
        self.assertEqual(b.profile_request(self.folder,raw,'responses')[0],raw)
        for pid in ('experiment1','experiment2'):
            (self.folder/'profile.json').write_text(json.dumps({'id':pid,'instructionsSuffix':'candidate'}))
            self.assertEqual(json.loads(b.profile_request(self.folder,raw,'responses')[0])['instructions'],'original\ncandidate')
        (self.folder/'profile.json').write_text(json.dumps({'id':'experiment1','instructionsSuffix':'changed'}))
        with self.assertRaisesRegex(GuardError,'PROFILE_CHANGED'):b.profile_request(self.folder,raw,'responses')
        (self.folder/'profile.json').write_text(json.dumps({'id':'experiment3','instructionsSuffix':''}))
        with self.assertRaisesRegex(GuardError,'PROFILE_INVALID'):b.profile_request(self.folder,raw,'responses')
    def test_planner_cannot_be_modified_by_synthesis_candidate(self):
        (self.folder/'profile.json').write_text(json.dumps({'id':'experiment1','instructionsSuffix':'candidate'}))
        raw=_canonical({'instructions':'original','text':{'format':{'name':'agent_plan'}}})
        self.assertEqual(b.profile_request(self.folder,raw,'responses')[0],raw)
    def test_expired_or_wrong_approval_before_sender(self):
        path=self.folder/'approval.json';a=json.loads(path.read_text());a['expiresAt']=0;path.write_text(json.dumps(a))
        with patch.object(b,'_https_sender',side_effect=AssertionError('must not create sender')):
            with self.assertRaisesRegex(GuardError,'PAID_APPROVAL_REQUIRED'):b.permit_sender(self.folder,self.meta,path)
    def test_no_approval_no_prepare(self):
        with self.assertRaisesRegex(GuardError,'PAID_APPROVAL_REQUIRED'):b.prepare(self.folder,55000,'not-read','sha256:'+'c'*64)
    def test_broker_ready_without_future_fixture_and_no_dispatch_before_binding(self):
        out=io.BytesIO()
        from test_ai_evaluation_guard import embedding
        frame={'id':'00000000-0000-4000-8000-000000000001','path':'embeddings',
               'body':base64.b64encode(embedding()).decode(),'timeoutMs':1000}
        sent=[]
        with patch.object(b,'permit_sender',return_value=lambda *args:sent.append(args)):
            b.broker(self.folder,live=True,approval=self.folder/'approval.json',inp=io.BytesIO(_canonical(frame)+b'\n'),out=out)
        replies=[json.loads(line) for line in out.getvalue().splitlines()]
        self.assertTrue(replies[0]['ready']);self.assertEqual(replies[1]['code'],'FIXTURE_BINDING_REQUIRED')
        self.assertEqual(sent,[])
    def test_first_valid_dispatch_binds_without_sqlite_self_deadlock(self):
        from test_ai_evaluation_guard import embedding,fake
        for name in ('fixture-binding.json','fresh-fixture.private.json'):(self.folder/name).write_text('{}')
        out=io.BytesIO();sent=[]
        frame={'id':'00000000-0000-4000-8000-000000000001','path':'embeddings',
               'body':base64.b64encode(embedding()).decode(),'timeoutMs':1000}
        def send(path,raw,timeout):sent.append(path);return fake(path,raw)
        with patch.object(b,'permit_sender',return_value=send):
            b.broker(self.folder,live=True,approval=self.folder/'approval.json',inp=io.BytesIO(_canonical(frame)+b'\n'),out=out)
        replies=[json.loads(line) for line in out.getvalue().splitlines()]
        self.assertTrue(replies[1]['ok']);self.assertEqual(sent,['embeddings'])
        b.verify_fixture(self.folder)

    def test_late_completed_call_observed_once_not_returned_as_answer(self):
        from test_ai_evaluation_guard import embedding,fake
        for name in ('fixture-binding.json','fresh-fixture.private.json'):(self.folder/name).write_text('{}')
        self.meta['deadlineProtocol']=2
        # This is a protocol test: identity is verified by the ordinary tests; do not mutate a real ledger.
        out=io.BytesIO();sent=[]
        frame={'id':'00000000-0000-4000-8000-000000000001','path':'embeddings',
               'body':base64.b64encode(embedding()).decode(),'timeoutMs':1000,'deadlineEpochMs':1000}
        class Deadline:
            def __init__(self,*args):pass
            def sender_budget(self):return .6
            def expired(self):return True
        def send(path,raw,timeout):sent.append(timeout);return fake(path,raw)
        with patch.object(b,'load',side_effect=lambda folder:(folder,self.meta)),patch.object(b,'FrameDeadline',Deadline),patch.object(b,'permit_sender',return_value=send),patch.object(b.EvaluationGuard,'_identity',return_value=None):
            b.broker(self.folder,live=True,approval=self.folder/'approval.json',inp=io.BytesIO(_canonical(frame)+b'\n'+_canonical({'summary':True})+b'\n'),out=out)
        replies=[json.loads(line) for line in out.getvalue().splitlines()]
        self.assertEqual(sent,[.6]);self.assertEqual(replies[1],{'ok':False,'code':'DEADLINE_EXCEEDED_OBSERVED','requestId':frame['id'],'observed':True})
        self.assertNotIn('body',replies[1]);self.assertEqual(replies[2]['summary']['embedding_calls'],1)
        with closing(sqlite3.connect(self.folder/'budget.sqlite')) as c:self.assertEqual(c.execute('SELECT outcome FROM reservations').fetchall(),[('OBSERVED',)])

if __name__=='__main__':unittest.main()
