import base64
import hashlib
import io
import json
from pathlib import Path
import tempfile
import time
import unittest
import uuid
from unittest.mock import patch, MagicMock

import ai_evaluation_broker as b
import ai_evaluation_guard as g
from test_ai_evaluation_guard import llm, embedding


class BrokerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name) / "run"
        self.meta = b.prepare(self.folder, 59991)

    def frame(self, path="responses", raw=None, request_id=None):
        return {"id": request_id or str(uuid.uuid4()), "path": path,
                "body": base64.b64encode(raw or llm()).decode(), "timeoutMs": 1000}

    def run_broker(self, frames, **kwargs):
        out = io.BytesIO()
        b.broker(self.folder, inp=io.BytesIO(b"".join(g._canonical(f) + b"\n" for f in frames)), out=out, **kwargs)
        return [json.loads(line) for line in out.getvalue().splitlines()]

    def guard(self):
        return g.EvaluationGuard(self.folder / "budget.sqlite", run_id=self.meta["runId"],
                                 source_hash=self.meta["sourceHash"], corpus_hash=self.meta["corpusHash"],
                                 context_hash=hashlib.sha256(g._canonical(self.meta)).hexdigest())

    def test_offline_actual_protocol_cannot_construct_network_or_read_environment_key(self):
        with patch.object(b.http.client, "HTTPSConnection", side_effect=AssertionError("NETWORK_FORBIDDEN")), \
                patch.dict("os.environ", {"OPENAI_API_KEY": "must-not-be-used", "AI_LIVE_EVALUATION": "true"}):
            result = self.run_broker([self.frame(), self.frame("embeddings", embedding())])
        self.assertEqual(result[0]["mode"], "offline")
        self.assertTrue(result[1]["ok"] and result[2]["ok"])
        self.assertEqual(self.guard().summary()["llm_calls"], 1)
        self.assertEqual(self.guard().summary()["embedding_calls"], 1)

    def test_live_without_approval_fails_before_network(self):
        with patch.object(b.http.client, "HTTPSConnection") as network:
            with self.assertRaisesRegex(g.GuardError, "MODE_CONFLICT"):
                self.run_broker([], live=True)
            network.assert_not_called()

    def test_existing_run_is_not_adopted_or_overwritten(self):
        before = (self.folder / "run.json").read_bytes()
        with self.assertRaises(FileExistsError):
            b.prepare(self.folder, 59991)
        self.assertEqual(before, (self.folder / "run.json").read_bytes())

    def test_target_and_source_and_corpus_mismatch_block_start(self):
        for key, value in (("sourceHash", "f" * 64), ("corpusHash", "e" * 64), ("dbName", "exchange_ai"), ("dbPort", 5433)):
            changed = dict(self.meta, **{key: value})
            (self.folder / "run.json").write_bytes(g._canonical(changed))
            with self.assertRaises(g.GuardError):
                self.run_broker([])
        (self.folder / "run.json").write_bytes(g._canonical(self.meta))

    def test_clean_broker_restart_keeps_counts_and_rejects_duplicate(self):
        request = self.frame()
        self.assertTrue(self.run_broker([request])[1]["ok"])
        result = self.run_broker([request, self.frame()])
        self.assertEqual(result[1]["code"], "REQUEST_ALREADY_RESERVED")
        self.assertTrue(result[2]["ok"])
        self.assertEqual(self.guard().summary()["llm_calls"], 2)

    def test_uncertain_sender_halts_new_process_attempt(self):
        with patch.object(b, "offline_sender", return_value=lambda *_: (_ for _ in ()).throw(TimeoutError("SECRET"))):
            self.assertEqual(self.run_broker([self.frame()])[1]["code"], "TRANSPORT_UNCERTAIN")
        self.assertEqual(self.run_broker([self.frame()])[1]["code"], "RUN_HALTED")

    def test_stop_persists_across_restart(self):
        result = self.run_broker([self.frame(), {"stop": True}, self.frame()])
        self.assertTrue(result[2]["stopped"])
        self.assertEqual(len(result), 3)
        self.assertEqual(self.run_broker([self.frame()])[1]["code"], "RUN_STOPPED")

    def test_unknown_endpoint_and_bad_frame_cannot_dispatch(self):
        result = self.run_broker([self.frame("https://api.openai.com/v1/responses"), {"unexpected": "secret"}])
        self.assertEqual(result[1]["code"], "ENDPOINT_NOT_ALLOWED")
        self.assertEqual(result[2]["code"], "FRAME_INVALID")
        self.assertEqual(self.guard().summary()["llm_calls"], 0)

    def test_call_limit_through_real_protocol(self):
        result = self.run_broker([self.frame() for _ in range(33)])
        self.assertEqual(sum(r.get("ok", False) for r in result), 32)
        self.assertEqual(result[-1]["code"], "RUN_LIMIT")

    def permit(self, **changes):
        key = Path(self.temp.name) / "fake-key"
        key.write_text("offline-sentinel-not-a-real-key", encoding="utf-8")
        permit = {"runId": self.meta["runId"], "sourceHash": self.meta["sourceHash"], "corpusHash": self.meta["corpusHash"],
                  "paidApproved": True, "capUsd": "2.00", "llmCalls": 32, "embeddingCalls": 80,
                  "expiresAt": int(time.time()) + 300, "keyFile": str(key)}
        permit.update(changes)
        path = Path(self.temp.name) / "fake-permit.json"
        path.write_bytes(g._canonical(permit))
        return path

    def test_bad_expired_or_wrong_identity_approval_blocks_before_network(self):
        for change in ({"paidApproved": False}, {"expiresAt": 0}, {"capUsd": "3.00"}, {"runId": "another"}):
            with patch.object(b.http.client, "HTTPSConnection") as network:
                with self.assertRaisesRegex(g.GuardError, "PAID_APPROVAL_REQUIRED"):
                    b.live_sender(self.folder, dict(self.meta, mode="live"), self.permit(**change))
                network.assert_not_called()

    def test_mocked_live_redirect_or_429_is_one_attempt_no_follow_no_retry(self):
        for status in (302, 429, 500):
            connection = MagicMock()
            connection.getresponse.return_value.status = status
            with patch.object(b.http.client, "HTTPSConnection", return_value=connection) as factory:
                sender = b.live_sender(self.folder, dict(self.meta, mode="live"), self.permit())
                with self.assertRaisesRegex(g.GuardError, "UPSTREAM_UNCERTAIN"):
                    sender("responses", llm(), 1)
                factory.assert_called_once_with("api.openai.com", 443, timeout=1)
                connection.request.assert_called_once()
                connection.close.assert_called_once()

    def test_non_finite_upstream_usage_does_not_enable_more_requests(self):
        with patch.object(b, "offline_sender", return_value=lambda *_: {"usage": {"input_tokens": 1, "output_tokens": 999999}}):
            self.assertEqual(self.run_broker([self.frame()])[1]["code"], "USAGE_UNVERIFIABLE")
        self.assertEqual(self.run_broker([self.frame()])[1]["code"], "RUN_HALTED")

    def test_manifest_port_or_mode_edit_is_bound_to_ledger_before_key_read(self):
        for change in ({"dbPort": 59992}, {"mode": "live"}):
            (self.folder / "run.json").write_bytes(g._canonical(dict(self.meta, **change)))
            with patch.object(b, "live_sender") as load_key:
                with self.assertRaisesRegex(g.GuardError, "IDENTITY_MISMATCH"):
                    self.run_broker([], live=change.get("mode") == "live")
                load_key.assert_not_called()
        (self.folder / "run.json").write_bytes(g._canonical(self.meta))

    def test_mocked_upstream_different_tier_is_not_accepted(self):
        connection=MagicMock();response=connection.getresponse.return_value;response.status=200
        response.read1.side_effect=[b'{"service_tier":"priority","usage":{"input_tokens":1,"output_tokens":1}}',b'']
        with patch.object(b.http.client,"HTTPSConnection",return_value=connection):
            sender=b.live_sender(self.folder,dict(self.meta,mode="live"),self.permit())
            with self.assertRaisesRegex(g.GuardError,"UPSTREAM_TIER_UNVERIFIED"):
                sender("responses",llm(),1)
            connection.request.assert_called_once()


if __name__ == "__main__":
    unittest.main()
