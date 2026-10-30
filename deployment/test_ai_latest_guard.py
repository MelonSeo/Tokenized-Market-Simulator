"""No providers, Docker or credentials: approved separate policy boundaries."""
import json
import sqlite3
import tempfile
import unittest
import uuid
from contextlib import closing
from pathlib import Path
from ai_evaluation_guard import EvaluationGuard, GuardError, POLICY, LATEST_POLICY
from test_ai_evaluation_guard import llm, embedding, fake

class LatestGuardTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'budget.sqlite'
        self.identity = dict(run_id='latest-test', source_hash='a'*64, corpus_hash='b'*64)
    def guard(self, **kw):
        return EvaluationGuard(self.path, **self.identity, policy=LATEST_POLICY, **kw)
    def test_legacy_default_unchanged_and_cross_policy_reopen_denied(self):
        g = EvaluationGuard(self.path, **self.identity, create=True)
        self.assertEqual(g.policy['cap_nusd'], 2000000000)
        self.assertEqual(POLICY['llm_calls'], 32)
        with self.assertRaisesRegex(GuardError, 'IDENTITY_MISMATCH'): self.guard()
    def test_latest_explicit_policy_and_default_denial(self):
        g = self.guard(create=True)
        self.assertEqual(g.policy['llm_calls'], 96)
        with self.assertRaisesRegex(GuardError, 'EXECUTION_NOT_APPROVED'):
            g.dispatch('responses', llm(), fake, request_id=str(uuid.uuid4()))
    def test_arbitrary_policy_rejected(self):
        with self.assertRaisesRegex(GuardError, 'UNAPPROVED_POLICY'):
            EvaluationGuard(self.path, **self.identity, create=True, policy={**LATEST_POLICY, 'cap_nusd':6000000000})
    def test_next_call_over_five_dollars_blocked_before_send(self):
        g = self.guard(create=True, execution_approved=True)
        with closing(sqlite3.connect(self.path)) as c:
            c.execute('INSERT INTO reservations VALUES(?,?,?,?,?,?,?,?)', ('old','llm',1,1,0,4999999999,'OBSERVED','a'*64))
            c.commit()
        sent=[]
        with self.assertRaisesRegex(GuardError, 'COST_LIMIT'):
            g.dispatch('responses', llm(), lambda *x:sent.append(x), request_id=str(uuid.uuid4()))
        self.assertFalse(sent)
    def test_each_call_and_token_limit_blocks_before_send(self):
        for field, limit in [('llm_calls',96),('input_tokens',1200000),('output_tokens',153600)]:
            with self.subTest(field=field), tempfile.TemporaryDirectory() as d:
                path=Path(d)/'budget.sqlite'
                g=EvaluationGuard(path,**self.identity,create=True,execution_approved=True,policy=LATEST_POLICY)
                with closing(sqlite3.connect(path)) as c:
                    for i in range(limit if field=='llm_calls' else 1):
                        c.execute('INSERT INTO reservations VALUES(?,?,?,?,?,?,?,?)',(str(i),'llm',limit if field=='input_tokens' else 0,limit if field=='output_tokens' else 0,0,0,'OBSERVED','a'*64))
                    c.commit()
                with self.assertRaisesRegex(GuardError,'RUN_LIMIT'):
                    g.dispatch('responses',llm(),lambda *x:self.fail('must not send'),request_id=str(uuid.uuid4()))
    def test_uncertain_dispatch_stays_charged_across_reopen(self):
        g=self.guard(create=True,execution_approved=True)
        with self.assertRaisesRegex(GuardError,'TRANSPORT_UNCERTAIN'):
            g.dispatch('responses',llm(),lambda *x:(_ for _ in ()).throw(TimeoutError()),request_id=str(uuid.uuid4()))
        reopened=self.guard(execution_approved=True)
        self.assertEqual(reopened.summary()['llm_calls'],1)
        self.assertGreater(reopened.summary()['reserved_nusd'],0)
        with self.assertRaisesRegex(GuardError,'RUN_HALTED'):
            reopened.dispatch('responses',llm(),lambda *x:self.fail('no retry'),request_id=str(uuid.uuid4()))
    def test_embedding_call_and_token_caps(self):
        for count,tokens in ((200,0),(1,400000)):
            with self.subTest(count=count),tempfile.TemporaryDirectory() as d:
                path=Path(d)/'budget.sqlite'
                g=EvaluationGuard(path,**self.identity,create=True,execution_approved=True,policy=LATEST_POLICY)
                with closing(sqlite3.connect(path)) as c:
                    for i in range(count):c.execute('INSERT INTO reservations VALUES(?,?,?,?,?,?,?,?)',
                        (str(i),'embedding',0,0,tokens,0,'OBSERVED','a'*64))
                    c.commit()
                with self.assertRaisesRegex(GuardError,'RUN_LIMIT'):
                    g.dispatch('embeddings',embedding(),lambda *x:self.fail('must not send'),request_id=str(uuid.uuid4()))

if __name__ == '__main__': unittest.main()
