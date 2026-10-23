import test from 'node:test';
import assert from 'node:assert/strict';
import { futuresEvidenceSummary } from '../src/ai-futures-evidence.js';
import { renderDiagnosis } from '../src/ui.js';
import { ApiClient } from '../src/api.js';
import { q12Response, positionId } from '../fixtures/futures-ai-response.js';

test('server decimals stay exact and liquidation mark never comes from current reference', () => {
  const result = futuresEvidenceSummary(q12Response), text = JSON.stringify(result.rows);
  for (const value of ['60,000 mKRW','약 13.333333','약 −99,999.99','약 39,999.99','1792454745']) assert.ok(text.includes(value));
  assert.ok(!text.includes('99000')); assert.equal(result.snapshot, true);
  assert.match(result.scope, /fresh RPC検証|fresh RPC 검증/);
});
test('missing, failed and cross-position evidence never fabricates data or a verified result', () => {
  assert.equal(futuresEvidenceSummary({}), null);
  assert.equal(futuresEvidenceSummary({toolEvidence:[{tool:'getFuturesPosition',status:'ERROR',data:{}}]}),null);
  const response = structuredClone(q12Response); response.toolEvidence[1].data.positionId='different';
  const result=futuresEvidenceSummary(response); assert.equal(result.mismatch,true); assert.deepEqual(result.rows,[]);
  response.toolEvidence.pop(); assert.match(JSON.stringify(futuresEvidenceSummary(response).rows), /확인 불가/);
});
test('INCONCLUSIVE and incomplete RPC evidence remain inconclusive even with a known hash', () => {
  const response=structuredClone(q12Response), data=response.toolEvidence[1].data;
  delete data.captureScope;delete data.freshChainVerification;
  data.verification='INCONCLUSIVE';data.operations[1].verification='INCONCLUSIVE';data.operations[1].databaseState='SUBMITTED';
  const result=futuresEvidenceSummary(response);assert.match(JSON.stringify(result.rows),/SUBMITTED \/ INCONCLUSIVE/);
  assert.match(result.scope,/보존된 스냅샷/); // First Tool is still preserved; never promote the pair.
});
test('futures request retains fixed Skill, exact question and only position target with existing JWT', async () => {
  const old=globalThis.fetch;let captured;
  globalThis.fetch=async(path,options)=>{captured={path,...options};return {ok:true,status:200,text:async()=>JSON.stringify(q12Response)};};
  try {const api=new ApiClient();api.setToken('offline-token');await api.agent('내 Long 포지션은 왜 강제청산됐나요? 기록된 근거로 진단해주세요.',{positionId},'futures-liquidation-diagnosis');
    const payload=JSON.parse(captured.body);assert.deepEqual(payload.target,{positionId});assert.equal(payload.skillId,'futures-liquidation-diagnosis');assert.equal(captured.headers.Authorization,'Bearer offline-token');assert.equal(captured.path,'/api/ai/agent/answers');
  }finally{globalThis.fetch=old;}
});
class Element {
  constructor(tag){this.tagName=tag;this.children=[];this.text='';}
  set textContent(v){this.text=String(v??'');this.children=[];}
  get textContent(){return this.text+this.children.map(c=>c.textContent).join(' ');}
  append(...nodes){this.children.push(...nodes);} replaceChildren(...nodes){this.text='';this.children=nodes;}
}
test('PARTIAL, exact raw JSON, snapshot limits and injected strings render as text only',()=>{
  const old=globalThis.document;globalThis.document={createElement:tag=>new Element(tag)};
  try{const container=new Element('section'), response=structuredClone(q12Response);response.answer+='<img src=x onerror=alert(1)>';
    response.toolEvidence[1].data.operations[1].journalId='<script>injection</script>';
    renderDiagnosis(container,response);const text=container.textContent;
    for(const fragment of ['부분 진단','Tool 원본 수치 요약','LLM 생성 문장 아님','진행 중인 유예가 아닙니다','13.333333333333333333','<script>injection</script>','<img','정책 출처'])assert.ok(text.includes(fragment));
    const tags=[];const walk=e=>{tags.push(e.tagName);e.children.forEach(walk);};walk(container);assert.ok(!tags.includes('img'));assert.ok(!tags.includes('script'));
    renderDiagnosis(container,{status:'PARTIAL',error:'RAG_UNAVAILABLE',toolEvidence:response.toolEvidence});assert.match(container.textContent,/RAG_UNAVAILABLE/);
  }finally{globalThis.document=old;}
});
