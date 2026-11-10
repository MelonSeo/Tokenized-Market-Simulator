"""Loopback-only authenticated adapter for an owned UNPAID v2 server.

No provider URL, API key, Docker, deployment or financial write API. Requests are
single bounded attempts; 401/timeout never trigger login/replay automatically.
"""
import http.client
import json
import re
import time
from urllib.parse import urlsplit
from ai_pilot_preflight import need


class PilotAuthenticatedHttp:
    def __init__(self, origin, accounts, *, private_control, fingerprint, review, budget_snapshot, web_submit=None,
                 connection_factory=http.client.HTTPConnection, sleep=time.sleep):
        need(re.fullmatch(r"http://127\.0\.0\.1:[1-9][0-9]{3,4}", origin) is not None, "V2_LOOPBACK_ORIGIN_REQUIRED")
        need(set(accounts) == {"owner", "other", "admin"} and all(set(v) == {"loginId", "password"} for v in accounts.values()), "V2_TRIAL_CREDENTIALS_REQUIRED")
        self.origin = urlsplit(origin);self.accounts = accounts;self.tokens = {};self.connection_factory = connection_factory
        need(1 <= self.origin.port <= 65535, "V2_LOOPBACK_ORIGIN_REQUIRED")
        self.private_control = private_control;self.financial_fingerprint = fingerprint;self.review = review
        self.budget_snapshot = budget_snapshot
        self._web_submit = web_submit;self.sleep = sleep

    def _once(self, method, path, body, token=None):
        need(method in {"GET", "POST"} and (path in {"/api/auth/login", "/api/me", "/api/c-futures/wallet", "/api/c-futures/config", "/api/ai/agent/answers", "/api/ai/index", "/api/ai/provider-budget/initialize"}
             or re.fullmatch(r"/api/ai/tools/[A-Za-z]+", path) is not None), "V2_LOCAL_ENDPOINT_NOT_ALLOWED")
        connection = self.connection_factory("127.0.0.1", self.origin.port, timeout=110)
        deadline = time.monotonic() + 110
        def remaining():
            left = deadline - time.monotonic()
            if left <= 0: raise TimeoutError()
            if connection.sock: connection.sock.settimeout(left)
            return left
        headers = {"Content-Type": "application/json"}
        if token: headers["Authorization"] = "Bearer " + token
        try:
            connection.request(method, path, body=None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8"), headers=headers)
            remaining();response = connection.getresponse();chunks = bytearray()
            while True:
                remaining();chunk = response.read1(min(65536, 2_000_001 - len(chunks)))
                if not chunk: break
                chunks.extend(chunk)
                need(len(chunks) <= 2_000_000, "V2_LOCAL_RESPONSE_LIMIT")
            raw = bytes(chunks)
            need(len(raw) <= 2_000_000, "V2_LOCAL_RESPONSE_LIMIT")
            return response.status, json.loads(raw) if raw else {}
        except (OSError, ValueError, http.client.HTTPException):
            raise ValueError("V2_LOCAL_HTTP_UNCERTAIN_NO_REPLAY") from None
        finally:
            connection.close()

    def request(self, actor, method, path, body):
        need(actor in self.accounts, "V2_TRIAL_ACTOR_REQUIRED")
        if actor not in self.tokens:
            status, login = self._once("POST", "/api/auth/login", self.accounts[actor])
            need(status == 200 and type(login.get("accessToken")) is str and login["accessToken"], "V2_LOCAL_AUTH_REJECTED")
            self.tokens[actor] = login["accessToken"]
        status, result = self._once(method, path, body, self.tokens[actor])
        if status == 401:
            self.tokens.pop(actor, None)
            raise ValueError("V2_LOCAL_AUTH_REJECTED_NO_REPLAY")
        return status, result

    def initialize_unpaid_budget(self):
        return self.request("admin", "POST", "/api/ai/provider-budget/initialize", {})

    def index(self, corpus):
        need(corpus in {"legacy9", "pilot10"}, "V2_CORPUS_MISMATCH")
        active = self.private_control({"op": "active"})
        need(active["corpus"] == corpus, "V2_CORPUS_MISMATCH")
        return self.request("admin", "POST", "/api/ai/index", {})

    def index_server_request(self):
        return self.private_control({"op": "index-request"})["serverRequest"]

    def control(self, value):
        # D1/B5 DTO controls are derived privately from frozen case + server run binding, never HTTP commands.
        need(value in {"NONE", "CONTROLLED_LINK_DTO", "CONTROLLED_RECEIPT_DTO", "PENDING_ROW_RPC_NOT_FOUND"}, "V2_DTO_CONTROL_INVALID")

    def web_submit(self, actor, body):
        need(self._web_submit is not None, "V2_BROWSER_NOT_RUN")
        return self._web_submit(actor, body)
