import test from 'node:test';
import assert from 'node:assert/strict';
import {trialPriceBounds,trialLoginReady,trialLoginState} from '../scripts/product-direct-browser-inputs.mjs';
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
