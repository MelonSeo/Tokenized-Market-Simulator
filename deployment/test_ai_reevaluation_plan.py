import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
import uuid
from contextlib import closing

from ai_evaluation_broker import prepare
from ai_reevaluation_plan import derive


class ReassessmentPlanTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.folder=Path(self.tmp.name)/'parent';prepare(self.folder,59999,mode='live')
        # Synthetic ledger observations with same aggregate bounds as actual run.
        with closing(sqlite3.connect(self.folder/'budget.sqlite',isolation_level=None)) as c:
            for i in range(5):
                inp=7887 if i<4 else 7889
                c.execute('insert into reservations values(?,?,?,?,?,?,?,?)',(str(uuid.uuid4()),'llm',inp,640,0,inp*2500+640*12000,'OBSERVED','a'*64))
            for i in range(35):
                emb=2091 if i<34 else 2107
                c.execute('insert into reservations values(?,?,?,?,?,?,?,?)',(str(uuid.uuid4()),'embedding',0,0,emb,emb*20,'OBSERVED','b'*64))
    def test_no_reset_exact_remaining_bounds(self):
        p=derive(self.folder)
        self.assertEqual(p['remaining'],{'llm_calls':27,'embedding_calls':45,'input_tokens':216563,'output_tokens':28800,'embedding_tokens':26799,'cap_nusd':1861543480})
        self.assertFalse(p['executable']);self.assertFalse(p['paidApproved']);self.assertFalse(p['guardLineageEnforcementImplemented'])
    def test_q1_and_search_not_automatically_repeated(self):
        p=derive(self.folder)
        self.assertEqual(p['questionNumbers'],list(range(2,17)));self.assertFalse(p['fullReembeddingFits'])
        self.assertTrue(p['callsOutputAndQueryEmbeddingFit']);self.assertTrue(p['inputTokensRequireDryRun'])
    def test_read_only_ledger_bytes_and_no_child_created(self):
        before=(self.folder/'budget.sqlite').read_bytes();derive(self.folder)
        self.assertEqual(before,(self.folder/'budget.sqlite').read_bytes())
        self.assertEqual(set(p.name for p in self.folder.iterdir()),{'budget.sqlite','run.json'})
    def test_uncertain_or_breached_parent_is_not_funded_again(self):
        with closing(sqlite3.connect(self.folder/'budget.sqlite',isolation_level=None)) as c:c.execute("update reservations set outcome='RESERVED' where id=(select id from reservations limit 1)")
        with self.assertRaisesRegex(ValueError,'UNCERTAIN_PARENT'):derive(self.folder)
    def test_identity_tamper_rejected(self):
        m=json.loads((self.folder/'run.json').read_text());m['sourceHash']='0'*64
        (self.folder/'run.json').write_text(json.dumps(m))
        with self.assertRaisesRegex(ValueError,'PARENT_IDENTITY_INVALID'):derive(self.folder)


if __name__=='__main__':unittest.main()
