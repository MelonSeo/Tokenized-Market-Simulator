"""Separate approved allocation. Offline mocks only; old ledgers never opened RW."""
import hashlib
import json
from pathlib import Path
import shutil
import sqlite3
import tempfile
import unittest
from unittest.mock import patch
import uuid
from contextlib import closing
from ai_evaluation_guard import EvaluationGuard,GuardError,NOVEL_POLICY,LATEST_POLICY_V2,_canonical
import ai_latest_evaluation_broker as broker
from test_ai_evaluation_guard import llm,embedding,fake


class NovelBudgetTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name);self.path=self.root/'budget.sqlite'
        self.identity=dict(run_id='novel-test',source_hash='a'*64,corpus_hash='b'*64)
    def guard(self,**kwargs):return EvaluationGuard(self.path,**self.identity,policy=NOVEL_POLICY,**kwargs)
    def test_zero_initial_reservation_and_other_policy_cannot_reopen(self):
        g=self.guard(create=True,execution_approved=True)
        self.assertEqual(g.summary()['reserved_nusd'],0)
        g.dispatch('responses',llm(),fake,request_id=str(uuid.uuid4()))
        self.assertEqual(g.summary()['llm_calls'],1)
        with self.assertRaisesRegex(GuardError,'IDENTITY_MISMATCH'):
            EvaluationGuard(self.path,**self.identity,policy=LATEST_POLICY_V2)
    def test_every_limit_is_checked_before_transport(self):
        for field,limit,kind in [('llm_calls',56,'llm'),('embedding_calls',80,'embedding'),('input_tokens',1835008,'llm'),
                ('output_tokens',89600,'llm'),('embedding_tokens',160000,'embedding'),('reserved_nusd',5700000000,'llm')]:
            with self.subTest(field=field),tempfile.TemporaryDirectory() as folder:
                g=EvaluationGuard(Path(folder)/'budget.sqlite',**self.identity,create=True,execution_approved=True,policy=NOVEL_POLICY)
                with closing(sqlite3.connect(g.path)) as db:
                    for i in range(limit if field.endswith('_calls') else 1):
                        db.execute('INSERT INTO reservations VALUES(?,?,?,?,?,?,?,?)',(str(i),kind,limit if field=='input_tokens' else 0,
                            limit if field=='output_tokens' else 0,limit if field=='embedding_tokens' else 0,
                            limit if field=='reserved_nusd' else 0,'OBSERVED','f'*64))
                    db.commit()
                with self.assertRaisesRegex(GuardError,'RUN_LIMIT|COST_LIMIT'):
                    g.dispatch('embeddings' if kind=='embedding' else 'responses',embedding() if kind=='embedding' else llm(),
                        lambda *args:self.fail('MUST_NOT_TRANSMIT'),request_id=str(uuid.uuid4()))
    def test_cache_write_premium_and_long_context_exclusion(self):
        g=self.guard(create=True,execution_approved=True)
        g.dispatch('responses',llm(),fake,request_id=str(uuid.uuid4()))
        s=g.summary();self.assertEqual(s['reserved_nusd'],s['input_tokens']*2500+s['output_tokens']*12000)
        with self.assertRaisesRegex(GuardError,'INPUT_LIMIT'):
            g.dispatch('responses',llm(input='x'*33000),lambda *args:self.fail('NO_LONG_CONTEXT_TIER'),request_id=str(uuid.uuid4()))
        with self.assertRaisesRegex(GuardError,'UNSUPPORTED_REQUEST'):
            g.dispatch('responses',llm(service_tier='priority'),lambda *args:self.fail('NO_PRIORITY_SURCHARGE'),request_id=str(uuid.uuid4()))
    def test_uncertain_call_keeps_full_reservation_and_blocks_next(self):
        g=self.guard(create=True,execution_approved=True)
        with self.assertRaisesRegex(GuardError,'TRANSPORT_UNCERTAIN'):
            g.dispatch('responses',llm(),lambda *args:(_ for _ in ()).throw(TimeoutError()),request_id=str(uuid.uuid4()))
        prior=g.summary();g=self.guard(execution_approved=True)
        self.assertEqual(g.summary(),prior)
        with self.assertRaisesRegex(GuardError,'RUN_HALTED'):
            g.dispatch('responses',llm(),lambda *args:self.fail('NO_RETRY'),request_id=str(uuid.uuid4()))
    def test_prepare_new_allocation_preserves_reference_and_rejects_duplicate_allocation(self):
        repo=self.root/'repo';(repo/'deployment/runtime').mkdir(parents=True)
        resource=repo/'backend/src/test/resources/ai';resource.mkdir(parents=True)
        shutil.copyfile(broker.REPO/'backend/src/test/resources/ai/novel-30-v2.json',resource/'novel-30-v2.json')
        parent=self.root/'historical';parent.mkdir()
        for leaf in ('run.json','budget.sqlite','STOP'):(parent/leaf).write_text('CLOSED_HISTORICAL_'+leaf)
        protected={n:(parent/n).read_bytes() for n in ('run.json','budget.sqlite','STOP')}
        totals=dict(llm_calls=96,embedding_calls=115,input_tokens=1366806,output_tokens=112800,embedding_tokens=367419,reserved_nusd=4777963380)
        child=self.root/'new';child.mkdir()
        with patch.object(broker,'REPO',repo),patch.object(broker,'study_source',return_value='a'*64),patch.object(broker,'corpus_hash',return_value='b'*64),\
             patch.object(broker,'read_key',return_value='offline-placeholder'),patch.object(broker,'closed_previous',return_value=({},totals,{})):
            m=broker.prepare(child,55000,'not-read','sha256:'+'c'*64,approved=True,novel_reference=parent)
            self.assertNotIn('verificationLineage',m);self.assertNotIn('continuation',m)
            self.assertEqual(m['separateApproval']['historicalTotalsNotCharged'],totals)
            g=EvaluationGuard(child/'budget.sqlite',run_id=m['runId'],source_hash=m['sourceHash'],corpus_hash=m['corpusHash'],
                context_hash=hashlib.sha256(_canonical(m)).hexdigest(),policy=NOVEL_POLICY)
            self.assertEqual(g.summary()['llm_calls'],0);self.assertEqual(g.summary()['reserved_nusd'],0)
            broker.verify_novel_reference(child,m)
            sibling=self.root/'sibling';sibling.mkdir()
            with self.assertRaises(FileExistsError):broker.prepare(sibling,55001,'not-read','sha256:'+'c'*64,approved=True,novel_reference=parent)
            self.assertFalse((sibling/'budget.sqlite').exists())
            for n,raw in protected.items():self.assertEqual((parent/n).read_bytes(),raw)
            (parent/'STOP').write_text('tamper')
            with self.assertRaisesRegex(GuardError,'HISTORICAL_REFERENCE_CHANGED'):broker.verify_novel_reference(child,m)
    def test_followup_carries_only_new_budget_and_never_resets_global_allocation(self):
        repo=self.root/'repo';(repo/'deployment/runtime').mkdir(parents=True)
        resource=repo/'backend/src/test/resources/ai';resource.mkdir(parents=True)
        shutil.copyfile(broker.REPO/'backend/src/test/resources/ai/novel-30-v2.json',resource/'novel-30-v2.json')
        historical=self.root/'historical';historical.mkdir()
        for leaf in ('run.json','budget.sqlite','STOP'):(historical/leaf).write_text('IMMUTABLE_'+leaf)
        totals=dict(llm_calls=96,embedding_calls=115,input_tokens=1366806,output_tokens=112800,embedding_tokens=367419,reserved_nusd=4777963380)
        original_closed=broker.closed_previous
        def closed(folder,**kwargs):return ({},totals,{}) if Path(folder)==historical else original_closed(folder,**kwargs)
        first=self.root/'root-session/latest-study';first.mkdir(parents=True)
        second=self.root/'child-session/latest-study';second.mkdir(parents=True)
        with patch.object(broker,'REPO',repo),patch.object(broker,'study_source',return_value='a'*64),patch.object(broker,'corpus_hash',return_value='b'*64),\
             patch.object(broker,'read_key',return_value='offline-placeholder'),patch.object(broker,'closed_previous',side_effect=closed):
            m=broker.prepare(first,55000,'not-read','sha256:'+'c'*64,approved=True,novel_reference=historical)
            for n in ('fresh-fixture.private.json','fixture-binding.json','questions.private.json','completion.private.json'):(first/n).write_text('{}')
            (first/'financial-after.private.json').write_text('{"unchanged":true}')
            broker.bind_fixture(first)
            def guard(folder,meta,create_carry=False):
                return EvaluationGuard(folder/'budget.sqlite',run_id=meta['runId'],source_hash=meta['sourceHash'],corpus_hash=meta['corpusHash'],
                    context_hash=hashlib.sha256(_canonical(meta)).hexdigest(),policy=NOVEL_POLICY,execution_approved=True,
                    **({'carry':meta['verificationLineage']['carry'],'validate_lineage':lambda:broker.verify_successor(meta)} if create_carry else {}))
            g=guard(first,m);g.dispatch('responses',llm(),fake,request_id=str(uuid.uuid4()));(first/'STOP').write_text('CLOSED')
            old_ledger=(first/'budget.sqlite').read_bytes();allocation=(repo/'deployment/runtime/novel30-approved-570.claim').read_bytes()
            child=broker.prepare(second,55001,'not-read','sha256:'+'c'*64,approved=True,predecessor=first,novel_reference=historical,novel_followup=True)
            broker.verify_novel_reference(second,child);broker.verify_successor(child)
            g2=guard(second,child,True);self.assertEqual(g2.summary()['llm_calls'],1)
            g2.dispatch('responses',llm(),fake,request_id=str(uuid.uuid4()));self.assertEqual(g2.summary()['llm_calls'],2)
            self.assertLess(g2.summary()['reserved_nusd'],4777963380)
            self.assertEqual((first/'budget.sqlite').read_bytes(),old_ledger)
            self.assertEqual((repo/'deployment/runtime/novel30-approved-570.claim').read_bytes(),allocation)


if __name__=='__main__':unittest.main()
