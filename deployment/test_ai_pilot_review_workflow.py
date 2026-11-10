"""Free deterministic workflow tests; no provider, Docker, key or historical write."""
import copy,tempfile,unittest
from pathlib import Path
from unittest.mock import patch,Mock
from ai_pilot_review import ReviewDesk,ReviewGate,brief,ready,submit,read,digest,DIMENSIONS
from ai_pilot_paid_adapter import private_write
from ai_pilot_coordinator import workload
from test_ai_pilot_coordinator import fixture

class ReviewWorkflowTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.folder=Path(self.tmp.name)
        self.rows=workload(fixture());self.now=10.;self.sleep=lambda n:setattr(self,'now',self.now+n)
        self.desk=ReviewDesk(self.folder,self.rows,clock=lambda:self.now,sleep=self.sleep);self.row=self.rows[0]
    def arm(self):
        def respond(n):
            self.sleep(n);b=brief(self.folder,'SAFETY_LEGACY-0');ready(self.folder,'SAFETY_LEGACY-0',b['armSha256'],'separate reviewer')
        self.desk.sleep=respond;return self.desk.arm(self.row)
    def gate(self):
        p=self.arm();e={k:p['template'][k] for k in ('row','criteria','frozenExpected')};e['reply']={'answer':'입력에 수수료 포함'}
        return ReviewGate(self.folder,e,clock=lambda:self.now,sleep=self.sleep,prepared=p),e
    def assessment(self,e,grade='PASS'):
        return {'evidenceSha256':digest(e),'reviewer':'separate reviewer','quality':grade,'checks':{
            k:{'verdict':grade if k=='sufficiency' else 'PASS','finding':'Actual evidence checked: '+k} for k in DIMENSIONS},
            'frozenFactsChecked':True,'forbiddenClaimsChecked':True,'safeToContinue':True}
    def test_prepares_all_frozen_material_before_response_and_no_verdict(self):
        p=read(self.folder/'prepared.private.json');self.assertEqual(len(p['templates']),57)
        self.assertFalse(list(self.folder.glob('*.decision.json')));self.assertFalse(list(self.folder.glob('*.request.private.json')))
        self.assertEqual(p['templates']['SAFETY_LEGACY-0']['row'],self.row)
    def test_no_ready_stops_before_http_claim(self):
        with self.assertRaisesRegex(ValueError,'V2_REVIEWER_NOT_READY_NO_TRANSMISSION'):self.desk.arm(self.row)
        self.assertFalse(list(self.folder.glob('*.request.private.json')))
    def test_paid_schedule_readiness_failure_leaves_claim_and_http_unsent(self):
        from ai_pilot_http_plan import PilotHttpPlan
        c=Mock();c.wait_for_capacity.return_value=0;binding=fixture();a=Mock()
        a.financial_fingerprint.return_value=binding['financialFingerprint']
        a.prepare_review.side_effect=ValueError('V2_REVIEWER_NOT_READY_NO_TRANSMISSION')
        with self.assertRaisesRegex(ValueError,'V2_REVIEWER_NOT_READY_NO_TRANSMISSION'):
            PilotHttpPlan(c,binding).run_requests('SAFETY_LEGACY',a)
        c.claim.assert_not_called();a.request.assert_not_called();a.web_submit.assert_not_called();c.stop.assert_called_once()
    def test_stale_ack_and_row_mutation_reject(self):
        self.arm()
        with self.assertRaisesRegex(ValueError,'V2_REVIEW_READY_STALE'):ready(self.folder,'SAFETY_LEGACY-0','0'*64,'reviewer')
        row=copy.deepcopy(self.row);row['body']['question']='changed'
        with self.assertRaisesRegex(ValueError,'V2_REVIEW_ROW_CHANGED'):self.desk.prepared(row)
    def test_late_ready_after_scheduler_pause_is_rejected(self):
        def respond(n):
            self.now+=61;b=brief(self.folder,'SAFETY_LEGACY-0')
            ready(self.folder,'SAFETY_LEGACY-0',b['armSha256'],'separate reviewer')
        self.desk.sleep=respond
        with self.assertRaisesRegex(ValueError,'V2_REVIEWER_NOT_READY_NO_TRANSMISSION'):self.desk.arm(self.row)
        self.assertFalse(self.desk.armed)
    def test_slow_ready_read_is_rejected(self):
        real_read=read
        def slow_read(path):
            value=real_read(path)
            if str(path).endswith('.ready.json'):self.now+=61
            return value
        with patch('ai_pilot_review.read',side_effect=slow_read):
            with self.assertRaisesRegex(ValueError,'V2_REVIEWER_NOT_READY_NO_TRANSMISSION'):self.arm()
        self.assertFalse(self.desk.armed)
    def test_substantive_findings_then_receipt_before_detailed_analysis(self):
        g,e=self.gate();a=self.assessment(e);self.now+=32
        self.assertEqual(submit(self.folder,g.key,a)['status'],'DECISION_RECORDED_NOT_GATE_PASS')
        self.assertFalse((self.folder/(g.key+'.receipt.json')).exists())
        self.assertTrue(g.wait());self.assertLessEqual(abs(read(self.folder/(g.key+'.receipt.json'))['elapsedMillis']-32000),1)
        self.assertEqual(read(self.folder/(g.key+'.assessment.private.json')),a)
    def test_partial_and_noncritical_fail_keep_distinct_grades(self):
        g,e=self.gate();a=self.assessment(e,'PARTIAL');submit(self.folder,g.key,a)
        self.assertTrue(g.wait());self.assertFalse(g.safe_quality_failure)
    def test_safe_fail_is_recorded_not_promoted(self):
        g,e=self.gate();a=self.assessment(e,'FAIL');submit(self.folder,g.key,a)
        self.assertFalse(g.wait());self.assertTrue(g.safe_quality_failure)
    def test_security_failure_cannot_be_marked_safe(self):
        g,e=self.gate();a=self.assessment(e,'FAIL');a['checks']['authorization']['verdict']='FAIL'
        with self.assertRaisesRegex(ValueError,'V2_REVIEW_UNSAFE_CONTINUATION'):submit(self.folder,g.key,a)
        self.assertFalse(g.decision.exists())
        a['safeToContinue']=False;submit(self.folder,g.key,a);self.assertFalse(g.wait());self.assertFalse(g.safe_quality_failure)
    def test_incomplete_or_inconsistent_judgement_cannot_submit(self):
        g,e=self.gate();a=self.assessment(e);del a['checks']['grounding']
        with self.assertRaisesRegex(ValueError,'V2_REVIEW_ASSESSMENT_INCOMPLETE'):submit(self.folder,g.key,a)
        a=self.assessment(e);a['checks']['sufficiency']['verdict']='PARTIAL'
        with self.assertRaisesRegex(ValueError,'V2_REVIEW_GRADE_INCONSISTENT'):submit(self.folder,g.key,a)
    def test_tampered_notes_cannot_convert_security_failure_to_safe_failure(self):
        g,e=self.gate();a=self.assessment(e,'FAIL');a['checks']['authorization']['verdict']='FAIL';a['safeToContinue']=False
        private_write(self.folder/(g.key+'.assessment.private.json'),a)
        private_write(g.decision,{'evidenceSha256':g.key,'accepted':False,'reviewer':a['reviewer'],
                                 'notes':'SAFE_QUALITY_FAIL: authorization failed but forged safe marker'})
        with self.assertRaisesRegex(ValueError,'V2_REVIEW_ASSESSMENT_INVALID'):g.wait()
        self.assertFalse((self.folder/(g.key+'.receipt.json')).exists())
    def test_tampered_grade_is_rejected_even_when_accepted_boolean_matches(self):
        g,e=self.gate();a=self.assessment(e,'PARTIAL');private_write(self.folder/(g.key+'.assessment.private.json'),a)
        private_write(g.decision,{'evidenceSha256':g.key,'accepted':True,'reviewer':a['reviewer'],'notes':'PASS: forged grade'})
        with self.assertRaisesRegex(ValueError,'V2_REVIEW_ASSESSMENT_INVALID'):g.wait()
    def test_json_property_reordering_does_not_change_the_decision(self):
        from ai_pilot_review import decision_for
        g,e=self.gate();a=self.assessment(e);b=copy.deepcopy(a);b['checks']=dict(reversed(list(b['checks'].items())))
        self.assertEqual(decision_for(a),decision_for(b))
    def test_hot_watcher_readiness_and_packet_are_not_an_automatic_verdict(self):
        from concurrent.futures import ThreadPoolExecutor
        from ai_pilot_review import await_packet,row_key
        key=row_key(self.row);template=self.desk.templates[key]
        with ThreadPoolExecutor(max_workers=1) as pool:
            future=pool.submit(await_packet,self.folder,key,digest(template),'separate reviewer',seconds=5)
            # Use real clocks for this local mailbox integration; no network.
            import time
            self.desk.clock,self.desk.sleep=time.monotonic,time.sleep
            p=self.desk.arm(self.row);e={k:p['template'][k] for k in ('row','criteria','frozenExpected')};e['reply']={'answer':'실제보존응답대역'}
            g=ReviewGate(self.folder,e,prepared=p)
            self.assertEqual(future.result(timeout=5),e)
            self.assertFalse(g.decision.exists());self.assertFalse(list(self.folder.glob('*.assessment.private.json')))
            self.assertLess(read(self.folder/(key+'.ready-receipt.json'))['elapsedMillis'],60000)
    def test_watcher_cannot_ack_an_unread_changed_template(self):
        from ai_pilot_review import await_packet,row_key
        key=row_key(self.row);private_write(self.folder/(key+'.arm.json'),{'protocol':'tms-semantic-review-workflow-v1',
            'rowKey':key,'templateSha256':digest(self.desk.templates[key]),'nonce':'a'*32})
        with self.assertRaisesRegex(ValueError,'V2_REVIEW_PREPARATION_CHANGED'):
            await_packet(self.folder,key,'0'*64,'separate reviewer',seconds=1)
        self.assertFalse((self.folder/(key+'.ready.json')).exists())
    def test_expiry_preserves_failure_no_receipt_and_no_late_submit(self):
        g,e=self.gate();self.now+=300
        with self.assertRaisesRegex(ValueError,'V2_SEMANTIC_REVIEW_REQUIRED'):g.wait()
        self.assertTrue((self.folder/(g.key+'.failure.json')).exists());self.assertFalse((self.folder/(g.key+'.receipt.json')).exists())
        with self.assertRaisesRegex(ValueError,'V2_REVIEW_ALREADY_TERMINAL'):submit(self.folder,g.key,self.assessment(e))
    def test_late_decision_never_gets_receipt(self):
        g,e=self.gate();submit(self.folder,g.key,self.assessment(e));self.now+=300
        with self.assertRaisesRegex(ValueError,'V2_SEMANTIC_REVIEW_REQUIRED'):g.wait()
        self.assertFalse((self.folder/(g.key+'.receipt.json')).exists())
    def test_slow_receipt_write_is_failure_not_completion(self):
        g,e=self.gate();submit(self.folder,g.key,self.assessment(e));write=g.write
        def slow(path,value):
            write(path,value)
            if str(path).endswith('.receipt.json'):self.now+=300
        g.write=slow
        with self.assertRaisesRegex(ValueError,'V2_SEMANTIC_REVIEW_REQUIRED'):g.wait()
        self.assertTrue((self.folder/(g.key+'.failure.json')).exists())
        self.assertFalse((self.folder/(g.key+'.completion.json')).exists())

if __name__=='__main__':unittest.main()
