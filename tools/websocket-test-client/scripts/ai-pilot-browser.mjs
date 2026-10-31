/** Real web/JWT/Agent/Tool/PG with controlled provider; not real-model quality acceptance. */
import {readFile,writeFile,mkdir,mkdtemp} from 'node:fs/promises';
import {join,resolve} from 'node:path';
import {spawn} from 'node:child_process';
import assert from 'node:assert/strict';
import {BoundedCdp,pollBounded,fetchJsonBounded} from './cdp-boundary.mjs';
import {findChrome} from './browser-executable.mjs';
const cfg=JSON.parse(await readFile(process.argv[2],'utf8')),out=resolve(process.argv[3]);
assert.match(cfg.origin,/^http:\/\/127\.0\.0\.1:[1-9][0-9]{3,4}$/);await mkdir(out);
const profile=await mkdtemp(join(out,'profile-'));let child,cdp,exited=false,passed=false;const codes=[];
// Own Chrome only; the Java supervisor waits longer than this bounded browser session.
function stopChrome(){if(child&&!exited){try{if(process.platform==='win32')child.kill();else process.kill(-child.pid,'SIGTERM');}catch{passed=false;}}}
const watchdog=setTimeout(()=>{passed=false;codes.push('BROWSER_DEADLINE');stopChrome();},150000);
const poll=fn=>pollBounded(fn,{timeoutMs:15000,intervalMs:100});
const evaluate=async expression=>{const r=await cdp.command('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true},5000);assert(!r.exceptionDetails);return r.result.value;};
const fill=(id,value)=>evaluate(`{const e=document.getElementById(${JSON.stringify(id)});e.value=${JSON.stringify(value)};e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));}`);
const click=id=>evaluate(`document.getElementById(${JSON.stringify(id)}).click()`);
async function login(user){await click('auth-open');await fill('login-id',user);await fill('password',cfg.password);await evaluate("document.getElementById('auth-form').requestSubmit()");await poll(()=>evaluate("!document.getElementById('logout').hidden"));await evaluate("location.hash='ai'");}
async function ask(kind,question,target=''){await fill('ai-kind',kind);await fill('ai-target',target);await fill('ai-question',question);await evaluate("document.getElementById('ai-form').requestSubmit()");await poll(()=>evaluate("!document.getElementById('ai-submit').disabled&&document.querySelector('#ai-result .answer')!==null"));return evaluate("document.getElementById('ai-result').textContent");}
try{
 const browser=findChrome();child=spawn(browser.executable,['--headless=new','--disable-gpu','--no-first-run','--no-default-browser-check','--remote-debugging-port=0',`--user-data-dir=${profile}`,'about:blank'],{windowsHide:true,detached:process.platform!=='win32',stdio:'ignore'});child.once('exit',()=>exited=true);child.once('error',()=>{exited=true;});
 const port=await poll(async()=>{assert(!exited);try{return (await readFile(join(profile,'DevToolsActivePort'),'utf8')).split('\n')[0];}catch{return null;}});
 const targets=await fetchJsonBounded(`http://127.0.0.1:${port}/json/list`,{timeoutMs:3000});const tab=targets.find(x=>x.type==='page');assert(tab);
 cdp=await BoundedCdp.connect(tab.webSocketDebuggerUrl,{timeoutMs:3000,onEvent:()=>{}});await cdp.command('Page.enable');await cdp.command('Network.enable');await cdp.command('Emulation.setDeviceMetricsOverride',{width:1366,height:768,deviceScaleFactor:1,mobile:false});
 await cdp.command('Page.navigate',{url:cfg.origin});await poll(()=>evaluate("document.getElementById('auth-open')!==null"));
 await login('ready-owner');let text=await ask('general','내 가용 잔고와 포트폴리오를 조회해줘');assert(text.includes('123.25')&&!text.includes('987.75'));codes.push('OWN_STATE_REAL_HTTP');
 text=await ask('general','대기 상태의 의미는?');assert(text.includes('정책 출처'));codes.push('RAG_REAL_HTTP_CONTROLLED_SYNTHESIS');
 text=await ask('quote','이 견적의 저장 상태와 만료 정책을 설명해줘',cfg.quoteId);assert(text.includes('관측 사실')&&text.includes('정책 출처')&&text.includes('진단 단계'));codes.push('SKILL_TOOL_RAG_REAL_HTTP');
 await writeFile(join(out,'01-ai-agent.png'),Buffer.from((await cdp.command('Page.captureScreenshot',{format:'png',captureBeyondViewport:false})).data,'base64'),{flag:'wx'});
 await click('logout');await poll(()=>evaluate("document.getElementById('logout').hidden"));assert.equal(await evaluate("document.getElementById('ai-result').textContent"),'');
 await login('ready-other');text=await ask('general','내 가용 잔고와 포트폴리오를 조회해줘');assert(text.includes('987.75')&&!text.includes('123.25'));codes.push('ACCOUNT_SWITCH_ISOLATION');
 await click('logout');await poll(()=>evaluate("document.getElementById('logout').hidden"));assert.equal(await evaluate("document.getElementById('ai-result').textContent"),'');
 await login('ready-admin');await fill('ai-kind','abnormal');await fill('ai-abnormal-filter','WAITING_LONG');await fill('ai-abnormal-limit','5');await fill('ai-abnormal-wait','120');
 text=await ask('abnormal','이상 주문 목록 조회');assert(text.includes('WAITING_LONG')&&text.includes('한도 5')&&text.includes('120초')&&text.includes('전체 이력 건수가 아닙니다'));codes.push('ADMIN_EXPLICIT_FILTER_REAL_HTTP');
 await writeFile(join(out,'02-ai-abnormal.png'),Buffer.from((await cdp.command('Page.captureScreenshot',{format:'png',captureBeyondViewport:false})).data,'base64'),{flag:'wx'});
 await click('logout');await poll(()=>evaluate("document.getElementById('logout').hidden"));assert.equal(await evaluate("document.getElementById('ai-result').textContent"),'');passed=true;
}catch(e){console.error('AI_BROWSER_FAILED_'+(e.code??e.name??'UNKNOWN'));}
finally{
 clearTimeout(watchdog);if(cdp)try{await cdp.close();}catch{passed=false;}stopChrome();if(child&&!exited){try{await pollBounded(()=>exited,{timeoutMs:5000});}catch{passed=false;}}
 await writeFile(join(out,'result.json'),JSON.stringify({passed,codes,controlledProvider:true,realModel:false,ownChromeExited:!child||exited}),{flag:'wx'});if(!passed)process.exitCode=1;
}
