/** Equivalent Chromium sandbox labels, not a relaxation of the required layers. */
export function sandboxLayers(text){
 return {namespace:/Namespace sandbox\s+Yes\b/i.test(text)||/Layer 1 Sandbox\s+Namespace\b/i.test(text),
  pid:/PID namespaces\s+Yes\b/i.test(text),network:/Network namespaces\s+Yes\b/i.test(text),seccomp:/Seccomp-BPF sandbox\s+Yes\b/i.test(text)};
}
export const layersVerified=text=>Object.values(sandboxLayers(text)).every(Boolean);
/** Attached observers do not own the browser lifetime. close() would send Browser.close. */
export async function detachSandboxTab(browser,tab,target){
 try{if(browser&&target)await browser.command('Target.closeTarget',{targetId:target});}
 finally{tab?.dispose();browser?.dispose();}
}
