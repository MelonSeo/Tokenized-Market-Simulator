import unittest
from ai_pilot_connection_report import routing,answer_completeness,case_result
from ai_pilot_retrieval_audit import select_like_product

class ConnectionReportTests(unittest.TestCase):
 def test_partial_and_answered_do_not_automatically_become_quality_pass(self):
  self.assertEqual(answer_completeness('MIXED',{'status':'PARTIAL'},200),'PARTIAL')
  self.assertNotEqual(answer_completeness('MIXED',{'status':'ANSWERED'},200),'PASS')
 def test_denial_and_declared_route_alternatives_remain_distinct(self):
  self.assertEqual(routing('DENIED',{'metrics':{'modelCalls':0,'retrievalCalls':0}},404),'PASS')
  self.assertEqual(routing('MIXED',{'route':'KNOWLEDGE'},200),'FAIL')
  self.assertEqual(routing('STATE_OR_CLARIFY',{'status':'NEEDS_CLARIFICATION'},200),'PASS')
  self.assertEqual(routing('STATE_OR_SCOPE_LIMIT',{'route':'KNOWLEDGE'},200),'PARTIAL_SCOPE_REVIEW_REQUIRED')
 def test_denial_requires_explicit_integer_zero_metrics(self):
  invalid=[{}, {'metrics':None},{'metrics':{}},{'metrics':{'modelCalls':0}},
   {'metrics':{'retrievalCalls':0}},{'metrics':{'modelCalls':False,'retrievalCalls':0}},
   {'metrics':{'modelCalls':'0','retrievalCalls':0}},{'metrics':{'modelCalls':0,'retrievalCalls':1}}]
  for reply in invalid:
   with self.subTest(reply=reply):
    self.assertNotEqual(routing('DENIED',reply,404),'PASS')
    self.assertNotEqual(answer_completeness('DENIED',reply,404),'PASS_AUTHORIZATION_NOT_ANSWER_QUALITY')
 def test_missing_execution_stays_not_run(self):
  self.assertEqual(case_result({'id':'A2'},None,[],{})['http'],'NOT_RUN')
 def test_empty_denial_does_not_claim_observed_zero_tool_execution(self):
  c={'id':'D5','category':'D','route':'DENIED','expectedHttp':404,'tools':[],'atoms':['ownership']}
  self.assertEqual(case_result(c,{'http':404,'reply':{}},[],{'ownership':{'path':'security.md','needle':'owner'}})['tools'],'NOT_VERIFIED_DENIAL')
 def test_right_document_without_exact_required_chunk_is_not_support(self):
  c={'id':'A2','category':'A','route':'MIXED','tools':['getMarketStatus'],'atoms':['volume']}
  r={'http':200,'reply':{'runId':'trial','route':'MIXED','status':'PARTIAL','knowledgeSources':[{'path':'market.md','content':'not the required passage'}]}}
  result=case_result(c,r,[{'requestId':'trial','kind':'FINAL','code':'OK'}],{'volume':{'path':'market.md','needle':'exact required passage'}})
  self.assertEqual(result['rag'],'PASS');self.assertEqual(result['requiredEvidence'],'FAIL_CONTROLLED_FIXTURE');self.assertEqual(result['answerSufficiency'],'PARTIAL')
 def test_readonly_selector_replays_order_and_per_document_cap_without_reranking(self):
  rows=[{'id':str(i),'path':p} for i,p in enumerate(['a','a','a','b','c','d','e'])]
  self.assertEqual([r['id'] for r in select_like_product(rows)],['0','1','3','4','5'])

if __name__=='__main__':unittest.main()
