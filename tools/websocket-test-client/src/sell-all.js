/** Amounts used for an order are decimal strings, never formatted display numbers. */
export function exactAvailableAmount(value) {
  if (typeof value !== 'string' || !/^\d+(?:\.\d{1,18})?$/.test(value) || !/[1-9]/.test(value)) {
    throw new Error('No valid available token amount');
  }
  return value;
}

/** One fresh REST read; a stale account/mode response must never fill a new order. */
export function createSellAllAction({ context, portfolio, invalidateQuote, fill, notify, changed, unauthorized }) {
  let generation = 0, busy = false;
  const allowed = value => value.userId != null && value.side === 'SELL' && !value.blocked;
  return {
    get busy() { return busy; },
    cancel() { generation++; busy = false; },
    async run() {
      const initial = { ...context() };
      if (busy || !allowed(initial)) return;
      invalidateQuote();
      const version = ++generation;
      const current = () => {
        const now = context();
        return version === generation && now.userId === initial.userId && now.epoch === initial.epoch
          && allowed(now);
      };
      busy = true; changed(); notify('매도 가능한 최신 수량을 조회하는 중입니다.');
      try {
        const { body } = await portfolio();
        if (!current()) return;
        fill(exactAvailableAmount(body?.tokenAvailableAmount));
        notify('현재 매도 가능 수량을 입력했습니다. 견적을 별도로 확인해 주세요.');
      } catch (error) {
        if (!current()) return;
        notify('매도 가능 수량을 확인하지 못했습니다. 잔고를 확인해 주세요.');
        if (error.status === 401) unauthorized();
      } finally {
        if (version === generation) { busy = false; changed(); }
      }
    },
  };
}
