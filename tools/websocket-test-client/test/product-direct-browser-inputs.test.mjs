import test from 'node:test';
import assert from 'node:assert/strict';
import {trialPriceBounds,trialLoginReady,trialLoginState,trialApiKind,trialErrorCode,trialAccountAtOrAfter} from '../scripts/product-direct-browser-inputs.mjs';
import {runInNewContext} from 'node:vm';
test('trial browser renders exact BigInt price bounds through the existing string codec',()=>{
 assert.deepEqual(trialPriceBounds('100'),{minimum:'95',maximum:'105'});
 assert.deepEqual(trialPriceBounds('1000000000000.00000001'),{minimum:'950000000000',maximum:'1050000000000.00000001'});
 assert.throws(()=>trialPriceBounds('1e9'));
});

test('static login DOM cannot be clicked until both app handlers and document completion are ready',()=>{
 const nodes={'auth-open':{},'auth-form':{}};
 const document={readyState:'loading',getElementById:id=>nodes[id]};
 assert.equal(trialLoginReady(document),false);
 document.readyState='complete';assert.equal(trialLoginReady(document),false);
 nodes['auth-open'].onclick=()=>{};assert.equal(trialLoginReady(document),false);
 nodes['auth-form'].onsubmit=()=>{};assert.equal(trialLoginReady(document),true);
 document.readyState='interactive';assert.equal(trialLoginReady(document),false);
});

test('CDP-serialized readiness function has no module dependency or side effects',()=>{
 let clicks=0;const nodes={'auth-open':{onclick:()=>clicks++},'auth-form':{onsubmit:()=>clicks++}};
 const document={readyState:'complete',getElementById:id=>nodes[id]};
 assert.equal(runInNewContext(`(${trialLoginReady.toString()})(document)`,{document}),true);
 assert.equal(clicks,0);
});

test('login failure snapshot contains only explicit booleans and no entered values',()=>{
 const nodes={'auth-open':{onclick:()=>{}},'auth-form':{onsubmit:()=>{}},'auth-dialog':{open:true},'auth-submit':{disabled:false},'logout':{hidden:true},'password':{value:'DO_NOT_EXPORT'},'auth-message':{textContent:'DO_NOT_EXPORT'}};
 const document={readyState:'complete',getElementById:id=>nodes[id]};
 const row=trialLoginState(document);
 assert.deepEqual(row,{documentComplete:true,openHandler:true,submitHandler:true,dialogOpen:true,submitDisabled:false,logoutVisible:false});
 assert(!JSON.stringify(row).includes('DO_NOT_EXPORT'));
 assert(Object.values(row).every(value=>typeof value==='boolean'));
});

test('trial API observations remove IDs, queries, credentials and external URLs',()=>{
 const origin='http://127.0.0.1:32173';
 for(const [path,kind]of [['/api/auth/login','LOGIN'],['/api/me','ME'],['/api/c-futures/config','C_CONFIG'],['/api/c-futures/account','C_ACCOUNT'],['/api/c-futures/wallet/challenges','C_WALLET'],['/api/c-futures/direct/intents/DO_NOT_EXPORT/execution','C_INTENT'],['/api/unknown/DO_NOT_EXPORT','OTHER_API']]){
  assert.equal(trialApiKind(origin+path+'?password=DO_NOT_EXPORT',origin),kind);
 }
 assert.equal(trialApiKind('https://external.invalid/api/me',origin),null);
 assert.equal(trialApiKind('not a URL',origin),null);
 assert.equal(trialApiKind(origin+'/assets/index.js',origin),null);
});

test('error observations expose only fixed codes, not arbitrary body content',()=>{
 assert.equal(trialErrorCode({code:'AUTHENTICATION_REQUIRED',accessToken:'DO_NOT_EXPORT'}),'AUTHENTICATION_REQUIRED');
 assert.equal(trialErrorCode({code:'INVALID_TOKEN'}),'INVALID_TOKEN');
 for(const body of [{code:'DO_NOT_EXPORT'},null,{message:'DO_NOT_EXPORT'},'DO_NOT_EXPORT'])assert.equal(trialErrorCode(body),'OTHER_CODE');
});

test('receipt confirmation cannot reuse an account rendering from before the mined transaction',()=>{
 let block='19',count=6;
 const label={textContent:'조회 기준 블록',get nextElementSibling(){return {textContent:block};}};
 const document={querySelectorAll:selector=>selector==='#c-account strong'?Array(count):[label]};
 assert.equal(trialAccountAtOrAfter(document,'20'),false);
 block='20';assert.equal(trialAccountAtOrAfter(document,'20'),true);
 block='21';assert.equal(trialAccountAtOrAfter(document,'20'),true);
 block='9007199254740993';assert.equal(trialAccountAtOrAfter(document,'9007199254740994'),false);
 block='9007199254740994';assert.equal(trialAccountAtOrAfter(document,'9007199254740994'),true);
 for(const invalid of [undefined,'—','0x14','20.0','-1']){
  assert.equal(trialAccountAtOrAfter(document,invalid),false);
  block=invalid;assert.equal(trialAccountAtOrAfter(document,'20'),false);
 }
 block='20';count=0;assert.equal(trialAccountAtOrAfter(document,'20'),false);
 assert.equal(trialAccountAtOrAfter({querySelectorAll:s=>s==='#c-account strong'?Array(6):[]},'20'),false);
 count=6;assert.equal(runInNewContext(`(${trialAccountAtOrAfter.toString()})(document,'20')`,{document}),true);
});
