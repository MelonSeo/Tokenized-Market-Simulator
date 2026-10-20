// Pure assertions shared by the actual-browser runner and offline negative tests. No product/test response injection.
export function futuresCardMatches(p,card,status,risk){
  const label={OPEN:'포지션 유지',CLOSED:'종료 확정',LIQUIDATED:'자동청산 확정'}[status];
  if(!card||card.heading!==`${p.side} · ${p.leverage}배 · ${label}`)return false;
  const f=card.fields;
  if(f?.['수량']!==`${p.quantity} mSEC 가격 노출`||f['DB 격리증거금']!==p.collateral||f['진입 / 평가 가격']?.split(' / ')[0]!==p.entryPrice)return false;
  if(f['확정 실현손익 / 악성 부채 기록']!==`${p.realized??'미확정'} / ${p.badDebt??'미확정'}`)return false;
  if(risk==='GRACE'&&!f['위험 상태']?.startsWith(`마진콜 · 체인 유예 종료 ${card.deadlineText} · 남은 `))return false;
  if(risk==='HEALTHY'&&f['위험 상태']!=='HEALTHY')return false;
  return true;
}
export function unsignedPriceReconfirmation(op){return op.state==='REJECTED'&&op.reason==='PRICE_CHANGED'&&op.txHash===null&&op.nonce===null;}
const faucetPaths=new Set(['/api/wallet/faucet','/api/wallet/faucet/status','/api/wallet/faucet/grants']);
const faucetCodes=new Set(['AUTHENTICATION_REQUIRED','ACCESS_DENIED','FAUCET_DB_UNAVAILABLE','FAUCET_POLICY_UNAVAILABLE','FAUCET_ACCOUNTING_INCONSISTENT','FAUCET_DAILY_LIMIT','FAUCET_LIFETIME_LIMIT','FAUCET_BUDGET_EXHAUSTED','FAUCET_INVALID_KEY','FAUCET_KEY_CONFLICT']);
/** Diagnostics only. Never preserve an original body, URL query, key, header or arbitrary error string. */
export function faucetObservation(path,status,body){
  if(!faucetPaths.has(path)||!Number.isInteger(status)||status<0||status>599)return null;
  const code=value=>faucetCodes.has(value)?value:value==null?null:'UNRECOGNIZED';
  return {path,status,code:code(body?.code),reason:code(body?.reason),eligible:typeof body?.eligible==='boolean'?body.eligible:null};
}
export function previewObservation(status,body){
  if(!Number.isInteger(status)||status<100||status>599)return null;
  const codes=new Set(['PRICE_CHANGED','MARKET_UNAVAILABLE','MARKET_CONNECTION_CHANGED','PREVIEW_EXPIRED','INSUFFICIENT_AVAILABLE_MARGIN','ACTIVE_POSITION_EXISTS','POSITION_BUSY_OR_CLOSED','STALE_POSITION_VERSION','INVALID_MARGIN','AUTHENTICATION_REQUIRED','ACCESS_DENIED']);
  return {status,code:codes.has(body?.code)?body.code:body?.code==null?null:'UNRECOGNIZED'};
}
export function safeBrowserFailure(error){
  const allowed=/^(WAIT_TIMEOUT|BROWSER_DEADLINE|CDP_[A-Z_]+|BROWSER_SCRIPT_FAILED|POSITION_[A-Z_]+|OPERATION_[A-Z_]+|READ_API_[0-9]{3}|(?:OPEN|CLOSE|MARGIN|PREVIEW)_HTTP_[0-9]{3}|[A-Z_]+_RECONFIRM_EXHAUSTED|PREVIEW_RECONFIRM_EXHAUSTED|EXPIRED_PREVIEW_SUBMITTED|EARLY_LIQUIDATION|MOBILE_OVERFLOW|FINAL_STATE_OR_PRIVATE_WS)$/;
  const types=new Set(['Error','TypeError','TimeoutError','AbortError','SyntaxError']);
  const causes=new Set(['ECONNREFUSED','ECONNRESET','ETIMEDOUT','EADDRNOTAVAIL','EADDRINUSE','ENOSPC','EACCES','EPERM','ENOENT','UND_ERR_CONNECT_TIMEOUT','UND_ERR_HEADERS_TIMEOUT','UND_ERR_SOCKET']);
  const code=error?.cause?.code??error?.code;
  return {error:allowed.test(error?.message)?error.message:/^Port \d+ is already in use$/.test(error?.message)?'WEB_PORT_IN_USE':'BROWSER_STEP_FAILED',errorType:types.has(error?.name)?error.name:'UNKNOWN',causeCode:causes.has(code)?code:null,ownLocation:error?.stack?.match(/futures-browser(?:-assertions)?\.mjs:\d+:\d+/)?.[0]??null};
}
