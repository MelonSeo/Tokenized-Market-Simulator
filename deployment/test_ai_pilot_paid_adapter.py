"""Private protocol/adapter tests only; no Docker, key, provider or approval activation."""
import json
import os
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import Mock,patch
from ai_pilot_paid_adapter import ServerControl,FrameReader,BrowserAdapter,SpringAdapter,private_write
import ai_pilot_paid_launcher as launch


class AdapterTests(unittest.TestCase):
    def test_frozen_readonly_anvil_profile_rejects_old_cap_pruning_and_fast_mining(self):
        value={'HostConfig':{'Memory':1024*1024**2,'MemorySwap':1024*1024**2},
               'Config':{'Cmd':['--no-mining','--preserve-historical-states']}}
        launch.require_frozen_anvil_profile(value)
        self.assertEqual(launch.frozen_anvil_resources(),['--memory=1024m','--memory-swap=1024m'])
        import copy
        for change in ('old-cap','swap','fast','pruned','missing-history'):
            item=copy.deepcopy(value)
            if change=='old-cap':item['HostConfig']['Memory']=384*1024**2
            elif change=='swap':item['HostConfig']['MemorySwap']=4096*1024**2
            elif change=='fast':item['Config']['Cmd']+=['--block-time','1']
            elif change=='pruned':item['Config']['Cmd'].append('--prune-history')
            else:item['Config']['Cmd'].remove('--preserve-historical-states')
            with self.subTest(change=change),self.assertRaisesRegex(ValueError,'V2_PAID_FROZEN_ANVIL_PROFILE_REQUIRED'):
                launch.require_frozen_anvil_profile(item)
        value['Config']['Cmd']=['-c','exec anvil --no-mining --preserve-historical-states --quiet']
        launch.require_frozen_anvil_profile(value)
    def test_legacy_anvil_profile_stops_before_pg_or_container_start(self):
        meta={'owner':'test','names':{'anvil':'old-anvil'},'images':{'anvil':'old-image'}}
        with patch.object(launch,'owned',return_value={'HostConfig':{'Memory':384*1024**2}}),patch.object(launch,'cmd') as cmd:
            with self.assertRaisesRegex(ValueError,'V2_PAID_FROZEN_ANVIL_PROFILE_REQUIRED'):launch.start_data(Path('.'),meta)
            cmd.assert_not_called()
    def test_reviewed23_parent_is_explicitly_scoped_before_container_creation(self):
        class AcceptedBeforeDocker(Exception):pass
        with tempfile.TemporaryDirectory() as tmp,patch.object(launch,'create',side_effect=AcceptedBeforeDocker) as create:
            owner='pilot-paid-'+'a'*16;scope=launch.ROOT/'deployment/runtime'/owner
            meta={'owner':owner,'images':{'app':'controlled'},'names':{},'parentSession':str(scope/'continuation-3e9742bfa5d17456/session')}
            with self.assertRaises(AcceptedBeforeDocker):launch.app(Path(tmp),Path(tmp),meta,'controlled','CONTINUATION_PLAN',Mock())
            self.assertEqual(create.call_count,1);create.reset_mock()
            for p in (scope/'continuation-ffffffffffffffff/session',scope.parent/'another-owner/session',scope/'continuation-3e9742bfa5d17456/other'):
                meta['parentSession']=str(p)
                with self.assertRaisesRegex(ValueError,'V2_CARRY_PARENT_SCOPE'):launch.app(Path(tmp),Path(tmp),meta,'controlled','CONTINUATION_PLAN',Mock())
            create.assert_not_called()
    def test_semantic_quality_failure_needs_explicit_safe_classification(self):
        with tempfile.TemporaryDirectory() as tmp,patch('ai_pilot_review.ReviewGate') as gate:
            obj=SpringAdapter.__new__(SpringAdapter);obj.folder=Path(tmp);obj.mode='PAID_EXPLICIT_V2';obj.dom_pending=False
            obj.review_desk=Mock()
            obj.server=Mock(return_value={'transportVerified':True});gate.return_value.wait.return_value=False
            gate.return_value.safe_quality_failure=False
            self.assertFalse(obj.review_reply({'case':'A2'},{}));self.assertFalse(obj.review_failure_can_continue({},{}))
            gate.return_value.safe_quality_failure=True
            self.assertFalse(obj.review_reply({'case':'A2'},{}));self.assertTrue(obj.review_failure_can_continue({},{}))
    def test_reviewed_synthesis_fail_is_not_promoted_to_quality_pass(self):
        with tempfile.TemporaryDirectory() as tmp,patch('ai_pilot_review.ReviewGate') as gate:
            obj=SpringAdapter.__new__(SpringAdapter);obj.folder=Path(tmp);obj.mode='PAID_EXPLICIT_V2';obj.dom_pending=False
            obj.review_desk=Mock()
            obj.server=Mock(return_value={'transportVerified':False,'quality':'FAIL','safeReviewedContinuation':True})
            gate.return_value.wait.return_value=False
            self.assertFalse(obj.review_reply({'case':'PC4b'},{}));self.assertTrue(obj.review_failure_can_continue({},{}))
            self.assertEqual(gate.call_args.args[1]['transportVerification']['quality'],'FAIL')
            gate.return_value.wait.return_value=True
            with self.assertRaisesRegex(ValueError,'V2_FAILED_QUALITY_CANNOT_BECOME_PASS'):obj.review_reply({'case':'PC4b'}, {})
            self.assertFalse(obj.reviewed_failure)
    def test_host_guard_denial_reaches_inner_without_granting_permission(self):
        from product_read_resources import ResourceError
        import ai_pilot_paid_inner as inner
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp);observer=Mock();observer.sample.side_effect=ResourceError('PRODUCT_READ_RESOURCES_RAM_LOW')
            self.assertFalse(launch.browser_admission_reply(p,observer))
            self.assertEqual(json.loads((p/'browser-host-admission.json').read_text()),{'allowed':False})
            observer.sample.assert_called_once_with('BEFORE_BROWSER',True)
            with patch.object(inner,'RUN',p):
                with self.assertRaisesRegex(ValueError,'V2_PAID_HOST_RESOURCE_BLOCKED'):inner.host_admission()

    def test_successful_host_guard_requires_enforced_sample(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp);observer=Mock()
            self.assertTrue(launch.browser_admission_reply(p,observer))
            observer.sample.assert_called_once_with('BEFORE_BROWSER',True)
            self.assertEqual(json.loads((p/'browser-host-admission.json').read_text()),{'allowed':True})

    def test_memory_capacity_is_not_obtained_by_lowering_guard_or_increasing_jvm_heap(self):
        from ai_pilot_preflight import ROOT
        source=(ROOT/'deployment/ai_pilot_paid_launcher.py').read_text()
        adapter=(ROOT/'deployment/ai_pilot_paid_adapter.py').read_text()
        inner=(ROOT/'deployment/ai_pilot_paid_inner.py').read_text()
        self.assertIn('--memory=3072m',source);self.assertIn('1920*1024**2',adapter)
        self.assertIn('-Xmx384m',inner);self.assertIn('-XX:MaxMetaspaceSize=256m',inner)
    def test_paid_private_deadlines_cover_review_pacing_and_total_without_product_change(self):
        from ai_pilot_paid_adapter import BOUNDS
        self.assertGreater(BOUNDS['paidAfterMs'],BOUNDS['serverVerifyMs']+BOUNDS['semanticReviewMs']+20000)
        self.assertGreater(BOUNDS['paidBeforeMs'],BOUNDS['coordinatorPacingMs']+BOUNDS['serverPacingMs']+20000)
        self.assertGreater(BOUNDS['paidWatchdogMs'],6*(BOUNDS['paidAfterMs']+BOUNDS['paidBeforeMs']+110000)+600000)
        self.assertLess(BOUNDS['paidWatchdogMs'],BOUNDS['supervisorMs'])
        self.assertEqual(BOUNDS['unpaidAfterMs'],10000)

    def test_review_ack_occurs_before_next_request_pacing(self):
        events=[];obj=SpringAdapter.__new__(SpringAdapter);obj.dom_pending=True;obj.browser=Mock()
        obj._review_reply=lambda *args:events.append('review') or True
        obj.browser.review_complete.side_effect=lambda ok:events.append(('ack',ok))
        self.assertTrue(obj.review_reply({},{}));events.append('next-pacing')
        self.assertEqual(events,['review',('ack',True),'next-pacing']);self.assertFalse(obj.dom_pending)

    def test_review_failure_explicitly_rejects_browser_without_replay(self):
        obj=SpringAdapter.__new__(SpringAdapter);obj.dom_pending=True;obj.browser=Mock()
        obj._review_reply=Mock(side_effect=ValueError('CONTROLLED_REJECT'))
        with self.assertRaises(ValueError):obj.review_reply({},{})
        obj.browser.review_complete.assert_called_once_with(False)

    def test_broken_browser_cleanup_does_not_skip_stop_java_or_primary_evidence(self):
        import ai_pilot_paid_inner as inner
        with tempfile.TemporaryDirectory() as tmp,patch.object(inner,'RUN',Path(tmp)):
            session=Path(tmp)/'session';session.mkdir();browser=Mock();browser.close.side_effect=BrokenPipeError('CONTROLLED')
            child=Mock(returncode=0);child.poll.return_value=None;server=Mock();log=Mock()
            result=inner.finalize_execution(browser,child,server,session,'PROBE',log,False,primary_present=True)
            self.assertEqual(result['browser'],'BrokenPipeError');self.assertTrue((session/'STOP').exists())
            server.assert_called_once_with('shutdown');child.wait.assert_called_once();log.close.assert_called_once()
            self.assertTrue(json.loads((Path(tmp)/'inner-cleanup.json').read_text())['primaryFailurePreserved'])

    def test_relay_connect_or_start_failure_still_cleans_all_owned_resources(self):
        for fail in ('connect','start'):
            with self.subTest(fail=fail),tempfile.TemporaryDirectory() as tmp:
                p=Path(tmp);meta={'owner':'pilot-paid-'+'a'*16,'images':{'app':'image','pg':'pg','anvil':'anvil'},
                    'names':{'internal':'net','pg':'owned-pg','anvil':'owned-anvil'}}
                def command(args,*unused):
                    if fail in args:raise OSError('CONTROLLED')
                    return ''
                with patch.object(launch,'cmd',side_effect=command),patch.object(launch,'create'),patch.object(launch,'cleanup_owned',return_value={}) as cleanup,patch.object(launch,'app') as app:
                    with self.assertRaises(OSError):launch.run_paid_infrastructure(p,p,meta,Mock(),Path('/NEVER_OPENED'),'10.254.0.0/28')
                    cleanup.assert_called_once();self.assertEqual(len(cleanup.call_args.args[1]),3);app.assert_not_called()

    def test_successful_paid_body_with_uncertain_cleanup_is_not_cli_success(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp);meta={'owner':'pilot-paid-'+'a'*16,'images':{'app':'image','pg':'pg','anvil':'anvil'},
                'names':{'internal':'net','pg':'owned-pg','anvil':'owned-anvil'}}
            with patch.object(launch,'cmd'),patch.object(launch,'create'),patch.object(launch,'start_data'),patch.object(launch,'app'),patch.object(launch,'cleanup_owned',return_value={'relay':'STOP_UNVERIFIED'}):
                with self.assertRaisesRegex(ValueError,'V2_PAID_CLEANUP_UNVERIFIED'):
                    launch.run_paid_infrastructure(p,p,meta,Mock(),Path('/NEVER_OPENED'),'10.254.0.0/28')

    def test_private_publication_is_atomic_and_does_not_overwrite_evidence(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/'proof.json';private_write(p,{'one':1})
            self.assertEqual(json.loads(p.read_text()),{'one':1})
            self.assertEqual(list(Path(tmp).glob('*.partial')),[])
            with self.assertRaisesRegex(ValueError,'V2_PRIVATE_EVIDENCE_EXISTS'):private_write(p,{'two':2})
            self.assertEqual(json.loads(p.read_text()),{'one':1})
    def test_control_private_request_reply_roundtrip(self):
        with tempfile.TemporaryDirectory() as tmp:
            d=Path(tmp)/'control';d.mkdir()
            def server():
                import time
                while not list(d.glob('request-*.json')):time.sleep(.005)
                f=next(d.glob('request-*.json'));r=json.loads(f.read_text());assert r=={'op':'fingerprint'}
                private_write(d/f.name.replace('request-','reply-'),{'ok':True,'value':{'db':'a'*64,'c':'b'*64}})
            t=threading.Thread(target=server);t.start()
            self.assertEqual(ServerControl(tmp)('fingerprint'),{'db':'a'*64,'c':'b'*64});t.join(2)
            with self.assertRaisesRegex(ValueError,'V2_SERVER_CONTROL_OPERATION'):ServerControl(tmp)('trade')

    @unittest.skipIf(os.name=='nt','selectors on Windows do not support anonymous pipes; exercised on Linux probe')
    def test_browser_frames_are_bounded_and_eof_is_not_a_success(self):
        a,b=os.pipe()
        with os.fdopen(a,'rb') as incoming,os.fdopen(b,'wb') as outgoing:
            r=FrameReader(incoming)
            try:
                outgoing.write(b'{"op":"ready"}\n');outgoing.flush();self.assertEqual(r.read(.1),{'op':'ready'})
                with self.assertRaisesRegex(ValueError,'V2_BROWSER_PROTOCOL_DEADLINE'):r.read(.01)
            finally:r.close()

    def test_browser_request_body_cannot_be_substituted_by_adapter(self):
        b=BrowserAdapter('.',{},host_admission=Mock());b.child=Mock();b.reader=Mock()
        b.reader.read.return_value={'op':'before','actor':'other','body':{'question':'changed'}}
        with self.assertRaisesRegex(ValueError,'V2_BROWSER_REQUEST_CHANGED'):b.web_submit('owner',{'question':'frozen'})
        b.child.stdin.write.assert_not_called()

    def test_browser_after_ack_is_deferred_until_caller_finishes_review(self):
        b=BrowserAdapter('.',{},host_admission=Mock());b.child=Mock();b.reader=Mock()
        body={'question':'frozen'};reply={'runId':'controlled'}
        b.reader.read.side_effect=[{'op':'before','actor':'owner','body':body,'slot':0},
                                   {'op':'after','slot':0,'reply':{'http':200,'body':reply}}]
        self.assertEqual(b.web_submit('owner',body),(200,reply))
        self.assertTrue(b.awaiting_ack);self.assertEqual(b.child.stdin.write.call_count,1)

    def test_six_frame_adapter_keeps_two_actor_order_and_explicit_review_ack(self):
        b=BrowserAdapter('.',{},host_admission=Mock());b.child=Mock();b.reader=Mock();frames=[]
        actors=['owner']*3+['other']*3
        for slot,actor in enumerate(actors):
            frames.extend([{'op':'before','actor':actor,'body':{'question':'CONTROLLED_PROTOCOL_DOUBLE'},'slot':slot},
                           {'op':'after','slot':slot,'reply':{'http':200,'body':{'runId':str(slot)}}}])
        b.reader.read.side_effect=frames
        for slot,actor in enumerate(actors):
            self.assertEqual(b.web_submit(actor,{'question':'CONTROLLED_PROTOCOL_DOUBLE'})[1]['runId'],str(slot))
            b.review_complete(True)
        self.assertEqual(b.submitted,6);self.assertEqual(b.child.stdin.write.call_count,12)
        self.assertFalse(b.awaiting_ack)

    def test_financial_fingerprint_does_not_ignore_c_vault(self):
        obj=SpringAdapter.__new__(SpringAdapter);obj.server=Mock(return_value={'db':'a'*64,'c':'c'*64});obj.baseline_c='b'*64
        with self.assertRaisesRegex(ValueError,'V2_C_FINANCIAL_FINGERPRINT_CHANGED'):obj.fingerprint()

    def test_local_settings_preserve_paid_jdbc_and_distinct_budget(self):
        s=launch.settings('pilot-paid-'+'a'*16,'FAKE_NOT_A_SECRET','PAID_EXPLICIT_V2','/work/deployment/runtime/test/session',
                          {'budget':'budget_paid','legacy9':'legacy_paid','pilot10':'pilot_paid'})
        self.assertEqual(len({s[k]['jdbcUrl'] for k in ('budget','legacy9','pilot10')}),3)
        self.assertTrue(all('127.0.0.1:5432/' in s[k]['jdbcUrl'] for k in ('budget','legacy9','pilot10')))
        with self.assertRaises(ValueError):launch.local_url('budget;drop','pilot-paid-'+'a'*16)
        owner='pilot-paid-'+'a'*16
        self.assertEqual(len(launch.local_url('x'*28,owner).split('/')[3].split('?')[0]),63)
        with self.assertRaisesRegex(ValueError,'V2_PAID_DATABASE_IDENTIFIER_LENGTH'):launch.local_url('x'*29,owner)
        self.assertLessEqual(len(launch.local_url('budget_p_'+'b'*16,owner).split('/')[3].split('?')[0]),63)

    def test_paid_without_approval_does_not_touch_key_reference_or_docker(self):
        with tempfile.TemporaryDirectory() as tmp,patch.object(launch,'cmd') as docker:
            with self.assertRaisesRegex(ValueError,'V2_PAID_APPROVAL_REQUIRED'):
                launch._paid(Path(tmp),{'owner':'pilot-paid-'+'a'*16},None,object())
            docker.assert_not_called()

    def test_readiness_without_actual_probe_evidence_cannot_be_enabled(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp);private_write(p/'approval-request.private.json',{'context':{'sourceHash':'a'*64}})
            with patch.object(launch,'get_owner',return_value=(p,{})),patch.object(launch,'cmd') as docker:
                with self.assertRaisesRegex(ValueError,'V2_PAID_CONTROLLED_PROBE_REQUIRED'):launch.readiness('CONTROLLED')
                docker.assert_not_called()

    def test_cleanup_continues_after_one_owned_stop_error(self):
        with patch.object(launch,'stop',side_effect=[OSError('CONTROLLED'), 'STOPPED']) as s:
            result=launch.cleanup_owned({'owner':'pilot-paid-'+'a'*16},[('one','image',1),('two','image',1)])
        self.assertEqual(s.call_count,2);self.assertEqual(result,{'two':'STOP_UNVERIFIED','one':'STOPPED'})

    def test_explicit_price_attestation_is_not_inferred_from_old_policy(self):
        p={'llmModel':'gpt-5.6-terra','embeddingModel':'text-embedding-3-small','inputNusdPerToken':2500,
           'outputNusdPerToken':12000,'embeddingNusdPerToken':20,'serviceTier':'default','regionalEndpoint':False,
           'verifiedAt':1000,'expiresAt':2000,'officialSources':['https://openai.com/api/pricing/'],'reviewer':'CONTROLLED_TEST_NOT_ACTUAL_PRICE_VERIFICATION'}
        self.assertTrue(launch.provider_check(p,{'expiresAt':1800},now=1500))
        with self.assertRaisesRegex(ValueError,'V2_PROVIDER_PRICE_CHECK_EXPIRED'):launch.provider_check(p,{'expiresAt':2100},now=1500)
        p['inputNusdPerToken']=9999
        with self.assertRaisesRegex(ValueError,'V2_PROVIDER_PRICE_OR_MODEL_MISMATCH'):launch.provider_check(p,{'expiresAt':1800},now=1500)


if __name__=='__main__':unittest.main()
