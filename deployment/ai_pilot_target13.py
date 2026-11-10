"""The explicitly approved post-57 target check. Same allocation, stricter subcap.

No key, network, approval issuance or Docker operations. Old evidence is read-only.
The original frozen questions/criteria/policy remain unchanged. No index is built.
"""
import hashlib
import json
import shutil
import sqlite3
import time
import re
from contextlib import closing
from pathlib import Path

from ai_pilot_coordinator import PilotCoordinator, workload, digest
from ai_pilot_continuation import read, sha, ro, row_hash
from ai_pilot_preflight import need, canonical
from ai_pilot_held_resume import HeldCoordinator

VERSION = 'tms-pilot-target13-v7'
CAPS = dict(llmCalls=14, embeddingCalls=11, llmInputTokens=458752,
            llmOutputTokens=22400, embeddingInputTokens=22528, reservedNusd=2000000000)


def target_rows(fixture):
    original = workload(fixture)
    rows = []
    for slot, case in enumerate(['PF1b'] * 4 + ['F3'] * 2 + ['C3', 'C4', 'C5', 'D3', 'E2']):
        source = next(r for r in original if r['case'] == case)
        row = {**source, 'phase': 'TARGET_HTTP', 'slot': slot, 'corpus': 'pilot10'}
        if case == 'F3' and slot == 5:
            # A separate expression, frozen before sending; the original F3 expectation is reused.
            row['body'] = {'question': '계정을 바꿨는데 방금 전 계정의 알림이 보여요. 지금 뭘 확인해야 하나요?'}
            row['bodySha256'] = digest(row['body'])
        rows.append(row)
    for slot, actor in enumerate(('owner', 'other')):
        source = next(r for r in original if r['phase'] == 'WEB_PILOT' and r['case'] == 'PF1b' and r['actor'] == actor)
        rows.append({**source, 'phase': 'TARGET_WEB', 'slot': slot, 'corpus': 'pilot10', 'web': True})
    need(len(rows) == 13, 'V7_SCOPE')
    return rows


def parent_summary(parent):
    """Exact completed allocation, including the one held UNKNOWN; never repair it."""
    parent = Path(parent)
    old = read(parent / 'session.json')
    need(old['mode'] == 'PAID_EXPLICIT_V2', 'V7_PARENT_MODE')
    with ro(parent / 'pilot-v2.sqlite') as db:
        ident = json.loads(db.execute('SELECT value FROM identity').fetchone()[0])
        need(ident['allocation'] == old['allocation'] and ident['source'] == old['source']
             and ident['fixture'] == old['fixtureHash'] and ident['mode'] == 'PAID_V2', 'V7_PARENT_IDENTITY')
        need(db.execute('SELECT stopped FROM control').fetchone() == (1,), 'V7_PARENT_NOT_COMPLETE')
        need(db.execute('SELECT count(*) FROM phases WHERE complete=0').fetchone()[0] == 0, 'V7_PARENT_NOT_COMPLETE')
        need(db.execute("SELECT count(*) FROM submissions WHERE phase NOT LIKE 'INDEX_%'").fetchone()[0] == 57,
             'V7_PARENT_SCOPE')
        totals = PilotCoordinator.totals(db)
        need(totals['llmCalls'] == 77 and totals['embeddingCalls'] == 54
             and totals['reservedNusd'] == 3653474240, 'V7_PARENT_TOTALS')
        unknown = db.execute("SELECT * FROM reservations WHERE outcome!='OBSERVED'").fetchall()
        need(unknown == [tuple(old['continuation']['heldReservation'])]
             and unknown[0][6] == 29060, 'V7_PARENT_UNKNOWN')
        return old, {'parentFiles': {n: sha(parent/n) for n in ('session.json', 'pilot-v2.sqlite')},
                     'stopSha256': sha(parent/'STOP') if (parent/'STOP').exists() else None,
                     'rowsSha256': row_hash(db), 'prior': totals,
                     'failedTicket': old['continuation']['failedTicket'], 'heldReservation': list(unknown[0])}


def verify_anchor(meta, parent=Path('/run/pilot-parent')):
    d = meta['target13']
    need(d['version'] == VERSION and d['caps'] == CAPS, 'V7_POLICY')
    old, summary = parent_summary(parent)
    need(all(d.get(k) == v for k, v in summary.items()), 'V7_PARENT_DRIFT')
    need(all(meta[k] == old[k] for k in ('allocation', 'approvalId', 'budgetAuthority', 'fixtureHash', 'fixture')),
         'V7_AUTHORITY_CHANGED')
    need(d['rows'] == target_rows(meta['fixture']), 'V7_ROWS_CHANGED')
    anchor = Path(meta['folder']).parent/'anchor'
    need(isinstance(d.get('childId'), str) and re.fullmatch('[0-9a-f]{16}', d['childId']), 'V7_CHILD_ID')
    claim = {'version': VERSION, 'allocation': meta['allocation'], 'source': meta['source'],
             'childId': d['childId'], 'target13Sha256': digest(d),
             'allocationPathSha256': hashlib.sha256(str(Path(meta['folder']).resolve()).encode()).hexdigest()}
    need(read(anchor/'single-target-claim.json') == claim
         and read(Path(parent)/'TARGET13_CLAIM.json') == claim, 'V7_SINGLE_CLAIM_REQUIRED')
    return summary


def make_meta(config, folder):
    from ai_pilot_channel import source_fingerprint
    old, summary = parent_summary('/run/pilot-parent')
    authority = hashlib.sha256((config['budget']['jdbcUrl']+'\n'+config['budget']['dbUser']).encode()).hexdigest()
    need(config['mode'] == 'PAID_EXPLICIT_V2' and authority == old['budgetAuthority'], 'V7_AUTHORITY_CHANGED')
    d = read(Path(folder)/'target13.json')
    need(all(d.get(k) == v for k, v in summary.items()) and d['rows'] == target_rows(old['fixture'])
         and d['caps'] == CAPS and d['version'] == VERSION, 'V7_PLAN_DRIFT')
    return {k: old[k] for k in ('allocation', 'mode', 'fixture', 'fixtureHash', 'budgetAuthority', 'approvalId')} | {
        'folder': config['run'], 'source': source_fingerprint(), 'target13': d}


def import_session(meta, permit_path, parent=Path('/run/pilot-parent')):
    from ai_pilot_paid_sender import authorize
    from ai_pilot_channel import source_fingerprint
    from ai_pilot_paid_adapter import private_write
    permit = read(permit_path)
    meta = {**meta, 'approvalFile': str(permit_path), 'permitHash': digest(permit)}
    authorize(meta, permit, current_source=source_fingerprint())
    folder = Path(meta['folder'])
    need(not folder.exists(), 'V7_NO_RESTART')
    folder.mkdir()
    shutil.copyfile(Path(parent)/'pilot-v2.sqlite', folder/'pilot-v2.sqlite')
    with closing(sqlite3.connect(folder/'pilot-v2.sqlite')) as db:
        with db:
            need(row_hash(db) == meta['target13']['rowsSha256'], 'V7_IMPORT_LOSS')
            identity = json.loads(db.execute('SELECT value FROM identity').fetchone()[0])
            identity['source'] = meta['source']
            db.execute('UPDATE identity SET value=?', (canonical(identity).decode(),))
            db.execute('UPDATE control SET phase=8,stopped=0')
            db.executemany('INSERT INTO phases(name) VALUES(?)', [('TARGET_HTTP',), ('TARGET_WEB',)])
            db.execute('CREATE TABLE target13_identity(value TEXT NOT NULL)')
            db.execute('INSERT INTO target13_identity VALUES(?)', (digest(meta['target13']),))
    private_write(folder/'session.json', meta)
    c = TargetCoordinator(folder/'pilot-v2.sqlite', meta=meta)
    need(c.summary()['reservedNusd'] == 3653474240, 'V7_IMPORT_TOTALS')
    return meta


class TargetCoordinator(HeldCoordinator):
    def __init__(self, path, *, meta, clock=time.time):
        self.meta = meta
        self.held = meta['target13']
        PilotCoordinator.__init__(self, path, allocation=meta['allocation'], source_hash=meta['source'],
                                  fixture=meta['fixture'], mode='PAID_V2', clock=clock)
        self.rows = self.held['rows']
        self.plan = {**self.plan, 'phases': self.plan['phases'] + [
            {'id': 'TARGET_HTTP', 'corpus': 'pilot10', 'requests': 11, 'llm': 14, 'embedding': 11},
            {'id': 'TARGET_WEB', 'corpus': 'pilot10', 'requests': 2, 'llm': 14, 'embedding': 11}]}

    def verify(self, c):
        super().verify(c)
        need(c.execute('SELECT value FROM target13_identity').fetchall() == [(digest(self.held),)], 'V7_POLICY_DRIFT')

    def additional_limit(self, c, kind, tokens, output, cost):
        need(tokens <= (32768 if kind == 'LLM' else 2048), 'V7_CALL_TOKEN_LIMIT')
        current = self.totals(c)
        delta = {k: current[k] - self.held['prior'][k] for k in CAPS}
        delta['llmCalls' if kind == 'LLM' else 'embeddingCalls'] += 1
        delta['llmInputTokens' if kind == 'LLM' else 'embeddingInputTokens'] += tokens
        delta['llmOutputTokens'] += output
        delta['reservedNusd'] += cost
        need(all(type(v) is int and 0 <= v <= CAPS[k] for k, v in delta.items()), 'V7_ADDITIONAL_CAP')


class TargetSchedule:
    def __init__(self, c, meta, permit, current_source):
        from ai_pilot_paid_sender import authorize
        authorize(meta, permit, current_source=current_source())
        self.c, self.meta, self.permit, self.current_source = c, meta, permit, current_source

    def run(self, adapter):
        from ai_pilot_paid_schedule import EvidenceAdapter
        from ai_pilot_http_plan import PilotHttpPlan
        verify_anchor(self.meta)
        with self.c.db() as db:
            need(db.execute("SELECT count(*) FROM submissions WHERE phase LIKE 'TARGET_%'").fetchone()[0] == 0,
                 'V7_NO_RESTART')
        need(self.c.reconcile(adapter.budget_snapshot())['matching'], 'V7_PG_MISMATCH')
        plan = PilotHttpPlan(self.c, self.meta['fixture'])
        plan.rows = self.c.rows
        output = []
        try:
            for phase in ('TARGET_HTTP', 'TARGET_WEB'):
                output.extend(plan.run_requests(phase, EvidenceAdapter(adapter, self.meta['folder'])))
            return {'transport': 'TARGET13_COMPLETED_REQUIRES_FINAL_QUALITY_REVIEW', 'results': output,
                    'original57Replayed': False, 'humanSatisfaction': 'NOT_RUN', 'operatingSla': 'NOT_RUN'}
        except BaseException:
            self.c.stop()
            raise
