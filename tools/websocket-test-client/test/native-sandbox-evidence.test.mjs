import test from 'node:test';import assert from 'node:assert/strict';
import {restrictedLiveRenderers as ok} from '../scripts/native-sandbox-evidence.mjs';
test('each active renderer needs restricted low/untrusted token',()=>{assert(ok([{pid:1,status:'OBSERVED',restricted:true,integrityRid:0}],[1]));});
test('missing or unavailable active PID fails closed',()=>{assert(!ok([],[1]));assert(!ok([{pid:1,status:'UNAVAILABLE'}],[1]));assert(!ok([],[]));});
test('unrestricted/high integrity renderer cannot pass',()=>{assert(!ok([{pid:1,status:'OBSERVED',restricted:false,integrityRid:0}],[1]));assert(!ok([{pid:1,status:'OBSERVED',restricted:true,integrityRid:8192}],[1]));});
test('exited spare PID not in final active snapshot is distinguished',()=>{assert(ok([{pid:1,status:'OBSERVED',restricted:true,integrityRid:4096},{pid:2,status:'UNAVAILABLE'}],[1]));assert(!ok([{pid:1,status:'OBSERVED',restricted:true,integrityRid:4096},{pid:2,status:'UNAVAILABLE'}],[1,2]));});
