import test from 'node:test';
import assert from 'node:assert/strict';
import {futuresCardMatches,unsignedPriceReconfirmation,safeBrowserFailure,faucetObservation,previewObservation} from './futures-browser-assertions.mjs';
const p={side:'LONG',leverage:10,quantity:'13.333333333333333333',entryPrice:'75000.00000000',collateral:'100000.000000000000000000',realized:'0.000000000000000000',badDebt:'0.000000000000000000'};
function card(){return {heading:'LONG · 10배 · 자동청산 확정',fields:{'수량':p.quantity+' mSEC 가격 노출','진입 / 평가 가격':p.entryPrice+' / 76000','DB 격리증거금':p.collateral,'확정 실현손익 / 악성 부채 기록':p.realized+' / '+p.badDebt,'위험 상태':'HEALTHY'},deadlineText:'오후 1:00:00'};}
test('exact labelled terminal values match without numeric conversion',()=>assert.equal(futuresCardMatches(p,card(),'LIQUIDATED'),true));
test('zero in another field cannot hide incorrect realized or bad debt',()=>{for(const value of ['1 / '+p.badDebt,p.realized+' / 1']){const c=card();c.fields['확정 실현손익 / 악성 부채 기록']=value;assert.equal(futuresCardMatches(p,c,'LIQUIDATED'),false);}});
test('collateral precision and historical entry price must match their own fields',()=>{for(const field of ['DB 격리증거금','진입 / 평가 가격','수량']){const c=card();c.fields[field]='0';assert.equal(futuresCardMatches(p,c,'LIQUIDATED'),false);}});
test('wrong side, leverage or terminal heading cannot match',()=>{for(const h of ['SHORT · 10배 · 자동청산 확정','LONG · 5배 · 자동청산 확정','LONG · 10배 · 포지션 유지']){const c=card();c.heading=h;assert.equal(futuresCardMatches(p,c,'LIQUIDATED'),false);}});
test('grace deadline and healthy state are checked independently',()=>{const c=card();c.heading='LONG · 10배 · 포지션 유지';c.fields['위험 상태']='마진콜 · 체인 유예 종료 오후 1:00:00 · 남은 59초';assert.equal(futuresCardMatches(p,c,'OPEN','GRACE'),true);assert.equal(futuresCardMatches(p,c,'OPEN','HEALTHY'),false);c.deadlineText='오후 2:00:00';assert.equal(futuresCardMatches(p,c,'OPEN','GRACE'),false);});
test('only definitive unsigned price rejection permits explicit new preview',()=>{const o={state:'REJECTED',reason:'PRICE_CHANGED',txHash:null,nonce:null};assert.equal(unsignedPriceReconfirmation(o),true);for(const state of ['SIGNED','SUBMITTED','REVIEW_REQUIRED','REVERTED','CONFIRMED'])assert.equal(unsignedPriceReconfirmation({...o,state}),false);assert.equal(unsignedPriceReconfirmation({...o,nonce:0}),false);assert.equal(unsignedPriceReconfirmation({...o,txHash:'existing'}),false);assert.equal(unsignedPriceReconfirmation({...o,reason:'unknown'}),false);});
test('safe failure diagnostics never retain original errors or secrets',()=>{const e=new TypeError('credential-private-value',{cause:{code:'ECONNRESET',message:'token-private-value'}});assert.deepEqual(safeBrowserFailure(e),{error:'BROWSER_STEP_FAILED',errorType:'TypeError',causeCode:'ECONNRESET',ownLocation:null});assert.doesNotMatch(JSON.stringify(safeBrowserFailure(e)),/private-value/);});
test('own OS failure codes are retained without the private path',()=>{const e=Object.assign(new Error('private-path-value'),{code:'ENOSPC'});assert.equal(safeBrowserFailure(e).causeCode,'ENOSPC');assert.doesNotMatch(JSON.stringify(safeBrowserFailure(e)),/private-path-value/);assert.equal(safeBrowserFailure(new Error('Port 5173 is already in use')).error,'WEB_PORT_IN_USE');});
test('faucet observation keeps only literal paths/status and allowlisted public codes',()=>{
  assert.deepEqual(faucetObservation('/api/wallet/faucet/status',503,{code:'FAUCET_DB_UNAVAILABLE',reason:'FAUCET_POLICY_UNAVAILABLE',eligible:false,password:'canary',accessToken:'canary',rawTransaction:'canary'}),{path:'/api/wallet/faucet/status',status:503,code:'FAUCET_DB_UNAVAILABLE',reason:'FAUCET_POLICY_UNAVAILABLE',eligible:false});
  assert.doesNotMatch(JSON.stringify(faucetObservation('/api/wallet/faucet',500,{code:'CANARY_SECRET',reason:'private-value',eligible:'secret'})),/CANARY_SECRET|private-value|secret/);
});
test('unrelated paths, query keys and invalid statuses cannot be recorded',()=>{
  for(const path of ['/api/auth/login','/api/wallet/faucet?key=secret','https://provider/private','/api/wallet/faucet/claims'])assert.equal(faucetObservation(path,200,{}),null);
  for(const status of [-1,600,NaN,'200'])assert.equal(faucetObservation('/api/wallet/faucet',status,{}),null);
});
test('preview diagnostics preserve price rejection but never store reports, signatures or arbitrary errors',()=>{
  assert.deepEqual(previewObservation(409,{code:'PRICE_CHANGED',rawTransaction:'canary',signature:'canary'}),{status:409,code:'PRICE_CHANGED'});
  assert.deepEqual(previewObservation(500,{code:'PRIVATE_SECRET',message:'canary'}),{status:500,code:'UNRECOGNIZED'});
  assert.equal(safeBrowserFailure(new Error('PREVIEW_HTTP_409')).error,'PREVIEW_HTTP_409');
});
test('preview observation rejects invalid status values and keeps HTTP failure even without a JSON body',()=>{
  for(const status of [-1,0,600,NaN,'SECRET_STATUS'])assert.equal(previewObservation(status,{}),null);
  assert.deepEqual(previewObservation(502,null),{status:502,code:null});
});
