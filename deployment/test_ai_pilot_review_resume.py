"""Synthetic 23-response carry and 34-only schedule. No real paid activation."""
import copy,json,uuid,unittest,shutil
from pathlib import Path
from unittest.mock import patch
import ai_pilot_review_resume as review
import ai_pilot_held_resume as held
import ai_pilot_continuation as carry
from ai_pilot_paid_adapter import private_write
from ai_pilot_paid_sender import context
from ai_pilot_review import DIMENSIONS
from test_ai_pilot_held_resume import HeldResumeTests
from test_ai_pilot_http_plan import ControlledAdapter

class ReviewResumeTests(HeldResumeTests):
    # Reuse ONLY synthetic setup, not the ancestor's full-suite test methods.
    def setUp(self):
        super().setUp();self.history=review.history_from(self.parent)
        old_bundle=copy.deepcopy(self.bundle);desc=copy.deepcopy(self.proposal)
        self.meta={**self.old,'continuation':desc}
        # This writes only this test's TemporaryDirectory synthetic data.
        (self.parent/'session.json').write_text(json.dumps(self.meta),encoding='utf8')
        from ai_pilot_coordinator import workload
        rows=workload(self.old['fixture'])[16:23];frozen=carry.read(review.ROOT/'backend/src/test/resources/ai/novel-30-v2.json')
        reviews=self.base/'reviews';reviews.mkdir();tickets=[]
        with held.ro(self.parent/'pilot-v2.sqlite'):pass
        import sqlite3
        with sqlite3.connect(self.parent/'pilot-v2.sqlite') as db:
            db.execute('CREATE TABLE held_terminal(ticket TEXT PRIMARY KEY, disposition TEXT NOT NULL)')
            db.execute('INSERT INTO held_terminal VALUES(?,?)',(desc['failedTicket'],'B5_TECHNICAL_FAIL_NO_REPLAY'))
            for i,row in enumerate(rows):
                ticket=str(uuid.uuid4());run=str(uuid.uuid4());tickets.append(ticket)
                db.execute('INSERT INTO submissions VALUES(?,?,?,?,?,1,1,?,1)',(ticket,row['phase'],row['slot'],row['uid'],row['bodySha256'],run))
                e={'row':row,'reply':{'httpStatus':200,'runId':run},'criteria':self.bundle[0]['evidence']['criteria'],
                   'frozenExpected':next(c for c in frozen['cases'] if c['id']==row['case'])}
                grade=['PASS','PASS','FAIL','PARTIAL','PARTIAL','PASS','PARTIAL'][i];key=held.digest(e)
                item={'evidence':e,'quality':grade}
                r={'evidenceSha256':key,'deadlineMs':300000,'elapsedMillis':50000,'accepted':grade!='FAIL','reviewer':'test reviewer'}
                private_write(reviews/(key+'.request.private.json'),e)
                private_write(reviews/(key+'.timing.json'),{'startedUtc':1,'deadlineMs':300000})
                private_write(reviews/(key+'.decision.json'),{'evidenceSha256':key,'accepted':grade!='FAIL','reviewer':'test reviewer',
                    'notes':('SAFE_QUALITY_FAIL:' if grade=='FAIL' else grade+':')+' synthetic content judgement'})
                if i<6:item['receipt']=r;private_write(reviews/(key+'.receipt.json'),r)
                else:
                    assessment={'evidenceSha256':key,'reviewer':'test reviewer','quality':'PARTIAL','checks':{
                        n:{'verdict':'PARTIAL' if n=='sufficiency' else 'PASS','finding':'Synthetic evidence finding '+n} for n in DIMENSIONS},
                        'frozenFactsChecked':True,'forbiddenClaimsChecked':True,'safeToContinue':True}
                    item['offlineReview']={'status':'OFFLINE_REVIEW_ONLY_NOT_ORIGINAL_GATE_PASS','providerRequests':0,'keyReads':0,
                        'originalResponseReused':True,'originalRequestSha256':carry.sha(reviews/(key+'.request.private.json')),
                        'reviewCodeSha256':carry.sha(review.ROOT/'deployment/ai_pilot_review.py'),
                        'assessment':assessment,'receipt':r,'completion':{'evidenceSha256':key,'elapsedMillis':51000,'deadlineMs':300000}}
                self.bundle.append(item)
            for i in range(13):
                db.execute('INSERT INTO reservations VALUES(?,?,?,?,?,?,?,?,?,?,?)',(str(uuid.uuid4()),tickets[i%7],'NOVEL30',
                    'LLM' if i<7 else 'EMBEDDING',1453,0,345258628 if i==0 else 1,'f'*64,'OBSERVED',1,0))
        private_write(self.base/'inner-failure.json',{'code':'V2_SEMANTIC_REVIEW_REQUIRED'})
        with carry.ro(self.parent/'pilot-v2.sqlite') as db:self.pg=[dict(zip(('id','kind','reserved','state'),r)) for r in db.execute('SELECT pg_id,kind,cost,outcome FROM reservations')]
        self.proposal=review.inspect(self.parent,self.bundle,self.pg,self.termination,self.history)
    def inspect23(self,**kwargs):
        return review.inspect(self.parent,kwargs.get('bundle',self.bundle),kwargs.get('pg',self.pg),self.termination,kwargs.get('history',self.history))
    def child23(self,approved=True):
        child=self.base/'child23';child.mkdir();a=child/'anchor';a.mkdir();now=1000000000
        exception={**held.exception_context(self.proposal,'e'*64,'d'*16),'explicitExceptionApproved':approved,'approvedAt':now,'expiresAt':now+3600}
        for n,v in [('prefix-twenty-three.json',self.bundle),('pg-rows.json',self.pg),('termination.json',self.termination),('history.json',self.history),('held-exception.json',exception)]:private_write(a/n,v)
        for n in self.proposal['executionFiles']:
            dest=a/'parent-execution'/n;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(self.base/n,dest)
        desc={**self.proposal,'childId':'d'*16,'anchorFiles':{p.name:carry.sha(p) for p in a.iterdir() if p.is_file()}}
        meta={**self.old,'source':'e'*64,'folder':str(child/'session'),'continuation':desc}
        private_write(self.parent/'CONTINUATION_CLAIM.json',{'childId':desc['childId'],'source':meta['source'],'allocation':meta['allocation'],'continuationSha256':held.digest(desc)})
        permit={**context(meta),'approvalId':meta['approvalId'],'paidApproved':True,'approvedAt':now,'expiresAt':now+3600,'keyFile':str(child/'NEVER_OPENED')}
        private_write(child/'permit.json',permit);return meta,child,permit
    def test_twenty_three_exactly_and_original_late_D2_remains_failed(self):
        self.assertEqual(self.proposal['remainingRequests'],34);self.assertEqual(self.proposal['originalD2Gate'],'FAIL')
        self.assertEqual(self.proposal['priorReservedNusd'],1418256940)
        for mutation in ('grade','receipt','late','question','unknown','pg','history','missing','code'):
            with self.subTest(mutation=mutation):
                b=copy.deepcopy(self.bundle);pg=copy.deepcopy(self.pg);h=copy.deepcopy(self.history)
                if mutation=='grade':b[-1]['quality']='PASS'
                elif mutation=='receipt':b[-1]['receipt']=b[-1]['offlineReview']['receipt']
                elif mutation=='late':b[-1]['offlineReview']['receipt']['elapsedMillis']=300000
                elif mutation=='question':b[-1]['evidence']['row']['body']['question']='changed'
                elif mutation=='unknown':pg[-1]['state']='UNKNOWN'
                elif mutation=='pg':pg[-1]['reserved']+=1
                elif mutation=='history':h['tables']['reservations'][0]=list(h['tables']['reservations'][0]);h['tables']['reservations'][0][6]+=1
                elif mutation=='code':b[-1]['offlineReview']['reviewCodeSha256']='0'*64
                else:del b[-1]['offlineReview']
                with self.assertRaises((ValueError,KeyError)):self.inspect23(bundle=b,pg=pg,history=h)
    def test_stage_and_source_fingerprint_share_the_exact_carry_allowlist(self):
        import inspect
        from ai_pilot_channel import CARRY_SOURCES,source_paths
        from ai_pilot_linux import stage_source
        self.assertIn('ai_pilot_review_resume.py',CARRY_SOURCES)
        self.assertIn('for n in CARRY_SOURCES',inspect.getsource(stage_source))
        paths={p.relative_to(review.ROOT).as_posix() for p in source_paths(review.ROOT)}
        self.assertTrue({'deployment/'+n for n in CARRY_SOURCES}<=paths)
    def test_unapproved_carry_cannot_import_or_open_key(self):
        meta,child,permit=self.child23(False)
        with patch('ai_pilot_paid_sender.sys.platform','linux'),patch('ai_pilot_paid_sender.time.time',return_value=1000000001),patch('ai_pilot_channel.source_fingerprint',return_value='e'*64):
            with self.assertRaises(ValueError):review.verify_anchor(meta,self.parent)
        self.assertFalse((child/'session').exists());self.assertFalse((child/'NEVER_OPENED').exists())
    def test_only_34_pending_preserve_59_reservations_and_all_completed(self):
        meta,child,permit=self.child23();before={n:carry.sha(self.parent/n) for n in ('pilot-v2.sqlite','session.json','STOP')}
        original=review.verify_anchor
        with patch('ai_pilot_paid_sender.sys.platform','linux'),patch('ai_pilot_paid_sender.time.time',return_value=1000000001),patch('ai_pilot_channel.source_fingerprint',return_value='e'*64),patch.object(review,'verify_anchor',side_effect=lambda m,*args:original(m,self.parent)):
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
            self.assertEqual(result['newRequests'],34);self.assertEqual(len(a.requests),34);self.assertEqual(len(a.web),6)
            d2=next(r for r in result['results'] if r.get('case')=='D2' and r.get('reused'))
            self.assertEqual((d2['quality'],d2['originalReviewGate']),('PARTIAL','FAIL'))
            self.assertEqual(len(set(a.seen)),34);self.assertFalse(set(a.seen)&{(e['evidence']['row']['phase'],e['evidence']['row']['slot']) for e in self.bundle})
            self.assertTrue(c.reconcile(a.pg)['matching']);self.assertEqual(c.summary()['unknown'],1)
            self.assertLessEqual(c.summary()['llmCalls'],92);self.assertLessEqual(c.summary()['embeddingCalls'],70)
            self.assertEqual(before,{n:carry.sha(self.parent/n) for n in before})

# Inherited tests describe the 16-prefix milestone, not this 23-prefix fixture.
for _name in dir(HeldResumeTests):
    if _name.startswith('test_') and _name not in ReviewResumeTests.__dict__:setattr(ReviewResumeTests,_name,None)
del HeldResumeTests
if __name__=='__main__':unittest.main()
