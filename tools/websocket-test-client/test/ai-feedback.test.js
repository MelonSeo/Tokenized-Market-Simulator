import test from 'node:test';
import assert from 'node:assert/strict';
import { aiFailureMessage } from '../src/ai-feedback.js';
test('cost, overload and timeout are distinct and never advise automatic retry', () => {
  assert.match(aiFailureMessage('AI_BUDGET_EXHAUSTED',503),/공급자 요청을 차단/);
  assert.match(aiFailureMessage('AI_ACCOUNT_RATE_LIMIT',429),/계정/);
  assert.match(aiFailureMessage('AGENT_BUSY',503),/자동 재시도하지/);
  assert.match(aiFailureMessage('AGENT_TIMEOUT',504),/외부 호출 종료 여부는 별도/);
  assert.equal(aiFailureMessage('OTHER',500),null);
  for(const code of [undefined,'TRADE_TIMEOUT','LOGIN_RATE_LIMIT']) for(const status of [429,504]) assert.equal(aiFailureMessage(code,status),null);
});
