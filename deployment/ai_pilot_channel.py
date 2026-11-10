"""Private stdio V2 connection, unpaid by default; separate bound permit for paid mode.

The Java server creates the channel, not the browser. Admission uses its verified
JWT principal, and worker binding precedes Agent.run (including zero-model STATE).
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import sys
import time
import uuid

from ai_evaluation_broker import offline_sender
from ai_pilot_coordinator import PilotCoordinator, need, digest
from ai_pilot_preflight import ROOT, canonical

CARRY_SOURCES=('ai_pilot_continuation.py','ai_pilot_review.py','ai_pilot_carry_launcher.py','ai_pilot_held_resume.py','ai_pilot_review_resume.py','ai_pilot_oom_resume.py')

def source_paths(root):
    paths = [p for folder in ("backend/src/main", "backend/src/test/java/com/pricetrack/exchange/ai/pilot",
                              "backend/src/test/resources/ai", "docs/ai-knowledge", "tools/websocket-test-client/src")
             for p in (root / folder).rglob("*") if p.is_file()]
    paths += [root / "deployment" / n for n in ("ai_pilot_channel.py", "ai_pilot_coordinator.py", "ai_pilot_fixture.py", "ai_pilot_http_plan.py", "ai_pilot_http_adapter.py", "ai_pilot_browser_host.py", "ai_pilot_preflight.py", "ai_novel_question_audit.py", "ai_evaluation_broker.py", "ai_evaluation_guard.py", "ai-pilot-budget-v2.json", "ai-pilot-execution-v2.json")]
    paths += [root / n for n in ("backend/build.gradle", "docs/ai/ingest-manifest.json", "docs/ai/ingest-manifest-pilot.json")]
    paths += [root / "deployment" / n for n in ("ai_pilot_unpaid_live.py", "ai_pilot_bind_live.py", "PilotV2ChainFixture.java", "product-read-ci.gradle", "product_read_resources.py", "product_read_network.py", "c_gate_network.py", "product_manual_clock.py")]
    paths += [root/'deployment/ai_pilot_paid_sender.py']
    paths += [root/'deployment'/n for n in ('ai_pilot_paid_transport.py','ai_pilot_egress.py','ai_pilot_paid_environment.py','ai_pilot_paid_schedule.py')]
    paths += [root/'deployment'/n for n in ('ai_pilot_paid_launcher.py','ai_pilot_paid_inner.py','ai_pilot_paid_adapter.py')]
    paths += [root/'deployment'/n for n in ('ai_pilot_chrome_resume.py','ai_pilot_fixture_audit.py','ai_pilot_linux_browser_probe.py')]
    paths += [root/'backend/src/test/java/com/pricetrack/exchange/futures/chain/PilotPaidServer.java']
    paths += [root/'tools/websocket-test-client/scripts'/n for n in ('ai-linux-pilot-browser.mjs','ai-linux-browser-proof.mjs','linux-sandbox-evidence.mjs','pilot-dom-readiness.mjs')]
    paths += [root/'deployment/pilot-linux-image/chromium-seccomp.json']
    paths += [root/'deployment/ai-pilot-driver-bounds.json']
    paths += [root/'deployment'/n for n in CARRY_SOURCES]
    artifact=root/'deployment/paid-build-identity.json'
    if artifact.is_file():paths += [artifact]
    paths += [root/'deployment'/n for n in ('ai_pilot_native_bridge.py','ai_pilot_native_probe.py')]
    paths += [root/'deployment/ai-readiness-plan.json']
    paths += [root/'deployment'/n for n in ('ai_pilot_linux.py','ai_pilot_linux_inner.py','PilotV2LinuxSmoke.java','pilot-linux-image/Dockerfile')]
    paths += [root / "backend/src/test/java/com/pricetrack/exchange/futures/chain" / n for n in ("PilotV2LiveHttpTest.java", "FuturesAutoPostgresAnvilE2ETest.java", "PilotReceiptProbeTest.java")]
    paths += [root / "tools/websocket-test-client/scripts" / n for n in ("ai-pilot-v2-flow.mjs", "ai-pilot-v2-browser.mjs", "cdp-boundary.mjs", "browser-executable.mjs", "native-sandbox-evidence.mjs", "ai-native-sandbox-probe.mjs")]
    return sorted(set(paths),key=lambda p:p.relative_to(root).as_posix())


def source_fingerprint(root=ROOT,*,artifact_raw=None):
    paths=source_paths(root)
    artifact=root/'deployment/paid-build-identity.json'
    if artifact_raw is not None:
        need(type(artifact_raw) is bytes,'V2_BUILD_IDENTITY_BYTES')
        paths=sorted(set(paths)|{artifact},key=lambda p:p.relative_to(root).as_posix())
    h = hashlib.sha256()
    for p in sorted(set(paths),key=lambda p:p.relative_to(root).as_posix()):
        raw=artifact_raw if p==artifact and artifact_raw is not None else p.read_bytes()
        h.update(p.relative_to(root).as_posix().encode() + b"\0" + raw.replace(b"\r\n", b"\n"))
    return h.hexdigest()


def prepare_unpaid(folder, fixture):
    folder = Path(folder)
    need(folder.is_dir() and not folder.is_symlink() and not any(folder.iterdir()), "V2_NEW_EMPTY_FOLDER_REQUIRED")
    meta = {"allocation": str(uuid.uuid4()), "mode": "UNPAID_CONTROLLED_ONLY",
            "source": source_fingerprint(), "fixture": fixture, "fixtureHash": digest(fixture)}
    # Exclusive creation, no approval file, key copy, old ledger or STOP access.
    with (folder / "session.json").open("x", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False)
    (folder / "session.json").chmod(0o600)
    PilotCoordinator(folder / "pilot-v2.sqlite", allocation=meta["allocation"], source_hash=meta["source"], fixture=fixture, create=True)
    return meta


def prepare_paid(folder,fixture,approval_file,budget_authority):
    """Not invoked without a separately approved permit. Never upgrades an UNPAID ledger."""
    from ai_pilot_paid_sender import authorize
    folder=Path(folder).resolve()
    need(folder.is_relative_to(ROOT/'deployment/runtime') and folder.is_dir() and not any(folder.iterdir()),'V2_NEW_EMPTY_FOLDER_REQUIRED')
    p=Path(approval_file);need(not p.is_symlink(),'V2_PAID_APPROVAL_REQUIRED');p=p.resolve();need(p.is_file() and p.stat().st_size<=16384,'V2_PAID_APPROVAL_REQUIRED')
    permit=json.loads(p.read_text(encoding='utf8'))
    meta={'allocation':permit['allocation'],'mode':'PAID_EXPLICIT_V2','source':source_fingerprint(),'fixture':fixture,'fixtureHash':digest(fixture),
          'folder':str(folder),'budgetAuthority':budget_authority,'approvalFile':str(p),'permitHash':digest(permit),'approvalId':permit['approvalId']}
    authorize(meta,permit,current_source=source_fingerprint())
    with (folder/'session.json').open('x',encoding='utf8') as out:json.dump(meta,out)
    PilotCoordinator(folder/'pilot-v2.sqlite',allocation=meta['allocation'],source_hash=meta['source'],fixture=fixture,mode='PAID_V2',create=True)
    return meta


def load(folder):
    folder = Path(folder)
    need(not folder.is_symlink() and not (folder / "session.json").is_symlink() and not (folder / "STOP").exists(), "V2_SESSION_LINK_OR_STOP")
    meta = json.loads((folder / "session.json").read_text(encoding="utf-8"))
    need(meta["mode"] in {"UNPAID_CONTROLLED_ONLY","PAID_EXPLICIT_V2"} and meta["source"] == source_fingerprint()
         and meta["fixtureHash"] == digest(meta["fixture"]), "V2_SESSION_IDENTITY")
    if meta['mode']=='PAID_EXPLICIT_V2':
        from ai_pilot_paid_sender import authorize,load_permit
        need(meta['folder']==str(folder.resolve()),'V2_PAID_ALLOCATION_PATH')
        authorize(meta,load_permit(meta),current_source=source_fingerprint())
    coordinator=PilotCoordinator
    if meta.get('continuation',{}).get('version') in {'tms-pilot-held-b5-v4','tms-pilot-review-carry23-v5','tms-pilot-oom-carry32-v6'}:
        from ai_pilot_held_resume import HeldCoordinator
        return HeldCoordinator(folder/'pilot-v2.sqlite',meta=meta)
    return coordinator(folder / "pilot-v2.sqlite", allocation=meta["allocation"], source_hash=meta["source"], fixture=meta["fixture"],mode='PAID_V2' if meta['mode']=='PAID_EXPLICIT_V2' else 'UNPAID')


def handle(c, frame, supplier):
    op = frame["op"]
    if op == "active":
        with c.db() as db:
            c.verify(db)
            p = c.active(db)
            return {"phase": p["id"], "corpus": p["corpus"]}
    if op == "dto":
        with c.db() as db:
            c.verify(db)
            phase = c.active(db)["id"]
            sub = db.execute("SELECT slot FROM submissions WHERE phase=? AND actor=? AND server_request=? AND admitted=1 AND "+c.pending_sql(), (phase, frame["actor"], frame["serverRequest"])).fetchall()
            need(len(sub) == 1, "V2_SERVER_REQUEST_MISMATCH")
            row = next(r for r in c.rows if r["phase"] == phase and r["slot"] == sub[0][0])
            tool = frame["tool"]
            return {"mode": row["control"] if (tool == "getQuote" and row["case"] == "D1") or (tool == "getReceiptSummary" and row["case"] == "B5") else "NONE"}
    if op == "claim":
        return {"ticket": c.claim(frame["phase"], frame.get("slot", 0))}
    if op == "admit":
        return {"ticket": c.admit(frame["actor"], frame["body"], frame["corpus"])}
    if op == "bind":
        c.bind_worker(frame["actor"], frame["serverRequest"], frame["corpus"])
        return {"bound": True}
    if op == "exit":
        c.worker_exited(frame["actor"], frame["serverRequest"])
        return {"workerExited": True}
    if op == "index-bind":
        with c.db() as db:
            c.verify(db)
            need(c.active(db)["id"].startswith("INDEX_"), "V2_INDEX_PHASE_REQUIRED")
            sub = db.execute("SELECT server_request FROM submissions WHERE phase=? AND actor=? AND admitted=1 AND complete=0", (c.active(db)["id"], frame["actor"])).fetchall()
            need(len(sub) == 1 and sub[0][0] in (None, frame["serverRequest"]), "V2_SERVER_REQUEST_MISMATCH")
        if sub[0][0] is None:
            c.bind_worker(frame["actor"], frame["serverRequest"], frame["corpus"])
        return {"bound": True}
    if op == "index-exit":
        with c.db() as db:
            c.verify(db)
            need(c.active(db)["id"].startswith("INDEX_"), "V2_INDEX_PHASE_REQUIRED")
            sub = db.execute("SELECT server_request FROM submissions WHERE phase=? AND actor=? AND admitted=1 AND complete=0", (c.active(db)["id"], frame["actor"])).fetchall()
            need(len(sub) == 1 and sub[0][0] is not None, "V2_INDEX_NO_EXECUTION")
        c.worker_exited(frame["actor"], sub[0][0])
        return {"serverRequest": sub[0][0]}
    if op == "index-request":
        with c.db() as db:
            c.verify(db)
            sub = db.execute("SELECT server_request,worker_exited FROM submissions WHERE phase=? AND actor=? AND complete=0", (c.active(db)["id"], c.admin)).fetchall()
            need(len(sub) == 1 and sub[0][1] == 1, "V2_INDEX_NOT_EXITED")
            return {"serverRequest": sub[0][0]}
    if op == "complete":
        c.complete_request(frame["ticket"], frame["serverRequest"])
        return {"complete": True}
    if op == "advance":
        c.advance(evidence_reviewed=frame.get("evidenceReviewed"))
        return {"advanced": True}
    if op == "summary":
        return c.summary()
    if op == "reconcile":
        return c.reconcile(frame["pgRows"])
    if op == "stop":
        c.stop()
        return {"stopped": True}
    need(op == "dispatch", "V2_PRIVATE_OPERATION")
    need(type(frame.get("timeoutMs")) is int and 0 < frame["timeoutMs"] <= 60000, "V2_CALL_DEADLINE")
    need(type(frame.get("deadlineEpochMs")) is int, "V2_CALL_DEADLINE")
    remaining = min(frame["timeoutMs"] / 1000, frame["deadlineEpochMs"] / 1000 - time.time())
    need(0 < remaining <= 60, "V2_CALL_DEADLINE")
    deadline = time.monotonic() + remaining
    raw = base64.b64decode(frame["body"], validate=True)
    def once(path, body):
        left = deadline - time.monotonic()
        need(left > 0, "V2_CALL_DEADLINE")
        if hasattr(supplier,'send'):
            return supplier.send(path,body,left,frame['pgReservation'],frame['actor'],frame['serverRequest'],frame['corpus'])
        return supplier(path, body, left)
    result = c.dispatch(path=frame["path"], raw=raw, pg_id=frame["pgReservation"], actor=frame["actor"],
                        server_request=frame["serverRequest"], corpus=frame["corpus"],
                        sender=once)
    return {"response": result}


def controlled_supplier(path, raw, timeout):
    """Protocol double only: no expected answers, semantic search or quality success.

    Respect the server's pre-authorized target in plan schema; synthesis remains
    INSUFFICIENT_EVIDENCE. Real-model classification/quality stays NOT_RUN.
    """
    result = offline_sender("success")(path, raw, timeout)
    if path == "responses":
        body = json.loads(raw)
        if body["text"]["format"]["name"] == "agent_plan":
            target = json.loads(body["input"]).get("targetKind", "NONE")
            need(target in {"NONE", "ORDER", "QUOTE", "FUTURES", "C_POSITION"}, "V2_CONTROLLED_TARGET_INVALID")
            plan = {"route": "KNOWLEDGE" if target == "NONE" else "MIXED", "subject": target}
            if "skillId" in body["text"]["format"]["schema"].get("properties", {}):
                plan["skillId"] = "NONE"
            result["output"][0]["content"][0]["text"] = json.dumps(plan)
    return result


def serve(folder, inp=None, out=None):
    inp, out = inp or sys.stdin.buffer, out or sys.stdout.buffer
    c = load(folder)
    supplier = controlled_supplier  # Default is always unpaid; a separate bound permit is mandatory otherwise.
    meta=json.loads((Path(folder)/'session.json').read_text(encoding='utf8'))
    if meta['mode']=='PAID_EXPLICIT_V2':
        from ai_pilot_paid_sender import PaidSender,load_permit
        supplier=PaidSender(c,meta,lambda:load_permit(meta),source_fingerprint)
    def emit(value):
        out.write(canonical(value) + b"\n")
        out.flush()
    emit({"ready": True, "mode": meta['mode']})
    while True:
        raw = inp.readline(100001)
        if not raw:
            break
        if len(raw) > 100000 or not raw.endswith(b"\n"):
            c.stop()
            emit({"ok": False, "code": "V2_FRAME_LIMIT"})
            break
        try:
            frame = json.loads(raw)
            result = handle(c, frame, supplier)
            emit({"ok": True, "result": result})
        except Exception as error:
            code = str(error)
            if not code.startswith("V2_") or not code.replace("_", "").isalnum():
                code = "V2_PROTOCOL_FAILURE"
            emit({"ok": False, "code": code})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", required=True)
    parser.add_argument("--prepare-unpaid", action="store_true")
    parser.add_argument("--fixture")
    args = parser.parse_args()
    if args.prepare_unpaid:
        need(args.fixture is not None, "V2_FIXTURE_REQUIRED")
        meta = prepare_unpaid(args.run, json.loads(Path(args.fixture).read_text(encoding="utf-8")))
        print(json.dumps({"prepared": True, "mode": meta["mode"], "paidCalls": 0}))
    else:
        need(args.fixture is None, "V2_ARGUMENTS")
        try:serve(args.run)
        except Exception as error:
            import re
            code=str(error)
            print(code if re.fullmatch(r'V2_[A-Z0-9_]+',code) else 'V2_STARTUP_FAILURE',file=sys.stderr)
            raise SystemExit(1)
