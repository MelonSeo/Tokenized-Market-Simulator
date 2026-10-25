/** Unwrap only the common transport envelope; keep the C API value envelope intact. */
export function createCApiAdapter(client) {
  return {
    async market() { return (await client.market()).body; },
    async get(path) { return (await client.cRequest(path)).body; },
    async post(path, body, key) {
      return (await client.cRequest(path, { method: 'POST', body: JSON.stringify(body),
        ...(key ? { headers: { 'Idempotency-Key': key } } : {}) })).body;
    },
  };
}
