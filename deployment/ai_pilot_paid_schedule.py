"""Approval-bound coordinator for the existing frozen schedule, not a budget issuer.

The actual server/DOM adapter must provide evidence review and PG/fingerprint
callbacks. This runner does not synthesize answers, silently approve quality,
create fixtures, issue permits, fetch keys, or retry an interrupted phase.
"""
import json
import os
import uuid
from pathlib import Path

from ai_pilot_paid_sender import authorize, load_permit
from ai_pilot_channel import load, source_fingerprint
from ai_pilot_http_plan import PilotHttpPlan
from ai_pilot_preflight import need, canonical


class EvidenceAdapter:
    """Private durable response capture BEFORE assertions/review can fail.

    Never records request credentials, headers, arbitrary exception messages or
    stdout. Each new file is exclusive/0600 and bounded; failures stop the plan.
    """
    def __init__(self, adapter, folder):
        self.adapter,self.folder=adapter,Path(folder)
        need(self.folder.is_dir() and not self.folder.is_symlink(),'V2_PAID_EVIDENCE_FOLDER')

    def __getattr__(self,name):return getattr(self.adapter,name)

    def record(self,kind,value):
        raw=canonical({'kind':kind,'value':value})
        need(len(raw)<=2_100_000,'V2_PAID_EVIDENCE_LIMIT')
        path=self.folder/('paid-evidence-'+uuid.uuid4().hex+'.private.json')
        fd=os.open(path,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
        with os.fdopen(fd,'wb') as out:
            out.write(raw);out.flush();os.fsync(out.fileno())

    def request(self,*args):
        result=self.adapter.request(*args);self.record('HTTP_REPLY',result);return result

    def index(self,*args):
        result=self.adapter.index(*args);self.record('INDEX_REPLY',result);return result

    def web_submit(self,*args):
        result=self.adapter.web_submit(*args);self.record('DOM_REPLY',result);return result

    def review(self,row,reply):
        try:
            result=self.adapter.review(row,reply)
        except BaseException:
            self.record('REVIEW_EXCEPTION',{'phase':row['phase'],'case':row['case']})
            raise
        self.record('REVIEW_DECISION',{'phase':row['phase'],'case':row['case'],'accepted':result is True})
        return result


class PaidSchedule:
    def __init__(self, coordinator, meta, permit, current_source):
        authorize(meta, permit, current_source=current_source())
        identity = json.loads(coordinator.identity)
        need(identity['mode']=='PAID_V2' and identity['allocation']==meta['allocation']
             and identity['source']==meta['source'] and identity['fixture']==meta['fixtureHash'], 'V2_PAID_SCHEDULE_IDENTITY')
        self.c, self.meta, self.permit, self.current_source = coordinator, meta, permit, current_source
        self.plan = PilotHttpPlan(coordinator, meta['fixture'])

    def run(self, adapter):
        # Every result needs real evidence review; HTTP200/PARTIAL is never a quality oracle.
        for name in ('review','budget_snapshot','financial_fingerprint','web_submit','index','request','control','sleep','index_server_request'):
            need(callable(getattr(adapter,name,None)), 'V2_PAID_ADAPTER_INCOMPLETE')
        adapter=EvidenceAdapter(adapter,self.meta['folder'])
        results=[]
        try:
            with self.c.db() as db:
                self.c.verify(db)
                need(db.execute('SELECT count(*) FROM submissions').fetchone()[0]==0,
                     'V2_PAID_AUTOMATIC_RESTART_FORBIDDEN')
            for phase in self.c.plan['phases']:
                authorize(self.meta,self.permit,current_source=self.current_source())
                if phase['id']=='EMBEDDING_HEADROOM':
                    # Reservation headroom is not an instruction to buy one extra call.
                    self.c.advance(evidence_reviewed=True)
                    break
                if phase['id'].startswith('INDEX_'):
                    results.append(self.plan.run_index(phase['id'],adapter))
                else:
                    results.extend(self.plan.run_requests(phase['id'],adapter))
            return {'transport':'COMPLETED_REQUIRES_FINAL_QUALITY_REVIEW','results':results,
                    'humanSatisfaction':'NOT_RUN','operatingSla':'NOT_RUN'}
        except BaseException:
            # Never reset the phase, UNKNOWN reservation, approval or old STOP.
            self.c.stop()
            raise


def from_session(folder):
    folder=Path(folder)
    meta=json.loads((folder/'session.json').read_text(encoding='utf8'))
    if 'target13' in meta:
        from ai_pilot_target13 import TargetSchedule
        return TargetSchedule(load(folder),meta,load_permit(meta),source_fingerprint)
    if 'continuation' in meta:
        from ai_pilot_continuation import ContinuationSchedule
        return ContinuationSchedule(load(folder),meta,load_permit(meta),source_fingerprint)
    return PaidSchedule(load(folder),meta,load_permit(meta),source_fingerprint)
