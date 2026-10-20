"""Offline v3 evidence -> v4 cumulative input budget; never touch actual parents."""
from contextlib import closing
import hashlib
import json
import sqlite3
import uuid
from pathlib import Path
import tempfile
import unittest
from ai_evaluation_guard import POLICY,LEGACY_POLICY_V3,EvaluationGuard,GuardError,_canonical
from ai_evaluation_lineage import prepare_child,verify
from ai_reevaluation_plan import derive
from test_ai_evaluation_guard import llm,fake
import test_ai_reevaluation_plan as plan_tests

class InputBudgetV4Test(unittest.TestCase):
    def setUp(self):
        plan_tests.ReassessmentPlanTest.setUp(self)
        self.meta=json.loads((self.folder/'run.json').read_text(encoding='utf-8'))
        self.meta['policy']=dict(LEGACY_POLICY_V3)
        (self.folder/'run.json').write_bytes(_canonical(self.meta))
        identity={'run':self.meta['runId'],'source':self.meta['sourceHash'],'corpus':self.meta['corpusHash'],
                  'context':hashlib.sha256(_canonical(self.meta)).hexdigest(),'policy':dict(LEGACY_POLICY_V3)}
        with closing(sqlite3.connect(self.folder/'budget.sqlite',isolation_level=None)) as db:db.execute('update identity set value=?',(_canonical(identity).decode(),))
    def test_only_version_and_input_limit_change(self):
        old=dict(LEGACY_POLICY_V3);new=dict(POLICY)
        self.assertEqual({k for k in new if old[k]!=new[k]},{'version','input_tokens'})
        self.assertEqual(new['input_tokens'],400000);self.assertEqual(old['input_tokens'],256000)
    def test_v3_parent_exact_identity_and_bytes_preserved_with_full_carry(self):
        before={f:(self.folder/f).read_bytes() for f in ['run.json','budget.sqlite']}
        p=derive(self.folder)
        self.assertEqual(p['parentPolicyVersion'],'futures-ai-eval-v3');self.assertEqual(p['currentPolicyVersion'],'futures-ai-eval-v4')
        self.assertEqual(p['remaining'],dict(llm_calls=27,embedding_calls=45,input_tokens=360563,output_tokens=28800,embedding_tokens=26799,cap_nusd=1861543480))
        self.assertEqual(before,{f:(self.folder/f).read_bytes() for f in before})
    def test_v3_parent_cannot_be_silently_adopted_by_v4_guard(self):
        with self.assertRaisesRegex(GuardError,'IDENTITY_MISMATCH'):
            EvaluationGuard(self.folder/'budget.sqlite',run_id=self.meta['runId'],source_hash=self.meta['sourceHash'],corpus_hash=self.meta['corpusHash'],context_hash=hashlib.sha256(_canonical(self.meta)).hexdigest())
    def test_v4_child_can_exceed_old_input_cap_but_not_400k_total(self):
        registry=Path(self.tmp.name)/'registry';child=Path(self.tmp.name)/'child'
        m=prepare_child(self.folder,child,59991,'a'*64,self.meta['corpusHash'],registry)
        args=dict(run_id=m['runId'],source_hash=m['sourceHash'],corpus_hash=m['corpusHash'],context_hash=hashlib.sha256(_canonical(m)).hexdigest(),
                  carry=verify(child,m,registry),validate_lineage=lambda:verify(child,m,registry),execution_approved=True)
        guard=EvaluationGuard(child/'budget.sqlite',**args);raw=llm(input='x'*25000,max_output_tokens=1)
        while guard.summary()['input_tokens']+len(raw)+4096<=400000:guard.dispatch('responses',raw,fake,request_id=str(uuid.uuid4()))
        self.assertGreater(guard.summary()['input_tokens'],256000)
        with self.assertRaisesRegex(GuardError,'RUN_LIMIT'):EvaluationGuard(child/'budget.sqlite',**args).dispatch('responses',raw,fake,request_id=str(uuid.uuid4()))
        with self.assertRaisesRegex(GuardError,'CHILD_ALREADY_CLAIMED'):prepare_child(self.folder,Path(self.tmp.name)/'second',59992,'a'*64,self.meta['corpusHash'],registry)
    def test_v3_overrun_not_legalized_by_v4_input_increase(self):
        with closing(sqlite3.connect(self.folder/'budget.sqlite',isolation_level=None)) as db:
            db.execute("update reservations set input=60000,cost=60000*2500+output*12000 where kind='llm'")
        with self.assertRaisesRegex(ValueError,'PARENT_EXCEEDS_POLICY'):derive(self.folder)

if __name__=='__main__':unittest.main()
