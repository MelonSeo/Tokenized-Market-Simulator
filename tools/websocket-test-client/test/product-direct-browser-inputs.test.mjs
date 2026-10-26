import test from 'node:test';
import assert from 'node:assert/strict';
import {trialPriceBounds} from '../scripts/product-direct-browser-inputs.mjs';
test('trial browser renders exact BigInt price bounds through the existing string codec',()=>{
 assert.deepEqual(trialPriceBounds('100'),{minimum:'95',maximum:'105'});
 assert.deepEqual(trialPriceBounds('1000000000000.00000001'),{minimum:'950000000000',maximum:'1050000000000.00000001'});
 assert.throws(()=>trialPriceBounds('1e9'));
});
