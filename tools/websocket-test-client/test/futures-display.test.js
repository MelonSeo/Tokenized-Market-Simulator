import test from 'node:test';
import assert from 'node:assert/strict';
import { futuresDecimal, futuresRisk, terminalPosition } from '../src/futures-display.js';

test('display summaries truncate strings without binary rounding or losing the raw source', () => {
  const raw = '9007199254740993.123456789012345678';
  assert.equal(futuresDecimal(raw), '약 9,007,199,254,740,993.12');
  assert.equal(raw, '9007199254740993.123456789012345678');
  assert.equal(futuresDecimal('-100000.999'), '약 −100,000.99');
  assert.equal(futuresDecimal('0.000000000000000000'), '0');
  assert.equal(futuresDecimal('13.333333333333333333', 6), '약 13.333333');
});
test('exact summaries do not claim approximation for integer or trailing-zero precision', () => {
  for (const value of ['200000', '200000.000000000000000000']) assert.equal(futuresDecimal(value), '200,000');
  assert.equal(futuresDecimal('200000.000000000000000001'), '약 200,000');
  assert.equal(futuresDecimal('1.230000000000000000'), '1.23');
  assert.equal(futuresDecimal('-1.230000000000000000'), '−1.23');
  assert.equal(futuresDecimal('-0.000000000000000000'), '0');
});
test('normal risk does not duplicate the primary position state; raw input stays unchanged', () => {
  const p = { status: 'OPEN', riskStatus: 'HEALTHY' };
  assert.equal(futuresRisk(p), ''); assert.equal(p.status, 'OPEN'); assert.equal(p.riskStatus, 'HEALTHY');
  assert.equal(futuresRisk({ ...p, riskStatus: 'OTHER' }), '위험 상태 확인 불가');
});
test('tiny signed nonzero values never become a zero summary', () => {
  assert.equal(futuresDecimal('0.000000000000000001'), '0 < 값 < 0.01');
  assert.equal(futuresDecimal('-0.000000000000000001'), '−0.01 < 값 < 0');
  for (const v of [null, undefined, 1, '1e3', 'NaN', '<img>']) assert.equal(futuresDecimal(v), '확인 불가');
});
test('terminal historical GRACE never renders an ongoing margin call', () => {
  for (const status of ['CLOSED', 'LIQUIDATED', 'OPEN_FAILED']) {
    const p = { status, riskStatus: 'GRACE', deadline: 100 };
    assert.equal(terminalPosition(p), true);
    assert.doesNotMatch(futuresRisk(p, 200000), /마진콜|남은|0초/);
    assert.equal(p.riskStatus, 'GRACE'); assert.equal(p.deadline, 100);
  }
});
test('OPEN grace countdown is indicative only and expiry awaits server confirmation', () => {
  const p = { status: 'OPEN', riskStatus: 'GRACE', deadline: 100 };
  assert.match(futuresRisk(p, 99000), /약 1초/);
  assert.match(futuresRisk(p, 100000), /서버의 체인 확인 대기/);
  assert.match(futuresRisk(p, 101000), /서버의 체인 확인 대기/);
  assert.equal(p.status, 'OPEN');
  assert.doesNotMatch(futuresRisk({ ...p, status: 'LIQUIDATING' }), /남은/);
  assert.match(futuresRisk({ ...p, deadline: null }), /확인 불가/);
});
