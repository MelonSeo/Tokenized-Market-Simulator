/** Real Chrome + real Spring APIs. Wallet READ adapter only; no MetaMask signature claim. */
import {readFile,writeFile,mkdir,mkdtemp} from 'node:fs/promises';
import {join,resolve} from 'node:path';
import {spawn} from 'node:child_process';
import assert from 'node:assert/strict';
import {BoundedCdp,pollBounded,fetchJsonBounded} from './cdp-boundary.mjs';
import {walletReadSource,amount18} from './c-live-browser-smoke.mjs';
import {findChrome} from './browser-executable.mjs';

const c=JSON.parse(await readFile(process.argv[2],'utf8')),out=resolve(process.argv[3]);
assert.equal(c.origin,'http://127.0.0.1:32171');await mkdir(out);const profile=await mkdtemp(join(out,'profile-'));
let child,cdp,exited=false,spawnFailed=false,passed=false,failure=null,ws=false,wsFrames=0,marketPriceMessageReceived=false,runtimeError=false,financial=false;
const api=[],images=[],platformFonts=[];
const end=Date.now()+85000;
const budget=ms=>Math.min(ms,end-Date.now());
const poll=op=>pollBounded(op,{timeoutMs:budget(12000),intervalMs:100});
const command=(m,p={})=>cdp.command(m,p,budget(8000));
const evaluate=async expression=>{const r=await command('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});assert(!r.exceptionDetails,'UI evaluation failed');return r.result.value;};
const click=id=>evaluate(`document.getElementById(${JSON.stringify(id)}).click()`);
const fill=(id,value)=>evaluate(`{const e=document.getElementById(${JSON.stringify(id)});e.value=${JSON.stringify(value)};e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));}`);
const capture=async(name,selector=null,fontSelector=selector)=>{
 await evaluate(selector?`document.querySelector(${JSON.stringify(selector)}).scrollIntoView({block:'center'})`:"window.scrollTo(0,0)");
 await evaluate('document.fonts.ready.then(()=>true)');
 if(process.platform==='linux'&&fontSelector){
  assert(await evaluate(`/[가-힣]/u.test(document.querySelector(${JSON.stringify(fontSelector)}).textContent)`),'Korean font target missing');
  const doc=await command('DOM.getDocument',{depth:0}),node=await command('DOM.querySelector',{nodeId:doc.root.nodeId,selector:fontSelector});
  const {fonts}=await command('CSS.getPlatformFontsForNode',{nodeId:node.nodeId});
  const used=fonts.filter(f=>f.glyphCount>0).map(({familyName,postScriptName,glyphCount,isCustomFont})=>({familyName,postScriptName,glyphCount,isCustomFont}));
  platformFonts.push({image:name+'.png',selector,fontSelector,fonts:used});
  assert(used.some(f=>/Noto Sans CJK/.test(f.familyName)),'Korean system font not used');
 }
 const r=await command('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});await writeFile(join(out,name+'.png'),Buffer.from(r.data,'base64'),{flag:'wx'});images.push(name+'.png');
};
async function login(u){
 await evaluate(`{const prior=window.ethereum;${walletReadSource(u.wallet,c.chainId,c.genesisHash)}if(prior){Object.assign(prior,window.ethereum);window.ethereum=prior;}}`);await click('auth-open');await fill('login-id',u.login);await fill('password',c.password);
 await evaluate("document.getElementById('auth-form').requestSubmit()");await poll(()=>evaluate("!document.getElementById('logout').hidden"));
}
try{
 const browser=findChrome();console.log(`PRODUCT_READ_CHROME family=${browser.family} major=${browser.major}`);
 child=spawn(browser.executable,['--headless=new','--disable-gpu','--no-first-run','--no-default-browser-check','--remote-debugging-port=0',`--user-data-dir=${profile}`,'about:blank'],{windowsHide:true,stdio:'ignore'});
 child.once('exit',()=>exited=true);child.once('error',()=>spawnFailed=true);
 const debug=await poll(async()=>{assert(!exited&&!spawnFailed);return (await readFile(join(profile,'DevToolsActivePort'),'utf8')).split('\n')[0];});
 const targets=await fetchJsonBounded(`http://127.0.0.1:${debug}/json/list`,{timeoutMs:budget(3000)}),target=targets.find(t=>t.type==='page'&&t.url==='about:blank');assert(target);
 cdp=await BoundedCdp.connect(target.webSocketDebuggerUrl,{timeoutMs:budget(3000),onEvent:m=>{if(m==='Network.webSocketCreated')ws=true;if(m==='Network.webSocketFrameReceived')wsFrames++;if(m==='Runtime.exceptionThrown')runtimeError=true;}});
 // Metadata only: JWT, signatures and response bodies are never recorded.
 cdp.socket.addEventListener('message',event=>{try{const r=JSON.parse(event.data);if(r.method==='Network.responseReceived'){const v=r.params.response,u=new URL(v.url);if(u.origin===c.origin&&u.pathname.startsWith('/api/'))api.push({path:u.pathname,status:v.status});}
  if(r.method==='Network.requestWillBeSent'){const v=r.params.request,u=new URL(v.url);if(v.method==='POST'&&!['/api/auth/login',...(c.aiReadMode?['/api/ai/agent/answers']:[])].includes(u.pathname))financial=true;}
  if(r.method==='Network.webSocketFrameReceived'){const frame=r.params.response?.payloadData;if(typeof frame==='string'&&frame.startsWith('MESSAGE\n')&&frame.includes('destination:/topic/markets/mSEC/price'))marketPriceMessageReceived=true;}}catch{}});
 await command('Runtime.enable');await command('Network.enable');await command('Page.enable');await command('Emulation.setDeviceMetricsOverride',{width:1366,height:768,deviceScaleFactor:1,mobile:false});
 if(process.platform==='linux'){await command('DOM.enable');await command('CSS.enable');}
 await command('Page.addScriptToEvaluateOnNewDocument',{source:walletReadSource(c.users[0].wallet,c.chainId,c.genesisHash)});
 await command('Page.addScriptToEvaluateOnNewDocument',{source:`document.addEventListener('DOMContentLoaded',()=>{const p=document.createElement('p');p.textContent='실제 격리 PG/Anvil 조회 · 시험 EOA 읽기 어댑터 · MetaMask/거래 인수 아님';document.body.prepend(p);});`});
 await command('Page.navigate',{url:c.origin});await poll(()=>evaluate("document.querySelectorAll('#market-chart canvas').length>0&&document.getElementById('price').textContent!=='—'"));
 await poll(()=>evaluate("document.getElementById('connection').textContent==='실시간 연결됨'"));await poll(()=>marketPriceMessageReceived);
 await click('auth-open');await capture('00-login','#auth-title');await click('auth-close');
 await login(c.users[0]);await capture('01-market','#connection');await evaluate("location.hash='custody'");await poll(()=>evaluate("!document.getElementById('c-connect').disabled"));
 for(let i=0;i<2;i++){
  const u=c.users[i];if(i){await login(u);await evaluate("location.hash='custody'");await poll(()=>evaluate("!document.getElementById('c-connect').disabled"));}
  await click('c-connect');await poll(()=>evaluate("document.getElementById('c-message').textContent.includes('연결 완료')"));await click('c-refresh');
  await poll(()=>evaluate("document.querySelectorAll('#c-account strong').length===6"));
  assert.equal(await evaluate("document.querySelector('#c-account strong').textContent"),amount18(u.walletBalance)+' cMKRW');
  assert.equal(await evaluate("document.getElementById('c-send').disabled"),true);
  await fill('c-position-id',u.positionId);await click('c-position-read');await poll(()=>evaluate(`document.getElementById('c-position').textContent.includes(${JSON.stringify(u.positionId)})`));
  assert(await evaluate(`document.getElementById('c-position').textContent.includes(${JSON.stringify(u.positionStatus)})`));await capture(i?'03-user-b':'02-user-a','#view-custody h1');
  if(!i){await capture('05-account','#c-account','#c-account > div');await capture('06-position','#c-position','#c-position dt');}
  if(c.aiReadMode){
   await evaluate("location.hash='ai'");await fill('ai-kind','c-account');await fill('ai-question','내 지갑과 Free Locked 조회');
   await evaluate("document.getElementById('ai-form').requestSubmit()");await poll(()=>evaluate("document.getElementById('ai-result').textContent.includes('C 온체인 지갑')"));
   assert(await evaluate(`document.getElementById('ai-result').textContent.includes(${JSON.stringify(amount18(u.walletBalance))})`));
   if(!i)await capture('07-ai-c-account','#ai-result');
   await fill('ai-kind','c-position');assert(await evaluate("!document.getElementById('ai-target-wrap').hidden"),'AI_C_TARGET_VISIBLE');await fill('ai-target',u.positionId);await fill('ai-question','C 포지션 조회');
   await evaluate("document.getElementById('ai-form').requestSubmit()");await poll(()=>evaluate("document.getElementById('ai-result').textContent.includes('C 온체인 포지션 상태')"));
   assert(await evaluate(`document.getElementById('ai-result').textContent.includes(${JSON.stringify(u.positionStatus)})`));
   if(!i)await capture('08-ai-c-position','#ai-result');
  }
  // Actual product logout must remove prior data, not just hide the route.
  await click('logout');await poll(()=>evaluate("document.getElementById('logout').hidden"));
  assert.equal(await evaluate("document.getElementById('c-account').textContent+document.getElementById('c-position').textContent+document.getElementById('c-identity').textContent"),'');
  assert.equal(await evaluate("document.getElementById('ai-result').textContent"),'');
 }
 await capture('04-logged-out');assert(ws);assert(!runtimeError);assert(!financial);assert(api.some(a=>a.path==='/api/c-futures/account'&&a.status===200));assert(api.some(a=>a.path==='/api/markets/mSEC/candles'&&a.status===200));passed=true;
}catch(e){failure=e.code??e.name??'BROWSER_FAILED';}
finally{
 if(cdp)await cdp.close();
 if(child&&!exited&&!spawnFailed){child.kill();try{await pollBounded(()=>exited,{timeoutMs:3000});}catch{passed=false;failure='OWNED_CHROME_EXIT';}}
 const cleanup={childCleanupComplete:!child||exited||spawnFailed,serverOwnedByPython:true};
 await writeFile(join(out,'result.json'),JSON.stringify({status:passed?'PASS':'BLOCKED',failure,api,images,platformFonts,webSocketCreated:ws,webSocketFramesReceived:wsFrames,marketPriceMessageReceived,runtimeError,financialRequests:financial?1:0,realMetaMask:false,cleanup},null,2),{flag:'wx'});
 if(!passed)process.exitCode=1;
}
