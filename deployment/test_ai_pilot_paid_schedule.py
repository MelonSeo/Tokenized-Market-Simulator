"""In-memory permit and controlled server/PG doubles. Never a paid or HTTP acceptance."""
import hashlib
import json
import tempfile
import time
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

from ai_pilot_paid_sender import context
from ai_pilot_preflight import canonical
from ai_pilot_coordinator import PilotCoordinator
from ai_pilot_paid_schedule import PaidSchedule
from test_ai_pilot_coordinator import fixture
from test_ai_pilot_http_plan import ControlledAdapter


class ScheduleTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        p=patch('ai_pilot_paid_sender.sys.platform','linux');p.start();self.addCleanup(p.stop)
        binding=fixture(); binding['mode']='REAL_ISOLATED' # Memory double, never a real fixture claim.
        self.meta={'allocation':str(uuid.uuid4()),'mode':'PAID_EXPLICIT_V2','source':'a'*64,
                   'fixture':binding,'fixtureHash':hashlib.sha256(canonical(binding)).hexdigest(),
                   'budgetAuthority':'b'*64,'folder':self.tmp.name,'approvalId':'tms_pilot_v2_'+'c'*32}
        now=int(time.time())
        self.permit={**context(self.meta),'approvalId':self.meta['approvalId'],'paidApproved':True,
                     'approvedAt':now-1,'expiresAt':now+3600,'keyFile':str(Path(self.tmp.name).resolve()/'NEVER_OPENED')}
        self.meta['permitHash']=hashlib.sha256(canonical(self.permit)).hexdigest()
        class Clock:
            value=1000000.
            def __call__(self):return self.value
        self.c=PilotCoordinator(Path(self.tmp.name)/'pilot-v2.sqlite',allocation=self.meta['allocation'],
                  source_hash='a'*64,fixture=binding,mode='PAID_V2',clock=Clock(),create=True)
        self.adapter=ControlledAdapter(self.c,binding)

    def runner(self):return PaidSchedule(self.c,self.meta,self.permit,lambda:'a'*64)

    def test_all_fixed_phases_share_one_allocation_and_exact_six_dom_adapter_requests(self):
        result=self.runner().run(self.adapter)
        self.assertEqual(len(self.adapter.requests),57)
        self.assertEqual([a for a,_ in self.adapter.web],['owner']*3+['other']*3)
        self.assertEqual(len(result['results']),59) # 51 HTTP + 6 DOM + 2 index results.
        self.assertTrue(self.c.summary()['stopped'])
        self.assertTrue(self.c.reconcile(self.adapter.pg)['matching'])
        self.assertLessEqual(self.c.summary()['llmCalls'],92)
        self.assertEqual(self.c.summary()['embeddingCalls'],68)
        self.assertEqual(result['humanSatisfaction'],'NOT_RUN')

    def test_invalid_permit_prevents_index_and_http(self):
        self.permit['paidApproved']=False
        with self.assertRaisesRegex(ValueError,'V2_PAID_APPROVAL_REQUIRED'):self.runner()
        self.assertEqual(self.adapter.requests,[]);self.assertEqual(self.adapter.pg,[])

    def test_failed_evidence_review_stops_and_does_not_execute_following_requests(self):
        self.adapter.review_allowed=False
        with self.assertRaisesRegex(ValueError,'V2_EVIDENCE_REVIEW_REJECTED'):self.runner().run(self.adapter)
        self.assertEqual(len(self.adapter.requests),1)
        self.assertTrue(self.c.summary()['stopped'])
        self.assertEqual(self.adapter.web,[])
        evidence=[json.loads(p.read_text()) for p in Path(self.tmp.name).glob('paid-evidence-*.private.json')]
        self.assertIn('HTTP_REPLY',[e['kind'] for e in evidence])
        self.assertEqual([e['value']['accepted'] for e in evidence if e['kind']=='REVIEW_DECISION'],[False])

    def test_http_assertion_failure_keeps_the_failed_reply_before_stopping(self):
        original=self.adapter.request
        def bad(*args):
            _,reply=original(*args)
            return 503,reply
        self.adapter.request=bad
        with self.assertRaisesRegex(ValueError,'V2_HTTP_EXPECTATION_FAILED'):self.runner().run(self.adapter)
        replies=[json.loads(p.read_text())['value'] for p in Path(self.tmp.name).glob('paid-evidence-*.private.json')
                 if json.loads(p.read_text())['kind']=='HTTP_REPLY']
        self.assertEqual(len(replies),1);self.assertEqual(replies[0][0],503)
        self.assertTrue(self.c.summary()['stopped'])

    def test_restarting_incomplete_phase_is_not_an_automatic_replay(self):
        self.c.claim('INDEX_LEGACY')
        with self.assertRaisesRegex(ValueError,'V2_PAID_AUTOMATIC_RESTART_FORBIDDEN'):self.runner().run(self.adapter)
        self.assertEqual(self.adapter.pg,[]);self.assertTrue(self.c.summary()['stopped'])

    def test_source_drift_after_first_phase_halts_before_next_http(self):
        source=iter(['a'*64,'a'*64,'d'*64])
        r=PaidSchedule(self.c,self.meta,self.permit,lambda:next(source))
        with self.assertRaisesRegex(ValueError,'V2_PAID_SOURCE_DRIFT'):r.run(self.adapter)
        self.assertEqual(self.adapter.requests,[]);self.assertTrue(self.c.summary()['stopped'])


if __name__=='__main__':unittest.main()
