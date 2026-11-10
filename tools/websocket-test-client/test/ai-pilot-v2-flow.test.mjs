import test from 'node:test';
import assert from 'node:assert/strict';
import {runPilotSix} from '../scripts/ai-pilot-v2-flow.mjs';
import {readFile} from 'node:fs/promises';
const rows=Array.from({length:6},(_,i)=>({phase:'WEB_PILOT',slot:i,actor:i<3?'owner':'other',case:['PA2a','PF1b','PCa'][i%3],body:{question:'controlled '+i},expectedHttp:200}));
test('two actors three DOM submissions each, logout clears before switch, private host sees real returned object',async()=>{
 const events=[];const ui={login:async a=>events.push('login:'+a),logout:async()=>events.push('logout'),assertCleared:async()=>events.push('clear'),submit:async b=>{events.push('submit:'+b.question);return{http:200,body:{runId:'controlled'}};}};
 const host={before:async r=>events.push('before:'+r.slot),after:async(r,v)=>{assert.equal(v.body.runId,'controlled');events.push('after:'+r.slot);},stop:async()=>events.push('stop')};
 const result=await runPilotSix(rows,ui,host);assert.equal(result.requests,6);assert.equal(result.humanSatisfaction,false);
 assert(events.indexOf('clear')<events.indexOf('login:other'));assert.equal(events.filter(x=>x==='clear').length,2);assert(!events.includes('stop'));
});
test('failure sends stop, no replay or seventh request',async()=>{
 let submits=0,stops=0;const ui={login:async()=>{},logout:async()=>{},assertCleared:async()=>{},submit:async()=>{submits++;throw Error('controlled');}};
 await assert.rejects(runPilotSix(rows,ui,{before:async()=>{},after:async()=>{},stop:async()=>stops++}));assert.equal(submits,1);assert.equal(stops,1);
});
test('actual driver observes HTTP instead of injecting successful browser responses',async()=>{
 const s=await readFile(new URL('../scripts/ai-pilot-v2-browser.mjs',import.meta.url),'utf8');
 assert(s.includes('Network.getResponseBody'));assert(s.includes('auth-form'));assert(s.includes('ai-form'));
 for(const denied of ['Fetch.fulfillRequest','Fetch.enable','window.fetch=','window.fetch =','page.route'])assert(!s.includes(denied));
});
