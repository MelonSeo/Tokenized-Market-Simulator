/** Private read-only failure capture; never reads form values, storage, headers or cookies. */
import assert from 'node:assert/strict';import {writeFile} from 'node:fs/promises';
import {BoundedCdp,fetchJsonBounded} from './cdp-boundary.mjs';
const port=Number(process.argv[2]),out=process.argv[3];assert(Number.isInteger(port)&&port>0&&port<=65535);assert.equal(out,'/run/pilot/browser/stop-diagnostic.private.json');
let cdp;try{
 const tabs=await fetchJsonBounded(`http://127.0.0.1:${port}/json/list`,{timeoutMs:3000});const tab=tabs.find(t=>t.type==='page'&&t.url.startsWith('http://127.0.0.1:8080'));assert(tab);
 cdp=await BoundedCdp.connect(tab.webSocketDebuggerUrl,{timeoutMs:3000});const body=await cdp.command('Runtime.evaluate',{expression:"({text:document.body.innerText.slice(0,12000),title:document.title,authFormVisible:!!document.getElementById('auth-form')?.getClientRects().length,logoutHidden:document.getElementById('logout')?.hidden})",returnByValue:true});
 await writeFile(out,JSON.stringify(body.result.value),{flag:'wx'});const png=await cdp.command('Page.captureScreenshot',{format:'png'});await writeFile(out+'.png',Buffer.from(png.data,'base64'),{flag:'wx'});
}finally{cdp?.dispose();}
