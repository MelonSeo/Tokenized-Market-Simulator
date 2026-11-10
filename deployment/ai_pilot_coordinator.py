"""V2 execution connection: durable phase quotas and one allocation, never a live CLI.

Only a private server channel may dispatch: OpenAiProvider first commits its
PgProviderBudget reservation, then sends that receipt and server principal here.
This module has no HTTP client, API key reader, Docker command or approval writer.
Tests inject controlled suppliers; paid activation remains a separate milestone.
"""
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import time
import uuid

from ai_pilot_preflight import ROOT, PLAN_PATH, POLICY_PATH, canonical, preflight, read_json, need


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def identifier(value):
    try:
        need(type(value) is str and str(uuid.UUID(value)) == value, "V2_INVALID_IDENTIFIER")
    except (ValueError, TypeError, AttributeError):
        raise ValueError("V2_INVALID_IDENTIFIER") from None
    return value


def request_body(case, actor):
    body = {"question": case["question"]}
    target = case["target"]
    fields = {"OWN_QUOTE": ("quoteId", "quoteId"), "OWN_ORDER": ("orderId", "orderId"),
              "OWN_POSITION": ("positionId", "positionId"),
              "FOREIGN_POSITION": ("positionId", "foreignPositionId"),
              "OWN_C_POSITION": ("cPositionId", "cPositionId")}
    if target != "NONE":
        key, field = fields[target]
        # These are the original frozen case's fixture bindings, not rewritten questions.
        if case["id"] == "F4":
            field = "pendingOrderId"
        if case["id"] == "C3":
            field = "unverifiedPositionId"
        body["target"] = {key: actor[field]}
    if case.get("skill"):
        body["skillId"] = case["skill"]
    if "abnormal" in case:
        body["abnormal"] = case["abnormal"]
    return body


def workload(bindings, root=ROOT):
    """An execution schedule, not an answer oracle passed to a provider."""
    preflight(root)  # Validates frozen input bytes, not paid readiness/authorization.
    plan = read_json(root / PLAN_PATH)
    novel = read_json(root / "backend/src/test/resources/ai/novel-30-v2.json")
    target = read_json(root / "backend/src/test/resources/ai/pilot-acceptance-v1.json")
    cases = {c["id"]: c for c in novel["cases"] + target["cases"]}
    users = bindings["actors"]
    need(set(users) == {"owner", "other", "admin"}, "V2_ACTORS_INVALID")
    need(all(type(a.get("uid")) is int and a["uid"] > 0 for a in users.values()), "V2_ACTORS_INVALID")
    need(len({a["uid"] for a in users.values()}) == 3, "V2_ACTORS_NOT_DISTINCT")
    rows = []
    for phase in plan["phases"]:
        for slot, case_id in enumerate(phase["cases"]):
            case = cases[case_id]
            who = "admin" if case["actor"] == "ADMIN" else "other" if case_id == "D5" else "owner"
            if phase["id"] == "WEB_PILOT":
                who = "owner" if slot < 3 else "other"
            actor = users[who]
            # Frozen ADMIN legacy diagnostics inspect the owner's case, as in the original harness.
            # This is explicit privileged subject binding, never an identity merge or C-wallet override.
            subject = users["owner"] if who == "admin" else actor
            # D5 must use a real foreign resource, never numeric-ID coincidence.
            subject = {**subject, "foreignPositionId": users["owner"]["positionId"]}
            body = request_body(case, subject)
            rows.append({"phase": phase["id"], "corpus": phase["corpus"], "slot": slot,
                         "case": case_id, "actor": who, "uid": actor["uid"], "body": body,
                         "bodySha256": digest(body), "expectedHttp": case.get("expectedHttp", 200),
                         "control": {"D1": "CONTROLLED_LINK_DTO", "B5": "CONTROLLED_RECEIPT_DTO",
                                     "F4": "PENDING_ROW_RPC_NOT_FOUND"}.get(case_id, "NONE")})
    need(len(rows) == 57, "V2_SCHEDULE_INVALID")
    return rows


def bound(path, raw, policy):
    """Same byte/framing reservation as PG, plus a strict text-only request schema."""
    need(isinstance(raw, bytes) and 0 < len(raw) <= policy["wireBytesPerCall"], "V2_WIRE_LIMIT")
    def unique(pairs):
        out = {}
        for k, v in pairs:
            need(k not in out, "V2_DUPLICATE_JSON_KEY")
            out[k] = v
        return out
    try:
        n = json.loads(raw.decode("utf-8"), object_pairs_hook=unique,
                       parse_constant=lambda _: (_ for _ in ()).throw(ValueError("V2_INVALID_WIRE")))
        canonical(n)
    except (ValueError, UnicodeError):
        raise ValueError("V2_INVALID_WIRE") from None
    tokens = len(raw) + policy["framingTokensPerCall"]
    output = 0
    if path == "responses":
        need(isinstance(n, dict) and set(n) == {"model", "store", "instructions", "input", "reasoning", "max_output_tokens", "text", "service_tier"}, "V2_REQUEST_SCHEMA")
        need(n["model"] == policy["llmModel"] and n["service_tier"] == "default" and n["store"] is False
             and n["reasoning"] == {"effort": "none"} and type(n["input"]) is str
             and type(n["instructions"]) is str, "V2_REQUEST_SCHEMA")
        output = n["max_output_tokens"]
        need(type(output) is int and 0 < output <= 1600, "V2_OUTPUT_LIMIT")
        need(isinstance(n["text"], dict) and set(n["text"]) == {"format"}, "V2_REQUEST_SCHEMA")
        f = n["text"]["format"]
        need(isinstance(f, dict) and set(f) == {"type", "name", "strict", "schema"}
             and f["type"] == "json_schema" and f["strict"] is True and type(f["name"]) is str
             and isinstance(f["schema"], dict), "V2_REQUEST_SCHEMA")
        kind = "LLM"
    elif path == "embeddings":
        need(isinstance(n, dict) and set(n) == {"model", "input", "dimensions", "encoding_format"}
             and n["model"] == policy["embeddingModel"] and type(n["dimensions"]) is int and n["dimensions"] == 1536
             and n["encoding_format"] == "float" and isinstance(n["input"], list) and 1 <= len(n["input"]) <= 16, "V2_REQUEST_SCHEMA")
        need(all(type(t) is str and t.strip() and len(t.encode("utf-8")) + 8 <= 8191 for t in n["input"]), "V2_EMBEDDING_LIMIT")
        kind = "EMBEDDING"
    else:
        raise ValueError("V2_ENDPOINT_NOT_ALLOWED")
    cost = tokens * policy["inputNusdPerToken" if kind == "LLM" else "embeddingNusdPerToken"] + output * policy["outputNusdPerToken"]
    return kind, tokens, output, cost


class PilotCoordinator:
    """One explicit allocation path across sessions/corpora; no ledger reset/refund API.

    Allocation mode is immutable and cannot upgrade an UNPAID ledger. The private
    channel verifies explicit permits for a NEW PAID_V2 allocation; this class
    never issues an approval. No existing evaluation ledger is
    opened; caller supplies a NEW pilot-v2.sqlite in its isolated evidence folder.
    """
    def __init__(self, path, *, allocation, source_hash, fixture, create=False, root=ROOT, clock=time.time, mode='UNPAID'):
        preflight(root)
        identifier(allocation)
        need(mode in {'UNPAID','PAID_V2'},'V2_ALLOCATION_MODE');self.mode=mode
        need(type(source_hash) is str and len(source_hash) == 64 and all(c in "0123456789abcdef" for c in source_hash), "V2_SOURCE_IDENTITY")
        self.path = Path(path).absolute()
        self.clock = clock
        need(self.path.name == "pilot-v2.sqlite" and not self.path.is_symlink(), "V2_LEDGER_PATH")
        self.policy = read_json(root / POLICY_PATH)
        self.plan = read_json(root / PLAN_PATH)
        self.rows = workload(fixture, root)
        self.admin = fixture["actors"]["admin"]["uid"]
        self.identity = canonical({"version": 2, "allocation": allocation, "mode": mode,
                                   "source": source_hash, "fixture": digest(fixture),
                                   "budget": digest(self.policy), "plan": digest(self.plan)}).decode()
        if create:
            descriptor = os.open(self.path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            os.close(descriptor)
            with self.db() as c:
                c.executescript("""
                CREATE TABLE identity(id INTEGER PRIMARY KEY CHECK(id=1), value TEXT NOT NULL);
                CREATE TABLE control(id INTEGER PRIMARY KEY CHECK(id=1), phase INTEGER NOT NULL, stopped INTEGER NOT NULL);
                CREATE TABLE phases(name TEXT PRIMARY KEY, complete INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE submissions(ticket TEXT PRIMARY KEY, phase TEXT NOT NULL, slot INTEGER NOT NULL, actor INTEGER NOT NULL, body_hash TEXT NOT NULL, admitted INTEGER NOT NULL DEFAULT 0, complete INTEGER NOT NULL DEFAULT 0, server_request TEXT UNIQUE, worker_exited INTEGER NOT NULL DEFAULT 0, UNIQUE(phase,slot));
                CREATE TABLE reservations(pg_id TEXT PRIMARY KEY, ticket TEXT NOT NULL, phase TEXT NOT NULL, kind TEXT NOT NULL, input INTEGER NOT NULL, output INTEGER NOT NULL, cost INTEGER NOT NULL, body_hash TEXT NOT NULL, outcome TEXT NOT NULL, actual_input INTEGER, actual_output INTEGER);
                CREATE TABLE activity(ticket TEXT PRIMARY KEY, actor INTEGER NOT NULL, at REAL NOT NULL);
                """)
                c.execute("INSERT INTO identity VALUES(1,?)", (self.identity,))
                c.execute("INSERT INTO control VALUES(1,0,0)")
                c.executemany("INSERT INTO phases(name) VALUES(?)", [(p["id"],) for p in self.plan["phases"]])
        with self.db() as c:
            self.verify(c)

    @contextmanager
    def db(self):
        need(not self.path.is_symlink(), "V2_LEDGER_PATH")
        c = sqlite3.connect(self.path.as_uri() + "?mode=rw", uri=True, timeout=5)
        try:
            c.execute("PRAGMA synchronous=FULL")
            with c:
                yield c
        finally:
            c.close()

    def verify(self, c):
        need(c.execute("SELECT value FROM identity WHERE id=1").fetchall() == [(self.identity,)], "V2_IDENTITY_MISMATCH")
        need(not (self.path.parent / "STOP").exists(), "V2_STOPPED")
        need(c.execute("SELECT stopped FROM control WHERE id=1").fetchone() == (0,), "V2_STOPPED")

    def active(self, c):
        return self.plan["phases"][c.execute("SELECT phase FROM control WHERE id=1").fetchone()[0]]

    def pending_sql(self):
        return "complete=0"

    def assert_no_unresolved(self, c, exclude=None):
        need(c.execute("SELECT 1 FROM reservations WHERE outcome!='OBSERVED'"+
             (" AND pg_id<>?" if exclude is not None else ""), (exclude,) if exclude is not None else ()).fetchone() is None,
             "V2_UNKNOWN_USAGE_HALTED")

    def phase_progress(self, c, phase):
        return c.execute("SELECT count(*),coalesce(sum(complete),0) FROM submissions WHERE phase=?",(phase,)).fetchone()

    def claim(self, phase, slot=0):
        with self.db() as c:
            c.execute("BEGIN IMMEDIATE")
            self.verify(c)
            p = self.active(c)
            need(p["id"] == phase, "V2_PHASE_ORDER")
            if p["requests"] == 0:
                need(phase in {"INDEX_LEGACY", "INDEX_PILOT"} and slot == 0, "V2_INDEX_TICKET")
                actor, body_hash = self.admin, digest({"indexCorpus": p["corpus"]})
            else:
                need(type(slot) is int and 0 <= slot < p["requests"], "V2_SUBMISSION_LIMIT")
                row = next(r for r in self.rows if r["phase"] == phase and r["slot"] == slot)
                actor, body_hash = row["uid"], row["bodySha256"]
            need(c.execute("SELECT 1 FROM submissions WHERE phase=? AND actor=? AND "+self.pending_sql(), (phase, actor)).fetchone() is None, "V2_ACTOR_IN_FLIGHT")
            ticket = str(uuid.uuid4())
            try:
                c.execute("INSERT INTO submissions(ticket,phase,slot,actor,body_hash) VALUES(?,?,?,?,?)", (ticket, phase, slot, actor, body_hash))
            except sqlite3.IntegrityError:
                raise ValueError("V2_SUBMISSION_ALREADY_CLAIMED") from None
            return ticket

    def admit(self, actor, body, corpus):
        """Server-side authenticated principal + exact HTTP body, not a client phase header."""
        need(type(actor) is int and isinstance(body, dict), "V2_ADMISSION_INVALID")
        with self.db() as c:
            c.execute("BEGIN IMMEDIATE")
            self.verify(c)
            p = self.active(c)
            need(p["corpus"] == corpus, "V2_CORPUS_MISMATCH")
            rows = c.execute("SELECT ticket,body_hash,admitted FROM submissions WHERE phase=? AND actor=? AND "+self.pending_sql(), (p["id"], actor)).fetchall()
            need(len(rows) == 1 and rows[0][1] == digest(body) and rows[0][2] == 0, "V2_HTTP_NOT_EXPECTED")
            c.execute("UPDATE submissions SET admitted=1 WHERE ticket=?", (rows[0][0],))
            return rows[0][0]

    def bind_worker(self, actor, server_request, corpus):
        """Private server observation hook BEFORE Agent.run executes, not first-model-call binding."""
        identifier(server_request)
        with self.db() as c:
            c.execute("BEGIN IMMEDIATE")
            self.verify(c)
            p = self.active(c)
            need(corpus == p["corpus"], "V2_CORPUS_MISMATCH")
            sub = c.execute("SELECT ticket,server_request FROM submissions WHERE phase=? AND actor=? AND admitted=1 AND "+self.pending_sql(), (p["id"], actor)).fetchall()
            need(len(sub) == 1 and sub[0][1] is None, "V2_SERVER_REQUEST_MISMATCH")
            try:
                c.execute("UPDATE submissions SET server_request=? WHERE ticket=?", (server_request, sub[0][0]))
            except sqlite3.IntegrityError:
                raise ValueError("V2_SERVER_REQUEST_MISMATCH") from None

    def worker_exited(self, actor, server_request):
        identifier(server_request)
        with self.db() as c:
            self.verify(c)
            need(c.execute("UPDATE submissions SET worker_exited=1 WHERE actor=? AND server_request=? AND admitted=1 AND "+self.pending_sql(), (actor, server_request)).rowcount == 1, "V2_SERVER_REQUEST_MISMATCH")

    @staticmethod
    def totals(c, phase=None):
        query = "SELECT count(CASE WHEN kind='LLM' THEN 1 END), count(CASE WHEN kind='EMBEDDING' THEN 1 END), coalesce(sum(CASE WHEN kind='LLM' THEN input ELSE 0 END),0), coalesce(sum(output),0), coalesce(sum(CASE WHEN kind='EMBEDDING' THEN input ELSE 0 END),0), coalesce(sum(cost),0) FROM reservations"
        values = c.execute(query + (" WHERE phase=?" if phase else ""), (phase,) if phase else ()).fetchone()
        return dict(zip(("llmCalls", "embeddingCalls", "llmInputTokens", "llmOutputTokens", "embeddingInputTokens", "reservedNusd"), values))

    def dispatch(self, *, path, raw, pg_id, actor, server_request, corpus, sender):
        """Invoked only after the server PG COMMIT; never retries/refunds either guard."""
        identifier(pg_id)
        identifier(server_request)
        need(type(actor) is int and actor > 0, "V2_ACTOR_INVALID")
        kind, tokens, output, cost = bound(path, raw, self.policy)
        with self.db() as c:
            c.execute("BEGIN IMMEDIATE")
            self.verify(c)
            p = self.active(c)
            need(p["corpus"] == corpus, "V2_CORPUS_MISMATCH")
            self.assert_no_unresolved(c)
            sub = c.execute("SELECT ticket,server_request FROM submissions WHERE phase=? AND actor=? AND admitted=1 AND "+self.pending_sql(), (p["id"], actor)).fetchall()
            need(len(sub) == 1 and sub[0][1] == server_request, "V2_SERVER_REQUEST_MISMATCH")
            ticket = sub[0][0]
            total, local = self.totals(c), self.totals(c, p["id"])
            self.additional_limit(c, kind, tokens, output, cost)
            need(local["llmCalls" if kind == "LLM" else "embeddingCalls"] < p["llm" if kind == "LLM" else "embedding"], "V2_PHASE_LIMIT")
            from ai_pilot_preflight import dispatch_preview
            dispatch_preview(self.policy, total, kind, len(raw), output)
            try:
                c.execute("INSERT INTO reservations VALUES(?,?,?,?,?,?,?,?,'UNKNOWN',NULL,NULL)", (pg_id, ticket, p["id"], kind, tokens, output, cost, hashlib.sha256(raw).hexdigest()))
                c.execute("INSERT OR IGNORE INTO activity VALUES(?,?,?)", (ticket, actor, self.clock()))
            except sqlite3.IntegrityError:
                raise ValueError("V2_DUPLICATE_RESERVATION") from None
        # FULL synchronous COMMIT before supplier callback. A crash here remains UNKNOWN.
        try:
            result = sender(path, raw)
            usage = result["usage"]
            actual_input = usage["input_tokens" if kind == "LLM" else "prompt_tokens"]
            actual_output = usage["output_tokens"] if kind == "LLM" else 0
            need(type(actual_input) is int and 0 <= actual_input <= tokens and
                 type(actual_output) is int and 0 <= actual_output <= output, "V2_USAGE_UNVERIFIABLE")
        except Exception:
            with self.db() as c:
                c.execute("UPDATE control SET stopped=1 WHERE id=1")
            raise ValueError("V2_UNKNOWN_USAGE_HALTED") from None
        with self.db() as c:
            self.verify(c)
            c.execute("UPDATE reservations SET outcome='OBSERVED',actual_input=?,actual_output=? WHERE pg_id=?", (actual_input, actual_output, pg_id))
        return result

    def additional_limit(self, c, kind, tokens, output, cost):
        """Optional stricter, atomic sub-allocation; cannot replace the original guard."""
        return None

    def complete_request(self, ticket, server_request=None):
        identifier(ticket)
        with self.db() as c:
            c.execute("BEGIN IMMEDIATE")
            self.verify(c)
            row = c.execute("SELECT admitted,complete,server_request,worker_exited FROM submissions WHERE ticket=? AND phase=?", (ticket, self.active(c)["id"])).fetchone()
            need(row and row[0] == 1 and row[1] == 0 and row[2] == server_request and row[3] == 1, "V2_COMPLETION_MISMATCH")
            need(c.execute("SELECT 1 FROM reservations WHERE ticket=? AND outcome!='OBSERVED'", (ticket,)).fetchone() is None, "V2_UNKNOWN_USAGE_HALTED")
            c.execute("UPDATE submissions SET complete=1 WHERE ticket=?", (ticket,))

    def advance(self, *, evidence_reviewed):
        need(evidence_reviewed is True, "V2_REVIEW_REQUIRED")
        with self.db() as c:
            c.execute("BEGIN IMMEDIATE")
            self.verify(c)
            p = self.active(c)
            expected = p["requests"] or (1 if p["id"].startswith("INDEX_") else 0)
            count = self.phase_progress(c,p["id"])
            need(count == (expected, expected), "V2_PHASE_INCOMPLETE")
            c.execute("UPDATE phases SET complete=1 WHERE name=?", (p["id"],))
            index = c.execute("SELECT phase FROM control WHERE id=1").fetchone()[0]
            if index + 1 < len(self.plan["phases"]):
                c.execute("UPDATE control SET phase=phase+1 WHERE id=1")
            else:
                c.execute("UPDATE control SET stopped=1 WHERE id=1")

    def stop(self):
        with self.db() as c:
            need(c.execute("SELECT value FROM identity WHERE id=1").fetchall() == [(self.identity,)], "V2_IDENTITY_MISMATCH")
            c.execute("UPDATE control SET stopped=1 WHERE id=1")

    def wait_for_capacity(self, actor, sleep=time.sleep):
        """Evaluation pacing, separate from measured HTTP latency; never relaxes server rate checks."""
        start = self.clock()
        while True:
            with self.db() as c:
                self.verify(c)
                recent = c.execute("SELECT at FROM activity WHERE actor=? ORDER BY at DESC LIMIT 5", (actor,)).fetchall()
            delay = max(0, recent[-1][0] + 60.5 - self.clock()) if len(recent) == 5 else 0
            if delay <= 0:
                return max(0, self.clock() - start)
            need(self.clock() - start + delay <= 65, "V2_ACCOUNT_PACING_UNAVAILABLE")
            sleep(min(delay, 1))

    def summary(self):
        with self.db() as c:
            need(c.execute("SELECT value FROM identity WHERE id=1").fetchall() == [(self.identity,)], "V2_IDENTITY_MISMATCH")
            return {"mode": self.mode, **self.totals(c),
                    "stopped": bool(c.execute("SELECT stopped FROM control WHERE id=1").fetchone()[0]) or (self.path.parent / "STOP").exists(),
                    "unknown": c.execute("SELECT count(*) FROM reservations WHERE outcome!='OBSERVED'").fetchone()[0]}

    def reconcile(self, pg_rows):
        """Private READ-ONLY PG snapshot. Never 'repair' a mismatch by retry/refund/reset."""
        with self.db() as c:
            need(c.execute("SELECT value FROM identity WHERE id=1").fetchall() == [(self.identity,)], "V2_IDENTITY_MISMATCH")
            local = {row[0]: (row[1], row[2]) for row in c.execute("SELECT pg_id,kind,cost FROM reservations")}
            observed = {}
            pg_unknown = 0
            for row in pg_rows:
                identifier(row["id"])
                need(row["id"] not in observed and row["kind"] in {"LLM", "EMBEDDING"}
                     and type(row["reserved"]) is int and row["reserved"] > 0
                     and row.get("state") in {"UNKNOWN", "OBSERVED"}, "V2_PG_SNAPSHOT_INVALID")
                observed[row["id"]] = (row["kind"], row["reserved"])
                pg_unknown += int(row["state"] == "UNKNOWN")
            evaluation_unknown = c.execute("SELECT count(*) FROM reservations WHERE outcome!='OBSERVED'").fetchone()[0]
            matched = observed == local and pg_unknown == 0 and evaluation_unknown == 0
            if not matched:
                c.execute("UPDATE control SET stopped=1 WHERE id=1")
            return {"matching": matched, "pgReservedNusd": sum(v[1] for v in observed.values()),
                    "evaluationReservedNusd": sum(v[1] for v in local.values()),
                    "pgOnlyReservations": len(set(observed) - set(local)),
                    "evaluationOnlyReservations": len(set(local) - set(observed)),
                    "pgUnknown": pg_unknown, "evaluationUnknown": evaluation_unknown,
                    "automaticRefundOrReplay": False}
