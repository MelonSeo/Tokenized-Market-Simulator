/** Inspect the actual pilot browser in a separate sandbox-status tab before request1. */
import assert from 'node:assert/strict';import {readFile,writeFile} from 'node:fs/promises';
import {BoundedCdp,pollBounded,fetchJsonBounded} from './cdp-boundary.mjs';
import {layersVerified,detachSandboxTab} from './linux-sandbox-evidence.mjs';
assert.equal(process.platform,'linux');const port=Number(process.argv[2]),out=process.argv[3];assert(Number.isInteger(port)&&port>0&&port<=65535);assert.equal(out,'/run/pilot/browser/linux-sandbox.private.json');
let browser,tab,target;
try{
 const v=await fetchJsonBounded(`http://127.0.0.1:${port}/json/version`,{timeoutMs:3000});browser=await BoundedCdp.connect(v.webSocketDebuggerUrl,{timeoutMs:3000});
 target=(await browser.command('Target.createTarget',{url:'chrome://sandbox'})).targetId;
 const t=await pollBounded(async()=>{const ts=await fetchJsonBounded(`http://127.0.0.1:${port}/json/list`,{timeoutMs:2000});return ts.find(t=>t.id===target);},{timeoutMs:4000});
 tab=await BoundedCdp.connect(t.webSocketDebuggerUrl,{timeoutMs:3000});
 const body=await pollBounded(async()=>{const r=await tab.command('Runtime.evaluate',{expression:'document.body?.innerText',returnByValue:true});return layersVerified(r.result.value??'')?r.result.value:null;},{timeoutMs:10000});
 let active=[],rows=[],verified=false;
 for(let attempt=0;attempt<3&&!verified;attempt++){
  const ids=(await browser.command('SystemInfo.getProcessInfo')).processInfo.filter(p=>p.type==='renderer').map(p=>p.id);rows=[];
  for(const pid of ids){assert(Number.isSafeInteger(pid)&&pid>0);try{const s=await readFile(`/proc/${pid}/status`,'utf8');rows.push({pid,uid:Number(s.match(/^Uid:\s+(\d+)/m)?.[1]),noNewPrivs:Number(s.match(/^NoNewPrivs:\s+(\d+)/m)?.[1]),seccomp:Number(s.match(/^Seccomp:\s+(\d+)/m)?.[1]),capEff:s.match(/^CapEff:\s+([0-9a-f]+)/m)?.[1]});}catch{rows.push({pid,unavailable:true});}}
  active=(await browser.command('SystemInfo.getProcessInfo')).processInfo.filter(p=>p.type==='renderer').map(p=>p.id);verified=active.length>0&&active.every(id=>rows.some(p=>p.pid===id&&p.uid===1000&&p.noNewPrivs===1&&p.seccomp===2&&/^0+$/.test(p.capEff??'')));
 }
 await writeFile(out,JSON.stringify({verified,body,rows,active,platform:process.platform,proof:'SAME_REAL_PILOT_BROWSER_BEFORE_FIRST_SUBMISSION'}),{flag:'wx'});assert(verified);
}finally{await detachSandboxTab(browser,tab,target);}
