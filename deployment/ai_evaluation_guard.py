"""Evaluation-only, durable pre-dispatch budget. No HTTP client, keys, or live CLI.

The future approved runner must route *all* provider POSTs through dispatch().
This module alone does not constrain other clients or an OpenAI account's bill.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import uuid
from contextlib import contextmanager
from types import MappingProxyType


class GuardError(RuntimeError):
    """Only fixed public codes, never request/response bodies or exceptions."""


POLICY = {
    "version": "futures-ai-eval-v3",
    "llm_model": "gpt-5.6-terra", "embedding_model": "text-embedding-3-small",
    "llm_calls": 32, "embedding_calls": 80,
    "input_tokens": 256000, "output_tokens": 32000, "embedding_tokens": 100000,
    # Integer nano-USD/token: standard, no cached discount; cache-write premium
    # is conservatively included in the input rate (2.50 rather than 2 USD/M).
    "input_nusd": 2500, "output_nusd": 12000, "embedding_nusd": 20,
    "cap_nusd": 2000000000, "framing_tokens": 4096,
}


def _canonical(value):
    return json.dumps(value, ensure_ascii=False, allow_nan=False,
                      sort_keys=True, separators=(",", ":")).encode("utf-8")


def _decode(raw, max_bytes=100000):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError()
            result[key] = value
        return result
    try:
        if not isinstance(raw, bytes) or len(raw) > max_bytes:
            raise ValueError()
        body = json.loads(raw.decode("utf-8"), object_pairs_hook=unique,
                          parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
        if not isinstance(body, dict):
            raise ValueError()
        # JSON escapes may decode to lone surrogates even with valid raw UTF-8.
        # Reject before an embedding encode exception could expose input text.
        _canonical(body)
        return body
    except Exception:
        raise GuardError("INVALID_REQUEST") from None


def _bound(path, raw, policy):
    body = _decode(raw)
    if path == "responses":
        if set(body) != {"model", "store", "instructions", "input", "reasoning", "max_output_tokens", "text", "service_tier"}:
            raise GuardError("UNSUPPORTED_REQUEST")
        if (body["model"] != policy["llm_model"] or body["store"] is not False
                or body["service_tier"] != "default"
                or body["reasoning"] != {"effort": "none"}
                or not isinstance(body["input"], str) or not isinstance(body["instructions"], str)
                or not isinstance(body["text"], dict) or set(body["text"]) != {"format"}):
            raise GuardError("UNSUPPORTED_REQUEST")
        fmt = body["text"]["format"]
        if (not isinstance(fmt, dict) or set(fmt) != {"type", "name", "strict", "schema"}
                or fmt["type"] != "json_schema" or fmt["strict"] is not True
                or not isinstance(fmt["name"], str) or not isinstance(fmt["schema"], dict)):
            raise GuardError("UNSUPPORTED_REQUEST")
        output = body["max_output_tokens"]
        if type(output) is not int or not 1 <= output <= 1600:
            raise GuardError("OUTPUT_LIMIT")
        # Text-only UTF-8 bytes conservatively dominate BPE text token count.
        # Include *entire* serialized request (schema/instructions too) + framing.
        tokens = len(raw) + policy["framing_tokens"]
        if tokens > 32768:
            raise GuardError("INPUT_LIMIT")
        return "llm", tokens, output, 0
    if path == "embeddings":
        if (set(body) != {"model", "input", "dimensions", "encoding_format"}
                or body["model"] != policy["embedding_model"]
                or type(body["dimensions"]) is not int or body["dimensions"] != 1536
                or body["encoding_format"] != "float"
                or not isinstance(body["input"], list) or not 1 <= len(body["input"]) <= 16):
            raise GuardError("UNSUPPORTED_REQUEST")
        tokens = 0
        for text in body["input"]:
            if not isinstance(text, str) or not text.strip():
                raise GuardError("INVALID_REQUEST")
            bound = len(text.encode("utf-8")) + 8
            if bound > 8191:
                raise GuardError("INPUT_LIMIT")
            tokens += bound
        return "embedding", 0, 0, tokens
    raise GuardError("ENDPOINT_NOT_ALLOWED")


class EvaluationGuard:
    """One immutable run identity, one ledger, no refunds/retries/reset API.

    sender(path, frozen_bytes) must implement exactly one bounded POST without
    redirects/retries. No sender is constructed here. Approval defaults false;
    do not derive it from existing AI_* environment variables or mere key presence.
    """
    def __init__(self, ledger, *, run_id, source_hash, corpus_hash, create=False,
                 execution_approved=False, context_hash="0" * 64):
        if (not re.fullmatch(r"[a-zA-Z0-9_-]{1,80}", run_id)
                or any(not re.fullmatch(r"[0-9a-f]{64}", h) for h in (source_hash, corpus_hash, context_hash))
                or type(execution_approved) is not bool):
            raise GuardError("INVALID_IDENTITY")
        self.path = Path(ledger).absolute()
        if self.path.is_symlink():
            raise GuardError("INVALID_LEDGER")
        self.approved = execution_approved
        self.policy = MappingProxyType(dict(POLICY))
        self.identity = _canonical({"run": run_id, "source": source_hash, "corpus": corpus_hash, "context": context_hash, "policy": dict(self.policy)}).decode()
        try:
            if create:
                fd = os.open(self.path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                os.close(fd)
                with self._db() as db:
                    db.executescript("""
                        CREATE TABLE identity (id INTEGER PRIMARY KEY CHECK(id=1), value TEXT NOT NULL);
                        CREATE TABLE reservations (
                          id TEXT PRIMARY KEY, kind TEXT NOT NULL, input INTEGER NOT NULL,
                          output INTEGER NOT NULL, embedding INTEGER NOT NULL, cost INTEGER NOT NULL,
                          outcome TEXT NOT NULL, digest TEXT NOT NULL);
                    """)
                    db.execute("INSERT INTO identity VALUES(1,?)", (self.identity,))
                    db.execute("CREATE TABLE control(id INTEGER PRIMARY KEY CHECK(id=1), stopped INTEGER NOT NULL)")
                    db.execute("INSERT INTO control VALUES(1,0)")
            with self._db() as db:
                self._identity(db)
        except (OSError, sqlite3.Error):
            raise GuardError("LEDGER_UNAVAILABLE") from None

    @contextmanager
    def _db(self):
        db = sqlite3.connect(self.path.as_uri() + "?mode=rw", uri=True, timeout=5)
        try:
            db.execute("PRAGMA synchronous=FULL")
            with db:
                yield db
        finally:
            db.close()

    def _identity(self, db):
        rows = db.execute("SELECT value FROM identity WHERE id=1").fetchall()
        if rows != [(self.identity,)]:
            raise GuardError("IDENTITY_MISMATCH")

    @staticmethod
    def _totals(db):
        row = db.execute("""SELECT count(CASE WHEN kind='llm' THEN 1 END),
            count(CASE WHEN kind='embedding' THEN 1 END), coalesce(sum(input),0),
            coalesce(sum(output),0), coalesce(sum(embedding),0), coalesce(sum(cost),0)
            FROM reservations""").fetchone()
        return dict(zip(("llm_calls", "embedding_calls", "input_tokens", "output_tokens", "embedding_tokens", "reserved_nusd"), row))

    def summary(self):
        try:
            with self._db() as db:
                self._identity(db)
                return self._totals(db)
        except sqlite3.Error:
            raise GuardError("LEDGER_UNAVAILABLE") from None

    def stop(self):
        self.approved = False
        try:
            with self._db() as db:
                self._identity(db)
                db.execute("UPDATE control SET stopped=1 WHERE id=1")
        except sqlite3.Error:
            raise GuardError("LEDGER_UNAVAILABLE") from None

    def dispatch(self, path, raw, sender, *, request_id):
        if not self.approved:
            raise GuardError("EXECUTION_NOT_APPROVED")
        try:
            if str(uuid.UUID(request_id)) != request_id:
                raise ValueError()
        except (ValueError, TypeError, AttributeError):
            raise GuardError("INVALID_REQUEST_ID") from None
        kind, inp, out, emb = _bound(path, raw, self.policy)
        cost = inp * self.policy["input_nusd"] + out * self.policy["output_nusd"] + emb * self.policy["embedding_nusd"]
        try:
            with self._db() as db:
                db.execute("BEGIN IMMEDIATE")
                self._identity(db)
                if db.execute("SELECT stopped FROM control WHERE id=1").fetchall() != [(0,)]:
                    raise GuardError("RUN_STOPPED")
                if db.execute("SELECT 1 FROM reservations WHERE id=?", (request_id,)).fetchone():
                    raise GuardError("REQUEST_ALREADY_RESERVED")
                # Sequential run: in-flight, crashed, uncertain, or unrecorded
                # usage must block *other instances*, not only this process.
                if db.execute("SELECT 1 FROM reservations WHERE outcome!='OBSERVED'").fetchone():
                    raise GuardError("RUN_HALTED")
                totals = self._totals(db)
                additions = {"llm_calls": int(kind == "llm"), "embedding_calls": int(kind == "embedding"),
                             "input_tokens": inp, "output_tokens": out, "embedding_tokens": emb}
                if any(totals[k] + v > self.policy[k] for k, v in additions.items()):
                    raise GuardError("RUN_LIMIT")
                if totals["reserved_nusd"] + cost > self.policy["cap_nusd"]:
                    raise GuardError("COST_LIMIT")
                db.execute("INSERT INTO reservations VALUES(?,?,?,?,?,?,?,?)",
                           (request_id, kind, inp, out, emb, cost, "RESERVED", hashlib.sha256(raw).hexdigest()))
            # Durable COMMIT precedes any transport invocation; uncertain failures
            # retain full reservation forever (even across a new guard/process).
        except sqlite3.Error:
            raise GuardError("LEDGER_UNAVAILABLE") from None
        try:
            response = sender(path, raw)
        except Exception:
            raise GuardError("TRANSPORT_UNCERTAIN") from None
        try:
            usage = response["usage"]
            actual_in = usage["input_tokens" if kind == "llm" else "prompt_tokens"]
            actual_out = usage["output_tokens"] if kind == "llm" else 0
            valid = (type(actual_in) is int and type(actual_out) is int
                     and 0 <= actual_in <= (inp if kind == "llm" else emb)
                     and 0 <= actual_out <= out)
        except (KeyError, TypeError):
            valid = False
        try:
            with self._db() as db:
                db.execute("UPDATE reservations SET outcome=? WHERE id=?",
                           ("OBSERVED" if valid else "BREACH", request_id))
        except sqlite3.Error:
            # Do not let an unrecorded usage breach permit subsequent dispatch.
            self.approved = False
            raise GuardError("LEDGER_UNAVAILABLE") from None
        if not valid:
            raise GuardError("USAGE_UNVERIFIABLE")
        return response
