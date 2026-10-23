// Display only: never convert financial strings to Number or feed summaries into requests.
export function futuresDecimal(value, places = 2) {
  if (typeof value !== 'string' || !/^-?\d+(\.\d+)?$/.test(value)) return '확인 불가';
  const negative = value.startsWith('-'), raw = negative ? value.slice(1) : value;
  const [whole, fraction = ''] = raw.split('.');
  const integer = whole.replace(/^0+(?=\d)/, '');
  const decimals = fraction.slice(0, places).replace(/0+$/, '');
  const nonzero = /[1-9]/.test(raw), truncated = /[1-9]/.test(fraction.slice(places));
  const unit = `0.${'0'.repeat(places - 1)}1`;
  if (nonzero && integer === '0' && !/[1-9]/.test(decimals)) return negative ? `−${unit} < 값 < 0` : `0 < 값 < ${unit}`;
  return `${truncated ? '약 ' : ''}${negative && nonzero ? '−' : ''}${integer.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}${decimals ? '.' + decimals : ''}`;
}
