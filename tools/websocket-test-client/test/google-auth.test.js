import test from 'node:test';
import assert from 'node:assert/strict';
import { GoogleAttempt } from '../src/google-auth.js';
import { ApiClient } from '../src/api.js';

const proof = purpose => ({ challengeId: 'opaque-id', nonce: 'n'.repeat(43), purpose, expiresAt: new Date(Date.now()+60000).toISOString() });
test('Google attempt callback is one-shot and bound to purpose', async () => {
  const attempt = new GoogleAttempt(); let callback, count=0;
  await attempt.start('LOGIN', async()=>proof('LOGIN'), (_, cb)=>callback=cb, async()=>count++);
  await callback('opaque-fixture'); await callback('opaque-fixture'); assert.equal(count,1);
  await assert.rejects(attempt.start('LINK',async()=>proof('LOGIN'),()=>{},()=>{}),/purpose/);
});
test('cancel, retry and late callback cannot overwrite a newer Google attempt', async()=>{
  const attempt = new GoogleAttempt(); let old, fresh, count=0;
  await attempt.start('LOGIN',async()=>proof('LOGIN'),(_,cb)=>old=cb,async()=>count++);
  attempt.cancel(); await old('old'); assert.equal(count,0);
  await attempt.start('LINK',async()=>proof('LINK'),(_,cb)=>fresh=cb,async()=>count+=10);
  await old('old'); await fresh('fresh'); assert.equal(count,10);
});
test('logout and delayed challenge are ignored, with independent tab state',async()=>{
  const first = new GoogleAttempt(), second = new GoogleAttempt();
  let resolve, callback, current=true, rendered=0, count=0;
  const pending=first.start('LOGIN',()=>new Promise(done=>resolve=done),()=>rendered++,()=>{});
  first.cancel(); resolve(proof('LOGIN')); await pending; assert.equal(rendered,0);
  await second.start('LOGIN',async()=>proof('LOGIN'),(_,cb)=>callback=cb,async()=>count++,()=>current);
  current=false; await callback('late'); assert.equal(count,0);
});
test('expired Google challenge never exchanges ID Token',async()=>{
  const attempt = new GoogleAttempt(); let callback, count=0;
  await attempt.start('LOGIN',async()=>({...proof('LOGIN'),expiresAt:'2000-01-01T00:00:00Z'}),(_,cb)=>callback=cb,async()=>count++);
  await assert.rejects(callback('expired'),/expired/); assert.equal(count,0);
});
test('Google API uses separate JWT protected LINK path, JSON custom header and no identity injection',async()=>{
  const previous = globalThis.fetch; const calls=[];
  globalThis.fetch=async(path,options)=>{calls.push({path,options});return {ok:true,status:200,text:async()=> '{}'};};
  try {
    const api=new ApiClient(); api.setToken('app-fixture');
    await api.googleChallenge('LINK'); await api.googleLink('id','fixture-credential','fixture-password');
    assert.equal(calls[0].path,'/api/me/google/challenges'); assert.equal(calls[1].path,'/api/me/google/link');
    assert.equal(calls[1].options.headers.Authorization,'Bearer app-fixture');
    assert.equal(calls[1].options.headers['X-Google-Auth'],'1');
    assert.deepEqual(Object.keys(JSON.parse(calls[1].options.body)).sort(),['challengeId','credential','password']);
    assert.ok(!calls.some(call=>/credential|password/.test(call.path)));
  } finally { globalThis.fetch=previous; }
});
