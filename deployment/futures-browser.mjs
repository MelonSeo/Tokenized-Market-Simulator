// Actual Chrome + product Vite UI + real Spring/PG/Anvil. No HTTP/STOMP fixtures or external providers.
import { readFile, writeFile, mkdir, rename } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { spawn } from 'node:child_process';
import { createServer as createNetServer } from 'node:net';
import { setTimeout as delay } from 'node:timers/promises';
import { futuresCardMatches,unsignedPriceReconfirmation,safeBrowserFailure } from './futures-browser-assertions.mjs';
import { createServer } from '../tools/websocket-test-client/node_modules/vite/dist/node/index.js';
const run=resolve(process.argv[2]), backend=Number(process.argv[3]);
if(!/futures-browser-[0-9a-f]{8}$/.test(run)||!Number.isInteger(backend)||backend<1024||backend>65535)throw Error('NEW_BROWSER_FIXTURE_REQUIRED');
const out=join(run,'browser');await mkdir(out,{recursive:false});
const account=JSON.parse(await readFile(join(run,'secrets/browser-account.json'),'utf8'));
const profile=join(out,'chrome-profile');await mkdir(profile);
const base=`http://127.0.0.1:${backend}`, result={stages:[],operations:[],events:[],previewRejections:[],browserErrors:[],status:'NOT_RUN',step:'PREPARE'};
let server,browser,ws,serial=0,controlSerial=0,token=null;const pending=new Map(),writes=[];
const budget=new AbortController(),until=Date.now()+465000;
const checkpoint=()=>{if(budget.signal.aborted||Date.now()>=until)throw Error('BROWSER_DEADLINE');};
const sleep=ms=>delay(ms,undefined,{signal:budget.signal});
async function poll(fn,ms=30000){const stop=Date.now()+ms;while(Date.now()<stop){checkpoint();const v=await fn();if(v)return v;await sleep(100);}throw Error('WAIT_TIMEOUT');}
function command(method,params={}){checkpoint();return new Promise((resolve,reject)=>{const id=++serial,t=setTimeout(()=>{pending.delete(id);reject(Error('CDP_TIMEOUT'));},Math.max(1,Math.min(8000,until-Date.now())));pending.set(id,{resolve:v=>{clearTimeout(t);resolve(v);},reject});ws.send(JSON.stringify({id,method,params}));});}
async function evaluate(expression){const r=await command('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(r.exceptionDetails)throw Error('BROWSER_SCRIPT_FAILED');return r.result.value;}
const click=id=>evaluate(`document.getElementById(${JSON.stringify(id)}).click()`);
const fill=(id,value)=>evaluate(`{const e=document.getElementById(${JSON.stringify(id)});e.value=${JSON.stringify(value)};e.dispatchEvent(new Event('input',{bubbles:true}));}`);
async function api(path){checkpoint();const r=await fetch(base+path,{headers:{Authorization:`Bearer ${token}`},signal:AbortSignal.any([budget.signal,AbortSignal.timeout(8000)])});if(!r.ok)throw Error('READ_API_'+r.status);return r.json();}
async function control(label){const sequence=++controlSerial,temp=join(run,`browser-control-${sequence}.tmp`);await writeFile(temp,JSON.stringify({sequence,label}),{flag:'wx'});await rename(temp,join(run,`browser-control-${sequence}.json`));await poll(async()=>{try{return JSON.parse(await readFile(join(run,`browser-control-${sequence}.ack.json`),'utf8')).committed===true;}catch{return false;}});}
async function screenshot(name,pid=null){if(pid){const rows=await api('/api/futures/positions'),index=rows.findIndex(p=>p.id===pid);if(index<0)throw Error('POSITION_NOT_FOUND');await evaluate(`document.getElementById('futures-positions').children[${index}].scrollIntoView({block:'start'})`);await evaluate('new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)))');}const shot=await command('Page.captureScreenshot',{format:'png'});await writeFile(join(out,name+'.png'),Buffer.from(shot.data,'base64'),{flag:'wx'});}
async function current(pid,status,risk=null,ms=30000){return poll(async()=>{
  const rows=await api('/api/futures/positions'),index=rows.findIndex(p=>p.id===pid),p=rows[index];if(p?.status==='REVIEW_REQUIRED'||p?.status==='OPEN_FAILED')throw Error('POSITION_'+p.status);
  if(p?.status!==status||(risk&&p.riskStatus!==risk))return false;
  const card=await evaluate(`(()=>{const c=document.getElementById('futures-positions').children[${index}];return c?{heading:c.querySelector('h2')?.textContent,fields:Object.fromEntries([...c.querySelectorAll('dt')].map(t=>[t.textContent,t.nextElementSibling?.textContent])),deadlineText:new Date(${p.deadline}*1000).toLocaleTimeString('ko-KR')}:null;})()`);
  return futuresCardMatches(p,card,status,risk)?p:false;
},ms);}
async function eventFor(pid,action,operationId=null){return poll(async()=>{
  for(const e of result.events.filter(e=>e.data?.positionId===pid&&(!operationId||e.data.operationId===operationId)).slice(-12)){
    const op=await api('/api/futures/operations/'+e.data.operationId);if(op.action===action&&op.settlementConfirmed){return {id:op.id,positionId:pid,action,txHash:op.txHash,blockNumber:op.blockNumber,blockHash:op.blockHash,privateEventId:e.eventId};}
  }return false;
});}
async function outcome(sent){
  return poll(async()=>{const op=await api('/api/futures/operations/'+sent.body.id);
    if(unsignedPriceReconfirmation(op)){result.previewRejections.push({operationId:op.id,positionId:op.positionId,reason:'PRICE_CHANGED',phase:'DEFINITIVE_UNSIGNED_REJECTION'});return {reconfirm:true};}
    if(['REJECTED','REVERTED','REVIEW_REQUIRED'].includes(op.state))throw Error('OPERATION_'+op.state);
    return op.settlementConfirmed?op:false;
  });
}
async function nextProductTick(){
  const previous=(await api('/api/markets/mSEC')).observedAt;
  // Observe a genuinely new committed tick before the UI preview. Never freeze prices or relax the product's zero-drift check.
  await poll(async()=>{const m=await api('/api/markets/mSEC'),age=Date.now()-Date.parse(m.observedAt);return m.provider==='SIMULATED'&&m.priceStatus==='SIMULATED'&&m.marketStatus==='OPEN'&&m.observedAt!==previous&&age>=0&&age<=300;},10000);
}
async function open(side,leverage){
  result.step=`OPEN_${side}_${leverage}`;
  await control('BASE');await fill('futures-side',side);await fill('futures-leverage',String(leverage));await fill('futures-margin','100000');
  for(let attempt=0;attempt<3;attempt++){
    await nextProductTick();
    const before=writes.length;await click('futures-preview-open');await poll(()=>evaluate("!document.getElementById('futures-confirm').disabled"));await click('futures-confirm');
    const sent=await poll(()=>writes.slice(before).find(x=>x.path==='/api/futures/positions'));
    if(sent.status===409&&sent.body.code==='FUTURES_REQUEST_REJECTED'){result.previewRejections.push({side,leverage,status:409});continue;}
    if(sent.status!==202)throw Error('OPEN_HTTP_'+sent.status);
    if((await outcome(sent)).reconfirm){await poll(async()=>{const p=(await api('/api/futures/positions')).find(p=>p.id===sent.body.positionId);return p?.status==='OPEN_FAILED';});continue;}
    await current(sent.body.positionId,'OPEN');result.operations.push(await eventFor(sent.body.positionId,'OPEN',sent.body.id));return sent.body.positionId;
  }throw Error('PREVIEW_RECONFIRM_EXHAUSTED');
}
async function cardAction(label,amount=null){
  await poll(()=>evaluate("[...document.getElementById('futures-positions').children].some(e=>e.textContent.includes('포지션 유지')&&e.querySelector('button'))"));
  if(amount!==null)await evaluate(`{const c=[...document.getElementById('futures-positions').children].find(e=>e.textContent.includes('포지션 유지')&&e.querySelector('button'));const i=c.querySelector('input');i.value=${JSON.stringify(amount)};i.dispatchEvent(new Event('input',{bubbles:true}));}`);
  await evaluate(`{const c=[...document.getElementById('futures-positions').children].find(e=>e.textContent.includes('포지션 유지')&&e.querySelector('button'));[...c.querySelectorAll('button')].find(b=>b.textContent===${JSON.stringify(label)}).click();}`);
}
async function close(pid){for(let attempt=0;attempt<3;attempt++){await nextProductTick();const before=writes.length;await cardAction('전체 종료 견적 확인');await poll(()=>evaluate("!document.getElementById('futures-confirm').disabled"));await click('futures-confirm');const sent=await poll(()=>writes.slice(before).find(x=>x.path.endsWith('/close')));if(sent.status===409&&sent.body.code==='FUTURES_REQUEST_REJECTED'){result.previewRejections.push({positionId:pid,status:409});continue;}if(sent.status!==202)throw Error('CLOSE_HTTP_'+sent.status);if((await outcome(sent)).reconfirm){await current(pid,'OPEN');continue;}await current(pid,'CLOSED');result.operations.push(await eventFor(pid,'CLOSE',sent.body.id));return;}throw Error('CLOSE_RECONFIRM_EXHAUSTED');}
async function topup(pid){for(let attempt=0;attempt<3;attempt++){await nextProductTick();const before=writes.length;await cardAction('증거금 추가','100000');const sent=await poll(()=>writes.slice(before).find(x=>x.path.endsWith('/margin')));if(sent.status!==202)throw Error('MARGIN_HTTP_'+sent.status);if((await outcome(sent)).reconfirm){await current(pid,'OPEN');continue;}await poll(async()=>{const p=await current(pid,'OPEN');return p.collateral==='200000.000000000000000000'?p:false;});result.operations.push(await eventFor(pid,'TOP_UP',sent.body.id));return;}throw Error('MARGIN_RECONFIRM_EXHAUSTED');}
const deadline=setTimeout(()=>{result.timeout=true;budget.abort();browser?.kill();},465000);
try{
  result.step='START_VITE';
  const probe=createNetServer();await new Promise((resolve,reject)=>{probe.once('error',reject);probe.listen(0,'127.0.0.1',resolve);});const webPort=probe.address().port;await new Promise((resolve,reject)=>probe.close(error=>error?reject(error):resolve()));
  // Preserve the product Vite config's SockJS browser global mapping without importing local env/config.
  // Vite treats port=0 as its default5173, NOT an OS ephemeral port. Select a free explicit port, fail closed on a race.
  server=await createServer({configFile:false,envDir:out,cacheDir:join(out,'vite-cache'),root:resolve('tools/websocket-test-client'),define:{global:'globalThis'},server:{host:'127.0.0.1',port:webPort,strictPort:true,proxy:{'/api':base,'/ws':{target:base.replace('http','ws'),ws:true}}},logLevel:'silent'});await server.listen();if(server.httpServer.address().port!==webPort)throw Error('WEB_PORT_CONFLICT');result.webPort=webPort;const web=`http://127.0.0.1:${webPort}`;result.step='START_CHROME';
  browser=spawn('C:/Program Files/Google/Chrome/Application/chrome.exe',['--headless=new','--disable-gpu','--disable-background-networking','--disable-component-update','--disable-sync','--no-first-run','--no-default-browser-check','--remote-debugging-port=0',`--user-data-dir=${profile}`,'about:blank'],{windowsHide:true,stdio:'ignore'});
  const port=await poll(async()=>{try{return (await readFile(join(profile,'DevToolsActivePort'),'utf8')).split('\n')[0];}catch{return false;}});
  const tabs=await(await fetch(`http://127.0.0.1:${port}/json/list`,{signal:AbortSignal.any([budget.signal,AbortSignal.timeout(5000)])})).json();ws=new WebSocket(tabs.find(t=>t.type==='page').webSocketDebuggerUrl);await Promise.race([new Promise(r=>ws.addEventListener('open',r)),sleep(5000).then(()=>{throw Error('CDP_CONNECT_TIMEOUT');})]);
  const responses=new Map();
  ws.addEventListener('message',async e=>{const m=JSON.parse(e.data);if(m.id){const p=pending.get(m.id);pending.delete(m.id);if(m.error)p?.reject(Error('CDP_ERROR'));else p?.resolve(m.result);return;}
    if(m.method==='Runtime.exceptionThrown'){const error=m.params.exceptionDetails?.exception;result.browserErrors.push({code:error?.description?.includes('global is not defined')?'GLOBAL_UNDEFINED':['ReferenceError','TypeError','SyntaxError'].includes(error?.className)?error.className:'SCRIPT_EXCEPTION'});}
    if(m.method==='Network.webSocketFrameReceived')try{const r=m.params.response,raw=r.opcode===2?Buffer.from(r.payloadData,'base64').toString():r.payloadData;for(const f of raw.split('\0')){if(!f.includes('MESSAGE')||!f.includes('/queue/futures'))continue;const event=JSON.parse(f.slice(f.indexOf('\n\n')+2));result.events.push({type:event.type,eventId:event.eventId,data:event.data,at:new Date().toISOString()});}}catch{}
    if(m.method==='Network.responseReceived'){const p=m.params,url=new URL(p.response.url);if(p.type==='XHR'||p.type==='Fetch')responses.set(p.requestId,{path:url.pathname,status:p.response.status});}
    if(m.method==='Network.loadingFinished'){const r=responses.get(m.params.requestId);if(!r)return;responses.delete(m.params.requestId);if(r.path!=='/api/auth/login'&&!r.path.startsWith('/api/futures/'))return;try{const response=await command('Network.getResponseBody',{requestId:m.params.requestId}),body=JSON.parse(response.base64Encoded?Buffer.from(response.body,'base64').toString():response.body);if(r.path==='/api/auth/login'){token=body.accessToken;}else if(r.path!=='/api/futures/previews'&&!Array.isArray(body))writes.push({...r,body});}catch{}}
  });
  result.step='LOAD_PRODUCT_UI';await command('Page.enable');await command('Runtime.enable');await command('Network.enable');await command('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});await command('Page.navigate',{url:web});
  await poll(()=>evaluate("typeof document.getElementById('futures-preview-open')?.onclick==='function'"));
  result.step='LOGIN';await click('auth-open');await fill('login-id',account.loginId);await fill('password',account.password);await evaluate("document.getElementById('auth-form').requestSubmit()");await poll(()=>token);result.userId=(await api('/api/me')).id;
  result.step='FAUCET';await evaluate("location.hash='portfolio'");await poll(()=>evaluate("!document.getElementById('faucet').disabled"));await click('faucet');await poll(async()=>Number((await api('/api/portfolio')).krwBalance)===1000000);result.stages.push('LOGIN_FAUCET');
  await evaluate("location.hash='futures'");const normal=await open('LONG',2);await topup(normal);await close(normal);result.stages.push('LONG_2_TOPUP_CLOSE');
  const short=await open('SHORT',5);await close(short);result.stages.push('SHORT_5_CLOSE');
  // Actual wall time, no clock override or expired-report bypass.
  const beforeExpiry=writes.length;await click('futures-preview-open');await poll(()=>evaluate("!document.getElementById('futures-confirm').disabled"));await sleep(31000);await click('futures-confirm');await poll(()=>evaluate("document.getElementById('futures-message').textContent.includes('견적 만료')"));if(writes.length!==beforeExpiry)throw Error('EXPIRED_PREVIEW_SUBMITTED');result.stages.push('REAL_30S_PREVIEW_EXPIRY');
  for(const side of ['LONG','SHORT']){
    const pid=await open(side,10);result.step='WAIT_'+side+'_GRACE';await control(side==='LONG'?'LONG_CRASH':'SHORT_CRASH');const grace=await current(pid,'OPEN','GRACE');result.operations.push(await eventFor(pid,'START_CALL'));const begun=Date.now();await screenshot(side.toLowerCase()+'-grace',pid);result.step='WAIT_'+side+'_LIQUIDATION';
    const liquidated=await current(pid,'LIQUIDATED',null,100000);result.operations.push(await eventFor(pid,'LIQUIDATE'));if(Date.now()<grace.deadline*1000)throw Error('EARLY_LIQUIDATION');await screenshot(side.toLowerCase()+'-liquidated',pid);result.stages.push({name:side+'_AUTO_LIQUIDATED',positionId:pid,deadline:grace.deadline,observedGraceAt:begun,completedAt:Date.now(),position:liquidated});
  }
  const recovered=await open('LONG',10);await control('LONG_CALL');await current(recovered,'OPEN','GRACE');await control('LONG_RECOVER');await current(recovered,'OPEN','HEALTHY');await close(recovered);result.stages.push('GRACE_PRICE_RECOVERY');
  const added=await open('SHORT',10);await control('SHORT_CALL');await current(added,'OPEN','GRACE');await topup(added);await current(added,'OPEN','HEALTHY');await close(added);result.stages.push('GRACE_TOPUP_RECOVERY');
  const latestPid=(await api('/api/futures/positions'))[0].id;
  await command('Emulation.setDeviceMetricsOverride',{width:360,height:800,deviceScaleFactor:1,mobile:true});if(!await evaluate('document.documentElement.scrollWidth<=innerWidth'))throw Error('MOBILE_OVERFLOW');await screenshot('mobile-final',latestPid);
  await command('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});await screenshot('desktop-final',latestPid);
  result.positions=await api('/api/futures/positions');result.portfolio=await api('/api/portfolio');
  if(result.events.length===0||result.positions.some(p=>!['CLOSED','LIQUIDATED'].includes(p.status)&&!(p.status==='OPEN_FAILED'&&result.previewRejections.some(r=>r.positionId===p.id&&r.phase==='DEFINITIVE_UNSIGNED_REJECTION'))))throw Error('FINAL_STATE_OR_PRIVATE_WS');
  result.status='PASS';
}catch(error){result.status='FAIL';Object.assign(result,safeBrowserFailure(error));
  if(!budget.signal.aborted){try{result.failurePositions=await api('/api/futures/positions');}catch{}try{result.failureDom=await evaluate("[...document.getElementById('futures-positions').children].map(c=>({heading:c.querySelector('h2')?.textContent,fields:Object.fromEntries([...c.querySelectorAll('dt')].map(t=>[t.textContent,t.nextElementSibling?.textContent]))}))");await screenshot('failure');}catch{}}
}
finally{clearTimeout(deadline);result.finishedAt=new Date().toISOString();await writeFile(join(out,'result.json'),JSON.stringify(result,null,2),{flag:'wx'});if(ws?.readyState===WebSocket.OPEN){try{await command('Browser.close');}catch{}ws.close();}browser?.kill();await server?.close();console.log(JSON.stringify({status:result.status,stages:result.stages.length,error:result.error}));}
process.exitCode=result.status==='PASS'?0:1;
