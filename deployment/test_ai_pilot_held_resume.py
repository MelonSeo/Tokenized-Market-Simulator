"""Synthetic stopped ledger only. No real credentials, provider, PG or Docker."""
import copy,json,hashlib,tempfile,unittest,uuid
from pathlib import Path
from unittest.mock import patch,Mock
import ai_pilot_continuation as carry
import ai_pilot_held_resume as held
from ai_pilot_coordinator import PilotCoordinator
from ai_pilot_paid_adapter import private_write
from ai_pilot_paid_sender import context,PaidSender
from ai_pilot_preflight import ROOT
from test_ai_pilot_coordinator import fixture,wire
from test_ai_pilot_http_plan import ControlledAdapter

class HeldResumeTests(unittest.TestCase):
    def test_hash_order_is_relative_posix_not_native_path_case_or_component_order(self):
        from ai_pilot_channel import source_fingerprint
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);files=[]
            for n in ('foo/Z.java','foo.java','a.java','AA.java'):
                p=root/n;p.parent.mkdir(exist_ok=True);p.write_bytes(n.encode());files.append(p)
            h=hashlib.sha256()
            for p in sorted(files,key=lambda x:x.relative_to(root).as_posix()):h.update(p.relative_to(root).as_posix().encode()+b'\0'+p.read_bytes())
            for order in (files,list(reversed(files)),sorted(files,key=lambda p:str(p).lower())):
                with patch('ai_pilot_channel.source_paths',return_value=order):self.assertEqual(source_fingerprint(root),h.hexdigest())
    def test_host_without_artifact_and_staged_runtime_bind_the_same_exact_build(self):
        from ai_pilot_channel import source_fingerprint
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);code=root/'deployment/example.py';code.parent.mkdir();code.write_bytes(b'public source')
            artifact=root/'deployment/paid-build-identity.json'
            for tree in ('classes/java/main','classes/java/test','resources/main','resources/test/ai','pilot-v2-test/jars','product-read-ci/helpers'):(root/'backend/build'/tree).mkdir(parents=True)
            clazz=root/'backend/build/classes/java/main/X.class';clazz.write_bytes(b'compiled bytes')
            def paths(unused):return [code]+([artifact] if artifact.exists() else [])
            images={'app':'sha256:'+'a'*64}
            with patch('ai_pilot_channel.source_paths',side_effect=paths):
                host=source_fingerprint(root);runtime=held.runtime_fingerprint(root,images)
                self.assertNotEqual(host,runtime)
                artifact.write_text(json.dumps(held.compiled_identity(root,images),sort_keys=True,separators=(',',':')),encoding='utf8')
                self.assertEqual(source_fingerprint(root),runtime)
                self.assertNotEqual(held.runtime_fingerprint(root,{'app':'sha256:'+'b'*64}),runtime)
                clazz.write_bytes(b'changed bytecode');self.assertNotEqual(held.runtime_fingerprint(root,images),runtime)
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.base=Path(self.tmp.name)
        self.parent=self.base/'parent';self.parent.mkdir();f=fixture();f['mode']='REAL_ISOLATED'
        self.old={'allocation':str(uuid.uuid4()),'mode':'PAID_EXPLICIT_V2','source':'a'*64,'fixture':f,'fixtureHash':held.digest(f),
            'budgetAuthority':'b'*64,'approvalId':'tms_pilot_v2_'+'c'*32,'folder':str(self.parent)}
        c=PilotCoordinator(self.parent/'pilot-v2.sqlite',allocation=self.old['allocation'],source_hash='a'*64,fixture=f,mode='PAID_V2',create=True)
        frozen=[carry.read(ROOT/'backend/src/test/resources/ai'/n) for n in ('pilot-acceptance-v1.json','novel-30-v2.json')]
        self.bundle=[];tickets=[]
        with c.db() as d:
            d.execute('UPDATE control SET phase=2,stopped=1');d.execute("UPDATE phases SET complete=1 WHERE name IN ('INDEX_LEGACY','SAFETY_LEGACY')")
            ix=str(uuid.uuid4());d.execute('INSERT INTO submissions VALUES(?,?,?,?,?,1,1,?,1)',(ix,'INDEX_LEGACY',0,c.admin,held.digest({'indexCorpus':'legacy9'}),str(uuid.uuid4())))
            for i,row in enumerate(c.rows[:16]):
                ticket=str(uuid.uuid4());run=str(uuid.uuid4());tickets.append(ticket)
                d.execute('INSERT INTO submissions VALUES(?,?,?,?,?,1,?,?,?)',(ticket,row['phase'],row['slot'],row['uid'],row['bodySha256'],int(i<15),run,int(i<15)))
                reply={'httpStatus':200 if i<15 else 503,'runId':run,'error':None if i<15 else 'AGENT_UNAVAILABLE'}
                e={'row':row,'reply':reply,'criteria':frozen[0]['criteria'],'frozenExpected':next(z for s in frozen for z in s['cases'] if z['id']==row['case'])}
                entry={'evidence':e,'quality':'FAIL' if i==4 else 'PASS' if i<15 else 'NOT_RUN'}
                if i<15:entry['receipt']={'evidenceSha256':held.digest(e),'deadlineMs':300000,'elapsedMillis':50,'accepted':i!=4}
                self.bundle.append(entry)
            for i in range(46):
                kind='LLM' if i<24 else 'EMBEDDING';phase='SAFETY_LEGACY' if i<7 or 30<=i<36 else 'INDEX_LEGACY' if 24<=i<30 else 'NOVEL30'
                ticket=ix if phase=='INDEX_LEGACY' else tickets[-1] if i==45 else tickets[0] if phase=='SAFETY_LEGACY' else tickets[6]
                cost=29060 if i==45 else 1072969196 if i==0 else 1
                d.execute('INSERT INTO reservations VALUES(?,?,?,?,?,?,?,?,?,?,?)',(str(uuid.uuid4()),ticket,phase,kind,1453,0,cost,'f'*64,
                    'UNKNOWN' if i==45 else 'OBSERVED',None if i==45 else 1,None if i==45 else 0))
        private_write(self.parent/'session.json',self.old);private_write(self.parent/'STOP',{'reason':'TEST_UNKNOWN_NO_REPLAY'})
        self.termination={'financialUnchanged':True,'cStateUnchanged':True,'resourcesStopped':True,'serverPassed':1,'serverFailed':0,'serverSkipped':0}
        with carry.ro(self.parent/'pilot-v2.sqlite') as d:self.pg=[dict(zip(('id','kind','reserved','state'),r)) for r in d.execute('SELECT pg_id,kind,cost,outcome FROM reservations')]
        self.proposal=held.inspect(self.parent,self.bundle,self.pg,self.termination)

    def child(self,approved=True):
        child=self.base/'child';child.mkdir();a=child/'anchor';a.mkdir();now=1000000000
        exception={**held.exception_context(self.proposal,'e'*64,'d'*16),'explicitExceptionApproved':approved,'approvedAt':now,'expiresAt':now+3600}
        for n,v in [('prefix-sixteen.json',self.bundle),('pg-rows.json',self.pg),('termination.json',self.termination),('held-exception.json',exception)]:private_write(a/n,v)
        desc={**self.proposal,'childId':'d'*16,'anchorFiles':{p.name:carry.sha(p) for p in a.iterdir()}}
        meta={**self.old,'source':'e'*64,'folder':str(child/'session'),'continuation':desc}
        private_write(self.parent/'CONTINUATION_CLAIM.json',{'childId':desc['childId'],'source':meta['source'],'allocation':meta['allocation'],'continuationSha256':held.digest(desc)})
        permit={**context(meta),'approvalId':meta['approvalId'],'paidApproved':True,'approvedAt':now,'expiresAt':now+3600,'keyFile':str(child/'NEVER_OPENED')}
        private_write(child/'permit.json',permit)
        return meta,child,permit

    def patches(self):
        original=held.verify_held_anchor
        return (patch('ai_pilot_paid_sender.sys.platform','linux'),patch('ai_pilot_paid_sender.time.time',return_value=1000000001),
            patch('ai_pilot_channel.source_fingerprint',return_value='e'*64),
            patch('ai_pilot_held_resume.verify_held_anchor',side_effect=lambda m,*a:original(m,self.parent)))

    def test_exact_historical_hold_is_not_refund_and_prefix_is_16(self):
        self.assertEqual(self.proposal['priorReservedNusd'],1072998300);self.assertEqual(self.proposal['remainingRequests'],41)
        for mutation in ('unknown','pg','late','question','quality','termination'):
            b=copy.deepcopy(self.bundle);p=copy.deepcopy(self.pg);t=copy.deepcopy(self.termination)
            if mutation=='unknown':p[0]['state']='UNKNOWN'
            elif mutation=='pg':p[0]['reserved']+=1
            elif mutation=='late':b[0]['receipt']['elapsedMillis']=300000
            elif mutation=='question':b[0]['evidence']['row']['body']['question']='changed'
            elif mutation=='quality':b[4]['quality']='PASS'
            else:t['resourcesStopped']=False
            with self.assertRaises(ValueError):held.inspect(self.parent,b,p,t)

    def test_missing_exception_cannot_import_or_read_key(self):
        meta,child,p=self.child(False);before={n:carry.sha(self.parent/n) for n in ('pilot-v2.sqlite','session.json','STOP')}
        x,y,z,q=self.patches()
        with x,y,z,q:
            with self.assertRaisesRegex(ValueError,'V4_EXPLICIT_EXCEPTION_REQUIRED'):carry.import_ledger(meta,child/'permit.json',self.parent)
        self.assertFalse((child/'session').exists());self.assertEqual(before,{n:carry.sha(self.parent/n) for n in before})

    def test_single_child_binding_expiry_and_changed_source_fail(self):
        e={**held.exception_context(self.proposal,'e'*64,'d'*16),'explicitExceptionApproved':True,'approvedAt':1000,'expiresAt':2000}
        held.validate_exception(self.proposal,e,'e'*64,'d'*16,now=1500)
        for source,child,now in [('a'*64,'d'*16,1500),('e'*64,'a'*16,1500),('e'*64,'d'*16,2000)]:
            with self.assertRaises(ValueError):held.validate_exception(self.proposal,e,source,child,now)
        with self.assertRaises(FileExistsError):
            with (self.parent/'CONTINUATION_CLAIM.json').open('x') as out:
                out.write('claim')
                with (self.parent/'CONTINUATION_CLAIM.json').open('x'):pass

    def test_41_suffix_both_corpora_and_six_mock_dom_requests_share_all_old_reservations(self):
        meta,child,permit=self.child();before={n:carry.sha(self.parent/n) for n in ('pilot-v2.sqlite','session.json','STOP')}
        x,y,z,q=self.patches()
        with x,y,z,q:
            imported=carry.import_ledger(meta,child/'permit.json',self.parent)
            class Clock:
                value=1000000.
                def __call__(self):return self.value
            c=held.HeldCoordinator(child/'session/pilot-v2.sqlite',meta=imported,clock=Clock())
            class Adapter(ControlledAdapter):
                def request(self,actor,method,path,body):
                    # Exact pending query from the production channel, including retired B5.
                    uid=self.binding['actors'][actor]['uid']
                    with self.c.db() as d:
                        phase=self.c.active(d);s=d.execute('SELECT slot FROM submissions WHERE phase=? AND actor=? AND '+self.c.pending_sql(),(phase['id'],uid)).fetchone()
                    self.seen.append((phase['id'],s[0]))
                    # Base test double has a raw pending query; patch ONLY its selection via a short wrapper.
                    return self.run_selected(actor,body,phase,s[0])
                def run_selected(self,actor,body,phase,slot):
                    uid=self.binding['actors'][actor]['uid'];row=next(r for r in self.c.rows if r['phase']==phase['id'] and r['slot']==slot)
                    self.requests.append((actor,'POST','/api/ai/agent/answers'));self.c.admit(uid,body,row['corpus']);run=str(uuid.uuid4());self.c.bind_worker(uid,run,row['corpus'])
                    if row['expectedHttp']<400:
                        self.dispatch(uid,run,row['corpus'],'EMBEDDING')
                        for _ in range(1 if 'skillId' in body or row['case'] in {'PCa','PCb','PCc'} else 2):self.dispatch(uid,run,row['corpus'],'LLM')
                    self.c.worker_exited(uid,run);return row['expectedHttp'],{'runId':run,'status':'MOCK_NOT_QUALITY'}
            a=Adapter(c,meta['fixture']);a.pg=copy.deepcopy(self.pg);a.seen=[]
            outcome=carry.ContinuationSchedule(c,imported,permit,lambda:'e'*64).run(a)
            self.assertEqual(outcome['newRequests'],41);self.assertEqual(len(a.requests),41);self.assertEqual(len(a.web),6)
            self.assertEqual(len(set(a.seen)),41);self.assertNotIn(('NOVEL30',9),a.seen)
            self.assertTrue(c.reconcile(a.pg)['matching']);self.assertEqual(c.summary()['unknown'],1)
            self.assertLessEqual(c.summary()['llmCalls'],92);self.assertLessEqual(c.summary()['embeddingCalls'],70)
            with c.db() as d:
                self.assertEqual(d.execute('SELECT complete,worker_exited FROM submissions WHERE ticket=?',(self.proposal['failedTicket'],)).fetchone(),(0,0))
                self.assertEqual(d.execute('SELECT * FROM reservations WHERE pg_id=?',(self.proposal['heldReservation'][0],)).fetchone(),tuple(self.proposal['heldReservation']))
            self.assertEqual(outcome['results'][5]['quality'],'FAIL')
            self.assertFalse((child/'NEVER_OPENED').exists())
        self.assertEqual(before,{n:carry.sha(self.parent/n) for n in before})

    def test_new_unknown_stops_without_ignoring_the_old_one(self):
        meta,child,p=self.child();x,y,z,q=self.patches()
        with x,y,z,q:
            imported=carry.import_ledger(meta,child/'permit.json',self.parent);c=held.HeldCoordinator(child/'session/pilot-v2.sqlite',meta=imported)
            row=c.rows[16];ticket=c.claim('NOVEL30',10);c.admit(row['uid'],row['body'],'legacy9');run=str(uuid.uuid4());c.bind_worker(row['uid'],run,'legacy9');calls=[]
            def fail(*args):calls.append(1);raise OSError('MOCK_UNKNOWN')
            args=dict(path='embeddings',raw=wire('EMBEDDING'),pg_id=str(uuid.uuid4()),actor=row['uid'],server_request=run,corpus='legacy9',sender=fail)
            with self.assertRaisesRegex(ValueError,'V2_UNKNOWN_USAGE_HALTED'):c.dispatch(**args)
            self.assertEqual(c.summary()['unknown'],2);self.assertTrue(c.summary()['stopped'])
            with self.assertRaises(ValueError):c.dispatch(**{**args,'pg_id':str(uuid.uuid4())})
            self.assertEqual(len(calls),1)

    def test_held_exception_does_not_enable_default_coordinator_or_replay_b5(self):
        meta,child,p=self.child();x,y,z,q=self.patches()
        with x,y,z,q:
            imported=carry.import_ledger(meta,child/'permit.json',self.parent)
            ordinary=PilotCoordinator(child/'session/pilot-v2.sqlite',allocation=meta['allocation'],source_hash=meta['source'],fixture=meta['fixture'],mode='PAID_V2')
            with ordinary.db() as d:
                with self.assertRaisesRegex(ValueError,'V2_UNKNOWN_USAGE_HALTED'):ordinary.assert_no_unresolved(d)
            c=held.HeldCoordinator(child/'session/pilot-v2.sqlite',meta=imported)
            with self.assertRaisesRegex(ValueError,'V2_SUBMISSION_ALREADY_CLAIMED'):c.claim('NOVEL30',9)
            with c.db() as d:
                with self.assertRaisesRegex(ValueError,'V4_B5_REPLAY_FORBIDDEN'):c.assert_no_unresolved(d,exclude=self.proposal['heldReservation'][0])
            oldrun=self.bundle[-1]['evidence']['reply']['runId']
            with self.assertRaisesRegex(ValueError,'V2_SERVER_REQUEST_MISMATCH'):c.worker_exited(meta['fixture']['actors']['owner']['uid'],oldrun)

    def test_sender_boundary_rejects_new_unknown_before_fake_key_read(self):
        meta,child,permit=self.child();x,y,z,q=self.patches()
        with x,y,z,q:
            imported=carry.import_ledger(meta,child/'permit.json',self.parent);c=held.HeldCoordinator(child/'session/pilot-v2.sqlite',meta=imported)
            key=Mock();factory=Mock();row=c.rows[16];c.claim('NOVEL30',10);c.admit(row['uid'],row['body'],'legacy9');run=str(uuid.uuid4());c.bind_worker(row['uid'],run,'legacy9')
            sender=PaidSender(c,imported,lambda:permit,lambda:'e'*64,key_reader=key,transport_factory=factory,clock=lambda:1000000001)
            def boundary(path,raw):
                with c.db() as d:
                    current=d.execute("SELECT pg_id FROM reservations WHERE ticket IN (SELECT ticket FROM submissions WHERE server_request=?)",(run,)).fetchone()[0]
                    r=list(self.proposal['heldReservation']);r[0]=str(uuid.uuid4());d.execute('INSERT INTO reservations VALUES(?,?,?,?,?,?,?,?,?,?,?)',r)
                return sender.send(path,raw,1,current,row['uid'],run,'legacy9')
            with self.assertRaisesRegex(ValueError,'V2_UNKNOWN_USAGE_HALTED'):
                c.dispatch(path='embeddings',raw=wire('EMBEDDING'),pg_id=str(uuid.uuid4()),actor=row['uid'],server_request=run,corpus='legacy9',sender=boundary)
            key.assert_not_called();factory.assert_not_called()

    def test_pg_state_mismatch_never_becomes_observed_or_refunded(self):
        meta,child,p=self.child();x,y,z,q=self.patches()
        with x,y,z,q:
            imported=carry.import_ledger(meta,child/'permit.json',self.parent);c=held.HeldCoordinator(child/'session/pilot-v2.sqlite',meta=imported)
            bad=copy.deepcopy(self.pg);next(r for r in bad if r['state']=='UNKNOWN')['state']='OBSERVED'
            self.assertFalse(c.reconcile(bad)['matching']);self.assertTrue(c.summary()['stopped']);self.assertEqual(c.summary()['unknown'],1)

if __name__=='__main__':unittest.main()
