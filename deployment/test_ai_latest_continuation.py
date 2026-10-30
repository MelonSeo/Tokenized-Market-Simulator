"""Closed observed carry only; no actual provider, credentials, Docker or historical runs."""
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
from ai_evaluation_guard import EvaluationGuard,GuardError,LATEST_POLICY,_canonical
from test_ai_evaluation_guard import llm,fake
import hashlib
import uuid


class ComparisonContinuationTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.parent=Path(self.tmp.name)/'latest-study';self.parent.mkdir()
        self.child=Path(self.tmp.name)/'latest-comparison';self.child.mkdir()
        self.addCleanup(patch.stopall)
        patch.object(b,'study_source',return_value='a'*64).start();patch.object(b,'corpus_hash',return_value='b'*64).start()
        patch.object(b,'read_key',return_value='fake-no-provider-key').start()
        self.p=b.prepare(self.parent,55000,'not-read','sha256:'+'c'*64,approved=True)
        for name in ('questions.private.json','fresh-fixture.private.json','fixture-binding.json'):(self.parent/name).write_text('{}')
        (self.parent/'STOP').touch()
        with closing(sqlite3.connect(self.parent/'budget.sqlite')) as c:
            c.execute('INSERT INTO reservations VALUES(?,?,?,?,?,?,?,?)',('old','llm',1000,400,0,7300000,'OBSERVED','a'*64));c.commit()
    def test_exact_carry_and_parent_unchanged(self):
        before=(self.parent/'budget.sqlite').read_bytes();m,_=b.prepare_comparison(self.child,self.parent,55001)
        folder,loaded=b.load(self.child);self.assertEqual(m,loaded)
        g=EvaluationGuard(folder/'budget.sqlite',run_id=m['runId'],source_hash=m['sourceHash'],corpus_hash=m['corpusHash'],
            context_hash=hashlib.sha256(_canonical(m)).hexdigest(),policy=LATEST_POLICY,carry=m['continuation']['carry'],
            validate_lineage=lambda:b.verify_parent(folder,m),execution_approved=True)
        self.assertEqual(g.summary()['llm_calls'],1);self.assertEqual(g.summary()['reserved_nusd'],7300000)
        g.dispatch('responses',llm(),fake,request_id=str(uuid.uuid4()))
        self.assertEqual(g.summary()['llm_calls'],2);self.assertEqual((self.parent/'budget.sqlite').read_bytes(),before)
    def test_unknown_parent_blocks_new_study(self):
        with closing(sqlite3.connect(self.parent/'budget.sqlite')) as c:c.execute("UPDATE reservations SET outcome='RESERVED'");c.commit()
        with self.assertRaisesRegex(GuardError,'PARENT_USAGE_UNCERTAIN'):b.prepare_comparison(self.child,self.parent,55001)
        self.assertFalse((self.child/'budget.sqlite').exists())
    def test_parent_change_fails_closed(self):
        m,_=b.prepare_comparison(self.child,self.parent,55001)
        (self.parent/'questions.private.json').write_text('{"changed":true}')
        with self.assertRaisesRegex(GuardError,'PARENT_CHANGED'):b.load(self.child)
    def test_child_broker_first_summary_then_valid_dispatch_keeps_carry(self):
        b.prepare_comparison(self.child,self.parent,55001)
        out=io.BytesIO()
        frame={'id':str(uuid.uuid4()),'path':'responses','body':base64.b64encode(llm()).decode(),'timeoutMs':1000}
        with patch.object(b,'permit_sender',return_value=lambda p,raw,t:fake(p,raw)):
            b.broker(self.child,live=True,approval=self.child/'approval.json',
                inp=io.BytesIO(_canonical({'summary':True})+b'\n'+_canonical(frame)+b'\n'),out=out)
        replies=[json.loads(line) for line in out.getvalue().splitlines()]
        self.assertTrue(replies[0]['ready']);self.assertEqual(replies[1]['summary']['llm_calls'],1)
        self.assertTrue(replies[2]['ok']);self.assertFalse((self.child/'STOP').exists())
    def test_parent_not_stopped_and_prior_candidates_denied(self):
        (self.parent/'STOP').unlink()
        with self.assertRaisesRegex(GuardError,'PARENT_NOT_CLOSED'):b.parent_state(self.parent)
        (self.parent/'STOP').touch()
        with closing(sqlite3.connect(self.parent/'budget.sqlite')) as c:c.execute("INSERT INTO profiles VALUES('experiment1','x')");c.commit()
        with self.assertRaisesRegex(GuardError,'PARENT_EXPERIMENTS_EXIST'):b.parent_state(self.parent)

    def closed_child(self):
        m,_=b.prepare_comparison(self.child,self.parent,55001)
        for name in ('comparison.private.json','completion.private.json'):(self.child/name).write_text('{}')
        (self.child/'STOP').touch()
        with closing(sqlite3.connect(self.child/'budget.sqlite')) as c:
            c.execute('INSERT INTO reservations VALUES(?,?,?,?,?,?,?,?)',('child','llm',2000,500,0,11000000,'OBSERVED','b'*64));c.commit()
        return m

    def test_successor_accumulates_bounds_once_without_old_keys(self):
        self.closed_child();(self.parent/'openai.key').unlink();(self.child/'openai.key').unlink()
        old=(self.child/'budget.sqlite').read_bytes()
        final=Path(self.tmp.name)/'new-session'/'latest-study';final.mkdir(parents=True)
        m=b.prepare(final,55002,'not-read','sha256:'+'c'*64,approved=True,predecessor=self.child)
        self.assertEqual(b.load(final)[1],m)
        g=EvaluationGuard(final/'budget.sqlite',run_id=m['runId'],source_hash=m['sourceHash'],corpus_hash=m['corpusHash'],
            context_hash=hashlib.sha256(_canonical(m)).hexdigest(),policy=LATEST_POLICY,carry=m['verificationLineage']['carry'],
            validate_lineage=lambda:b.verify_successor(m),execution_approved=True)
        self.assertEqual(g.summary()['llm_calls'],2);self.assertEqual(g.summary()['reserved_nusd'],18300000)
        self.assertNotEqual(m['dbName'],b.closed_comparison(self.child)[0]['dbName'])
        self.assertEqual((self.child/'budget.sqlite').read_bytes(),old)
        (self.child/'completion.private.json').write_text('{"tampered":true}')
        with self.assertRaisesRegex(GuardError,'PARENT_CHANGED'):b.load(final)

    def test_input_extension_carries_once_and_keeps_old_policy_bytes(self):
        self.closed_child();old=(self.child/'budget.sqlite').read_bytes();old_meta=(self.child/'run.json').read_bytes()
        final=Path(self.tmp.name)/'v2-session'/'latest-study';final.mkdir(parents=True)
        m=b.prepare(final,55002,'not-read','sha256:'+'c'*64,approved=True,predecessor=self.child,input_extension=True,residual=True)
        self.assertEqual(m['policy']['input_tokens'],1500000);self.assertTrue(m['residualAcceptance'])
        self.assertEqual({k:v for k,v in m['policy'].items() if k not in ('version','input_tokens')},{k:v for k,v in b.LATEST_POLICY.items() if k not in ('version','input_tokens')})
        self.assertEqual(m['verificationLineage']['carry']['llm_calls'],2);self.assertEqual(m['verificationLineage']['carry']['reserved_nusd'],18300000)
        self.assertEqual(b.load(final)[1],m)
        self.assertEqual((self.child/'budget.sqlite').read_bytes(),old);self.assertEqual((self.child/'run.json').read_bytes(),old_meta)
        for name in ('fresh-fixture.private.json','fixture-binding.json'):(final/name).write_text('{}')
        b.bind_fixture(final)
        g=EvaluationGuard(final/'budget.sqlite',run_id=m['runId'],source_hash=m['sourceHash'],corpus_hash=m['corpusHash'],
            context_hash=hashlib.sha256(_canonical(m)).hexdigest(),policy=b.LATEST_POLICY_V2,carry=m['verificationLineage']['carry'],validate_lineage=lambda:b.verify_successor(m),execution_approved=True)
        g.dispatch('responses',llm(),fake,request_id=str(uuid.uuid4()));self.assertEqual(g.summary()['llm_calls'],3)
        self.assertEqual((self.child/'budget.sqlite').read_bytes(),old)
        m['policyTransition']['approval']='unapproved';(final/'run.json').write_text(json.dumps(m))
        with self.assertRaisesRegex(GuardError,'POLICY_TRANSITION_INVALID'):b.load(final)

    def test_input_extension_without_parent_and_other_relaxation_rejected(self):
        final=Path(self.tmp.name)/'v2-session'/'latest-study';final.mkdir(parents=True)
        with self.assertRaisesRegex(GuardError,'INPUT_EXTENSION_REQUIRES_CARRY'):b.prepare(final,55002,'not-read','sha256:'+'c'*64,approved=True,input_extension=True)
        with self.assertRaisesRegex(GuardError,'UNAPPROVED_POLICY'):
            EvaluationGuard(final/'bad.sqlite',run_id='bad',source_hash='a'*64,corpus_hash='b'*64,policy={**b.LATEST_POLICY_V2,'cap_nusd':6000000000},create=True)
        self.assertFalse((final/'bad.sqlite').exists())
        with self.assertRaisesRegex(GuardError,'UNAPPROVED_POLICY'):b.policy_for({'policy':{**b.LATEST_POLICY_V2,'input_tokens':1500000.0}})

    def test_unknown_comparison_and_missing_stop_deny_successor(self):
        self.closed_child()
        with closing(sqlite3.connect(self.child/'budget.sqlite')) as c:c.execute("UPDATE reservations SET outcome='RESERVED'");c.commit()
        with self.assertRaisesRegex(GuardError,'PARENT_USAGE_UNCERTAIN'):b.closed_comparison(self.child)
        (self.child/'STOP').unlink()
        with self.assertRaisesRegex(GuardError,'CLOSED_COMPARISON_REQUIRED'):b.closed_comparison(self.child)

    def test_successor_claim_blocks_sibling_ledger_and_tampering(self):
        self.closed_child()
        final=Path(self.tmp.name)/'first';final.mkdir()
        m=b.prepare(final,55002,'not-read','sha256:'+'c'*64,approved=True,predecessor=self.child)
        second=Path(self.tmp.name)/'second';second.mkdir()
        with self.assertRaises(FileExistsError):b.prepare(second,55003,'not-read','sha256:'+'c'*64,approved=True,predecessor=self.child)
        self.assertFalse((second/'budget.sqlite').exists())
        claim=self.child.parent/'latest-final-successor.claim'
        claim.write_text('{}')
        with self.assertRaisesRegex(GuardError,'SUCCESSOR_CLAIM_INVALID'):b.load(final)

    def test_closed_zero_dispatch_environment_failure_can_continue_only_as_chain(self):
        self.closed_child()
        first=Path(self.tmp.name)/'first-session'/'latest-study';first.mkdir(parents=True)
        m=b.prepare(first,55002,'not-read','sha256:'+'c'*64,approved=True,predecessor=self.child)
        (first/'STOP').write_text('PRE_DISPATCH_ENVIRONMENT_FAILED')
        (first/'openai.key').unlink()
        before={n:(first/n).read_bytes() for n in ('run.json','budget.sqlite','STOP')}
        next_run=Path(self.tmp.name)/'next-session'/'latest-study';next_run.mkdir(parents=True)
        successor=b.prepare(next_run,55003,'not-read','sha256:'+'c'*64,approved=True,predecessor=first)
        self.assertEqual(successor['verificationLineage']['carry'],m['verificationLineage']['carry'])
        self.assertEqual(b.load(next_run)[1],successor)
        for n,raw in before.items():self.assertEqual((first/n).read_bytes(),raw)
        sibling=Path(self.tmp.name)/'sibling-session'/'latest-study';sibling.mkdir(parents=True)
        with self.assertRaises(FileExistsError):b.prepare(sibling,55004,'not-read','sha256:'+'c'*64,approved=True,predecessor=first)

    def test_closed_successor_adds_own_rows_once_and_unknown_blocks(self):
        self.closed_child();first=Path(self.tmp.name)/'first-session'/'latest-study';first.mkdir(parents=True)
        m=b.prepare(first,55002,'not-read','sha256:'+'c'*64,approved=True,predecessor=self.child)
        for n in ('fresh-fixture.private.json','fixture-binding.json'):(first/n).write_text('{}')
        b.bind_fixture(first);(first/'STOP').touch()
        with closing(sqlite3.connect(first/'budget.sqlite')) as c:
            c.execute('INSERT INTO reservations VALUES(?,?,?,?,?,?,?,?)',('final','llm',1000,400,0,7300000,'OBSERVED','a'*64));c.commit()
        _,carry,_=b.closed_previous(first)
        self.assertEqual(carry['llm_calls'],3);self.assertEqual(carry['reserved_nusd'],25600000)
        with closing(sqlite3.connect(first/'budget.sqlite')) as c:c.execute("UPDATE reservations SET outcome='RESERVED'");c.commit()
        with self.assertRaisesRegex(GuardError,'PARENT_USAGE_UNCERTAIN'):b.closed_previous(first)
        with self.assertRaisesRegex(GuardError,'PARENT_USAGE_UNCERTAIN'):b.closed_previous(first,approve_uncertain=True)
        waiver={**b.UNCERTAIN_CARRY,'runId':m['runId'],'requestId':'final','input':1000,'output':400,'embedding':0,'cost':7300000}
        with patch.object(b,'UNCERTAIN_CARRY',waiver):
            _,approved,_=b.closed_previous(first,approve_uncertain=True)
            self.assertEqual(approved,carry)
            next_run=Path(self.tmp.name)/'approved-session'/'latest-study';next_run.mkdir(parents=True)
            child=b.prepare(next_run,55003,'not-read','sha256:'+'c'*64,approved=True,predecessor=first,uncertain_approved=True)
            self.assertEqual(child['verificationLineage']['carry'],carry);self.assertEqual(b.load(next_run)[1],child)
            with closing(sqlite3.connect(first/'budget.sqlite')) as c:
                self.assertEqual(c.execute('SELECT outcome FROM reservations').fetchall(),[('RESERVED',)])
                c.execute('UPDATE reservations SET cost=cost+1');c.commit()
            with self.assertRaises(GuardError):b.load(next_run)

if __name__=='__main__':unittest.main()
