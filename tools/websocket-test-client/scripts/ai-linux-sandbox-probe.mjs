/** Observe a real Linux renderer and chrome://sandbox. No TMS/provider requests. */
import assert from 'node:assert/strict';
import {readFile,writeFile,mkdtemp,open} from 'node:fs/promises';
import {spawn} from 'node:child_process';
import {join,resolve} from 'node:path';
import {findChrome} from './browser-executable.mjs';
import {BoundedCdp,pollBounded,fetchJsonBounded} from './cdp-boundary.mjs';
import {layersVerified} from './linux-sandbox-evidence.mjs';
assert.equal(process.platform,'linux');const out=resolve(process.argv[2]);assert.equal(out,'/run/probe');
const profile=await mkdtemp(join(out,'chrome-profile-')),log=await open(join(out,'chrome.private.log'),'wx');
let child,cdp,browserCdp,exited=false;
const watchdog=setTimeout(()=>{if(child&&!exited)try{process.kill(-child.pid,'SIGKILL');}catch{}},30000);watchdog.unref();
try{
 const browser=findChrome();const args=['--headless=new','--disable-gpu','--disable-background-networking','--no-first-run','--no-default-browser-check','--remote-debugging-port=0',`--user-data-dir=${profile}`,'about:blank'];
 child=spawn(browser.executable,args,{detached:true,stdio:['ignore','ignore',log.fd]});child.once('exit',()=>exited=true);child.once('error',()=>exited=true);
 const port=await pollBounded(async()=>{assert(!exited);try{return(await readFile(join(profile,'DevToolsActivePort'),'utf8')).split('\n')[0];}catch{return null;}},{timeoutMs:10000});
 const tabs=await fetchJsonBounded(`http://127.0.0.1:${port}/json/list`,{timeoutMs:3000});cdp=await BoundedCdp.connect(tabs.find(t=>t.type==='page').webSocketDebuggerUrl,{timeoutMs:3000});
 await cdp.command('Page.enable');const navigation=await cdp.command('Page.navigate',{url:'chrome://sandbox'});
 let body='',pageVerified=false,pageError=null;
 try{body=await pollBounded(async()=>{const r=await cdp.command('Runtime.evaluate',{expression:'document.body?.innerText',returnByValue:true});const text=r.result.value;if(typeof text==='string')body=text;return typeof text==='string'&&layersVerified(text)?text:null;},{timeoutMs:10000});pageVerified=true;}catch(error){pageError=error.code??error.name;}
 await cdp.command('Page.navigate',{url:'data:text/html,<p>linux-sandbox-probe</p>'});
 await pollBounded(async()=>{const r=await cdp.command('Runtime.evaluate',{expression:"document.body?.innerText==='linux-sandbox-probe'",returnByValue:true});return r.result.value;},{timeoutMs:3000});
 const info=await fetchJsonBounded(`http://127.0.0.1:${port}/json/version`,{timeoutMs:3000});browserCdp=await BoundedCdp.connect(info.webSocketDebuggerUrl,{timeoutMs:3000});
 let observed=[],active=[],verified=false;
 for(let i=0;i<3&&!verified;i++){
  const ids=(await browserCdp.command('SystemInfo.getProcessInfo')).processInfo.filter(p=>p.type==='renderer').map(p=>p.id);
  observed=[];for(const pid of ids){assert(Number.isSafeInteger(pid)&&pid>0);try{const s=await readFile(`/proc/${pid}/status`,'utf8');observed.push({pid,uid:Number(s.match(/^Uid:\s+(\d+)/m)?.[1]),noNewPrivs:Number(s.match(/^NoNewPrivs:\s+(\d+)/m)?.[1]),seccomp:Number(s.match(/^Seccomp:\s+(\d+)/m)?.[1]),capEff:s.match(/^CapEff:\s+([0-9a-f]+)/m)?.[1]});}catch{observed.push({pid,unavailable:true});}}
  active=(await browserCdp.command('SystemInfo.getProcessInfo')).processInfo.filter(p=>p.type==='renderer').map(p=>p.id);
  verified=active.length>0&&active.every(pid=>observed.some(p=>p.pid===pid&&p.uid===1000&&p.noNewPrivs===1&&p.seccomp===2&&/^0+$/.test(p.capEff??'')));
 }
 await writeFile(join(out,'linux-sandbox.private.json'),JSON.stringify({verified:verified&&pageVerified,rendererVerified:verified,pageVerified,pageError,navigation,body,observed,active,platform:process.platform,major:browser.major,args:args.filter(a=>!a.startsWith('--user-data-dir'))}),{flag:'wx'});assert(verified&&pageVerified);
 console.log(JSON.stringify({sandboxCapability:true,realSixRequests:'NOT_RUN'}));
}finally{
 clearTimeout(watchdog);if(browserCdp){try{await browserCdp.command('Browser.close',{},1000);}catch{}try{await browserCdp.close();}catch{}}
 if(cdp)try{await cdp.close();}catch{}
 if(child&&!exited){try{process.kill(-child.pid,'SIGTERM');}catch{}try{await pollBounded(()=>exited,{timeoutMs:3000});}catch{try{process.kill(-child.pid,'SIGKILL');}catch{}await pollBounded(()=>exited,{timeoutMs:3000});}}
 await log.close();
}
