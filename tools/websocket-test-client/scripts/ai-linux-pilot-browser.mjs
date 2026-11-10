/** Linux-only derivative of the frozen DOM driver; assertions preserved, metadata-only diagnostics added. */
import assert from 'node:assert/strict';
import {readFile,writeFile,mkdir,mkdtemp,open} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {spawn} from 'node:child_process';
import {createInterface} from 'node:readline';
import {BoundedCdp,pollBounded,fetchJsonBounded} from './cdp-boundary.mjs';
import {findChrome} from './browser-executable.mjs';
import {runPilotSix} from './ai-pilot-v2-flow.mjs';
import {nativeSandboxEvidence} from './native-sandbox-evidence.mjs';
import {loginReadyExpression} from './pilot-dom-readiness.mjs';

const cfg=JSON.parse(await readFile(process.argv[2],'utf8')),out=resolve(process.argv[3]);
assert.match(cfg.origin,/^http:\/\/127\.0\.0\.1:[1-9][0-9]{3,4}$/);
assert(['UNPAID_CONTROLLED_ONLY','PAID_EXPLICIT_V2'].includes(cfg.mode));await mkdir(out);
const connectionProbe=cfg.scope==='SINGLE_CONNECTION_PROBE';
if(connectionProbe){assert.equal(cfg.mode,'UNPAID_CONTROLLED_ONLY');assert.equal(cfg.rows.length,1);assert.equal(cfg.rows[0].case,'PF1b');}
const paid=cfg.mode==='PAID_EXPLICIT_V2';
// Existing UNPAID /driver copies remain standalone, with their original bounds.
const bounds=paid?JSON.parse(await readFile(new URL('../../../deployment/ai-pilot-driver-bounds.json',import.meta.url),'utf8')):{unpaidBeforeMs:70000,unpaidAfterMs:10000,unpaidWatchdogMs:720000};
const profile=await mkdtemp(join(out,'profile-'));let child,cdp,browserCdp,chromeLog,exited=false,success=false,current=null,shot=0;
const replies=[],waiters=[],networkSummary=[],socketSummary=[];const input=createInterface({input:process.stdin});
let stage='START';
input.on('line',line=>{try{const value=JSON.parse(line);const waiter=waiters.shift();if(waiter)waiter(value);else replies.push(value);}catch{process.exitCode=1;}});
async function privateStep(frame){
 process.stdout.write(JSON.stringify(frame)+'\n');let waiter,timer;
 try{const waitMs=frame.op==='stop'?10000:['before','ready'].includes(frame.op)?(paid?bounds.paidBeforeMs:bounds.unpaidBeforeMs):(paid?bounds.paidAfterMs:bounds.unpaidAfterMs);const value=await Promise.race([replies.length?Promise.resolve(replies.shift()):new Promise(r=>{waiter=r;waiters.push(r);}),new Promise((_,r)=>{timer=setTimeout(()=>r(new Error('HOST_DEADLINE')),waitMs);timer.unref();})]);assert.equal(value.ok,true);}
 finally{clearTimeout(timer);const i=waiters.indexOf(waiter);if(i>=0)waiters.splice(i,1);}
}
const stop=()=>{if(child&&!exited)try{if(process.platform==='win32')child.kill();else process.kill(-child.pid,'SIGTERM');}catch{success=false;}};
const watchdog=setTimeout(stop,paid?bounds.paidWatchdogMs:bounds.unpaidWatchdogMs);watchdog.unref();
const evaluate=async expression=>{const r=await cdp.command('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true},5000);assert(!r.exceptionDetails);return r.result.value;};
const poll=fn=>pollBounded(fn,{timeoutMs:110000,intervalMs:100});
const fill=(id,value)=>evaluate(`{const e=document.getElementById(${JSON.stringify(id)});e.value=${JSON.stringify(value)};e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));}`);
const click=id=>evaluate(`document.getElementById(${JSON.stringify(id)}).click()`);
const requestBodies=new Map();let pendingReply=null,recordingFailure=false,networkTap=null;
function event(method,p){
 if(method==='Network.responseReceived'){try{const u=new URL(p.response.url);if(u.origin===cfg.origin&&u.pathname.startsWith('/api/'))networkSummary.push({path:u.pathname,status:p.response.status});}catch{}}
 if(method==='Network.webSocketFrameReceived'){const first=String(p.response?.payloadData??'').split('\n')[0];if(['ERROR','CONNECTED'].includes(first))socketSummary.push({frame:first});}
  if(method==='Network.requestWillBeSent'&&p.request.url===cfg.origin+'/api/ai/agent/answers'){
    if(p.request.method!=='POST'){recordingFailure=true;return;}requestBodies.set(p.requestId,p.request.postData);
  }
  if(method==='Network.responseReceived'&&requestBodies.has(p.requestId))pendingReply={id:p.requestId,http:p.response.status,finished:false};
  if(method==='Network.loadingFinished'&&pendingReply?.id===p.requestId)pendingReply.finished=true;
  if(method==='Network.loadingFailed'&&requestBodies.has(p.requestId))recordingFailure=true;
}
try{
 stage='CHROME_START';const browser=findChrome();chromeLog=await open(join(out,'chrome.private.log'),'wx');child=spawn(browser.executable,['--headless=new','--disable-gpu','--no-first-run','--no-default-browser-check','--remote-debugging-port=0',`--user-data-dir=${profile}`,'about:blank'],{windowsHide:true,detached:process.platform!=='win32',stdio:['ignore','ignore',chromeLog.fd]});child.once('exit',()=>exited=true);child.once('error',()=>exited=true);
 const port=await poll(async()=>{assert(!exited);try{return (await readFile(join(profile,'DevToolsActivePort'),'utf8')).split('\n')[0];}catch{return null;}});
 const tabs=await fetchJsonBounded(`http://127.0.0.1:${port}/json/list`,{timeoutMs:3000});const tab=tabs.find(x=>x.type==='page');assert(tab);
 cdp=await BoundedCdp.connect(tab.webSocketDebuggerUrl,{timeoutMs:3000});
 if(process.env.TMS_NATIVE_SANDBOX_CHECK==='true'){
  stage='SANDBOX_PROOF';
  const v=await fetchJsonBounded(`http://127.0.0.1:${port}/json/version`,{timeoutMs:3000});browserCdp=await BoundedCdp.connect(v.webSocketDebuggerUrl,{timeoutMs:3000});
  await writeFile(join(out,'native-sandbox.private.json'),JSON.stringify(await nativeSandboxEvidence(browserCdp)),{flag:'wx'});
 }
 // The shared bounded transport intentionally exposes event names only. Keep body capture local/private.
 networkTap=message=>{try{if(message.data.length>2_800_000){recordingFailure=true;return;}const e=JSON.parse(message.data);if(e.params&&e.method?.startsWith('Network.'))event(e.method,e.params);}catch{recordingFailure=true;}};
 await cdp.command('Network.enable');await privateStep({op:'ready'});
 stage='WEB_NAVIGATE';cdp.socket.addEventListener('message',networkTap);await cdp.command('Page.enable');await cdp.command('Network.enable');
 await cdp.command('Emulation.setDeviceMetricsOverride',{width:1366,height:768,deviceScaleFactor:1,mobile:false});await cdp.command('Page.navigate',{url:cfg.origin});await poll(()=>evaluate(loginReadyExpression));
 const ui={
  async login(actor){stage='LOGIN';const a=cfg.accounts[actor];assert(a);await click('auth-open');await fill('login-id',a.loginId);await fill('password',a.password);await evaluate("document.getElementById('auth-form').requestSubmit()");await poll(()=>evaluate("!document.getElementById('logout').hidden"));await evaluate("location.hash='ai'");current=actor;},
  async logout(){await click('logout');await poll(()=>evaluate("document.getElementById('logout').hidden"));current=null;},
  async assertCleared(){assert.equal(await evaluate("document.getElementById('ai-result').textContent"),'');},
  async submit(body){
   stage='DOM_SUBMIT';
   assert(current);pendingReply=null;recordingFailure=false;requestBodies.clear();
   const quote=body.target?.quoteId;assert(!body.target||quote);assert(!body.abnormal);assert(!body.skillId||body.skillId==='signed-quote-diagnosis');
   // General PCa already contains 'C Vault'; do not prepend/change the frozen question.
   await fill('ai-kind',quote?'quote':'general');await fill('ai-target',quote??'');await fill('ai-question',body.question);
   await evaluate("document.getElementById('ai-form').requestSubmit()");await poll(()=>{if(recordingFailure)throw Error('HTTP_CAPTURE_FAILED');return pendingReply?.finished;});assert(!recordingFailure);
   const captured=pendingReply;assert.deepEqual(JSON.parse(requestBodies.get(captured.id)),body);
   const raw=await cdp.command('Network.getResponseBody',{requestId:captured.id});const response=JSON.parse(raw.base64Encoded?Buffer.from(raw.body,'base64').toString('utf8'):raw.body);
   await poll(()=>evaluate("!document.getElementById('ai-submit').disabled"));
   assert.equal(await evaluate("document.querySelector('#ai-result .answer')!==null"),true);
   assert.equal(await evaluate("document.querySelectorAll('#ai-result .source').length"),(response.knowledgeSources??[]).length);
   for(const u of response.uncertainties??[])assert.equal(await evaluate(`document.getElementById('ai-result').textContent.includes(${JSON.stringify(u)})`),true);
   const png=await cdp.command('Page.captureScreenshot',{format:'png'});await writeFile(join(out,`pilot-${shot++}.png`),Buffer.from(png.data,'base64'),{flag:'wx'});
   return {http:captured.http,body:response};
  }
 };
 const host={before:row=>privateStep({op:'before',slot:row.slot,actor:row.actor,body:row.body}),after:(row,reply)=>privateStep({op:'after',slot:row.slot,reply}),stop:()=>privateStep({op:'stop'})};
 let result;
 if(connectionProbe){const row=cfg.rows[0];await ui.login(row.actor);await host.before(row);const reply=await ui.submit(row.body);assert.equal(reply.http,row.expectedHttp);await host.after(row,reply);await ui.logout();await ui.assertCleared();result={requests:1,accounts:1,humanSatisfaction:false,scope:'CONNECTION_PROBE_NOT_SIX_ACCEPTANCE'};}
 else result=await runPilotSix(cfg.rows,ui,host);
 success=true;
 await writeFile(join(out,'six-dom-submissions.json'),JSON.stringify({...result,mode:cfg.mode,realModel:cfg.mode==='PAID_EXPLICIT_V2',node:process.version,platform:process.platform,chromeMajor:browser.major}),{flag:'wx'});
}catch(error){const code=/^[A-Z][A-Z0-9_]{0,79}$/.test(error?.code??'')?error.code:'BROWSER_FAILURE';try{await writeFile(join(out,'browser-failure.json'),JSON.stringify({stage,shot,code,errorType:error?.constructor?.name??'Error',networkSummary,socketSummary}),{flag:'wx'});}catch{}process.stderr.write('V2_BROWSER_FAILED_NO_RETRY\n');}
finally{await writeFile(join(out,'network-summary.private.json'),JSON.stringify({networkSummary,socketSummary}),{flag:'wx'});clearTimeout(watchdog);input.close();if(browserCdp){try{await browserCdp.command('Browser.close',{},1000);}catch{}try{await browserCdp.close();}catch{}}if(cdp){if(networkTap)cdp.socket.removeEventListener('message',networkTap);try{await cdp.close();}catch{success=false;}}stop();if(child&&!exited)try{await pollBounded(()=>exited,{timeoutMs:5000});}catch{success=false;}if(chromeLog)await chromeLog.close();if(!success)process.exitCode=1;}
