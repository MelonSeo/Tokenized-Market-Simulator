import test from 'node:test';
import assert from 'node:assert/strict';
import {findChrome} from '../scripts/browser-executable.mjs';
const version=()=> 'Google Chrome 155.0.1.2';
test('explicit Chrome executable is validated without launching a profile',()=>{
 assert.equal(findChrome({env:{CHROME_PATH:'/selected'},platform:'linux',exists:()=>true,version}).executable,'/selected');
});
test('missing explicit Chrome never falls back',()=>{
 assert.throws(()=>findChrome({env:{CHROME_PATH:'/missing',PATH:'/bin'},exists:()=>false,version}),/EXPLICIT_PATH_MISSING/);
});
test('Linux PATH discovery',()=>{
 assert.equal(findChrome({env:{PATH:'/bin'},platform:'linux',exists:p=>p==='/bin/google-chrome',version}).executable,'/bin/google-chrome');
});
test('Windows PATH discovery uses exe and semicolon',()=>{
 assert.equal(findChrome({env:{PATH:'X:\\bin;Y:\\bin'},platform:'win32',exists:p=>p==='Y:\\bin\\chrome.exe',version}).executable,'Y:\\bin\\chrome.exe');
});
test('Windows installation root comes from environment',()=>{
 const x=findChrome({env:{LOCALAPPDATA:'X:\\apps'},platform:'win32',exists:p=>p==='X:\\apps\\Google\\Chrome\\Application\\chrome.exe',version});assert.equal(x.major,155);
});
test('no executable is a clear redacted error',()=>{
 assert.throws(()=>findChrome({env:{PATH:''},exists:()=>false,version}),/NOT_FOUND_SET_CHROME_PATH/);
});
test('old browser and a different browser family are rejected',()=>{
 for(const text of ['Google Chrome 111.0.1.2','Microsoft Edge 155.0.1.2','unknown'])assert.throws(()=>findChrome({env:{CHROME_PATH:'/test'},exists:()=>true,version:()=>text}),/UNSUPPORTED_VERSION/);
});
test('explicit expected browser version cannot silently drift',()=>{
 assert.throws(()=>findChrome({env:{CHROME_PATH:'/test',TMS_CHROME_EXPECTED_MAJOR:'154'},exists:()=>true,version}),/EXPECTED_MAJOR_MISMATCH/);
});
test('probe errors never reveal paths',()=>{
 assert.throws(()=>findChrome({env:{CHROME_PATH:'/private'},exists:()=>true,version:()=>{throw Error('/private');}}),e=>!e.message.includes('/private')&&e.code==='PRODUCT_READ_CHROME_VERSION_PROBE_FAILED');
});
test('Chromium version is explicit',()=>{
 assert.equal(findChrome({env:{CHROME_PATH:'/test'},exists:()=>true,version:()=> 'Chromium 155.0.1.2'}).family,'Chromium');
});
