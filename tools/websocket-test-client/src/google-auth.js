/** GIS is loaded only after the local config enables it and the user starts an attempt.
 * No tokens/nonces/passwords go to storage, URLs, debug output, or another tab.
 */
export class GoogleAttempt {
  #generation = 0;
  #active = null;
  cancel() { this.#generation++; this.#active = null; }
  async start(purpose, issue, render, exchange, current = () => true) {
    this.cancel();
    const generation = this.#generation;
    const fresh = () => generation === this.#generation && current();
    const challenge = await issue();
    if (!fresh()) return;
    if (challenge.purpose !== purpose) throw new Error('Google challenge purpose mismatch');
    this.#active = challenge;
    render(challenge.nonce, async credential => {
      if (!fresh() || this.#active !== challenge) return;
      // A single GIS callback is exchangeable. A timeout never causes automatic resubmission.
      this.#active = null;
      if (Date.parse(challenge.expiresAt) <= Date.now()) throw new Error('Google challenge expired');
      await exchange(challenge.challengeId, credential, fresh);
    });
  }
}

let sdkPromise;
export function loadGoogleSdk(document, scope = window) {
  if (scope.google?.accounts?.id) return Promise.resolve(scope.google.accounts.id);
  if (!sdkPromise) sdkPromise = new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = 'https://accounts.google.com/gsi/client'; script.async = true;
    script.onload = () => scope.google?.accounts?.id ? resolve(scope.google.accounts.id) : reject(new Error('Google SDK unavailable'));
    script.onerror = () => { script.remove(); sdkPromise = null; reject(new Error('Google SDK unavailable')); };
    document.head.append(script);
  });
  return sdkPromise;
}

export function createGooglePanel({ api, document, session, login, notify, begin = () => null }) {
  const attempt = new GoogleAttempt();
  const auth = document.createElement('section');
  const account = document.createElement('section'); account.className = 'card';
  const title = document.createElement('h2'); title.textContent = '로그인 수단'; account.append(title);
  const hint = document.createElement('p'); hint.textContent = '이메일 일치로 계정이나 자산을 합치지 않습니다. 기존 자산은 현재 계정에서 Google을 연결해야 유지됩니다.';
  const start = document.createElement('button'); start.type = 'button'; start.className = 'button secondary';
  const cancel = document.createElement('button'); cancel.type = 'button'; cancel.className = 'button secondary'; cancel.textContent = 'Google 인증 취소'; cancel.hidden = true;
  const password = document.createElement('input'); password.type = 'password'; password.maxLength = 72;
  password.autocomplete = 'current-password'; password.setAttribute('aria-label', 'Google 연결을 위한 현재 로컬 비밀번호');
  password.placeholder = '연결할 현재 계정의 비밀번호';
  const button = document.createElement('div');
  const status = document.createElement('p'); status.className = 'notice'; status.setAttribute('role', 'status');
  for (const [element, id] of [[start,'google-start'],[cancel,'google-cancel'],[password,'google-link-password'],[button,'google-button'],[status,'google-status'],[account,'google-account']]) element.id = id;
  auth.append(hint, start, cancel, password, button, status);
  let config = null, local = false, linked = false, revision = 0, ticket = null;
  function stop() {
    if (ticket?.current()) ticket.finish('idle'); ticket = null;
    attempt.cancel(); revision++; button.replaceChildren(); password.value = ''; cancel.hidden = true; start.disabled = false;
  }
  function update() {
    const user = session().user;
    (user ? account : auth).hidden = !config?.enabled;
    if (user) { account.append(auth); password.hidden = !local || linked; start.hidden = !local || linked; start.textContent = '현재 계정에 Google 연결'; }
    else { document.getElementById('auth-form').append(auth); account.hidden = true; password.hidden = true; start.hidden = false; start.textContent = 'Google 로그인 / 신규 가입'; }
    if (config?.enabled) auth.hidden = false;
  }
  cancel.onclick = () => { stop(); status.textContent = '취소했습니다. 새 인증은 다시 시작해 주세요.'; };
  start.onclick = async () => {
    if (!config?.enabled) return;
    const enteredPassword = password.value;
    stop(); password.value = enteredPassword;
    const version = revision; const initial = session();
    const purpose = initial.user ? 'LINK' : 'LOGIN';
    if (purpose === 'LINK' && (!local || !password.value)) {
      status.textContent = '현재 계정의 로컬 비밀번호를 입력한 뒤 연결을 시작해 주세요.'; return;
    }
    ticket = begin(purpose);
    start.disabled = true; cancel.hidden = false; status.textContent = 'Google 버튼을 준비하는 중입니다.';
    const activeTicket = ticket;
    const current = () => version === revision && (!activeTicket || activeTicket.current())
      && session().epoch === initial.epoch && session().user?.id === initial.user?.id;
    try {
      const sdk = await loadGoogleSdk(document);
      if (!current()) return;
      const issue = async () => {
        const request = async () => (await api.googleChallenge(purpose)).body;
        return globalThis.navigator?.locks ? navigator.locks.request('tms-google-challenge', request) : request();
      };
      await attempt.start(purpose, issue, (nonce, callback) => {
        sdk.initialize({ client_id: config.clientId, nonce, auto_select: false,
          callback: response => { Promise.resolve(callback(response.credential)).catch(() => {
            if (current()) { stop(); status.textContent = 'Google 인증을 완료하지 못했습니다. 새로 시작해 주세요.'; }
          }); } });
        sdk.renderButton(button, { type: 'standard', theme: 'outline', size: 'large', text: purpose === 'LINK' ? 'continue_with' : 'signin_with' });
        status.textContent = purpose === 'LINK' ? '현재 비밀번호를 입력하고 Google 버튼으로 연결을 확인하세요.' : '새 Google 계정이면 0원 모의자산 계정이 생성됩니다.';
      }, async (challengeId, credential, fresh) => {
        if (purpose === 'LINK') {
          const value = password.value; password.value = '';
          if (!value) throw new Error('Local password required');
          await api.googleLink(challengeId, credential, value);
          if (fresh()) { stop(); linked = true; update(); status.textContent = '현재 계정에 Google을 연결했습니다. 사용자 ID와 기존 자산은 그대로입니다.'; }
        } else {
          const result = await api.googleLogin(challengeId, credential);
          if (fresh()) await login(result.body, fresh);
        }
      }, current);
    } catch {
      if (current()) { stop(); status.textContent = 'Google 인증을 시작하지 못했습니다. 기존 아이디 로그인은 계속 사용할 수 있습니다.'; }
    }
  };
  // Never load GIS while feature off; old deployments or fixtures may not implement config.
  api.googleConfig().then(result => { config = result.body; update(); }).catch(() => { auth.hidden = true; account.hidden = true; });
  document.getElementById('auth-form').append(auth); auth.hidden = true; account.hidden = true;
  document.getElementById('view-portfolio').append(account);
  return {
    cancel: stop,
    async context() {
      stop(); status.textContent = ''; const initial = session(); const version = revision; local = false; linked = false; update();
      if (!initial.user || !config?.enabled) return;
      try {
        const result = await api.authMethods();
        if (version !== revision || initial.epoch !== session().epoch) return;
        local = result.body.local; linked = result.body.google; update();
        status.textContent = linked ? 'Google 연결됨 · 연결 해제와 계정 복구는 현재 지원하지 않습니다.' : local ? '비밀번호 재확인 후 Google을 연결할 수 있습니다.' : 'Google 전용 계정입니다.';
      } catch { if (version === revision) notify('로그인 수단을 확인하지 못했습니다.'); }
    },
  };
}
