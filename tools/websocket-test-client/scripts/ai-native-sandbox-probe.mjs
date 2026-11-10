/** Native Chrome sandbox observation in a fresh owned profile. No TMS/API/credentials. */
import assert from 'node:assert/strict';
import {readFile,writeFile,mkdtemp,open} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {spawn,execFileSync} from 'node:child_process';
import {findChrome} from './browser-executable.mjs';
import {BoundedCdp,pollBounded,fetchJsonBounded} from './cdp-boundary.mjs';
const folder=resolve(process.argv[2]);
assert.match(folder.replaceAll('\\','/'),/\/deployment\/runtime\/pilot-linux-[a-f0-9]{16}$/);
const profile=await mkdtemp(join(folder,'native-profile-'));const log=await open(join(folder,'native-chrome.private.log'),'wx');
const browser=findChrome();let child,cdp,browserCdp,exited=false;
const watchdog=setTimeout(()=>{if(child&&!exited)child.kill();},40000);watchdog.unref();
try{
 const args=['--headless=new','--disable-gpu','--disable-background-networking','--no-first-run','--no-default-browser-check','--remote-debugging-port=0',`--user-data-dir=${profile}`,'about:blank'];
 child=spawn(browser.executable,args,{windowsHide:true,stdio:['ignore','ignore',log.fd]});child.once('exit',()=>exited=true);child.once('error',()=>exited=true);
 const port=await pollBounded(async()=>{assert(!exited);try{return(await readFile(join(profile,'DevToolsActivePort'),'utf8')).split('\n')[0];}catch{return null;}},{timeoutMs:15000});
 const tabs=await fetchJsonBounded(`http://127.0.0.1:${port}/json/list`,{timeoutMs:3000});cdp=await BoundedCdp.connect(tabs.find(t=>t.type==='page').webSocketDebuggerUrl,{timeoutMs:3000});
 await cdp.command('Page.enable');await cdp.command('Page.navigate',{url:'chrome://sandbox'});
 const body=await pollBounded(async()=>{const r=await cdp.command('Runtime.evaluate',{expression:'document.body?.innerText',returnByValue:true});return typeof r.result.value==='string'&&/sandbox/i.test(r.result.value)?r.result.value:null;},{timeoutMs:10000});
 await cdp.command('Page.navigate',{url:'data:text/html,<p>sandbox-probe</p>'});
 await pollBounded(async()=>{const r=await cdp.command('Runtime.evaluate',{expression:"document.body?.innerText==='sandbox-probe'",returnByValue:true});return r.result.value;},{timeoutMs:5000});
 const info=await fetchJsonBounded(`http://127.0.0.1:${port}/json/version`,{timeoutMs:3000});browserCdp=await BoundedCdp.connect(info.webSocketDebuggerUrl,{timeoutMs:3000});
 const script=resolve('deployment/ai_pilot_native_probe.py');
 let tokens=[],active=[],verified=false;
 for(let i=0;i<3&&!verified;i++){
  const processes=(await browserCdp.command('SystemInfo.getProcessInfo')).processInfo.filter(p=>p.type==='renderer');assert(processes.length>0);
  tokens=JSON.parse(execFileSync(process.env.TMS_PROBE_PYTHON,['-B','-S',script,'--tokens',...processes.map(p=>String(p.id))],{windowsHide:true,encoding:'utf8',timeout:10000,maxBuffer:65536}));
  active=(await browserCdp.command('SystemInfo.getProcessInfo')).processInfo.filter(p=>p.type==='renderer').map(p=>p.id);
  // An exited spare renderer is not a live unsandboxed renderer. Every live PID still needs proof.
  verified=active.length>0&&active.every(id=>tokens.some(t=>t.pid===id&&t.status==='OBSERVED'&&t.restricted&&t.integrityRid<=4096));
 }
 await writeFile(join(folder,'native-sandbox.private.json'),JSON.stringify({platform:process.platform,node:process.version,family:browser.family,major:browser.major,args:args.filter(a=>!a.startsWith('--user-data-dir')),body,tokens,active,verified}),{flag:'wx'});
 assert(verified);
 console.log(JSON.stringify({nativeBrowserStarted:true,sandboxDisabled:false,rendererRestrictedTokens:true,platform:process.platform,major:browser.major,inspection:'PASS_RESTRICTED_RENDERER_NOT_FULL_AUDIT',paidCalls:0,apiKeyReads:0}));
}finally{
 clearTimeout(watchdog);
 if(browserCdp){try{await browserCdp.command('Browser.close',{},1000);}catch{}try{await browserCdp.close();}catch{}}
 if(cdp)try{await cdp.close();}catch{}
 if(child&&!exited)child.kill();if(child&&!exited)await pollBounded(()=>exited,{timeoutMs:5000});await log.close();
}
