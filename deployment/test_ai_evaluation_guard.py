"""Offline only: every transport is an in-memory function, no socket/credentials."""
import concurrent.futures
from contextlib import closing
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest
import uuid
from unittest.mock import patch

import ai_evaluation_guard as g


def llm(**updates):
    value = {"model": "gpt-5.6-terra", "store": False, "instructions": "근거만 설명",
             "service_tier": "default",
             "input": "질문", "reasoning": {"effort": "none"}, "max_output_tokens": 400,
             "text": {"format": {"type": "json_schema", "name": "answer", "strict": True,
                                  "schema": {"type": "object"}}}}
    value.update(updates)
    return json.dumps(value, ensure_ascii=False).encode()


def embedding(**updates):
    value = {"model": "text-embedding-3-small", "input": ["문서"], "dimensions": 1536, "encoding_format": "float"}
    value.update(updates)
    return json.dumps(value, ensure_ascii=False).encode()


def fake(path, raw):
    return {"usage": {"input_tokens": 1, "output_tokens": 1}} if path == "responses" else {"usage": {"prompt_tokens": 1}}


class GuardTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "budget.sqlite"
        self.identity = dict(run_id="offline-run", source_hash="a" * 64, corpus_hash="b" * 64)
        self.guard = g.EvaluationGuard(self.path, **self.identity, create=True, execution_approved=True)
        self.sent = []

    def send(self, path="responses", raw=None, request_id=None, sender=None, guard=None):
        def record(p, r):
            self.sent.append((p, r))
            return fake(p, r)
        return (guard or self.guard).dispatch(path, raw if raw is not None else llm(), sender or record,
                                            request_id=request_id or str(uuid.uuid4()))

    def assert_blocked(self, code, **kwargs):
        before = len(self.sent)
        with self.assertRaisesRegex(g.GuardError, "^" + code + "$"):
            self.send(**kwargs)
        self.assertEqual(before, len(self.sent))

    def test_default_denies_even_when_environment_contains_live_flags(self):
        with patch.dict("os.environ", {"AI_LIVE_EVALUATION": "true", "OPENAI_API_KEY": "never-read"}):
            guard = g.EvaluationGuard(self.path, **self.identity)
            self.assert_blocked("EXECUTION_NOT_APPROVED", guard=guard)
        self.assertEqual(guard.summary()["reserved_nusd"], 0)

    def test_reserves_before_transport_and_passes_exact_frozen_bytes(self):
        raw = llm()
        def inspect(path, payload):
            self.assertIs(payload, raw)
            self.assertEqual(self.guard.summary()["llm_calls"], 1)
            self.assertGreater(self.guard.summary()["reserved_nusd"], 0)
            return fake(path, payload)
        self.send(raw=raw, sender=inspect)

    def test_full_serialized_bytes_include_schema_instructions_and_korean(self):
        raw = llm(instructions="가" * 100)
        self.send(raw=raw)
        self.assertEqual(self.guard.summary()["input_tokens"], len(raw) + 4096)

    def test_cost_uses_integer_reservation_not_reported_usage_or_refund(self):
        raw = llm()
        self.send(raw=raw)
        self.assertEqual(self.guard.summary()["reserved_nusd"], (len(raw) + 4096) * 2500 + 400 * 12000)

    def test_32_llm_then_33_rejected(self):
        for _ in range(32):
            self.send()
        self.assert_blocked("RUN_LIMIT")

    def test_80_embedding_then_81_rejected(self):
        for _ in range(80):
            self.send("embeddings", embedding())
        self.assert_blocked("RUN_LIMIT", path="embeddings", raw=embedding())

    def test_aggregate_output_limit(self):
        for _ in range(20):
            self.send(raw=llm(max_output_tokens=1600))
        self.assert_blocked("RUN_LIMIT", raw=llm(max_output_tokens=1))

    def test_aggregate_input_limit(self):
        raw = llm(input="a" * 27000)
        while self.guard.summary()["input_tokens"] + len(raw) + 4096 <= 256000:
            self.send(raw=raw)
        self.assert_blocked("RUN_LIMIT", raw=raw)

    def test_aggregate_embedding_limit_and_no_partial_batch(self):
        raw = embedding(input=["a" * 8000] * 12)
        self.send("embeddings", raw)
        self.assert_blocked("RUN_LIMIT", path="embeddings", raw=raw)
        self.assertEqual(self.guard.summary()["embedding_calls"], 1)

    def test_exact_cost_limit_boundary(self):
        cost = (len(llm()) + 4096) * 2500 + 400 * 12000
        # Smaller test cap only, never a production/approved budget change.
        with patch.dict(g.POLICY, cap_nusd=cost):
            path = Path(self.temp.name) / "small.sqlite"
            guard = g.EvaluationGuard(path, **self.identity, create=True, execution_approved=True)
            self.send(guard=guard)
            self.assert_blocked("COST_LIMIT", guard=guard)

    def test_timeout_no_retry_no_refund_and_no_original_error_leak(self):
        count = []
        request_id = str(uuid.uuid4())
        def fail(*_):
            count.append(1)
            raise TimeoutError("SECRET-TOKEN-do-not-print")
        self.assert_blocked("TRANSPORT_UNCERTAIN", sender=fail, request_id=request_id)
        self.assertEqual(count, [1])
        self.assertEqual(self.guard.summary()["llm_calls"], 1)
        self.assert_blocked("REQUEST_ALREADY_RESERVED", request_id=request_id)

    def test_pending_reservation_survives_real_second_python_process(self):
        request_id = str(uuid.uuid4())
        self.assert_blocked("TRANSPORT_UNCERTAIN", request_id=request_id,
                            sender=lambda *_: (_ for _ in ()).throw(OSError()))
        code = """import sys, ai_evaluation_guard as g
x=g.EvaluationGuard(sys.argv[1],run_id='offline-run',source_hash='a'*64,corpus_hash='b'*64)
assert x.summary()['llm_calls']==1
assert x.summary()['reserved_nusd']>0
print('DURABLE_PASS')
"""
        child = subprocess.run([sys.executable, "-B", "-S", "-c", code, str(self.path)],
                               cwd=Path(__file__).parent, capture_output=True, text=True, timeout=15)
        self.assertEqual(child.returncode, 0, child.stderr)
        self.assertEqual(child.stdout.strip(), "DURABLE_PASS")
        self.assert_blocked("REQUEST_ALREADY_RESERVED", request_id=request_id,
                            guard=g.EvaluationGuard(self.path, **self.identity, execution_approved=True))

    def test_concurrent_final_call_is_atomic_across_guard_instances(self):
        for _ in range(31):
            self.send()
        def compete(_):
            guard = g.EvaluationGuard(self.path, **self.identity, execution_approved=True)
            try:
                guard.dispatch("responses", llm(), fake, request_id=str(uuid.uuid4()))
                return "PASS"
            except g.GuardError as e:
                return str(e)
        with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
            results = list(pool.map(compete, range(6)))
        self.assertEqual(results.count("PASS"), 1)
        self.assertEqual(sum(r in ("RUN_LIMIT", "RUN_HALTED") for r in results), 5)

    def test_duplicate_request_id_race_dispatches_once(self):
        request_id = str(uuid.uuid4())
        def compete(_):
            try:
                self.send(request_id=request_id)
                return True
            except g.GuardError as e:
                self.assertEqual(str(e), "REQUEST_ALREADY_RESERVED")
                return False
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            self.assertEqual(sum(pool.map(compete, range(4))), 1)
        self.assertEqual(len(self.sent), 1)

    def test_identity_change_and_accidental_recreate_blocked(self):
        for field, value in (("source_hash", "c" * 64), ("corpus_hash", "d" * 64), ("run_id", "new-run")):
            args = dict(self.identity, **{field: value})
            with self.assertRaisesRegex(g.GuardError, "IDENTITY_MISMATCH"):
                g.EvaluationGuard(self.path, **args)
        with self.assertRaisesRegex(g.GuardError, "LEDGER_UNAVAILABLE"):
            g.EvaluationGuard(self.path, **self.identity, create=True)

    def test_policy_change_blocked(self):
        with patch.dict(g.POLICY, input_nusd=2501):
            with self.assertRaisesRegex(g.GuardError, "IDENTITY_MISMATCH"):
                g.EvaluationGuard(self.path, **self.identity)

    def test_missing_or_corrupt_ledger_fails_closed_without_transport(self):
        self.path.unlink()
        self.assert_blocked("LEDGER_UNAVAILABLE")
        self.assertFalse(self.path.exists())

    def test_usage_missing_negative_or_above_reservation_halts_future_calls(self):
        for index, response in enumerate(({}, {"usage": {"input_tokens": -1, "output_tokens": 1}},
                                          {"usage": {"input_tokens": 1, "output_tokens": 401}},
                                          {"usage": {"input_tokens": True, "output_tokens": 1}})):
            guard = g.EvaluationGuard(Path(self.temp.name) / f"bad{index}.sqlite", **self.identity,
                                      create=True, execution_approved=True)
            self.assert_blocked("USAGE_UNVERIFIABLE", guard=guard, sender=lambda *_, r=response: r)
            self.assert_blocked("RUN_HALTED", guard=guard)

    def test_unknown_model_endpoint_and_expensive_features_rejected(self):
        self.assert_blocked("ENDPOINT_NOT_ALLOWED", path="https://evil.invalid/responses")
        for change in ({"model": "other"}, {"tools": []}, {"background": True}, {"service_tier": "priority"},
                       {"previous_response_id": "secret"}, {"input": [{"type": "image"}]},
                       {"reasoning": {"effort": "high"}}, {"store": True}):
            self.assert_blocked("UNSUPPORTED_REQUEST", raw=llm(**change))

    def test_invalid_json_duplicate_fields_invalid_utf8_are_not_logged(self):
        for raw in (b'{"model":1,"model":2}', b'{"input":NaN}', b'\xff', b'[]', b'SECRET-TOKEN',
                    embedding(input=["x"]).replace(b'"x"', b'"\\ud800"')):
            self.assert_blocked("INVALID_REQUEST", raw=raw)

    def test_individual_output_and_input_bounds(self):
        for value in (0, -1, 1601, True, 1.5):
            self.assert_blocked("OUTPUT_LIMIT", raw=llm(max_output_tokens=value))
        self.assert_blocked("INPUT_LIMIT", raw=llm(input="가" * 11000))
        self.assert_blocked("INPUT_LIMIT", path="embeddings", raw=embedding(input=["a" * 8192]))
        self.assert_blocked("UNSUPPORTED_REQUEST", path="embeddings", raw=embedding(input=["x"] * 17))

    def test_embedding_batch_counts_one_http_not_sixteen(self):
        self.send("embeddings", embedding(input=["문서"] * 16))
        self.assertEqual(self.guard.summary()["embedding_calls"], 1)
        self.assertEqual(self.guard.summary()["embedding_tokens"], 16 * (6 + 8))

    def test_service_tier_is_explicit_standard_not_project_auto(self):
        for tier in ("auto", "priority", "fast", "flex", None):
            self.assert_blocked("UNSUPPORTED_REQUEST", raw=llm(service_tier=tier))
        body=json.loads(llm());del body["service_tier"]
        self.assert_blocked("UNSUPPORTED_REQUEST", raw=json.dumps(body).encode())

    def test_no_prompt_secret_response_body_saved(self):
        secret = "sensitive-secret-sentinel"
        self.send(raw=llm(input=secret))
        with closing(sqlite3.connect(self.path)) as db:
            dump = "\n".join(db.iterdump())
        self.assertNotIn(secret, dump)
        self.assertNotIn("Bearer", dump)

    def test_runtime_policy_reference_cannot_relax_existing_guard(self):
        for _ in range(32):
            self.send()
        with patch.dict(g.POLICY, llm_calls=1000, cap_nusd=999999999999):
            self.assert_blocked("RUN_LIMIT")

    def test_storage_failure_before_send_does_not_dispatch(self):
        with patch.object(self.guard, "_db", side_effect=sqlite3.OperationalError("SECRET-ERROR")):
            self.assert_blocked("LEDGER_UNAVAILABLE")
        self.assertEqual(self.guard.summary()["llm_calls"], 0)

    def test_storage_failure_after_send_retains_reservation_disables_instance(self):
        def break_storage(path, raw):
            self.guard._db = lambda: (_ for _ in ()).throw(sqlite3.OperationalError("SECRET-ERROR"))
            return fake(path, raw)
        self.assert_blocked("LEDGER_UNAVAILABLE", sender=break_storage)
        self.assert_blocked("EXECUTION_NOT_APPROVED")
        resumed = g.EvaluationGuard(self.path, **self.identity)
        self.assertEqual(resumed.summary()["llm_calls"], 1)
        approved_resume = g.EvaluationGuard(self.path, **self.identity, execution_approved=True)
        self.assert_blocked("RUN_HALTED", guard=approved_resume)

    def test_usage_input_above_bound_and_embedding_usage_missing_halt(self):
        self.assert_blocked("USAGE_UNVERIFIABLE", sender=lambda *_: {"usage": {"input_tokens": 999999, "output_tokens": 1}})
        self.assert_blocked("RUN_HALTED")
        guard = g.EvaluationGuard(Path(self.temp.name) / "embedding-usage.sqlite", **self.identity,
                                  create=True, execution_approved=True)
        self.assert_blocked("USAGE_UNVERIFIABLE", guard=guard, path="embeddings", raw=embedding(), sender=lambda *_: {})
        self.assert_blocked("RUN_HALTED", guard=guard, path="embeddings", raw=embedding())

    def test_observed_breach_with_storage_failure_halts_other_instances(self):
        def fail_recording(*_):
            self.guard._db = lambda: (_ for _ in ()).throw(sqlite3.OperationalError())
            return {"usage": {"input_tokens": 999999, "output_tokens": 999999}}
        self.assert_blocked("LEDGER_UNAVAILABLE", sender=fail_recording)
        resumed = g.EvaluationGuard(self.path, **self.identity, execution_approved=True)
        self.assert_blocked("RUN_HALTED", guard=resumed)

    def test_inflight_request_blocks_second_instance_before_response(self):
        def pending(path, raw):
            other = g.EvaluationGuard(self.path, **self.identity, execution_approved=True)
            self.assert_blocked("RUN_HALTED", guard=other)
            return fake(path, raw)
        self.send(sender=pending)
        self.send()  # A verified, durably recorded result permits the next call.
        self.assertEqual(self.guard.summary()["llm_calls"], 2)


if __name__ == "__main__":
    unittest.main()
