import base64
from pathlib import Path
import json
import tempfile
import unittest
from ai_evaluation_dry_run import assess
from ai_evaluation_broker import prepare
from test_ai_evaluation_guard import llm

class DryRunTest(unittest.TestCase):
    def test_actual_bytes_and_bound_not_token_estimate(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);prepare(root/'parent',59999,mode='live');raw=llm()
            file=root/'capture.json';file.write_text(json.dumps({'providerCalls':0,'profiles':[{'profile':'TEST','requests':[{'question':2,'path':'responses','bodyBase64':base64.b64encode(raw).decode()}]}]}),encoding='utf-8')
            result=assess(root/'parent',file);self.assertEqual(result['profiles'][0]['total']['input_tokens'],len(raw)+4096)
            self.assertTrue(result['allProfilesFit']);self.assertFalse(result['paidApproved'])
    def test_scope_and_external_capture_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);prepare(root/'parent',59999,mode='live');file=root/'capture.json'
            for body in [{'providerCalls':1},{'providerCalls':0,'profiles':[{'profile':'BAD','requests':[{'question':1}]}]}]:
                file.write_text(json.dumps(body),encoding='utf-8')
                with self.assertRaises(ValueError):assess(root/'parent',file)

if __name__=='__main__':unittest.main()
