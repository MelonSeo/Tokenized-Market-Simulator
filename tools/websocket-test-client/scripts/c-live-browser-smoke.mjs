/** Actual isolated Spring/PG/Anvil reads only. No mock API, signing, financial writes or paid AI. */
import http from 'node:http';
import { readFile, writeFile, mkdir, mkdtemp, realpath, readdir } from 'node:fs/promises';
import { appendFileSync } from 'node:fs';
import { resolve, join, extname, sep, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { spawn } from 'node:child_process';
import { BoundaryError, BoundedCdp, bounded, cleanupOwned, finalGate, fetchJsonBounded, pollBounded } from './cdp-boundary.mjs';

const reads = new Set(['/api/me','/api/auth/google/config','/api/me/auth-methods','/api/markets/mSEC','/api/markets/mSEC/candles',
  '/api/portfolio','/api/orders','/api/trades','/api/futures/positions','/api/conditional-orders/capabilities','/api/conditional-orders',
  '/api/wallet/faucet/status','/api/wallet/faucet/grants','/api/c-futures/config','/api/c-futures/wallet','/api/c-futures/account','/api/c-futures/direct/market',
  '/api/ai/diagnoses','/api/ai/observability','/api/admin/conditional-orders','/api/admin/transaction-observations',
  '/api/admin/trade-audits','/api/admin/reserve-reconciliations','/api/admin/reserve-reconciliations/baseline',
  '/api/admin/futures/audits','/api/admin/futures/audits/baseline','/api/admin/faucet','/api/admin/faucet/grants']);
export function allowedApi(method, path) { return method === 'GET' && reads.has(path) || method === 'POST' && path === '/api/auth/login'; }
export function validateConfig(c) {
  for (const key of ['appBaseUrl','origin']) { const u = new URL(c[key]); if (u.protocol !== 'http:' || u.hostname !== '127.0.0.1' || !u.port || u.username || u.password || u.pathname !== '/' || u.search || u.hash) throw new BoundaryError('CONFIG_ORIGIN'); }
  if (new URL(c.origin).port !== '32171' || c.origin === c.appBaseUrl) throw new BoundaryError('CONFIG_ORIGIN');
  for (const key of ['login0','login1','password']) if (typeof c[key] !== 'string' || !c[key] || c[key].length > 100) throw new BoundaryError('CONFIG_ACCOUNT');
  for (const key of ['wallet0','wallet1']) if (!/^0x[0-9a-fA-F]{40}$/.test(c[key])) throw new BoundaryError('CONFIG_WALLET');
  if (!/^\d+$/.test(String(c.chainId)) || !/^0x[0-9a-fA-F]{64}$/.test(c.genesisHash) || !c.sourceIdentity) throw new BoundaryError('CONFIG_IDENTITY');
  return c;
}
export function walletReadSource(wallet, chainId, genesisHash) {
  return `window.__readOnlyDeniedSigning??=0;window.ethereum={on(){},removeListener(){},async request({method,params}){if(method==='eth_accounts'||method==='eth_requestAccounts')return [${JSON.stringify(wallet)}];if(method==='eth_chainId')return ${JSON.stringify('0x'+BigInt(chainId).toString(16))};if(method==='eth_getBlockByNumber'&&params?.[0]==='0x0')return {hash:${JSON.stringify(genesisHash)}};window.__readOnlyDeniedSigning++;throw Object.assign(new Error('READ_ONLY_TEST_ADAPTER_SIGNING_AND_SEND_DISABLED'),{code:4100});}};`;
}
export function amount18(value) { const n=BigInt(value), fraction=(n%10n**18n).toString().padStart(18,'0').replace(/0+$/,'');return (n/10n**18n).toString()+(fraction?'.'+fraction:''); }
export function sameHashes(a,b) { return a&&b&&Object.keys(a).length===Object.keys(b).length&&Object.keys(a).every(k=>Object.hasOwn(b,k)&&a[k]===b[k]); }
export function allowedStatic(key, hashes) { return typeof key==='string'&&!key.includes('\\')&&!key.includes(':')&&key.split('/').every(p=>p&&p!=='.'&&p!=='..'&&p!=='.smoke')&&Object.hasOwn(hashes,key); }
async function distIdentity(dir,prefix='') { const result={};for(const e of (await readdir(join(dir,prefix),{withFileTypes:true})).sort((a,b)=>a.name.localeCompare(b.name))){if(e.name==='.smoke')continue;const key=prefix?prefix+'/'+e.name:e.name;if(e.isDirectory())Object.assign(result,await distIdentity(dir,key));else if(e.isFile())result[key]=createHash('sha256').update(await readFile(join(dir,key))).digest('hex');else throw new BoundaryError('DIST_LINK_UNSUPPORTED');}return result; }
export async function run(configPath, evidencePath) {
  const c = validateConfig(JSON.parse(await readFile(configPath,'utf8'))), out = resolve(evidencePath), dist = resolve('dist');
  const distBefore=await distIdentity(dist);if(!sameHashes(c.distHashes,distBefore))throw new BoundaryError('DIST_HASH_MISMATCH');
  await mkdir(out); const profile = await mkdtemp(join(out,'profile-')), tracePath = join(out,'boundaries.jsonl');
  await writeFile(tracePath,'',{flag:'wx'}); let traceFailed = false;
  const trace = v => { try { appendFileSync(tracePath,JSON.stringify(v)+'\n'); } catch { traceFailed=true; } };
  const end=Date.now()+85000, budget=ms=>Math.min(ms,end-Date.now()), api=[], images=[], pages=[], sockets=new Set(), upstreams=new Set();
  let cdp,child,exited=false,spawnFailed=false,passed=false,failure='NOT_STARTED',runtimeError=false,wsUpgrades=0,accountRendered=false;
  let latestBalance=null, deniedSigning=0;
  const server=http.createServer(async(req,res)=>{
    try {
      const u=new URL(req.url,c.origin), p=u.pathname;
      if(p.startsWith('/api/')) {
        if(!allowedApi(req.method,p)){api.push({method:req.method,path:reads.has(p)?p:'DENIED_PATH',status:403});res.writeHead(403,{'Content-Type':'application/json'});res.end('{"error":"READ_ONLY_GATE_DENIED"}');return;}
        if(req.headers.origin && req.headers.origin!==c.origin){res.writeHead(403);res.end();return;}
        let size=0;const chunks=[];for await(const chunk of req){size+=chunk.length;if(size>8192)throw new Error();chunks.push(chunk);}
        const controller=new AbortController(), timer=setTimeout(()=>controller.abort(),5000);
        try {
          const headers={connection:'close'};for(const name of ['authorization','content-type','origin'])if(req.headers[name])headers[name]=req.headers[name];
          const reply=await fetch(new URL(p+u.search,c.appBaseUrl),{method:req.method,headers,body:req.method==='POST'?Buffer.concat(chunks):undefined,signal:controller.signal,redirect:'error'});
          const data=Buffer.from(await reply.arrayBuffer());api.push({method:req.method,path:p,status:reply.status});
          if(p==='/api/c-futures/account'&&reply.status===200)try{latestBalance=JSON.parse(data).value.walletBalance;}catch{}
          res.writeHead(reply.status,{'Content-Type':reply.headers.get('content-type')??'application/json'});res.end(data);
        } finally {clearTimeout(timer);}return;
      }
      if(req.method!=='GET'){res.writeHead(403);res.end();return;}
      const path=await realpath(join(dist,decodeURIComponent(p==='/'?'index.html':p.slice(1))));
      if(!path.startsWith(dist+sep)){res.writeHead(403);res.end();return;}
      if(!allowedStatic(relative(dist,path).split(sep).join('/'),distBefore)){res.writeHead(403);res.end();return;}
      const data=await readFile(path), types={'.html':'text/html','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml','.png':'image/png'};
      res.writeHead(200,{'Content-Type':types[extname(path)]??'application/octet-stream','Content-Security-Policy':`default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self' ws://127.0.0.1:32171; font-src 'self'; object-src 'none'; frame-src 'none'`});res.end(data);
    }catch{if(!res.headersSent)res.writeHead(502,{'Content-Type':'application/json'});res.end('{"error":"READ_GATE_PROXY_FAILURE"}');}
  });
  server.on('connection',s=>{sockets.add(s);s.once('close',()=>sockets.delete(s));});
  server.on('upgrade',(req,socket,head)=>{
    if(req.url!=='/ws'||req.headers.origin!==c.origin){socket.destroy();return;}
    const upstream=http.request(new URL('/ws',c.appBaseUrl),{headers:{...req.headers,host:new URL(c.appBaseUrl).host},timeout:5000});upstreams.add(upstream);
    upstream.on('upgrade',(reply,remote,tail)=>{wsUpgrades++;remote.setTimeout(0);sockets.add(remote);remote.once('close',()=>sockets.delete(remote));socket.write('HTTP/1.1 101 Switching Protocols\r\n'+Object.entries(reply.headers).map(([k,v])=>`${k}: ${v}`).join('\r\n')+'\r\n\r\n');if(tail.length)socket.write(tail);if(head.length)remote.write(head);remote.pipe(socket);socket.pipe(remote);socket.once('close',()=>remote.destroy());remote.once('close',()=>socket.destroy());});
    upstream.on('timeout',()=>{upstream.destroy();socket.destroy();});upstream.on('error',()=>socket.destroy());upstream.on('response',()=>{upstream.destroy();socket.destroy();});upstream.once('close',()=>upstreams.delete(upstream));upstream.end();
  });
  const poll=op=>pollBounded(op,{timeoutMs:budget(9000),trace}), cmd=(method,params={})=>cdp.command(method,params,budget(7000));
  const evaluate=async expression=>{const r=await cmd('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(r.exceptionDetails)throw new BoundaryError('UI_EVALUATION');return r.result.value;};
  const click=id=>evaluate(`document.getElementById(${JSON.stringify(id)}).click()`);
  const fill=(id,value)=>evaluate(`{const e=document.getElementById(${JSON.stringify(id)});e.value=${JSON.stringify(value)};e.dispatchEvent(new Event('input',{bubbles:true}));}`);
  const capture=async name=>{const image=await cmd('Page.captureScreenshot',{format:'png',captureBeyondViewport:false}), data=Buffer.from(image.data,'base64');await writeFile(join(out,name+'.png'),data,{flag:'wx'});images.push({name,sha256:createHash('sha256').update(data).digest('hex')});};
  const assert=condition=>{if(!condition)throw new BoundaryError('REQUIRED_UI_READ_GATE');};
  async function login(name,wallet){await evaluate(walletReadSource(wallet,c.chainId,c.genesisHash));await click('auth-open');if(name===c.login0)await capture('login-dialog');await fill('login-id',name);await fill('password',c.password);await evaluate("document.getElementById('auth-form').requestSubmit()");await poll(()=>evaluate("!document.getElementById('logout').hidden"));assert(api.some(a=>a.path==='/api/me'&&a.status===200));}
  try {
    await bounded('ACTUAL_UI_PROXY_LISTEN',()=>new Promise((ok,no)=>{server.once('error',no);server.listen(32171,'127.0.0.1',()=>{server.removeListener('error',no);ok();});}),{timeoutMs:budget(3000),trace});
    child=spawn(process.env.CHROME_PATH??'C:/Program Files/Google/Chrome/Application/chrome.exe',['--headless=new','--disable-gpu','--no-first-run','--no-default-browser-check','--remote-debugging-port=0',`--user-data-dir=${profile}`,'about:blank'],{windowsHide:true,stdio:'ignore'});
    child.once('exit',()=>{exited=true;});child.once('error',()=>{spawnFailed=true;});
    const debug=await poll(async()=>{if(exited||spawnFailed)throw new BoundaryError('CHROME_START');return(await readFile(join(profile,'DevToolsActivePort'),'utf8')).split('\n')[0];});assert(/^\d+$/.test(debug));
    const targets=await fetchJsonBounded(`http://127.0.0.1:${debug}/json/list`,{timeoutMs:budget(3000),trace}), target=targets.find(t=>t.type==='page'&&t.url==='about:blank');assert(target);
    const ws=new URL(target.webSocketDebuggerUrl);assert(ws.protocol==='ws:'&&['127.0.0.1','localhost'].includes(ws.hostname)&&ws.port===debug);
    cdp=await BoundedCdp.connect(ws.href,{timeoutMs:budget(3000),trace,onEvent:m=>{if(m==='Runtime.exceptionThrown')runtimeError=true;}});
    await cmd('Runtime.enable');await cmd('Page.enable');await cmd('Emulation.setDeviceMetricsOverride',{width:1366,height:768,deviceScaleFactor:1,mobile:false});
    await cmd('Page.addScriptToEvaluateOnNewDocument',{source:walletReadSource(c.wallet0,c.chainId,c.genesisHash)});
    await cmd('Page.addScriptToEvaluateOnNewDocument',{source:`document.addEventListener('DOMContentLoaded',()=>{const b=document.createElement('p');b.textContent='실제 격리 서비스·읽기 전용 / 시험 계정 / 지갑 서명·금융·AI 실행 OFF';b.style.cssText='background:#fff2bb;padding:8px';document.body.prepend(b);});`});
    await cmd('Page.navigate',{url:c.origin});await poll(()=>evaluate("document.querySelectorAll('#market-chart canvas').length>0&&document.getElementById('price').textContent!=='—'"));
    await poll(()=>wsUpgrades>0);await poll(()=>evaluate("document.getElementById('connection').textContent==='실시간 연결됨'"));await poll(()=>api.some(a=>a.path==='/api/markets/mSEC/candles'&&a.status===200));assert(api.some(a=>a.path==='/api/markets/mSEC'&&a.status===200));
    await login(c.login0,c.wallet0);
    for(const route of ['market','futures','custody','ai']){
      const apiStart=api.length;
      await evaluate(`location.hash=${JSON.stringify(route)}`);
      if(route==='futures'){await poll(()=>api.slice(apiStart).some(a=>a.path==='/api/futures/positions'&&[403,404].includes(a.status)));await poll(()=>evaluate("/비활성|조회 실패/.test(document.getElementById('futures-message').textContent)"));}
      if(route==='custody'){await poll(()=>evaluate("!document.getElementById('c-connect').disabled"));await click('c-connect');await poll(()=>evaluate("document.getElementById('c-message').textContent.includes('연결 완료')"));await click('c-refresh');await poll(()=>evaluate("document.querySelectorAll('#c-account strong').length===6"));assert(typeof latestBalance==='string'&&BigInt(latestBalance)===0n);accountRendered=await evaluate(`document.querySelector('#c-account strong').textContent===${JSON.stringify(amount18(latestBalance)+' cMKRW')}`);assert(accountRendered);}
      await poll(()=>evaluate(`document.getElementById('view-${route}')?.hidden===false`));assert(!runtimeError);await evaluate('window.scrollTo(0,0)');await capture(route);
      if(route==='market'){await evaluate("document.getElementById('market-chart').scrollIntoView({block:'center'})");await capture('market-chart');await evaluate("document.getElementById('trade-form').scrollIntoView()");await capture('spot-form');}
      if(route==='custody'){await evaluate("document.getElementById('c-account').scrollIntoView()");await capture('c-account-metrics');}
      pages.push({route,rendered:true,api:api.slice(apiStart),scope:route==='custody'?'ACTUAL_ZERO_FUNDED_TEST_ACCOUNT_READ_NOT_USER_BALANCE':'ACTUAL_READ_ONLY_ACTIONS_OFF',controls:await evaluate(`Array.from(document.querySelectorAll('#view-${route} button')).map(b=>({id:b.id,disabled:b.disabled}))`)});
    }
    await click('logout');await login(c.login1,c.wallet1);assert(await evaluate("!document.getElementById('admin-nav').hidden"));const adminStart=api.length;await evaluate("location.hash='admin'");await poll(()=>evaluate("document.getElementById('view-admin')?.hidden===false"));
    await poll(()=>api.slice(adminStart).some(a=>['/api/admin/trade-audits','/api/admin/futures/audits'].includes(a.path)&&[403,404].includes(a.status)));
    await poll(()=>evaluate("/조회에 실패|조회하지 못|기능이 없습니다/.test(document.getElementById('audit-message').textContent+' '+document.getElementById('futures-audit-message').textContent)"));
    await evaluate('window.scrollTo(0,0)');await capture('admin');await evaluate("document.getElementById('audit-result').scrollIntoView()");await capture('admin-audit-off');pages.push({route:'admin',rendered:true,api:api.slice(adminStart),controls:await evaluate("Array.from(document.querySelectorAll('#view-admin button')).map(b=>({id:b.id,disabled:b.disabled}))"),scope:'ACTUAL_ADMIN_READ_ONLY_AUDIT_BACKEND_OFF'});
    deniedSigning=await evaluate('window.__readOnlyDeniedSigning');
    assert(!runtimeError&&deniedSigning===0&&accountRendered&&api.some(a=>a.path==='/api/c-futures/config'&&a.status===200)&&api.some(a=>a.path==='/api/c-futures/account'&&a.status===200));passed=true;failure=null;
  }catch(e){failure=e instanceof BoundaryError?e.code:'ACTUAL_UI_SAFE_FAILURE';}
  finally{
    if(cdp)await cdp.close(700);for(const request of upstreams)request.destroy();for(const socket of sockets)socket.destroy();
    const cleanup=await cleanupOwned({child,hasExited:()=>exited,spawnFailed,server,connections:sockets,trace,exitWaitMs:700,killWaitMs:700,closeWaitMs:700});
    let distUnchanged=false;try{distUnchanged=sameHashes(distBefore,await distIdentity(dist));}catch{}if(!distUnchanged){passed=false;failure='DIST_CHANGED';}
    const result={...finalGate({uiAssertionsPassed:passed,...cleanup,traceFailed,failure}),scope:'ACTUAL_SERVICE_READ_ONLY_TEST_WALLET_ADAPTER_NOT_METAMASK_NOT_TRADING',sourceIdentity:c.sourceIdentity,origin:c.origin,wsUpgrades,pages,images,api,runtimeError,accountRendered,deniedSigning,paidCalls:0,financialCalls:0,distBefore,distUnchanged,cPosition:'NOT_RUN_NO_POSITION',genesisSource:'CONFIG_ONLY_NOT_WALLET_RPC_ACCEPTANCE',...cleanup,traceFailed,externalRequestsAllowed:false};
    try{await writeFile(join(out,'result.json'),JSON.stringify(result,null,2),{flag:'wx'});}catch{result.verdict='BLOCKED';result.failure='RESULT_WRITE_FAILED';}
    console.log(JSON.stringify({verdict:result.verdict,failure:result.failure}));return result.verdict==='PASS'?0:1;
  }
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)){try{process.exitCode=await run(process.argv[2],process.argv[3]);}catch{console.error('ACTUAL_UI_SAFE_PREFLIGHT_FAILURE');process.exitCode=1;}}
