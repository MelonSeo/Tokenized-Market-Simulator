"""Controlled protocol/SQLite durability only; no real PG, HTTP, browser or provider."""
import copy
import hashlib
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
import uuid
from unittest.mock import Mock

from ai_pilot_coordinator import PilotCoordinator, workload, bound, digest
from ai_pilot_channel import handle, prepare_unpaid, load, controlled_supplier
from ai_pilot_fixture import bind_fixture
from ai_pilot_http_plan import PilotHttpPlan


def fixture():
    def actor(n):
        return {"uid": n, "quoteId": "0x" + str(n) * 64, "orderId": n,
                "positionId": f"00000000-0000-0000-0000-{n:012d}",
                "unverifiedPositionId": f"00000000-0000-0000-0001-{n:012d}",
                "pendingOrderId": n + 10, "cPositionId": "0x" + str(n + 3) * 64, "cWallet": "0x" + str(n) * 40}
    return {"bindingVersion": 2, "scope": "NEW_ISOLATED_PILOT_V2_NOT_USER_DATA", "mode": "MOCK_CONTROLLED",
            "financialFingerprint": "a" * 64, "actors": {"owner": actor(1), "other": actor(2), "admin": {"uid": 3}}}


def wire(kind="EMBEDDING", **changes):
    if kind == "EMBEDDING":
        body = {"model": "text-embedding-3-small", "input": ["controlled"], "dimensions": 1536, "encoding_format": "float"}
    else:
        body = {"model": "gpt-5.6-terra", "store": False, "service_tier": "default", "instructions": "controlled",
                "input": "controlled", "reasoning": {"effort": "none"}, "max_output_tokens": 1,
                "text": {"format": {"type": "json_schema", "strict": True, "name": "probe", "schema": {}}}}
    body.update(changes)
    return json.dumps(body, ensure_ascii=False).encode()


def controlled(path, raw):
    return {"usage": {"prompt_tokens": 1} if path == "embeddings" else {"input_tokens": 1, "output_tokens": 1}}


class PilotCoordinatorTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.binding = fixture()
        self.identity = dict(allocation=str(uuid.uuid4()), source_hash="a" * 64, fixture=self.binding)
        self.c = PilotCoordinator(self.root / "pilot-v2.sqlite", **self.identity, create=True)

    def index(self, c=None, phase="INDEX_LEGACY"):
        c = c or self.c
        corpus = "legacy9" if phase == "INDEX_LEGACY" else "pilot10"
        ticket = c.claim(phase)
        c.admit(3, {"indexCorpus": corpus}, corpus)
        run = str(uuid.uuid4())
        c.bind_worker(3, run, corpus)
        return ticket, run, corpus

    def dispatch(self, run, corpus="legacy9", actor=3, kind="EMBEDDING", sender=controlled, pg_id=None):
        return self.c.dispatch(path="embeddings" if kind == "EMBEDDING" else "responses", raw=wire(kind),
                               pg_id=pg_id or str(uuid.uuid4()), actor=actor, server_request=run, corpus=corpus, sender=sender)

    def finish(self, ticket, run, actor=3):
        self.c.worker_exited(actor, run)
        self.c.complete_request(ticket, run)
        self.c.advance(evidence_reviewed=True)

    def test_pg_first_receipt_and_exact_request_binding_are_required(self):
        ticket, run, corpus = self.index()
        called = Mock(side_effect=controlled)
        with self.assertRaisesRegex(ValueError, "V2_INVALID_IDENTIFIER"):
            self.dispatch(run, sender=called, pg_id="client-forged-id")
        with self.assertRaisesRegex(ValueError, "V2_SERVER_REQUEST_MISMATCH"):
            self.dispatch(str(uuid.uuid4()), sender=called)
        called.assert_not_called()
        self.dispatch(run, sender=called)
        self.assertEqual(1, called.call_count)

    def test_phase_limit_is_before_supplier_and_persists_across_reopen(self):
        ticket, run, _ = self.index()
        for _ in range(6):
            self.dispatch(run)
        self.c = PilotCoordinator(self.root / "pilot-v2.sqlite", **self.identity)
        called = Mock(side_effect=controlled)
        with self.assertRaisesRegex(ValueError, "V2_PHASE_LIMIT"):
            self.dispatch(run, sender=called)
        called.assert_not_called()
        self.assertEqual(6, self.c.summary()["embeddingCalls"])

    def test_llm_cannot_spend_index_or_pilot_allowance_early(self):
        ticket, run, _ = self.index()
        called = Mock(side_effect=controlled)
        with self.assertRaisesRegex(ValueError, "V2_PHASE_LIMIT"):
            self.dispatch(run, kind="LLM", sender=called)
        with self.assertRaisesRegex(ValueError, "V2_PHASE_ORDER"):
            self.c.claim("WEB_PILOT")
        called.assert_not_called()

    def test_global_limits_are_checked_even_if_local_counter_is_below_limit(self):
        _, run, _ = self.index()
        with self.c.db() as db:
            for _ in range(70):
                db.execute("INSERT INTO reservations VALUES(?,?,?,'EMBEDDING',1025,0,20500,'x','OBSERVED',1,0)", (str(uuid.uuid4()), "historic-in-this-test", "OTHER_PHASE"))
        called = Mock(side_effect=controlled)
        with self.assertRaisesRegex(ValueError, "V2_PRE_DISPATCH_LIMIT"):
            self.dispatch(run, sender=called)
        called.assert_not_called()

    def test_duplicate_pg_receipt_never_dispatches_twice(self):
        _, run, _ = self.index()
        pg = str(uuid.uuid4())
        self.dispatch(run, pg_id=pg)
        called = Mock(side_effect=controlled)
        with self.assertRaisesRegex(ValueError, "V2_DUPLICATE_RESERVATION"):
            self.dispatch(run, pg_id=pg, sender=called)
        called.assert_not_called()

    def test_timeout_and_missing_usage_preserve_full_reservation_and_stop_restart(self):
        for malformed in ("timeout", "missing-output", "negative", "oversized"):
            with self.subTest(malformed=malformed), tempfile.TemporaryDirectory() as d:
                path = Path(d) / "pilot-v2.sqlite"
                c = PilotCoordinator(path, **self.identity, create=True)
                ticket, run, _ = self.index(c)
                c.worker_exited(3, run)
                c.complete_request(ticket, run)
                c.advance(evidence_reviewed=True)
                c.claim("SAFETY_LEGACY", 0)
                row = next(r for r in c.rows if r["phase"] == "SAFETY_LEGACY" and r["slot"] == 0)
                c.admit(1, row["body"], "legacy9")
                run = str(uuid.uuid4())
                c.bind_worker(1, run, "legacy9")
                def fail(p, raw):
                    if malformed == "timeout":
                        raise TimeoutError()
                    usage = {"input_tokens": -1 if malformed == "negative" else 1000000 if malformed == "oversized" else 1}
                    if malformed != "missing-output":
                        usage["output_tokens"] = 1
                    return {"usage": usage}
                with self.assertRaisesRegex(ValueError, "V2_UNKNOWN_USAGE_HALTED"):
                    c.dispatch(path="responses", raw=wire("LLM"), pg_id=str(uuid.uuid4()), actor=1,
                               server_request=run, corpus="legacy9", sender=fail)
                self.assertEqual((1, 1, True), (c.summary()["llmCalls"], c.summary()["unknown"], c.summary()["stopped"]))
                with self.assertRaisesRegex(ValueError, "V2_STOPPED"):
                    PilotCoordinator(path, **self.identity)

    def test_embedding_unknown_stops_without_retry_or_refund(self):
        _, run, _ = self.index()
        with self.assertRaisesRegex(ValueError, "V2_UNKNOWN_USAGE_HALTED"):
            self.dispatch(run, sender=lambda *a: (_ for _ in ()).throw(TimeoutError()))
        summary = self.c.summary()
        self.assertEqual((1, 1, True), (summary["embeddingCalls"], summary["unknown"], summary["stopped"]))
        self.assertGreater(summary["reservedNusd"], 0)
        with self.assertRaisesRegex(ValueError, "V2_STOPPED"):
            PilotCoordinator(self.root / "pilot-v2.sqlite", **self.identity)

    def test_server_worker_binding_prevents_late_first_frame_and_timeout_reuse(self):
        ticket, run, _ = self.index()
        with self.assertRaisesRegex(ValueError, "V2_COMPLETION_MISMATCH"):
            self.c.complete_request(ticket, run)
        with self.assertRaisesRegex(ValueError, "V2_ACTOR_IN_FLIGHT"):
            self.c.claim("INDEX_LEGACY")
        self.c.worker_exited(3, run)
        with self.assertRaisesRegex(ValueError, "V2_COMPLETION_MISMATCH"):
            self.c.complete_request(ticket, str(uuid.uuid4()))
        self.c.complete_request(ticket, run)
        with self.assertRaisesRegex(ValueError, "V2_SERVER_REQUEST_MISMATCH"):
            self.dispatch(run)

    def test_shared_allocation_is_immutable_not_new_session_or_corpus_budget(self):
        _, run, _ = self.index()
        self.dispatch(run)
        reopened = PilotCoordinator(self.root / "pilot-v2.sqlite", **self.identity)
        self.assertEqual(self.c.summary(), reopened.summary())
        for change in ({"allocation": str(uuid.uuid4())}, {"source_hash": "b" * 64}, {"fixture": {**self.binding, "mode": "REAL_ISOLATED"}}):
            with self.subTest(change=change), self.assertRaisesRegex(ValueError, "V2_IDENTITY_MISMATCH"):
                PilotCoordinator(self.root / "pilot-v2.sqlite", **{**self.identity, **change})
        with self.assertRaises(FileExistsError):
            PilotCoordinator(self.root / "pilot-v2.sqlite", **self.identity, create=True)

    def test_pg_evaluation_mismatch_is_conservative_halt_not_retry_repair(self):
        _, run, _ = self.index()
        pg_id = str(uuid.uuid4())
        self.dispatch(run, pg_id=pg_id)
        kind, _, _, cost = bound("embeddings", wire(), self.c.policy)
        rows = [{"id": pg_id, "kind": kind, "reserved": cost, "state": "OBSERVED"}]
        self.assertTrue(self.c.reconcile(rows)["matching"])
        rows.append({"id": str(uuid.uuid4()), "kind": "EMBEDDING", "reserved": cost, "state": "UNKNOWN"})
        result = self.c.reconcile(rows)
        self.assertFalse(result["matching"])
        self.assertEqual(1, result["pgOnlyReservations"])
        self.assertFalse(result["automaticRefundOrReplay"])
        self.assertTrue(self.c.summary()["stopped"])

    def test_source_and_stop_file_are_checked_on_private_channel_restart(self):
        with tempfile.TemporaryDirectory() as d:
            prepare_unpaid(d, fixture())
            load(d)
            (Path(d) / "STOP").write_text("preserved")
            with self.assertRaisesRegex(ValueError, "V2_SESSION_LINK_OR_STOP"):
                load(d)

    def test_request_schedule_uses_frozen_questions_own_targets_and_no_oracle_prompt(self):
        rows = workload(self.binding)
        self.assertEqual(57, len(rows))
        pilot = [r for r in rows if r["phase"] == "WEB_PILOT"]
        self.assertEqual(["owner"] * 3 + ["other"] * 3, [r["actor"] for r in pilot])
        self.assertEqual(self.binding["actors"]["other"]["quoteId"], pilot[4]["body"]["target"]["quoteId"])
        self.assertTrue(all(set(r["body"]) <= {"question", "target", "skillId", "abnormal"} for r in rows))
        self.assertEqual("CONTROLLED_LINK_DTO", next(r for r in rows if r["case"] == "D1")["control"])

    def test_no_unknown_extra_wire_fields_and_no_larger_output_or_retention(self):
        for change in ({"tools": []}, {"prompt_cache_retention": "12h"}, {"service_tier": "priority"},
                       {"model": "other"}, {"max_output_tokens": 1601}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                bound("responses", wire("LLM", **change), self.c.policy)

    def test_rate_pacing_is_durable_and_not_charged_as_http_latency(self):
        class Clock:
            value = 1000000.0
            def __call__(self): return self.value
        clock = Clock();self.c.clock = clock
        with self.c.db() as db:
            for i in range(5): db.execute("INSERT INTO activity VALUES(?,?,?)", (str(uuid.uuid4()), 1, clock.value - i))
        def sleep(seconds): clock.value += seconds
        waited = self.c.wait_for_capacity(1, sleep)
        self.assertGreaterEqual(waited, 56.5)
        reopened = PilotCoordinator(self.root / "pilot-v2.sqlite", **self.identity, clock=clock)
        self.assertEqual(0, reopened.wait_for_capacity(1, sleep))
        self.assertEqual(0, self.c.summary()["llmCalls"])

    def test_matching_ids_with_unknown_pg_usage_still_halt(self):
        _, run, _ = self.index()
        pg_id = str(uuid.uuid4());self.dispatch(run, pg_id=pg_id)
        kind, _, _, cost = bound("embeddings", wire(), self.c.policy)
        result = self.c.reconcile([{"id": pg_id, "kind": kind, "reserved": cost, "state": "UNKNOWN"}])
        self.assertFalse(result["matching"]);self.assertTrue(self.c.summary()["stopped"])

    def test_controlled_plan_respects_target_but_never_claims_answer_quality(self):
        body = json.loads(wire("LLM"))
        body["text"]["format"]["name"] = "agent_plan"
        body["text"]["format"]["schema"] = {"properties": {"skillId": {}}}
        for target in ("NONE", "ORDER", "QUOTE", "FUTURES", "C_POSITION"):
            body["input"] = json.dumps({"question": "controlled-not-an-answer", "targetKind": target})
            reply = controlled_supplier("responses", json.dumps(body).encode(), 1)
            plan = json.loads(reply["output"][0]["content"][0]["text"])
            self.assertEqual(target, plan["subject"]);self.assertEqual("NONE", plan["skillId"])
        body["text"]["format"]["name"] = "agent_interpretation"
        reply = controlled_supplier("responses", json.dumps(body).encode(), 1)
        self.assertEqual("INSUFFICIENT_EVIDENCE", json.loads(reply["output"][0]["content"][0]["text"])["status"])


if __name__ == "__main__":
    unittest.main()
