import {accessSync,constants} from 'node:fs';
import path from 'node:path';
import {execFileSync} from 'node:child_process';

export class ChromeDiscoveryError extends Error {
  constructor(code){super(code);this.code=code;}
}
const fail=code=>{throw new ChromeDiscoveryError(code);};
function candidates(env,platform){
  const p=platform==='win32'?path.win32:path.posix;
  const names=platform==='win32'?['chrome.exe']:['google-chrome','google-chrome-stable','chromium','chromium-browser'];
  const found=(env.PATH??'').split(platform==='win32'?';':':').filter(Boolean).flatMap(dir=>names.map(name=>p.join(dir,name)));
  if(platform==='win32')for(const key of ['PROGRAMFILES','PROGRAMFILES(X86)','LOCALAPPDATA'])if(env[key])found.push(p.join(env[key],'Google','Chrome','Application','chrome.exe'));
  return found;
}
function probe(executable,platform,env){
  try{
    if(platform==='win32'){
      // Reading file version metadata does not launch Chrome or touch a user profile.
      const command='$v=(Get-Item -LiteralPath $env:TMS_CHROME_CANDIDATE -ErrorAction Stop).VersionInfo; [pscustomobject]@{name=$v.ProductName;version=$v.ProductVersion}|ConvertTo-Json -Compress';
      const value=JSON.parse(execFileSync('powershell.exe',['-NoProfile','-NonInteractive','-Command',command],{env:{...env,TMS_CHROME_CANDIDATE:executable},encoding:'utf8',timeout:5000,maxBuffer:65536,windowsHide:true}));
      return `${value.name} ${value.version}`;
    }
    return execFileSync(executable,['--version'],{env,encoding:'utf8',timeout:5000,maxBuffer:65536,windowsHide:true});
  }catch{fail('PRODUCT_READ_CHROME_VERSION_PROBE_FAILED');}
}
export function findChrome({env=process.env,platform=process.platform,exists,version=probe}={}){
  exists??=(p=>{try{accessSync(p,constants.X_OK);return true;}catch{return false;}});
  let executable;
  if(env.CHROME_PATH){executable=env.CHROME_PATH;if(!exists(executable))fail('PRODUCT_READ_CHROME_EXPLICIT_PATH_MISSING');}
  else executable=candidates(env,platform).find(exists);
  if(!executable)fail('PRODUCT_READ_CHROME_NOT_FOUND_SET_CHROME_PATH');
  let text;try{text=version(executable,platform,env);}catch{fail('PRODUCT_READ_CHROME_VERSION_PROBE_FAILED');}
  const match=/^(Google Chrome(?: for Testing)?|Chromium)\s+(\d+)\.[0-9.]+/.exec(text.trim());
  if(!match||Number(match[2])<112)fail('PRODUCT_READ_CHROME_UNSUPPORTED_VERSION');
  const expected=env.TMS_CHROME_EXPECTED_MAJOR;
  if(expected!==undefined&&(!/^[1-9][0-9]*$/.test(expected)||Number(expected)!==Number(match[2])))fail('PRODUCT_READ_CHROME_EXPECTED_MAJOR_MISMATCH');
  return {executable,major:Number(match[2]),family:match[1]};
}
