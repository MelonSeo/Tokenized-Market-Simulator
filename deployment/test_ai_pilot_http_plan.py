"""In-process controlled server adapter, NOT browser or real HTTP acceptance."""
from pathlib import Path
import tempfile
import unittest
import uuid
from ai_pilot_coordinator import PilotCoordinator, bound
from ai_pilot_http_plan import PilotHttpPlan
from test_ai_pilot_fixture import MockFixtureApi
from ai_pilot_fixture import bind_fixture
from test_ai_pilot_coordinator import wire, controlled


class ControlledAdapter:
    def __init__(self, c, binding):
        self.c, self.binding = c, binding
        self.web = [];self.requests = [];self.pg = [];self.controls = []
        self.review_allowed = True
        self.uid = binding["actors"]["admin"]["uid"]
        self.current_index_run = None

    def financial_fingerprint(self): return self.binding["financialFingerprint"]
    def sleep(self, seconds): self.c.clock.value += seconds
    def control(self, value): self.controls.append(value)
    def review(self, row, reply): return self.review_allowed
    def budget_snapshot(self): return self.pg

    def dispatch(self, actor, run, corpus, kind):
        raw = wire(kind);name = "embeddings" if kind == "EMBEDDING" else "responses"
        pg_id = str(uuid.uuid4());k, _, _, cost = bound(name, raw, self.c.policy)
        # Mock of a PG COMMIT. Actual SQL/transaction semantics are a separate NOT_RUN gate.
        reservation = {"id": pg_id, "kind": k, "reserved": cost, "state": "UNKNOWN"}
        self.pg.append(reservation)
        result = self.c.dispatch(path=name, raw=raw, actor=actor, pg_id=pg_id, server_request=run,
                                 corpus=corpus, sender=controlled)
        reservation["state"] = "OBSERVED"
        return result

    def index(self, corpus):
        self.c.admit(self.uid, {"indexCorpus": corpus}, corpus)
        run = self.current_index_run = str(uuid.uuid4())
        self.c.bind_worker(self.uid, run, corpus)
        for _ in range(6): self.dispatch(self.uid, run, corpus, "EMBEDDING")
        self.c.worker_exited(self.uid, run)
        return 200, {"documents": 9 if corpus == "legacy9" else 10, "chunks": 88 if corpus == "legacy9" else 93, "unchanged": False}

    def index_server_request(self): return self.current_index_run

    def request(self, actor, method, path, body):
        self.requests.append((actor, method, path))
        uid = self.binding["actors"][actor]["uid"]
        with self.c.db() as db:
            phase = self.c.active(db)
            sub = db.execute("SELECT slot FROM submissions WHERE phase=? AND actor=? AND complete=0", (phase["id"], uid)).fetchone()
        row = next(r for r in self.c.rows if r["phase"] == phase["id"] and r["slot"] == sub[0])
        self.c.admit(uid, body, row["corpus"])
        run = str(uuid.uuid4());self.c.bind_worker(uid, run, row["corpus"])
        if row["expectedHttp"] < 400:
            self.dispatch(uid, run, row["corpus"], "EMBEDDING")
            forced = row["case"].startswith(("PA2", "PC4")) or row["case"] in {"PCa", "PCb", "PCc"}
            count = 1 if "skillId" in body or forced else 2
            if phase["id"] == "NOVEL30": count = 1 if "skillId" in body else 2
            for _ in range(count): self.dispatch(uid, run, row["corpus"], "LLM")
        self.c.worker_exited(uid, run)
        return row["expectedHttp"], {"runId": run, "status": "CONTROLLED_PROTOCOL_REPLY_NOT_QUALITY_RESULT"}

    def web_submit(self, actor, body):
        self.web.append((actor, body))
        # Deliberate Mock adapter; the real DOM script is tested separately, not claimed here.
        return self.request(actor, "POST", "/api/ai/agent/answers", body)


class HttpPlanTests(unittest.TestCase):
    def test_explicitly_reviewed_failure_is_never_quality_pass(self):
        self.plan.run_index('INDEX_LEGACY',self.adapter)
        self.adapter.review_allowed=False
        self.adapter.review_failure_can_continue=lambda row,reply:True
        rows=self.plan.run_requests('SAFETY_LEGACY',self.adapter)
        self.assertTrue(all(r['quality']=='FAIL_REVIEWED_NOT_ACCEPTED' for r in rows))
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        api = MockFixtureApi();self.binding = bind_fixture(api.recipe, api.request)
        class Clock:
            value = 1000000.0
            def __call__(self): return self.value
        self.c = PilotCoordinator(Path(self.temp.name) / "pilot-v2.sqlite", allocation=str(uuid.uuid4()),
                                  source_hash="a" * 64, fixture=self.binding, create=True, clock=Clock())
        self.plan = PilotHttpPlan(self.c, self.binding);self.adapter = ControlledAdapter(self.c, self.binding)

    def test_complete_frozen_schedule_has_two_corpora_one_ledger_and_six_explicit_web_requests(self):
        for phase in self.c.plan["phases"][:-1]:
            if phase["id"].startswith("INDEX_"): self.plan.run_index(phase["id"], self.adapter)
            else: self.plan.run_requests(phase["id"], self.adapter)
        self.assertEqual(57, len(self.adapter.requests))
        self.assertEqual(["owner"] * 3 + ["other"] * 3, [a for a, _ in self.adapter.web])
        self.assertTrue(all(p == "/api/ai/agent/answers" for _, _, p in self.adapter.requests))
        self.assertLessEqual(self.c.summary()["llmCalls"], 92)
        self.assertEqual(68, self.c.summary()["embeddingCalls"])
        self.assertTrue(self.c.reconcile(self.adapter.pg)["matching"])
        self.c.advance(evidence_reviewed=True)  # unused headroom, not a hidden extra experiment
        self.assertTrue(self.c.summary()["stopped"])
        self.assertNotIn("twoClause", str(self.adapter.requests))

    def test_failed_evidence_review_stops_without_automatic_retry(self):
        self.plan.run_index("INDEX_LEGACY", self.adapter)
        self.adapter.review_allowed = False
        with self.assertRaisesRegex(ValueError, "V2_EVIDENCE_REVIEW_REJECTED"):
            self.plan.run_requests("SAFETY_LEGACY", self.adapter)
        self.assertEqual(1, len(self.adapter.requests))
        self.assertTrue(self.c.summary()["stopped"])


if __name__ == "__main__": unittest.main()
