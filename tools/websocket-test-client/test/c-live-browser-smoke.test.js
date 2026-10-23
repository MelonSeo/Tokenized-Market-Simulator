import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { allowedApi, allowedStatic, validateConfig, walletReadSource, amount18, sameHashes } from '../scripts/c-live-browser-smoke.mjs';
const config={appBaseUrl:'http://127.0.0.1:32172',origin:'http://127.0.0.1:32171',login0:'trial0',login1:'trial1',password:'private-test-only',wallet0:'0x'+'1'.repeat(40),wallet1:'0x'+'2'.repeat(40),chainId:'31337',genesisHash:'0x'+'3'.repeat(64),sourceIdentity:'source-only-test'};
test('live browser config permits loopback source origin only',()=>{
  assert.equal(validateConfig(config),config);
  for(const change of [{origin:'http://127.0.0.1:32173'},{appBaseUrl:'https://example.test'},{appBaseUrl:config.origin},{wallet0:'invalid'},{origin:'http://user:pass@127.0.0.1:32171'}])assert.throws(()=>validateConfig({...config,...change}));
});
test('actual proxy permits exact reads and login only; all finance/signing/AI writes denied',()=>{
  for(const p of ['/api/me','/api/markets/mSEC','/api/markets/mSEC/candles','/api/c-futures/config','/api/c-futures/account'])assert.equal(allowedApi('GET',p),true);
  assert.equal(allowedApi('POST','/api/auth/login'),true);
  for(const p of ['/api/ai/agent/answers','/api/c-futures/wallet/link','/api/c-futures/direct/intents','/api/orders/buy','/api/admin/trade-audits','/api/auth/signup'])for(const method of ['POST','PUT','DELETE'])assert.equal(allowedApi(method,p),false);
  assert.equal(allowedApi('GET','/api/c-futures/account/../config'),false);
});
test('read-only public wallet adapter never signs or sends; genesis is config only',async()=>{
  const context=vm.createContext({window:{}});vm.runInContext(walletReadSource(config.wallet0,config.chainId,config.genesisHash),context);
  const provider=context.window.ethereum;
  assert.equal((await provider.request({method:'eth_accounts'}))[0],config.wallet0);
  assert.equal(await provider.request({method:'eth_chainId'}),'0x7a69');
  assert.equal((await provider.request({method:'eth_getBlockByNumber',params:['0x0',false]})).hash,config.genesisHash);
  for(const method of ['personal_sign','eth_signTypedData_v4','eth_sendTransaction','eth_sendRawTransaction'])await assert.rejects(provider.request({method}),e=>e.code===4100);
  assert.equal(context.window.__readOnlyDeniedSigning,4);
  vm.runInContext(walletReadSource(config.wallet1,config.chainId,config.genesisHash),context);
  assert.equal(context.window.__readOnlyDeniedSigning,4);
  await assert.rejects(context.window.ethereum.request({method:'personal_sign'}));
  assert.equal(context.window.__readOnlyDeniedSigning,5);
  await assert.rejects(provider.request({method:'eth_getBlockByNumber',params:['latest',false]}));
});
test('dist manifest compares keys and hashes independent of Python/Node ordering',()=>{
  assert.equal(sameHashes({'a':'1','B':'2'},{'B':'2','a':'1'}),true);
  assert.equal(sameHashes({'a':'1'},{'a':'2'}),false);assert.equal(sameHashes({'a':'1'},{}),false);
  assert.equal(Boolean(sameHashes(undefined,{})),false);
});
test('static serving allows only verified manifest files, never smoke evidence or traversal',()=>{
  const hashes={'index.html':'1','assets/main.js':'2','.smoke/evidence.json':'not-an-asset'};
  assert.equal(allowedStatic('index.html',hashes),true);assert.equal(allowedStatic('assets/main.js',hashes),true);
  for(const key of ['.smoke/evidence.json','.smoke/profile/Preferences','unknown.js','../index.html','assets/../index.html','/index.html','assets\\main.js','C:/index.html','toString'])assert.equal(allowedStatic(key,hashes),false);
});
test('account rendering comparator preserves 18 decimal places without Number conversion',()=>{
  assert.equal(amount18('0'),'0');assert.equal(amount18('1'),'0.000000000000000001');
  assert.equal(amount18('1000003066148919985598442'),'1000003.066148919985598442');
});
