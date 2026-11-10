"""Private host protocol with controlled adapter, NOT Chrome or user acceptance."""
import tempfile
import unittest
import uuid
from pathlib import Path
from ai_pilot_coordinator import PilotCoordinator
from ai_pilot_http_plan import PilotHttpPlan
from ai_pilot_browser_host import PilotBrowserHost
from test_ai_pilot_http_plan import ControlledAdapter
from test_ai_pilot_fixture import MockFixtureApi
from ai_pilot_fixture import bind_fixture


class BrowserHostTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        api = MockFixtureApi();self.binding = bind_fixture(api.recipe, api.request)
        class Clock:
            value = 1000000.0
            def __call__(self): return self.value
        self.c = PilotCoordinator(Path(self.temp.name) / "pilot-v2.sqlite", allocation=str(uuid.uuid4()), source_hash="a" * 64,
                                  fixture=self.binding, create=True, clock=Clock())
        self.adapter = ControlledAdapter(self.c, self.binding)
        plan = PilotHttpPlan(self.c, self.binding)
        for p in self.c.plan["phases"]:
            if p["id"] == "WEB_PILOT": break
            if p["id"].startswith("INDEX_"): plan.run_index(p["id"], self.adapter)
            else: plan.run_requests(p["id"], self.adapter)
        self.host = PilotBrowserHost(self.c, self.adapter.review, self.adapter.budget_snapshot,
                                     self.adapter.financial_fingerprint, self.adapter.sleep)

    def test_six_real_driver_frames_have_exact_actor_body_and_worker_completion(self):
        for row in self.host.rows:
            self.assertTrue(self.host.step({"op": "before", "slot": row["slot"], "actor": row["actor"], "body": row["body"]})["ok"])
            http, body = self.adapter.request(row["actor"], "POST", "/api/ai/agent/answers", row["body"])
            self.assertTrue(self.host.step({"op": "after", "slot": row["slot"], "reply": {"http": http, "body": body}})["ok"])
        self.assertEqual(6, self.host.slot)
        with self.assertRaisesRegex(ValueError, "V2_BROWSER_SEQUENCE"):
            self.host.step({"op": "before", "slot": 6})

    def test_changed_question_or_actor_stops_instead_of_spending_pilot_allowance(self):
        row = self.host.rows[0]
        with self.assertRaisesRegex(ValueError, "V2_BROWSER_REQUEST_CHANGED"):
            self.host.step({"op": "before", "slot": 0, "actor": "other", "body": row["body"]})
        self.assertTrue(self.c.summary()["stopped"])


if __name__ == "__main__": unittest.main()
