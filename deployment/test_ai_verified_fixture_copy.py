import json
from pathlib import Path
import tempfile
import unittest
from ai_verified_fixture_copy import copy_verified,validate,inventory


class CopyTest(unittest.TestCase):
    def source(self,root):
        s=root/'phase-a-test';f=s/'fixtures/futures-ai-latest-test';r=s/'latest-study'
        for d in (r,f/'chain',f/'postgres',f/'release',f/'secrets'):d.mkdir(parents=True,exist_ok=True)
        (r/'STOP').write_text('STOP')
        (r/'run.json').write_text(json.dumps({'finalAcceptance':True,'purpose':'LATEST_AI_QUALITY_20261030'}))
        (r/'fresh-fixture.private.json').write_text(json.dumps({'source':'NEW_PG_ANVIL_LEGACY_DB_COLLATERAL_NOT_C_VAULT','futures':{'verification':'VERIFIED','positionStatus':'LIQUIDATED'},'spot':{'status':'FILLED'}}))
        (r/'financial-after.private.json').write_text(json.dumps({'unchanged':True,'futuresFingerprint':'test-hash'}))
        (f/'run.json').write_text(json.dumps({'name':'owned-test','runId':'old-test'}))
        (f/'chain/state.json').write_text('{}');(f/'postgres/PG_VERSION').write_text('16');(f/'release/chain.properties').write_text('test')
        return s,f

    def target(self,root):
        t=root/'new'
        for c in ('chain','postgres','release','secrets'):(t/c).mkdir(parents=True,exist_ok=True)
        return t

    def test_copy_preserves_source_and_matches_hashes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);s,f=self.source(root);before=inventory(f)
            _,proof=copy_verified(s,root,self.target(root),lambda _:None)
            self.assertEqual(before,inventory(f));self.assertEqual(proof['sourceFinancialFingerprint'],'test-hash')
            self.assertEqual(inventory(f/'chain'),inventory(root/'new/chain'))

    def test_running_source_and_not_cold_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);s,f=self.source(root)
            with self.assertRaisesRegex(RuntimeError,'SOURCE_CONTAINER_NOT_COLD_OWNED'):
                copy_verified(s,root,self.target(root),lambda _:{'State':{'Running':True},'Config':{'Labels':{'phase-a.fixture':'owned-test'}}})
            (f/'postgres/postmaster.pid').write_text('1')
            with self.assertRaisesRegex(RuntimeError,'COLD_POSTGRES_REQUIRED'):validate(s,root)

    def test_wrong_scope_and_missing_proof_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);s,f=self.source(root)
            (root/'newroot').mkdir()
            with self.assertRaisesRegex(RuntimeError,'OWN_AI_RUNTIME_SOURCE_REQUIRED'):validate(s,root/'newroot')
            (r:=s/'latest-study/financial-after.private.json').write_text('{"unchanged":false}')
            with self.assertRaisesRegex(RuntimeError,'VERIFIED_FINANCIAL_SOURCE_REQUIRED'):validate(s,root)

if __name__=='__main__':unittest.main()
