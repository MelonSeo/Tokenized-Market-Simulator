"""No paid calls: exact two-phase prefix and three immutable ledger generations."""
import copy,hashlib,json,unittest
from unittest.mock import patch
from test_ai_pilot_five_prefix import FiveTests
from test_ai_pilot_http_plan import ControlledAdapter
from ai_pilot_coordinator import PilotCoordinator
from ai_pilot_paid_adapter import private_write
from ai_pilot_paid_sender import context
from ai_pilot_preflight import ROOT,canonical
import ai_pilot_continuation as carry

class TwelveTests(FiveTests):
 def test_twelve_cross_phase_prefix_and_45_remaining(self):
  old,middle,bundle,pg=self.build_five();_,d=carry.inspect_five(middle/'session',bundle,pg)
  parent=self.root/'twelve';parent.mkdir();a=parent/'anchor';a.mkdir()
  private_write(a/'prefix-five.json',bundle);private_write(a/'pg-rows.json',pg)
  d.update(version='tms-pilot-carry-five-v2',childId='f'*16,
   rootFiles={n:carry.sha(self.parent/n) for n in ('pilot-v2.sqlite','session.json','STOP','CONTINUATION_CLAIM.json')},
   anchorFiles={p.name:carry.sha(p) for p in a.iterdir()})
  meta={**old,'source':'f'*64,'folder':str(parent/'session'),'continuation':d}
  private_write(middle/'session/CONTINUATION_CLAIM.json',{'childId':'f'*16,'source':'f'*64,'allocation':meta['allocation'],'continuationSha256':hashlib.sha256(canonical(d)).hexdigest()})
  now=1000000000;permit={**context(meta),'approvalId':meta['approvalId'],'paidApproved':True,'approvedAt':now,'expiresAt':now+3600,'keyFile':str(parent/'NEVER')}
  private_write(parent/'permit.json',permit)
  with patch('ai_pilot_paid_sender.sys.platform','linux'),patch('ai_pilot_paid_sender.time.time',return_value=now+1),patch('ai_pilot_channel.source_fingerprint',return_value='f'*64),patch.object(carry,'verify_anchor',side_effect=lambda m,*x:carry.verify_five_anchor(m,middle/'session',self.parent)):
   imported=carry.import_ledger(meta,parent/'permit.json',middle/'session')
  class Clock:
   value=1000000.
   def __call__(self):return self.value
  c=PilotCoordinator(parent/'session/pilot-v2.sqlite',allocation=meta['allocation'],source_hash='f'*64,fixture=meta['fixture'],mode='PAID_V2',clock=Clock())
  adapter=ControlledAdapter(c,meta['fixture']);adapter.pg=copy.deepcopy(pg);dispatch=adapter.dispatch;seen=0
  def reduced(actor,run,corpus,kind):
   nonlocal seen
   with c.db() as db:phase=c.active(db)['id'];slot=db.execute('SELECT slot FROM submissions WHERE phase=? AND complete=0',(phase,)).fetchone()[0]
   if phase=='NOVEL30' and slot==1 and kind=='LLM':
    seen+=1
    if seen==2:return {} # A2 deterministic route uses one model, matching the real prefix.
   return dispatch(actor,run,corpus,kind)
  adapter.dispatch=reduced
  sets=[carry.read(ROOT/'backend/src/test/resources/ai'/n) for n in ('pilot-acceptance-v1.json','novel-30-v2.json')]
  extra=[r for r in c.rows if (r['phase']=='SAFETY_LEGACY' and r['slot']==5) or (r['phase']=='NOVEL30' and r['slot']<6)]
  for row in extra:
   t=c.claim(row['phase'],row['slot']);_,reply=adapter.request(row['actor'],'POST','/api/ai/agent/answers',row['body']);c.complete_request(t,reply['runId'])
   e={'row':row,'reply':reply,'criteria':sets[0]['criteria'],'frozenExpected':next(x for s in sets for x in s['cases'] if x['id']==row['case'])}
   bundle.append({'evidence':e,'quality':'PASS','receipt':{'evidenceSha256':hashlib.sha256(canonical(e)).hexdigest(),'accepted':True,'deadlineMs':300000,'elapsedMillis':100}})
   if row['phase']=='SAFETY_LEGACY':c.advance(evidence_reviewed=True)
  with c.db() as db:
   total=db.execute('SELECT sum(cost) FROM reservations').fetchone()[0];uid=db.execute('SELECT pg_id FROM reservations ORDER BY pg_id LIMIT 1').fetchone()[0]
   db.execute('UPDATE reservations SET cost=cost+? WHERE pg_id=?',(844583340-total,uid))
  c.stop();private_write(parent/'session/STOP',{'reason':'EXECUTION_ABORTED_NO_REPLAY'})
  with carry.ro(parent/'session/pilot-v2.sqlite') as db:rows=[{'id':r[0],'kind':r[1],'reserved':r[2],'state':r[3]} for r in db.execute('SELECT pg_id,kind,cost,outcome FROM reservations')]
  _,d3=carry.inspect_twelve(parent/'session',bundle,rows);self.assertEqual(d3['priorLlmCalls'],18)
  child=self.root/'third';child.mkdir();anchor=child/'anchor';anchor.mkdir()
  private_write(anchor/'prefix-twelve.json',bundle);private_write(anchor/'pg-rows.json',rows)
  d3.update(version='tms-pilot-carry-twelve-v3',childId='1'*16,rootFiles=d['rootFiles'],
    middleFiles={n:carry.sha(middle/'session'/n) for n in ('pilot-v2.sqlite','session.json','STOP','CONTINUATION_CLAIM.json')},anchorFiles={p.name:carry.sha(p) for p in anchor.iterdir()})
  m3={**meta,'source':'1'*64,'folder':str(child/'session'),'continuation':d3}
  private_write(parent/'session/CONTINUATION_CLAIM.json',{'childId':'1'*16,'source':'1'*64,'allocation':meta['allocation'],'continuationSha256':hashlib.sha256(canonical(d3)).hexdigest()})
  self.assertEqual(len(carry.verify_twelve_anchor(m3,parent/'session',self.parent,middle/'session')),12)
  p3={**context(m3),'approvalId':meta['approvalId'],'paidApproved':True,'approvedAt':now,'expiresAt':now+3600,'keyFile':str(child/'NEVER')};private_write(child/'permit.json',p3)
  with patch('ai_pilot_paid_sender.sys.platform','linux'),patch('ai_pilot_paid_sender.time.time',return_value=now+1),patch('ai_pilot_channel.source_fingerprint',return_value='1'*64),patch.object(carry,'verify_anchor',side_effect=lambda m,*x:carry.verify_twelve_anchor(m,parent/'session',self.parent,middle/'session')):
   m3=carry.import_ledger(m3,child/'permit.json',parent/'session')
   new=PilotCoordinator(child/'session/pilot-v2.sqlite',allocation=meta['allocation'],source_hash='1'*64,fixture=meta['fixture'],mode='PAID_V2',clock=Clock())
   adapter=ControlledAdapter(new,meta['fixture']);adapter.pg=copy.deepcopy(rows)
   result=carry.ContinuationSchedule(new,m3,p3,lambda:'1'*64).run(adapter)
   self.assertEqual(len(adapter.requests),45);self.assertEqual(result['reusedRequests'],12);self.assertEqual(result['results'][5]['quality'],'FAIL')
   self.assertTrue(new.reconcile(adapter.pg)['matching']);self.assertLessEqual(new.summary()['llmCalls'],92)
  bad=copy.deepcopy(rows);bad[0]['state']='UNKNOWN'
  with self.assertRaises(ValueError):carry.inspect_twelve(parent/'session',bundle,bad)

if __name__=='__main__':unittest.main()
