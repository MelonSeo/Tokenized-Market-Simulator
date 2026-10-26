/** Actual integrated UI + isolated trial EOA actor. NOT MetaMask or independent human approval. */
import {readFile,writeFile,mkdir,mkdtemp} from 'node:fs/promises';
import {join,resolve} from 'node:path';
import {spawn} from 'node:child_process';
import assert from 'node:assert/strict';
import {BoundedCdp,pollBounded,fetchJsonBounded} from './cdp-boundary.mjs';
import {findChrome} from './browser-executable.mjs';
import {units} from '../src/c-direct-intent.js';
import {trialPriceBounds} from './product-direct-browser-inputs.mjs';
const c=JSON.parse(await readFile(process.argv[2],'utf8')),out=resolve(process.argv[3]);
assert.equal(c.origin,'http://127.0.0.1:32173');assert.match(c.walletEndpoint,/^http:\/\/10\.254\.\d+\.\d+:8092$/);
await mkdir(out);const profile=await mkdtemp(join(out,'profile-')),end=Date.now()+150000;
let child,cdp,exited=false,spawnFailed=false,passed=false,failure=null,stage='START';const history=[],images=[];
const budget=n=>Math.min(n,end-Date.now());const poll=fn=>pollBounded(fn,{timeoutMs:budget(15000),intervalMs:100});
const command=(m,p={})=>cdp.command(m,p,budget(8000));
const evaluate=async expression=>{const r=await command('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});assert(!r.exceptionDetails,'UI evaluation');return r.result.value;};
const click=id=>evaluate(`document.getElementById(${JSON.stringify(id)}).click()`);
const fill=(id,value)=>evaluate(`{const e=document.getElementById(${JSON.stringify(id)});e.value=${JSON.stringify(value)};e.dispatchEvent(new Event('input',{bubbles:true}));}`);
const pending=()=>evaluate(`{const k=Object.keys(localStorage).find(k=>k.startsWith('tms-c-direct:'));k?JSON.parse(localStorage.getItem(k)):null;}`);
const wallet=async(path,body)=>{const r=await fetch(c.walletEndpoint+path,{method:'POST',headers:{'Content-Type':'application/json','X-Trial-Wallet':c.walletToken},body:JSON.stringify(body),signal:AbortSignal.timeout(budget(15000))});assert(r.ok,'Trial wallet stopped; no retry');return (await r.json()).result;};
const shot=async(name,selector)=>{await evaluate(`document.querySelector(${JSON.stringify(selector)}).scrollIntoView({block:'center'})`);await evaluate('document.fonts.ready.then(()=>true)');const r=await command('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});await writeFile(join(out,name+'.png'),Buffer.from(r.data,'base64'),{flag:'wx'});images.push(name+'.png');};
const idle=()=>poll(()=>evaluate("!document.getElementById('c-refresh').disabled"));
async function confirm(kind){
 stage=kind+'_RECEIPT';
 await poll(async()=>{const p=await pending();return p?.phase==='SUBMITTED';});
 await click('c-resolve');await poll(()=>evaluate("document.getElementById('c-result').textContent.includes('CONFIRMED')"));await idle();
 const p=await pending();assert.equal(p.phase,'RECEIPT_OBSERVED');assert.equal(p.result.status,'CONFIRMED');assert.equal(p.result.completeCoverage,false);
 history.push({kind,txHash:p.txHash,blockHash:p.result.blockHash,scope:p.result.scope});await click('c-ack');await idle();assert.equal(await pending(),null);
}
async function tick(){const first=await evaluate("fetch('/api/markets/mSEC').then(r=>r.json())");await poll(async()=>{const next=await evaluate("fetch('/api/markets/mSEC').then(r=>r.json())");return next.observedAt!==first.observedAt;});}
async function trade(action){
 stage=action+'_DRAFT';
 await fill('c-action',action);
 if(action==='CLOSE'){await click('c-position-read');await poll(()=>evaluate("document.getElementById('c-position').textContent.includes('ACTIVE')"));await idle();}
 else{await fill('c-side','LONG');await fill('c-leverage','10');await fill('c-collateral','1000');}
 const market=await evaluate("fetch('/api/markets/mSEC').then(r=>r.json())"),bounds=trialPriceBounds(market.price);
 await fill('c-min-price',bounds.minimum);await fill('c-max-price',bounds.maximum);
 await click('c-prepare');await poll(async()=>{const p=await pending();return p?.phase==='DRAFT_READY';});await idle();
 await tick(); // Read-only bounded observation before Report issuance; no refresh after signing.
 stage=action+'_REPORT';
 await click('c-price');await poll(async()=>{const p=await pending();return p?.phase==='EXECUTION_READY';});await idle();
 await evaluate("{const e=document.getElementById('c-consent');e.checked=true;e.dispatchEvent(new Event('change',{bubbles:true}));}");
 await poll(()=>evaluate("!document.getElementById('c-send').disabled"));stage=action+'_SEND';await click('c-send');await confirm(action);
}
try{
 const browser=findChrome();child=spawn(browser.executable,['--headless=new','--disable-gpu','--no-first-run','--no-default-browser-check','--remote-debugging-port=0',`--user-data-dir=${profile}`,'about:blank'],{windowsHide:true,stdio:'ignore'});child.once('exit',()=>exited=true);child.once('error',()=>spawnFailed=true);
 const debug=await poll(async()=>{assert(!exited&&!spawnFailed);return(await readFile(join(profile,'DevToolsActivePort'),'utf8')).split('\n')[0];});
 const targets=await fetchJsonBounded(`http://127.0.0.1:${debug}/json/list`,{timeoutMs:budget(3000)}),target=targets.find(t=>t.type==='page'&&t.url==='about:blank');assert(target);
 cdp=await BoundedCdp.connect(target.webSocketDebuggerUrl,{timeoutMs:budget(3000)});
 cdp.socket.addEventListener('message',event=>{let m;try{m=JSON.parse(event.data);}catch{return;}if(m.method!=='Runtime.bindingCalled'||m.params.name!=='__trialWallet')return;
  void(async()=>{const request=JSON.parse(m.params.payload);let value,error;try{value=await wallet('/rpc',request.call);}catch{error='TRIAL_WALLET_STOP_NO_RETRY';}await evaluate(`window.__trialReply(${JSON.stringify(request.id)},${JSON.stringify(value??null)},${JSON.stringify(error??null)})`);})().catch(()=>{failure='WALLET_BRIDGE';});
 });
 await command('Runtime.enable');await command('Page.enable');await command('Runtime.addBinding',{name:'__trialWallet'});await command('Emulation.setDeviceMetricsOverride',{width:1366,height:768,deviceScaleFactor:1,mobile:false});
 await command('Page.addScriptToEvaluateOnNewDocument',{source:`{let seq=0;const waiting=new Map();window.__trialReply=(id,value,error)=>{const p=waiting.get(id);if(!p)return;waiting.delete(id);error?p.reject(new Error(error)):p.resolve(value);};window.ethereum={on(){},request(call){return new Promise((resolve,reject)=>{const id=++seq;waiting.set(id,{resolve,reject});window.__trialWallet(JSON.stringify({id,call}));});}};}`});
 await command('Page.navigate',{url:c.origin});await poll(()=>evaluate("!!document.getElementById('auth-open')"));
 stage='LOGIN';await click('auth-open');await fill('login-id',c.login);await fill('password',c.password);await evaluate("document.getElementById('auth-form').requestSubmit()");await poll(()=>evaluate("!document.getElementById('logout').hidden"));
 await evaluate("location.hash='custody'");await poll(()=>evaluate("!document.getElementById('c-connect').disabled"));await click('c-connect');await idle();await click('c-link');await poll(()=>evaluate("document.getElementById('c-message').textContent.includes('연결')"));await idle();await click('c-refresh');await poll(()=>evaluate("document.querySelectorAll('#c-account strong').length===6"));await idle();
 await fill('c-asset-amount','3000');await click('c-approve');await confirm('APPROVE');await click('c-deposit');await confirm('DEPOSIT');
 await trade('OPEN');await shot('01-open','#c-account');await trade('CLOSE');
 const free=await evaluate("document.querySelectorAll('#c-account strong')[1].textContent.replace(' cMKRW','')");assert(units(free,18)>0n);await fill('c-asset-amount',free);await click('c-withdraw');await confirm('WITHDRAW');
 await click('c-position-read');await poll(()=>evaluate("document.getElementById('c-position').textContent.includes('CLOSED')"));await idle();await shot('02-final-account','#c-account');await shot('03-closed','#c-position');
 assert.equal(history.length,5);assert.equal(await evaluate("document.querySelectorAll('#c-history dl').length"),5);await shot('04-verified-history','#c-history');assert(!failure);await wallet('/finish',{});passed=true;
}catch(e){failure=e.code??e.name??'DIRECT_BROWSER_FAILED';}
finally{if(cdp)await cdp.close();if(child&&!exited&&!spawnFailed){child.kill();try{await pollBounded(()=>exited,{timeoutMs:3000});}catch{passed=false;failure='OWNED_CHROME_EXIT';}}
 await writeFile(join(out,'result.json'),JSON.stringify({status:passed?'PASS':'BLOCKED',failure,stage,history,images,trialEoa:true,realMetaMask:false,childCleanupComplete:!child||exited||spawnFailed},null,2),{flag:'wx'});if(!passed)process.exitCode=1;}
