"""Private parent protocol for the real six-DOM-request driver. Does not launch Chrome.

The environment supervisor may connect this to the child's stdin/stdout. Unit
tests use controlled frames; no browser acceptance is inferred from those tests.
"""
from ai_pilot_preflight import need
from ai_pilot_coordinator import digest


class PilotBrowserHost:
    def __init__(self, coordinator, review, budget_snapshot, fingerprint, sleep=None):
        import time
        self.c, self.review = coordinator, review
        self.sleep = sleep or time.sleep
        self.budget_snapshot = budget_snapshot;self.fingerprint = fingerprint
        self.before_fingerprint = fingerprint()
        self.rows = [r for r in coordinator.rows if r["phase"] == "WEB_PILOT"]
        self.slot = 0
        self.ticket = None

    def step(self, frame):
        try:
            if frame.get("op") == "stop":
                self.c.stop()
                return {"ok": True}
            need(self.slot < 6 and frame.get("slot") == self.slot, "V2_BROWSER_SEQUENCE")
            row = self.rows[self.slot]
            if frame["op"] == "before":
                need(self.fingerprint() == self.before_fingerprint, "V2_FINANCIAL_FINGERPRINT_CHANGED")
                need(self.ticket is None and frame.get("actor") == row["actor"] and digest(frame.get("body")) == row["bodySha256"], "V2_BROWSER_REQUEST_CHANGED")
                self.c.wait_for_capacity(row["uid"], self.sleep)
                self.ticket = self.c.claim("WEB_PILOT", self.slot)
            else:
                need(frame["op"] == "after" and self.ticket is not None, "V2_BROWSER_SEQUENCE")
                reply = frame["reply"]
                need(reply.get("http") == row["expectedHttp"], "V2_BROWSER_HTTP_FAILED")
                self.c.complete_request(self.ticket, reply["body"].get("runId"))
                need(self.review(row, reply["body"]) is True, "V2_EVIDENCE_REVIEW_REJECTED")
                self.slot += 1;self.ticket = None
                if self.slot == 6:
                    need(self.fingerprint() == self.before_fingerprint, "V2_FINANCIAL_FINGERPRINT_CHANGED")
                    need(self.c.reconcile(self.budget_snapshot())["matching"], "V2_BUDGET_RECONCILIATION_FAILED")
                    self.c.advance(evidence_reviewed=True)
            return {"ok": True}
        except BaseException:
            self.c.stop()
            raise
