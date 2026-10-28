/** Explicit trial actor's 95%-105% bounds, not server defaults or product pricing policy. */
import {units,decimal} from '../src/c-direct-intent.js';
export function trialPriceBounds(price){
 const mark=units(String(price),8);
 return {minimum:decimal((mark*95n/100n).toString(),8),maximum:decimal((mark*105n/100n).toString(),8)};
}

/** Static HTML is not proof that the app module has attached its login handlers. */
export function trialLoginReady(document){
 return document.readyState==='complete'
  &&typeof document.getElementById('auth-open')?.onclick==='function'
  &&typeof document.getElementById('auth-form')?.onsubmit==='function';
}

/** Only booleans/enums: never form values, messages, JWTs, URLs or response bodies. */
export function trialLoginState(document){
 return {documentComplete:document.readyState==='complete',
  openHandler:typeof document.getElementById('auth-open')?.onclick==='function',
  submitHandler:typeof document.getElementById('auth-form')?.onsubmit==='function',
  dialogOpen:document.getElementById('auth-dialog')?.open===true,
  submitDisabled:document.getElementById('auth-submit')?.disabled===true,
  logoutVisible:document.getElementById('logout')?.hidden===false};
}

/** Reduce URLs to fixed categories; never export IDs, queries, hosts or response bodies. */
export function trialApiKind(url,origin){
 try{
  const u=new URL(url);if(u.origin!==origin)return null;
  if(u.pathname==='/api/auth/login')return 'LOGIN';
  if(u.pathname==='/api/me')return 'ME';
  if(u.pathname==='/api/c-futures/config')return 'C_CONFIG';
  if(u.pathname==='/api/c-futures/account')return 'C_ACCOUNT';
  if(u.pathname.startsWith('/api/c-futures/wallet'))return 'C_WALLET';
  if(u.pathname.startsWith('/api/c-futures/direct/intents'))return 'C_INTENT';
  return u.pathname.startsWith('/api/')?'OTHER_API':null;
 }catch{return null;}
}
