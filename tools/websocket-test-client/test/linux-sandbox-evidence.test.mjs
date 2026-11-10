import test from 'node:test';import assert from 'node:assert/strict';
import {layersVerified,detachSandboxTab} from '../scripts/linux-sandbox-evidence.mjs';
test('new Layer1Namespace and old NamespaceYes are equivalent only with every required layer',()=>{
 const other='PID namespaces\tYes\nNetwork namespaces\tYes\nSeccomp-BPF sandbox\tYes';
 assert(layersVerified('Layer 1 Sandbox\tNamespace\n'+other));assert(layersVerified('Namespace sandbox\tYes\n'+other));
 for(const prefix of ['Layer 1 Sandbox\tNone','Namespace sandbox\tNo','Sandbox Status'])assert(!layersVerified(prefix+'\n'+other));
 for(const field of ['PID namespaces','Network namespaces','Seccomp-BPF sandbox'])assert(!layersVerified(('Layer 1 Sandbox\tNamespace\n'+other).replace(field+'\tYes',field+'\tNo')));
});
test('attached sandbox observer closes only its own tab, never the actual pilot browser',async()=>{
 const calls=[];const browser={command:async(method,args)=>calls.push([method,args]),dispose:()=>calls.push('browser-detach')};
 await detachSandboxTab(browser,{dispose:()=>calls.push('tab-detach')},'owned-proof-tab');
 assert.deepEqual(calls,[['Target.closeTarget',{targetId:'owned-proof-tab'}],'tab-detach','browser-detach']);
});
test('both observer sockets detach even if the owned proof-tab close fails',async()=>{
 const calls=[];await assert.rejects(detachSandboxTab({command:async()=>{throw Error('close failed')},dispose:()=>calls.push('browser')},{dispose:()=>calls.push('tab')},'proof'));
 assert.deepEqual(calls,['tab','browser']);
});
