"""Adapter-driven HTTP schedule, no HTTP client and no browser response interception.

The environment adapter supplies authenticated read/request methods, DTO-control
hooks and a financial fingerprint. No expected answer is added to Agent input.
Web submissions are delegated to an actual DOM driver, not replaced with API calls.
"""
from ai_pilot_coordinator import workload
from ai_pilot_preflight import need


class PilotHttpPlan:
    def __init__(self, coordinator, binding):
        self.c = coordinator
        self.binding = binding
        self.rows = workload(binding)

    def run_requests(self, phase, adapter):
        need(phase not in {"INDEX_LEGACY", "INDEX_PILOT", "EMBEDDING_HEADROOM"}, "V2_REQUEST_PHASE_REQUIRED")
        output = []
        fingerprint = adapter.financial_fingerprint()
        need(fingerprint == self.binding["financialFingerprint"], "V2_FINANCIAL_FINGERPRINT_CHANGED")
        try:
            for row in (r for r in self.rows if r["phase"] == phase):
                pacing_seconds = self.c.wait_for_capacity(row["uid"], adapter.sleep)
                prepare_review=getattr(adapter,'prepare_review',None)
                if callable(prepare_review):prepare_review(row) # Criteria/readiness BEFORE claim and provider work.
                ticket = self.c.claim(phase, row["slot"])
                # Private adapter, not a public endpoint/model command. Existing D1/B5 controls remain labelled DTOs.
                adapter.control(row["control"])
                try:
                    if phase == "WEB_PILOT" or row.get('web') is True:
                        status, reply = adapter.web_submit(row["actor"], row["body"])
                    else:
                        status, reply = adapter.request(row["actor"], "POST", "/api/ai/agent/answers", row["body"])
                finally:
                    adapter.control("NONE")
                need(status == row["expectedHttp"], "V2_HTTP_EXPECTATION_FAILED")
                # For real HTTP, the private server observer has already bound/exited this exact run.
                # A timeout cannot be used to release admission while an old worker is still alive.
                self.c.complete_request(ticket, reply.get("runId"))
                output.append({"phase": phase, "case": row["case"], "actor": row["actor"],
                               "http": status, "reply": reply, "control": row["control"],
                               "evaluationPacingSecondsNotHttpLatency": pacing_seconds,
                               "quality": "REQUIRES_EVIDENCE_REVIEW_NOT_HTTP_200_PASS"})
                accepted=adapter.review(row,reply)
                if accepted is not True:
                    continuation=getattr(adapter,'review_failure_can_continue',None)
                    need(callable(continuation) and continuation(row,reply) is True,'V2_EVIDENCE_REVIEW_REJECTED')
                    output[-1]['quality']='FAIL_REVIEWED_NOT_ACCEPTED'
                else:output[-1]['quality']='REVIEW_ACCEPTED_NOT_HTTP200_ONLY'
            need(adapter.financial_fingerprint() == fingerprint, "V2_FINANCIAL_FINGERPRINT_CHANGED")
            need(self.c.reconcile(adapter.budget_snapshot())["matching"], "V2_BUDGET_RECONCILIATION_FAILED")
        except BaseException:
            self.c.stop()
            raise
        self.c.advance(evidence_reviewed=True)
        return output

    def run_index(self, phase, adapter):
        need(phase in {"INDEX_LEGACY", "INDEX_PILOT"}, "V2_INDEX_PHASE_REQUIRED")
        corpus = "legacy9" if phase == "INDEX_LEGACY" else "pilot10"
        self.c.wait_for_capacity(self.c.admin, adapter.sleep)
        ticket = self.c.claim(phase)
        try:
            status, reply = adapter.index(corpus)
            need(status == 200 and reply.get("documents") == (9 if corpus == "legacy9" else 10)
                 and reply.get("chunks") == (88 if corpus == "legacy9" else 93)
                 and reply.get("unchanged") is False, "V2_FRESH_INDEX_REQUIRED")
            need(self.c.reconcile(adapter.budget_snapshot())["matching"], "V2_BUDGET_RECONCILIATION_FAILED")
            # Index is synchronous: the adapter binds the server's internal request and signals physical exit.
            self.c.complete_request(ticket, adapter.index_server_request())
            self.c.advance(evidence_reviewed=True)
        except BaseException:
            self.c.stop()
            raise
        return reply
