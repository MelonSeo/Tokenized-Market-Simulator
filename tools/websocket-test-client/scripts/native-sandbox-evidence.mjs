import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {resolve} from 'node:path';
export function restrictedLiveRenderers(tokens,active){return active.length>0&&active.every(id=>tokens.some(t=>t.pid===id&&t.status==='OBSERVED'&&t.restricted===true&&Number.isInteger(t.integrityRid)&&t.integrityRid>=0&&t.integrityRid<=4096));}
export async function nativeSandboxEvidence(browserCdp){
 assert.equal(process.platform,'win32');let tokens=[],active=[];
 for(let i=0;i<3;i++){
  const processes=(await browserCdp.command('SystemInfo.getProcessInfo')).processInfo.filter(p=>p.type==='renderer');assert(processes.length>0);
  tokens=JSON.parse(execFileSync(process.env.TMS_PROBE_PYTHON,['-B','-S',resolve('deployment/ai_pilot_native_probe.py'),'--tokens',...processes.map(p=>String(p.id))],{windowsHide:true,encoding:'utf8',timeout:10000,maxBuffer:65536}));
  active=(await browserCdp.command('SystemInfo.getProcessInfo')).processInfo.filter(p=>p.type==='renderer').map(p=>p.id);
  if(restrictedLiveRenderers(tokens,active))return {active,tokens,rendererRestricted:true,sandboxDisabled:false,scope:'RESTRICTED_TOKEN_NOT_FULL_SANDBOX_AUDIT'};
 }throw new Error('NATIVE_RENDERER_SANDBOX_UNVERIFIED');
}
