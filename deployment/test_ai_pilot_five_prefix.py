"""No supplier: exact five-response inheritance and unchanged cumulative limits."""
import copy,hashlib,json,sqlite3,shutil,unittest
from pathlib import Path
from unittest.mock import patch
from test_ai_pilot_continuation import CarryTests
from test_ai_pilot_http_plan import ControlledAdapter
from ai_pilot_coordinator import PilotCoordinator
from ai_pilot_http_plan import PilotHttpPlan
from ai_pilot_paid_adapter import private_write
from ai_pilot_paid_sender import context
from ai_pilot_preflight import ROOT,canonical
import ai_pilot_continuation as carry

class FiveTests(CarryTests):
    def build_five(self):
        meta,child=self.make_child();now=1000000000
        permit={**context(meta),'approvalId':meta['approvalId'],'paidApproved':True,'approvedAt':now,'expiresAt':now+3600,'keyFile':str(child/'NEVER')}
        private_write(child/'permit.json',permit);original=carry.verify_anchor
        with patch('ai_pilot_paid_sender.sys.platform','linux'),patch('ai_pilot_paid_sender.time.time',return_value=now+1),patch('ai_pilot_channel.source_fingerprint',return_value='e'*64),patch.object(carry,'verify_anchor',side_effect=lambda m,*args:original(m,self.parent)):
            imported=carry.import_ledger(meta,child/'permit.json',self.parent)
        class Clock:
            value=1000000.
            def __call__(self):return self.value
        c=PilotCoordinator(child/'session/pilot-v2.sqlite',allocation=meta['allocation'],source_hash='e'*64,fixture=meta['fixture'],mode='PAID_V2',clock=Clock())
        adapter=ControlledAdapter(c,meta['fixture']);adapter.pg=copy.deepcopy(self.pg)
        bundle=[{'evidence':self.request,'receipt':self.receipt,'quality':'PASS'}]
        frozen=carry.read(ROOT/'backend/src/test/resources/ai/pilot-acceptance-v1.json')
        for row in [r for r in c.rows if r['phase']=='SAFETY_LEGACY' and 1<=r['slot']<5]:
            ticket=c.claim(row['phase'],row['slot']);_,reply=adapter.request(row['actor'],'POST','/api/ai/agent/answers',row['body']);c.complete_request(ticket,reply['runId'])
            if row['slot']==4:reply.update(status='PARTIAL',uncertainties=['SYNTHESIS_UNAVAILABLE'],toolEvidence=[{'tool':'listAbnormalOrders','status':'SUCCESS'}])
            e={'row':row,'reply':reply,'frozenExpected':next(x for x in frozen['cases'] if x['id']==row['case']),'criteria':frozen['criteria'],'kind':'CONTROLLED_TEST_ONLY'}
            bundle.append({'evidence':e,'quality':'FAIL' if row['slot']==4 else 'PASS','receipt':{'evidenceSha256':hashlib.sha256(canonical(e)).hexdigest(),'accepted':row['slot']!=4,'elapsedMillis':50,'deadlineMs':300000}})
        with c.db() as db:
            # Pin the actual run's cumulative amount in this in-memory-model fixture.
            total=db.execute('SELECT sum(cost) FROM reservations').fetchone()[0]
            uid=db.execute('SELECT pg_id FROM reservations ORDER BY pg_id LIMIT 1').fetchone()[0]
            db.execute('UPDATE reservations SET cost=cost+? WHERE pg_id=?',(343083460-total,uid))
        c.stop();private_write(child/'session/STOP',{'reason':'EXECUTION_ABORTED_NO_REPLAY'})
        with carry.ro(child/'session/pilot-v2.sqlite') as db:
            pg=[{'id':r[0],'kind':r[1],'reserved':r[2],'state':r[3]} for r in db.execute('SELECT pg_id,kind,cost,outcome FROM reservations')]
        return imported,child,bundle,pg
    def test_five_import_keeps_fail_and_old_rows_and_only_52_new_requests(self):
        old,parent,bundle,pg=self.build_five();_,desc=carry.inspect_five(parent/'session',bundle,pg)
        self.assertEqual(desc['priorReservedNusd'],343083460);self.assertEqual(desc['priorLlmCalls'],6)
        rootfiles={n:carry.sha(self.parent/n) for n in ('pilot-v2.sqlite','session.json','STOP','CONTINUATION_CLAIM.json')}
        child=self.root/'second';child.mkdir();anchor=child/'anchor';anchor.mkdir()
        private_write(anchor/'prefix-five.json',bundle);private_write(anchor/'pg-rows.json',pg)
        desc.update(version='tms-pilot-carry-five-v2',childId='f'*16,rootFiles=rootfiles,anchorFiles={p.name:carry.sha(p) for p in anchor.iterdir()})
        meta={**old,'folder':str(child/'session'),'source':'f'*64,'continuation':desc}
        private_write(parent/'session/CONTINUATION_CLAIM.json',{'childId':'f'*16,'source':'f'*64,'allocation':meta['allocation'],'continuationSha256':hashlib.sha256(canonical(desc)).hexdigest()})
        # Directly exercise both ancestors, without any key or sender.
        self.assertEqual(carry.verify_five_anchor(meta,parent/'session',self.parent)[4]['quality'],'FAIL')
        now=1000000000;permit={**context(meta),'approvalId':meta['approvalId'],'paidApproved':True,'approvedAt':now,'expiresAt':now+3600,'keyFile':str(child/'NEVER')}
        private_write(child/'permit.json',permit);before={n:carry.sha(parent/'session'/n) for n in ('pilot-v2.sqlite','STOP','session.json')}
        with patch('ai_pilot_paid_sender.sys.platform','linux'),patch('ai_pilot_paid_sender.time.time',return_value=now+1),patch('ai_pilot_channel.source_fingerprint',return_value='f'*64),patch.object(carry,'verify_anchor',side_effect=lambda m,*a:carry.verify_five_anchor(m,parent/'session',self.parent)):
            imported=carry.import_ledger(meta,child/'permit.json',parent/'session')
            class Clock:
                value=1000000.
                def __call__(self):return self.value
            c=PilotCoordinator(child/'session/pilot-v2.sqlite',allocation=meta['allocation'],source_hash='f'*64,fixture=meta['fixture'],mode='PAID_V2',clock=Clock())
            a=ControlledAdapter(c,meta['fixture']);a.pg=copy.deepcopy(pg)
            result=carry.ContinuationSchedule(c,imported,permit,lambda:'f'*64).run(a)
            self.assertEqual(len(a.requests),52);self.assertEqual(len(a.web),6)
            self.assertEqual(result['reusedRequests'],5);self.assertEqual(result['results'][5]['quality'],'FAIL')
            self.assertTrue(c.reconcile(a.pg)['matching']);self.assertLessEqual(c.summary()['llmCalls'],92)
            self.assertEqual(before,{n:carry.sha(parent/'session'/n) for n in before})
    def test_changed_failure_unknown_and_missing_prefix_reject(self):
        _,parent,bundle,pg=self.build_five()
        for kind in ('fail','unknown','missing'):
            e=copy.deepcopy(bundle);p=copy.deepcopy(pg)
            if kind=='fail':e[4]['quality']='PASS'
            elif kind=='unknown':p[0]['state']='UNKNOWN'
            else:e.pop()
            with self.assertRaises(ValueError):carry.inspect_five(parent/'session',e,p)

if __name__=='__main__':unittest.main()
