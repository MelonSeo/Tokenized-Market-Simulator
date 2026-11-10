"""Fake loopback transport only. No HTTP listener or external API call."""
import json
import unittest
from ai_pilot_http_adapter import PilotAuthenticatedHttp


class Reply:
    def __init__(self, status, body): self.status = status;self.raw = json.dumps(body).encode()
    def read1(self, size): part, self.raw = self.raw[:size], self.raw[size:];return part


class Connection:
    def __init__(self, harness): self.h = harness;self.sock = None
    def request(self, method, path, body=None, headers=None):
        self.h.requests.append((method, path, body, headers))
        if self.h.fail: raise TimeoutError()
    def getresponse(self): return self.h.replies.pop(0)
    def close(self): self.h.closed += 1


class HttpAdapterTests(unittest.TestCase):
    def setUp(self):
        self.requests = [];self.closed = 0;self.replies = [];self.fail = False
        self.accounts = {a: {"loginId": "controlled-" + a, "password": "controlled-only"} for a in ("owner", "other", "admin")}
        self.adapter = PilotAuthenticatedHttp("http://127.0.0.1:54321", self.accounts, private_control=lambda f: {"corpus": "legacy9"},
            fingerprint=lambda: "a" * 64, review=lambda *a: True, budget_snapshot=lambda: [], connection_factory=lambda *a, **k: Connection(self))

    def test_jwt_is_only_a_loopback_header_not_agent_body(self):
        self.replies = [Reply(200, {"accessToken": "CONTROLLED_NOT_A_REAL_TOKEN"}), Reply(200, {"id": 1}), Reply(200, {"status": "PARTIAL"})]
        self.adapter.request("owner", "GET", "/api/me", None)
        self.adapter.request("owner", "POST", "/api/ai/agent/answers", {"question": "controlled"})
        self.assertEqual(3, len(self.requests))
        self.assertEqual({"question": "controlled"}, json.loads(self.requests[2][2]))
        self.assertEqual("Bearer CONTROLLED_NOT_A_REAL_TOKEN", self.requests[2][3]["Authorization"])
        self.assertEqual(3, self.closed)

    def test_timeout_is_one_attempt_no_reauthentication_no_resend(self):
        self.adapter.tokens["owner"] = "controlled";self.fail = True
        with self.assertRaisesRegex(ValueError, "V2_LOCAL_HTTP_UNCERTAIN_NO_REPLAY"):
            self.adapter.request("owner", "POST", "/api/ai/agent/answers", {"question": "controlled"})
        self.assertEqual(1, len(self.requests));self.assertEqual(1, self.closed)

    def test_401_clears_memory_and_does_not_replay(self):
        self.adapter.tokens["owner"] = "controlled";self.replies = [Reply(401, {})]
        with self.assertRaisesRegex(ValueError, "V2_LOCAL_AUTH_REJECTED_NO_REPLAY"):
            self.adapter.request("owner", "POST", "/api/ai/agent/answers", {"question": "controlled"})
        self.assertNotIn("owner", self.adapter.tokens);self.assertEqual(1, len(self.requests))

    def test_external_urls_financial_actions_and_api_keys_are_not_supported(self):
        with self.assertRaisesRegex(ValueError, "V2_LOOPBACK_ORIGIN_REQUIRED"):
            PilotAuthenticatedHttp("https://api.openai.com:443", self.accounts, private_control=None, fingerprint=None, review=None, budget_snapshot=None)
        self.adapter.tokens["owner"] = "controlled"
        for path in ("/api/orders/buy", "/api/c-futures/direct/intents", "https://example.com/api/me"):
            with self.assertRaisesRegex(ValueError, "V2_LOCAL_ENDPOINT_NOT_ALLOWED"):
                self.adapter.request("owner", "POST", path, {})
        self.assertEqual([], self.requests)


if __name__ == "__main__": unittest.main()
