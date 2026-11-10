"""Only synthetic temporary ledgers/permits and controlled senders; never paid activation."""
import copy,json,uuid,sqlite3,shutil,unittest,tempfile
from contextlib import closing
from pathlib import Path
from unittest.mock import patch,Mock
import ai_pilot_oom_resume as oom
import ai_pilot_review_resume as review
import ai_pilot_continuation as carry
import ai_pilot_held_resume as held
from ai_pilot_paid_adapter import private_write
from ai_pilot_paid_sender import context
from ai_pilot_review import DIMENSIONS
from test_ai_pilot_review_resume import ReviewResumeTests
from test_ai_pilot_http_plan import ControlledAdapter


class OomResumeTests(ReviewResumeTests):
    def setUp(self):
        super().setUp();previous=copy.deepcopy(self.proposal);self.history=review.history_from(self.parent)
        a=self.base/'anchor';a.mkdir();private_write(a/'prefix-twenty-three.json',self.bundle)
        for n in previous['executionFiles']:
            dest=a/'parent-execution'/n;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(self.base/n,dest)
        self.meta={**self.meta,'continuation':previous};(self.parent/'session.json').write_text(json.dumps(self.meta),encoding='utf8')
        frozen=carry.read(oom.ROOT/'backend/src/test/resources/ai/novel-30-v2.json')
        from ai_pilot_coordinator import workload
        rows=workload(self.old['fixture'])[23:32];tickets=[]
        for n in ('session','post-stop-db-linux-audit-r3','post-stop-pg-only-audit','resume-evidence'):(self.base/n).mkdir(exist_ok=True)
        with closing(sqlite3.connect(self.parent/'pilot-v2.sqlite')) as db, db:
            for i,row in enumerate(rows):
                ticket=str(uuid.uuid4());run=str(uuid.uuid4());tickets.append(ticket)
                db.execute('INSERT INTO submissions VALUES(?,?,?,?,?,1,1,?,1)',(ticket,row['phase'],row['slot'],row['uid'],row['bodySha256'],run))
                reply={'httpStatus':row['expectedHttp'],'runId':run,'status':'PARTIAL' if i==8 else 'ANSWERED','uncertainties':['SYNTHESIS_UNAVAILABLE'] if i==8 else []}
                e={'row':row,'reply':reply,'criteria':self.bundle[0]['evidence']['criteria'],'frozenExpected':next(c for c in frozen['cases'] if c['id']==row['case'])}
                grade=['FAIL','PARTIAL','PASS','PASS','FAIL','PASS','PASS','PARTIAL','NOT_REVIEWED'][i]
                cap='session/paid-evidence-'+uuid.uuid4().hex+'.private.json';private_write(self.base/cap,{'kind':'HTTP_REPLY','value':[row['expectedHttp'],reply]})
                item={'evidence':e,'quality':grade,'httpCapture':cap};key=held.digest(e)
                if i==8:item['serverVerification']='FAIL'
                else:
                    assessment={'evidenceSha256':key,'reviewer':'synthetic reviewer','quality':grade,'checks':{n:{'verdict':grade if n=='sufficiency' else 'PASS','finding':'Synthetic test finding '+n} for n in DIMENSIONS},'frozenFactsChecked':True,'forbiddenClaimsChecked':True,'safeToContinue':True}
                    r={'evidenceSha256':key,'deadlineMs':300000,'elapsedMillis':50000,'accepted':grade!='FAIL','reviewer':'synthetic reviewer'}
                    private_write(self.base/'reviews'/(key+'.request.private.json'),e)
                    private_write(self.base/'reviews'/(key+'.assessment.private.json'),assessment)
                    private_write(self.base/'reviews'/(key+'.receipt.json'),r)
                    private_write(self.base/'reviews'/(key+'.completion.json'),{'evidenceSha256':key,'deadlineMs':300000,'elapsedMillis':51000})
                    item['receipt']=r
                self.bundle.append(item)
            for i in range(20):
                db.execute('INSERT INTO reservations VALUES(?,?,?,?,?,?,?,?,?,?,?)',(str(uuid.uuid4()),tickets[i%9],'NOVEL30','LLM' if i<14 else 'EMBEDDING',1453,0,493196901 if i==0 else 1,'f'*64,'OBSERVED',1,0))
        private_write(self.base/'final-fingerprint.json',{'cStateUnchanged':'UNKNOWN'})
        private_write(self.base/'post-stop-db-linux-audit-r3/financial-db.json',{'baselineMatch':True})
        private_write(self.base/'post-stop-pg-only-audit/result.json',{'pgSqliteMatching':True,'unknownHeldUnchanged':True,'pgStopped':'STOPPED'})
        private_write(self.base/'carry-cleanup.json',{'node':'STOPPED'})
        proof=self.base/'resume-evidence/frozen-copy-proof.json'
        private_write(proof,{'status':'PASS_FROZEN_COPY_NOT_ORIGINAL_FINALITY','originalCFinal':'UNKNOWN_PRESERVED','sourceUnchanged':True,'restoredExit':0,'providerCalls':0,'keyReads':0,'restoredC':{'baselineMatch':True,'sourceGenesisAndHeadHeadersMatch':True}})
        self.termination={'originalCFinal':'UNKNOWN','originalFinalFingerprintSha256':carry.sha(self.base/'final-fingerprint.json'),'dbBaselineMatch':True,'resourcesStopped':True,'frozenCopyBaselineMatch':True,'frozenCopyCProofSha256':carry.sha(proof)}
        with carry.ro(self.parent/'pilot-v2.sqlite') as db:self.pg=[dict(zip(('id','kind','reserved','state'),r)) for r in db.execute('SELECT pg_id,kind,cost,outcome FROM reservations')]
        self.proposal=self.inspect32()

    def inspect32(self,**kw):
        return oom.inspect(self.parent,kw.get('bundle',self.bundle),kw.get('pg',self.pg),kw.get('termination',self.termination),kw.get('history',self.history))

    def child32(self,approved=True):
        child=self.base/'child32';child.mkdir();a=child/'anchor';a.mkdir();now=1000000000
        exception={**held.exception_context(self.proposal,'e'*64,'d'*16),'explicitExceptionApproved':approved,'approvedAt':now,'expiresAt':now+3600}
        for n,v in [(oom.PREFIX,self.bundle),('pg-rows.json',self.pg),('termination.json',self.termination),('history.json',self.history),('held-exception.json',exception)]:private_write(a/n,v)
        for n in self.proposal['executionFiles']:
            dest=a/'parent-execution'/n;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(self.base/n,dest)
        desc={**self.proposal,'childId':'d'*16,'anchorFiles':{p.name:carry.sha(p) for p in a.iterdir() if p.is_file()}}
        meta={**self.old,'source':'e'*64,'folder':str(child/'session'),'continuation':desc}
        private_write(self.parent/'CONTINUATION_CLAIM.json',{'childId':desc['childId'],'source':meta['source'],'allocation':meta['allocation'],'continuationSha256':held.digest(desc)})
        permit={**context(meta),'approvalId':meta['approvalId'],'paidApproved':True,'approvedAt':now,'expiresAt':now+3600,'keyFile':str(child/'NEVER_OPENED')}
        private_write(child/'permit.json',permit);return meta,child,permit

    def test_exact_32_totals_old_failures_and_tamper_denial(self):
        self.assertEqual((self.proposal['remainingRequests'],self.proposal['priorReservedNusd']),(25,1911453860))
        self.assertEqual(self.proposal['originalCFinal'],'UNKNOWN')
        for mutation in ('question','grade','f1','unknown','pg','history','late','copy'):
            with self.subTest(mutation=mutation):
                b=copy.deepcopy(self.bundle);p=copy.deepcopy(self.pg);h=copy.deepcopy(self.history);t=copy.deepcopy(self.termination)
                if mutation=='question':b[-2]['evidence']['row']['body']['question']='changed'
                elif mutation=='grade':b[-5]['quality']='PASS'
                elif mutation=='f1':b[-1]['quality']='PASS'
                elif mutation=='unknown':p[0]['state']='UNKNOWN'
                elif mutation=='pg':p[0]['reserved']+=1
                elif mutation=='history':h['files']['STOP']='0'*64
                elif mutation=='late':b[-2]['receipt']['elapsedMillis']=300000
                else:t['frozenCopyCProofSha256']='0'*64
                with self.assertRaises((ValueError,KeyError)):self.inspect32(bundle=b,pg=p,history=h,termination=t)

    def test_unapproved_import_has_no_session_or_key(self):
        meta,child,permit=self.child32(False)
        with patch('ai_pilot_paid_sender.sys.platform','linux'),patch('ai_pilot_paid_sender.time.time',return_value=1000000001),patch('ai_pilot_channel.source_fingerprint',return_value='e'*64):
            with self.assertRaises(ValueError):carry.import_ledger(meta,child/'permit.json',self.parent)
        self.assertFalse((child/'session').exists());self.assertFalse((child/'NEVER_OPENED').exists())

    def test_only_25_schedule_import_79_no_replay_and_unknown_halt(self):
        meta,child,permit=self.child32();before={n:carry.sha(self.parent/n) for n in ('pilot-v2.sqlite','session.json','STOP')}
        original=oom.verify_anchor
        with patch('ai_pilot_paid_sender.sys.platform','linux'),patch('ai_pilot_paid_sender.time.time',return_value=1000000001),patch('ai_pilot_channel.source_fingerprint',return_value='e'*64),patch.object(oom,'verify_anchor',side_effect=lambda m,*args:original(m,self.parent)):
            imported=carry.import_ledger(meta,child/'permit.json',self.parent)
            class Clock:
                value=1000000.
                def __call__(self):return self.value
            c=held.HeldCoordinator(child/'session/pilot-v2.sqlite',meta=imported,clock=Clock())
            class Adapter(ControlledAdapter):
                def request(self,actor,method,path,body):
                    uid=self.binding['actors'][actor]['uid']
                    with self.c.db() as db:
                        phase=self.c.active(db);slot=db.execute('SELECT slot FROM submissions WHERE phase=? AND actor=? AND '+self.c.pending_sql(),(phase['id'],uid)).fetchone()[0]
                    self.seen.append((phase['id'],slot));self.requests.append((actor,method,path));row=next(r for r in self.c.rows if r['phase']==phase['id'] and r['slot']==slot)
                    self.c.admit(uid,body,row['corpus']);run=str(uuid.uuid4());self.c.bind_worker(uid,run,row['corpus'])
                    if row['expectedHttp']<400:
                        self.dispatch(uid,run,row['corpus'],'EMBEDDING')
                        for _ in range(1 if 'skillId' in body or row['case'] in {'PCa','PCb','PCc'} else 2):self.dispatch(uid,run,row['corpus'],'LLM')
                    self.c.worker_exited(uid,run);return row['expectedHttp'],{'runId':run,'status':'MOCK_NOT_QUALITY'}
            a=Adapter(c,meta['fixture']);a.pg=copy.deepcopy(self.pg);a.seen=[]
            result=carry.ContinuationSchedule(c,imported,permit,lambda:'e'*64).run(a)
            self.assertEqual((result['reusedRequests'],result['newRequests'],len(a.requests),len(a.web)),(32,25,25,6))
            self.assertEqual(len(set(a.seen)),25);self.assertFalse(set(a.seen)&{(e['evidence']['row']['phase'],e['evidence']['row']['slot']) for e in self.bundle})
            f1=next(e for e in result['results'] if e.get('case')=='F1');self.assertEqual((f1['quality'],f1['serverVerification']),('NOT_REVIEWED','FAIL'))
            self.assertTrue(c.reconcile(a.pg)['matching']);self.assertEqual(c.summary()['unknown'],1)
            self.assertLessEqual(c.summary()['llmCalls'],92);self.assertLessEqual(c.summary()['embeddingCalls'],70)
            with c.db() as db:db.execute("UPDATE reservations SET outcome='UNKNOWN' WHERE pg_id=(SELECT pg_id FROM reservations WHERE outcome='OBSERVED' LIMIT 1)")
            with self.assertRaisesRegex(ValueError,'UNKNOWN_USAGE_HALTED'):c.reconcile(a.pg)
            self.assertEqual(before,{n:carry.sha(self.parent/n) for n in before})


class AnvilSupervisorTests(unittest.TestCase):
    def test_app_stop_evidence_failure_does_not_replace_primary(self):
        import ai_pilot_paid_launcher as launcher
        with tempfile.TemporaryDirectory() as td:
            folder=Path(td);m={'owner':'test','names':{'anvil':'owned-node','pg':'owned-pg'},'images':{'anvil':'sha256:test','app':'sha256:app'}}
            with patch.object(launcher,'observe_paid_anvil',side_effect=[{},ValueError('V2_PAID_ANVIL_UNAVAILABLE')]),patch.object(launcher,'create',return_value='owned-app'),patch.object(launcher,'cmd'),patch.object(launcher,'owned',return_value={'State':{'Running':True}}),patch.object(launcher,'stop',return_value='STOPPED'),patch.object(launcher,'save',side_effect=OSError('SYNTHETIC_DISK_FAILURE')),patch.object(launcher.sys,'stderr'):
                with self.assertRaisesRegex(ValueError,'ANVIL_UNAVAILABLE'):launcher.app(folder,folder,m,'test','PAID',Mock())

    def test_terminal_collection_failure_preserves_primary_and_blocks_clean_run(self):
        import ai_pilot_carry_launcher as launcher
        for primary in (True,False):
            with self.subTest(primary=primary),tempfile.TemporaryDirectory() as td:
                m={'names':{'anvil':'owned-node','pg':'owned-pg'},'images':{'app':'sha256:app','pg':'sha256:pg','anvil':'sha256:node'}}
                with patch.object(launcher,'cleanup_owned',return_value={'node':'STOPPED'}),patch.object(launcher,'record_paid_anvil_termination',side_effect=RuntimeError('SECONDARY')):
                    if primary:
                        with self.assertRaisesRegex(ValueError,'ORIGINAL'):
                            try:raise ValueError('ORIGINAL')
                            finally:launcher.finish_run(Path(td),m,Mock(),'relay',primary=True)
                    else:
                        with self.assertRaisesRegex(ValueError,'TERMINATION_UNVERIFIED'):launcher.finish_run(Path(td),m,Mock(),'relay',primary=False)
                    self.assertEqual(carry.read(Path(td)/'carry-terminal-failure.json')['primaryFailurePreserved'],primary)

    def test_primary_survives_diagnostic_write_failure_too(self):
        import ai_pilot_carry_launcher as launcher
        m={'names':{'anvil':'node','pg':'pg'},'images':{'app':'app','pg':'pg','anvil':'node'}}
        with tempfile.TemporaryDirectory() as td,patch.object(launcher,'cleanup_owned',return_value={'node':'STOPPED'}),patch.object(launcher,'record_paid_anvil_termination',side_effect=RuntimeError('SECONDARY')),patch.object(launcher,'save',side_effect=OSError('SYNTHETIC_DISK_FAILURE')),patch.object(launcher.sys,'stderr'):
            with self.assertRaisesRegex(ValueError,'ORIGINAL'):
                try:raise ValueError('ORIGINAL')
                finally:launcher.finish_run(Path(td),m,Mock(),'relay',primary=True)

    def test_running_app_is_stopped_when_anvil_health_fails(self):
        import ai_pilot_paid_launcher as launcher
        with tempfile.TemporaryDirectory() as td:
            folder=Path(td);m={'owner':'test','names':{'anvil':'owned-node','pg':'owned-pg'},'images':{'anvil':'sha256:test','app':'sha256:app'}}
            with patch.object(launcher,'observe_paid_anvil',side_effect=[{},ValueError('V2_PAID_ANVIL_UNAVAILABLE')]),patch.object(launcher,'create',return_value='owned-app'),patch.object(launcher,'cmd'),patch.object(launcher,'owned',return_value={'State':{'Running':True}}),patch.object(launcher,'stop',return_value='STOPPED') as stopped:
                with self.assertRaisesRegex(ValueError,'ANVIL_UNAVAILABLE'):launcher.app(folder,folder,m,'test','PAID',Mock())
                stopped.assert_called_once_with('owned-app','test','sha256:app',30)
                self.assertTrue(carry.read(folder/'test-stop.json')['primaryFailurePreserved'])

    def test_profile_memory_and_oom_observation_blocks(self):
        import ai_pilot_paid_launcher as launcher
        with tempfile.TemporaryDirectory() as td:
            m={'owner':'test','names':{'anvil':'owned'},'images':{'anvil':'sha256:test'}}
            item={'HostConfig':{'Memory':1024**3,'MemorySwap':1024**3},'Config':{'Cmd':['--no-mining','--preserve-historical-states']},'State':{'Running':True,'OOMKilled':False,'ExitCode':0}}
            with patch.object(launcher,'owned',return_value=item),patch.object(launcher,'cmd',return_value='{"MemUsage":"272MiB / 1GiB","MemPerc":"27%"}'):
                self.assertTrue(launcher.observe_paid_anvil(Path(td),m,sample=True)['running'])
                item['State'].update(Running=False,OOMKilled=True,ExitCode=137)
                with self.assertRaisesRegex(ValueError,'ANVIL_UNAVAILABLE'):launcher.observe_paid_anvil(Path(td),m)
                self.assertTrue(carry.read(Path(td)/'anvil-health-failure.json')['oomKilled'])
                terminal=launcher.record_paid_anvil_termination(Path(td),m)
                self.assertEqual((terminal['running'],terminal['oomKilled'],terminal['exitCode']),(False,True,137))
            self.assertNotIn('key',Path(td,'anvil-memory.jsonl').read_text().lower())


for _name in dir(ReviewResumeTests):
    if _name.startswith('test_') and _name not in OomResumeTests.__dict__:setattr(OomResumeTests,_name,None)
del ReviewResumeTests
if __name__=='__main__':unittest.main()
