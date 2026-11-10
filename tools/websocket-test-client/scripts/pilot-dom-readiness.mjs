/** HTML presence is not module/handler readiness. Does not invoke or replace an app handler. */
export const loginReadyExpression="document.readyState==='complete' && typeof document.getElementById('auth-open')?.onclick==='function' && typeof document.getElementById('auth-form')?.onsubmit==='function'";
