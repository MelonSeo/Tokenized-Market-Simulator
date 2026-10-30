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
    paths += [Path(__file__).with_name(name) for name in ("ai_live_run_prepare.py","ai_db_relay.py","ai_evaluation_lineage.py","ai_reevaluation_plan.py","ai_index_copy_fixture.py","ai_evaluation_dry_run.py","ai_evaluation_continuation.py")]
    paths += [Path(__file__).with_name(name) for name in ("ai_web_demo_scope.py","ai_web_demo_broker.py")]
    paths += list((REPO/'backend/src/test/java/com/pricetrack/exchange/futures/chain').glob('FuturesWeb*.java'))
    digest = hashlib.sha256()
    # Importing from backend via ../deployment must yield the SAME identity as
    # the root CLI. Normalize __file__-derived paths before sorting and naming.
    for p in sorted({p.resolve() for p in paths}):
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
        if 'continuation' in meta:
            from ai_evaluation_continuation import verify, assess, context
            verify(folder,meta)
            expected['continuationContextHash']=context(meta)
            expected['questions']=meta['questions']
            expected['localLimits']=meta['continuation']['scope']
            assess(meta,_decode((folder/'dry-run.private.json').read_bytes(),max_bytes=2000000))
            proof=_decode((folder/'index-copy-verification.json').read_bytes())
            if proof.get('status')!='PASS' or proof.get('newOwner')!=meta['runId'] or proof.get('providerCalls')!=0:
                raise ValueError()
        elif 'lineage' in meta:
            from ai_evaluation_lineage import verify
            from ai_evaluation_dry_run import assess
            verify(folder,meta)
            expected['parentLedgerSha256']=meta['lineage']['parentLedgerSha256']
            expected['questions']=list(range(2,17))
            capture=_decode((folder/'dry-run.private.json').read_bytes(),max_bytes=2000000)
            if (capture.get('sourceHash')!=meta['sourceHash'] or capture.get('processedQuestions')!=list(range(2,17))
                    or capture.get('preservedFuturesEvidence') is not True
                    or {p.get('profile') for p in capture.get('profiles',[])}!={'EXPECTED_STATE','MAXIMUM_MIXED'}
                    or not assess(meta['lineage']['parentDir'],folder/'dry-run.private.json')['allProfilesFit']):
                raise ValueError()
            proof=_decode((folder/'index-copy-verification.json').read_bytes())
            if proof.get('status')!='PASS' or proof.get('newOwner')!=meta['runId'] or proof.get('providerCalls')!=0:
                raise ValueError()
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

    return _https_sender(folder, permit, key)


def _https_sender(folder, permit, key):
    """Shared bounded HTTPS transport; caller must already verify its permit."""
    def send(path, raw, timeout):
        if time.time() >= permit["expiresAt"] or (folder / "STOP").exists():
            raise GuardError("RUN_STOPPED")
        # A fresh connection; no implicit SDK retry, redirect, proxy or token refresh.
        deadline = time.monotonic() + timeout
        started = time.monotonic()
        attempt = uuid.uuid4().hex
        connection = None
        phase, status = "CONNECTION_SETUP", None
        def record(code):
            _transport_event(folder, attempt, path, phase, code, started, timeout, status)
        try:
            record("STARTED")
            connection = http.client.HTTPSConnection("api.openai.com", 443, timeout=timeout)
            def remaining():
                left=deadline-time.monotonic()
                if left<=0:raise TimeoutError()
                if connection.sock:connection.sock.settimeout(left)
                return left
            phase = "REQUEST_CALL_BEGIN"
            record("STARTED")
            connection.timeout=remaining()
            connection.request("POST", "/v1/" + path, body=raw,
                               headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"})
            phase = "REQUEST_CALL_RETURNED"
            record("OK")
            remaining()
            response = connection.getresponse()
            phase, status = "RESPONSE_HEADERS", response.status
            record("OK")
            if response.status != 200:
                raise GuardError("UPSTREAM_UNCERTAIN")
            phase = "RESPONSE_BODY"
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
            phase = "PAYLOAD_DECODED"
            record("OK")
            return result
        except Exception as failure:
            code = "UPSTREAM_TIMEOUT" if isinstance(failure, TimeoutError) else "UPSTREAM_CONNECTION_FAILURE" if isinstance(failure, OSError) else "UPSTREAM_RESPONSE_INVALID" if isinstance(failure, (ValueError, TypeError)) else "UPSTREAM_UNCERTAIN"
            record(code)
            raise
        finally:
            if connection is not None:
                connection.close()
    return send


def _transport_event(folder, attempt, path, phase, code, started, timeout, status):
    """Private best-effort allow-list. A phase is local observation, never proof of remote billing/completion."""
    phases = {"CONNECTION_SETUP", "REQUEST_CALL_BEGIN", "REQUEST_CALL_RETURNED", "RESPONSE_HEADERS", "RESPONSE_BODY", "PAYLOAD_DECODED"}
    codes = {"STARTED", "OK", "UPSTREAM_TIMEOUT", "UPSTREAM_CONNECTION_FAILURE", "UPSTREAM_RESPONSE_INVALID", "UPSTREAM_UNCERTAIN"}
    try:
        if not re.fullmatch(r"[0-9a-f]{32}", attempt) or phase not in phases or code not in codes:
            return
        target = Path(folder) / ("python-transport-" + attempt + ".jsonl")
        if target.is_symlink() or target.exists() and target.stat().st_size > 65536:
            return
        event = {"version": 1, "phase": phase, "resultCode": code,
                 "endpoint": path if path in {"responses", "embeddings"} else "UNKNOWN",
                 "elapsedMillis": max(0, int((time.monotonic()-started)*1000)),
                 "timeoutMillis": max(0, int(timeout*1000)), "observedAt": time.time(),
                 "httpStatus": status if type(status) is int and 100 <= status <= 599 else None}
        with target.open("a", encoding="utf-8") as output:
            output.write(json.dumps(event, separators=(",", ":"))+"\n")
    except Exception:
        pass  # Never replace the guard's original uncertainty or mutate budget/STOP.


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
    carry,validator=None,None
    local_limits=None
    if 'continuation' in meta:
        from ai_evaluation_continuation import verify
        carry=verify(folder,meta);validator=lambda:verify(folder,meta)
        local_limits=meta['continuation']['scope']
    elif 'lineage' in meta:
        from ai_evaluation_lineage import verify
        carry=verify(folder,meta);validator=lambda:verify(folder,meta)
    guard = EvaluationGuard(folder / "budget.sqlite", run_id=meta["runId"], source_hash=meta["sourceHash"],
                            corpus_hash=meta["corpusHash"], execution_approved=True,
                            context_hash=hashlib.sha256(_canonical(meta)).hexdigest(),carry=carry,validate_lineage=validator,local_limits=local_limits)
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
            if 'continuation' in meta:
                # The single-question continuation never continues after an
                # error (including uncertain transport or local cap rejection).
                try:
                    with (folder/'STOP').open('xb') as marker:marker.write(b'STOPPED')
                except OSError:pass
                try:guard.stop()
                except GuardError:pass
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
            meta = _decode((Path(args.run_dir) / 'run.json').read_bytes())
            if meta.get('purpose') == 'LATEST_AI_QUALITY_20261030':
                from ai_latest_evaluation_broker import broker as latest_broker
                latest_broker(args.run_dir, live=args.live, approval=args.approval)
            else:
                broker(args.run_dir, live=args.live, approval=args.approval)
    except Exception as error:
        code = str(error) if isinstance(error, GuardError) else "BROKER_FAILURE"
        print(json.dumps({"ok": False, "code": code if re.fullmatch(r"[A-Z_]{1,60}", code) else "BROKER_FAILURE"}))
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
