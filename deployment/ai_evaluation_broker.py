"""Private stdio broker for the evaluation runner. Offline by default, no env keys.

Only --live together with a run-bound, unexpired explicit approval file can
construct the single-request HTTPS sender. No listener, proxies or redirects.
"""
import argparse
import base64
import hashlib
import http.client
import json
from pathlib import Path
import re
import sys
import time
import uuid

from ai_evaluation_guard import EvaluationGuard, GuardError, POLICY, _canonical, _decode

REPO = Path(__file__).resolve().parent.parent


def source_hash():
    paths = list((REPO / "backend/src/main/java").rglob("*.java"))
    paths += [p for p in (REPO / "backend/src/main/resources").rglob("*") if p.is_file()]
    paths += list((REPO / "contracts/src").rglob("*.sol"))
    paths += list((REPO / "backend/src/test/java/com/pricetrack/exchange/ai/evaluation").rglob("*.java"))
    paths += list((REPO / "docs/ai-knowledge").glob("*.md"))
    paths += [REPO / "backend/build.gradle", REPO / "docs/ai/ingest-manifest.json", Path(__file__), Path(__file__).with_name("ai_evaluation_guard.py")]
    paths += [Path(__file__).with_name(name) for name in ("ai_live_run_prepare.py","ai_db_relay.py")]
    digest = hashlib.sha256()
    for p in sorted(paths):
        digest.update(p.relative_to(REPO).as_posix().encode() + b"\0")
        digest.update(p.read_bytes().replace(b"\r\n", b"\n").replace(b"\r", b"\n") + b"\0")
    return digest.hexdigest()


def corpus_hash():
    manifest = json.loads((REPO / "docs/ai/ingest-manifest.json").read_text(encoding="utf-8"))
    rows = []
    for entry in manifest["documents"]:
        if not re.fullmatch(r"[a-z0-9-]+\.md", entry["path"]):
            raise GuardError("CORPUS_INVALID")
        text = (REPO / "docs/ai-knowledge" / entry["path"]).read_text(encoding="utf-8")
        text = text.replace("\r\n", "\n").replace("\r", "\n")
        sha = hashlib.sha256(text.encode()).hexdigest()
        if sha != entry["sha256"]:
            raise GuardError("CORPUS_INVALID")
        rows.append((entry["path"], sha))
    identity = "text-embedding-3-small:1536:heading-byte-v2-CLEAN:1200:60"
    identity += "".join("\n" + name + ":" + sha for name, sha in sorted(rows))
    return hashlib.sha256(identity.encode()).hexdigest()


def prepare(folder, db_port, *, fixture="success", mode="offline"):
    folder = Path(folder).absolute()
    if (type(db_port) is not int or not 49152 <= db_port <= 65535 or fixture not in {"success", "timeout", "breach", "slow"}
            or mode not in {"offline", "live"} or (mode == "live" and fixture != "success")):
        raise GuardError("PREPARATION_INVALID")
    # Never adopt an existing directory or a previous session.
    folder.mkdir(exist_ok=False, parents=False)
    token = uuid.uuid4().hex
    meta = {"version": 1, "runId": "eval_" + token, "sourceHash": source_hash(),
            "corpusHash": corpus_hash(), "dbPort": db_port, "dbName": "exchange_ai_eval_" + token,
            "dbUser": "exchange_ai_eval_" + token, "fixture": fixture, "mode": mode, "policy": POLICY}
    (folder / "run.json").write_bytes(_canonical(meta))
    EvaluationGuard(folder / "budget.sqlite", run_id=meta["runId"], source_hash=meta["sourceHash"],
                    corpus_hash=meta["corpusHash"], create=True, context_hash=hashlib.sha256(_canonical(meta)).hexdigest())
    return meta


def load_run(folder):
    folder = Path(folder).resolve(strict=True)
    if folder.is_symlink() or (folder / "run.json").is_symlink() or (folder / "budget.sqlite").is_symlink():
        raise GuardError("RUN_INVALID")
    meta = _decode((folder / "run.json").read_bytes())
    if (meta.get("version") != 1 or meta.get("sourceHash") != source_hash()
            or meta.get("corpusHash") != corpus_hash() or meta.get("policy") != POLICY
            or not re.fullmatch(r"eval_[0-9a-f]{32}", meta.get("runId", ""))):
        raise GuardError("RUN_IDENTITY_MISMATCH")
    token = meta["runId"][5:]
    if (meta.get("dbName") != "exchange_ai_eval_" + token or meta.get("dbUser") != meta["dbName"]
            or type(meta.get("dbPort")) is not int or not 49152 <= meta["dbPort"] <= 65535):
        raise GuardError("RUN_TARGET_INVALID")
    return folder, meta


def live_sender(folder, meta, approval_path):
    # No key access at all before validation; approval is not inferred from env.
    try:
        permit = _decode(Path(approval_path).read_bytes())
        if meta.get("mode") != "live":
            raise ValueError()
        expected = {"runId": meta["runId"], "sourceHash": meta["sourceHash"], "corpusHash": meta["corpusHash"],
                    "capUsd": "2.00", "llmCalls": 32, "embeddingCalls": 80, "paidApproved": True}
        if (permit.get("paidApproved") is not True or any(permit.get(k) != v for k, v in expected.items())
                or type(permit.get("expiresAt")) is not int or not time.time() < permit["expiresAt"] <= time.time() + 14400):
            raise ValueError()
        key_path = Path(permit["keyFile"])
        if not key_path.is_absolute() or not key_path.is_file() or key_path.is_symlink():
            raise ValueError()
        key = key_path.read_text(encoding="utf-8").strip()
        if not key or "\n" in key or "\r" in key:
            raise ValueError()
    except Exception:
        raise GuardError("PAID_APPROVAL_REQUIRED") from None

    def send(path, raw, timeout):
        if time.time() >= permit["expiresAt"] or (folder / "STOP").exists():
            raise GuardError("RUN_STOPPED")
        # A fresh connection; no implicit SDK retry, redirect, proxy or token refresh.
        connection = http.client.HTTPSConnection("api.openai.com", 443, timeout=timeout)
        deadline = time.monotonic() + timeout
        try:
            connection.request("POST", "/v1/" + path, body=raw,
                               headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"})
            response = connection.getresponse()
            if response.status != 200:
                raise GuardError("UPSTREAM_UNCERTAIN")
            chunks = bytearray()
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise GuardError("UPSTREAM_UNCERTAIN")
                if connection.sock:
                    connection.sock.settimeout(remaining)
                chunk = response.read1(min(65536, 2000001 - len(chunks)))
                if not chunk:
                    break
                chunks.extend(chunk)
                if len(chunks) > 2000000:
                    raise GuardError("UPSTREAM_UNCERTAIN")
            result = json.loads(bytes(chunks))
            if path == "responses" and result.get("service_tier") != "default":
                raise GuardError("UPSTREAM_TIER_UNVERIFIED")
            return result
        finally:
            connection.close()
    return send


def offline_sender(fixture):
    def send(path, raw, timeout):
        if fixture == "timeout":
            raise TimeoutError()
        if fixture == "slow":
            time.sleep(2)
        if path == "embeddings":
            body = _decode(raw)
            return {"usage": {"prompt_tokens": 1}, "data": [
                {"index": i, "embedding": [1.0] + [0.0] * 1535} for i in range(len(body["input"]))]}
        body = _decode(raw)
        name = body["text"]["format"]["name"]
        output = {"ok": True}
        if name == "rag_answer":
            output = {"status": "INSUFFICIENT_EVIDENCE", "answer": "offline fixture", "citationIds": []}
        if name == "agent_plan":
            output = {"route": "KNOWLEDGE", "subject": "NONE"}
            if "skillId" in body["text"]["format"]["schema"].get("properties", {}):
                output["skillId"] = "NONE"
        if name == "agent_interpretation":
            output = {"status": "INSUFFICIENT_EVIDENCE", "interpretation": "offline fixture", "citationIds": [],
                      "facts": [], "uncertainties": [], "recommendedNextCheck": []}
        return {"status": "completed", "service_tier": "default", "usage": {"input_tokens": 1, "output_tokens": 99999 if fixture == "breach" else 1},
                "output": [{"content": [{"type": "output_text", "text": json.dumps(output)}]}]}
    return send


def broker(folder, *, live=False, approval=None, inp=None, out=None):
    inp = inp or sys.stdin.buffer
    out = out or sys.stdout.buffer
    folder, meta = load_run(folder)
    if not live and approval is not None:
        raise GuardError("MODE_CONFLICT")
    if ("live" if live else "offline") != meta.get("mode"):
        raise GuardError("MODE_CONFLICT")
    guard = EvaluationGuard(folder / "budget.sqlite", run_id=meta["runId"], source_hash=meta["sourceHash"],
                            corpus_hash=meta["corpusHash"], execution_approved=True,
                            context_hash=hashlib.sha256(_canonical(meta)).hexdigest())
    sender = live_sender(folder, meta, approval) if live else offline_sender(meta["fixture"])
    def emit(value):
        out.write(_canonical(value) + b"\n")
        out.flush()
    emit({"ready": True, "mode": "live" if live else "offline", "sourceHash": meta["sourceHash"], "corpusHash": meta["corpusHash"]})
    while True:
        line = inp.readline(160001)
        if not line:
            return
        try:
            if len(line) > 160000 or not line.endswith(b"\n"):
                raise GuardError("FRAME_INVALID")
            request = _decode(line, max_bytes=160000)
            if request == {"summary": True}:
                emit({"summary": guard.summary()})
                continue
            if request == {"stop": True}:
                (folder / "STOP").touch(exist_ok=True)
                guard.stop()
                emit({"stopped": True})
                return
            if (folder / "STOP").exists():
                guard.stop()
                raise GuardError("RUN_STOPPED")
            if set(request) != {"id", "path", "body", "timeoutMs"} or type(request["timeoutMs"]) is not int or not 1 <= request["timeoutMs"] <= 60000:
                raise GuardError("FRAME_INVALID")
            raw = base64.b64decode(request["body"], validate=True)
            response = guard.dispatch(request["path"], raw,
                                      lambda p, b: sender(p, b, request["timeoutMs"] / 1000), request_id=request["id"])
            encoded = _canonical(response)
            if len(encoded) > 2000000:
                guard.stop()
                raise GuardError("RESPONSE_LIMIT")
            emit({"ok": True, "body": base64.b64encode(encoded).decode()})
        except Exception as error:
            code = str(error) if isinstance(error, GuardError) else "BROKER_FAILURE"
            if not re.fullmatch(r"[A-Z_]{1,60}", code):
                code = "BROKER_FAILURE"
            emit({"ok": False, "code": code})


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--run-dir", required=True)
    p.add_argument("--prepare", action="store_true")
    p.add_argument("--db-port", type=int)
    p.add_argument("--live", action="store_true")
    p.add_argument("--approval")
    p.add_argument("--fixture", choices=["success", "timeout", "breach", "slow"], default="success")
    p.add_argument("--evaluation-mode", choices=["offline", "live"], default="offline")
    args = p.parse_args()
    try:
        if args.prepare:
            if args.live or args.approval:
                raise GuardError("MODE_CONFLICT")
            prepare(args.run_dir, args.db_port, fixture=args.fixture, mode=args.evaluation_mode)
            print("PREPARED_OFFLINE")
        else:
            broker(args.run_dir, live=args.live, approval=args.approval)
    except Exception as error:
        code = str(error) if isinstance(error, GuardError) else "BROKER_FAILURE"
        print(json.dumps({"ok": False, "code": code if re.fullmatch(r"[A-Z_]{1,60}", code) else "BROKER_FAILURE"}))
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
