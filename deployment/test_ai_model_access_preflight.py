import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock

import ai_model_access_preflight as m


class ModelAccessTest(unittest.TestCase):
    def test_only_approved_two_model_gets_no_post_no_extra_requests(self):
        connections = []
        def factory(*args, **kwargs):
            self.assertEqual(args, ("api.openai.com", 443))
            c = MagicMock()
            index = len(connections)
            c.getresponse.return_value.status = 200
            c.getresponse.return_value.read.return_value = ('{"id":"' + m.MODELS[index] + '"}').encode()
            connections.append(c)
            return c
        result = m.run("offline-placeholder", factory)
        self.assertEqual(result["status"], "PASS")
        self.assertEqual(result["metadataGets"], 2)
        self.assertEqual(result["llmCalls"] + result["embeddingCalls"], 0)
        for c, model in zip(connections, m.MODELS):
            self.assertEqual(c.request.call_count, 1)
            self.assertEqual(c.request.call_args.args[:2], ("GET", "/v1/models/" + model))

    def test_first_access_failure_stops_without_raw_error_or_retry(self):
        c = MagicMock(); c.getresponse.return_value.status = 404
        c.getresponse.return_value.read.return_value = b'{"error":{"message":"sensitive-sentinel"}}'
        factory = MagicMock(return_value=c)
        result = m.run("offline-placeholder", factory)
        self.assertEqual(result["status"], "BLOCKED")
        self.assertEqual(result["metadataGets"], 1)
        self.assertNotIn("sensitive-sentinel", str(result))
        self.assertEqual(c.request.call_count, 1)

    def test_uncertain_response_stops_without_retry(self):
        c = MagicMock(); c.getresponse.side_effect = TimeoutError("sensitive-sentinel")
        result = m.run("offline-placeholder", MagicMock(return_value=c))
        self.assertEqual(result["metadataGets"], 1)
        self.assertEqual(result["models"][0]["status"], "INCONCLUSIVE")
        self.assertNotIn("sensitive-sentinel", str(result))

    def test_explicit_key_selection_no_automatic_env_import(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "settings"
            path.write_text('OTHER_SECRET=do-not-read\nOPENAI_API_KEY="offline-placeholder"\n', encoding="utf-8")
            self.assertEqual(m.read_key(path), "offline-placeholder")
            path.write_text('OPENAI_API_KEY=x\nOPENAI_API_KEY=y\n', encoding="utf-8")
            with self.assertRaises(ValueError):
                m.read_key(path)


if __name__ == "__main__":
    unittest.main()
