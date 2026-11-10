"""Fixed CONNECT route. No connection/key read occurs when importing or constructing it.

App stays on an internal Docker network. Only the separately owned egress relay
can dial the fixed provider. TLS remains end-to-end, including hostname checking.
Environment proxy variables and redirects are never used.
"""
import http.client
import ssl
import json
import time
import os
from pathlib import Path

PROXY_HOST = 'pilot-egress'
PROXY_PORT = 8443
PROVIDER_HOST = 'api.openai.com'


def connection(timeout):
    context = ssl.create_default_context()
    # HTTPSConnection wraps the CONNECT socket with server_hostname=_tunnel_host.
    c = http.client.HTTPSConnection(PROXY_HOST, PROXY_PORT, timeout=timeout, context=context)
    c.set_tunnel(PROVIDER_HOST, 443)
    return c


def sender(folder, permit, key):
    from ai_evaluation_broker import _https_sender
    post = _https_sender(folder, permit, key, connection_factory=connection)
    checked = False
    failed = False
    def send(path, raw, timeout):
        nonlocal checked, failed
        from ai_pilot_preflight import need, canonical
        need(not failed, 'V2_PROVIDER_ACCESS_PRECHECK_FAILED')
        need(path in {'responses','embeddings'} and 0 < timeout <= 60, 'V2_CALL_DEADLINE')
        end = time.monotonic() + timeout
        if not checked:
            # Invoked only by PaidSender AFTER PG + SQLite reservations. These
            # two metadata GETs do not generate tokens and never retry. The
            # original call's deadline still covers all precheck overhead.
            started = time.monotonic()
            evidence = {'models':[], 'status':'FAILED', 'paidPostStarted':False,
                        'activeModel':None,'httpStatus':None,'failureCode':None}
            try:
                for model in ('gpt-5.6-terra','text-embedding-3-small'):
                    evidence['activeModel']=model;evidence['httpStatus']=None
                    left = min(end-time.monotonic(), permit['expiresAt']-time.time())
                    need(left > 0 and not (Path(folder)/'STOP').exists(), 'V2_CALL_DEADLINE')
                    c = connection(left)
                    try:
                        c.request('GET','/v1/models/'+model,headers={'Authorization':'Bearer '+key})
                        left = min(end-time.monotonic(),permit['expiresAt']-time.time())
                        need(left > 0, 'V2_CALL_DEADLINE')
                        if c.sock:c.sock.settimeout(left)
                        r = c.getresponse()
                        evidence['httpStatus']=r.status if type(r.status) is int and 100<=r.status<=599 else None
                        need(r.status == 200, 'V2_PROVIDER_MODEL_ACCESS_DENIED')
                        body = bytearray()
                        while True:
                            left = min(end-time.monotonic(),permit['expiresAt']-time.time())
                            need(left > 0, 'V2_CALL_DEADLINE')
                            if c.sock:c.sock.settimeout(left)
                            part = r.read1(min(4096,16385-len(body)))
                            if not part:break
                            body.extend(part)
                            need(len(body)<=16384, 'V2_PROVIDER_METADATA_LIMIT')
                        need(json.loads(body).get('id') == model, 'V2_PROVIDER_MODEL_IDENTITY')
                        evidence['models'].append(model)
                    finally:c.close()
                evidence['status'] = 'PASS_DNS_TLS_AND_ACCOUNT_MODEL_METADATA'
                checked = True
            except BaseException as error:
                failed = True
                allowed={'V2_CALL_DEADLINE','V2_PROVIDER_MODEL_ACCESS_DENIED','V2_PROVIDER_METADATA_LIMIT','V2_PROVIDER_MODEL_IDENTITY'}
                code=str(error) if isinstance(error,ValueError) else None
                evidence['failureCode']=(code if code in allowed else 'TLS_VERIFICATION_FAILED' if isinstance(error,ssl.SSLError)
                    else 'METADATA_TIMEOUT' if isinstance(error,TimeoutError) else 'METADATA_CONNECTION_FAILED' if isinstance(error,OSError)
                    else 'METADATA_PAYLOAD_INVALID' if isinstance(error,(ValueError,TypeError)) else 'METADATA_INTERRUPTED')
                raise
            finally:
                evidence['elapsedMillis']=max(0,int((time.monotonic()-started)*1000))
                # No headers, raw response, key, project ID or arbitrary errors.
                fd = os.open(Path(folder)/'provider-access.private.json',os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
                with os.fdopen(fd,'wb') as out:
                    out.write(canonical(evidence));out.flush();os.fsync(out.fileno())
        left = min(end-time.monotonic(),permit['expiresAt']-time.time())
        need(left > 0, 'V2_CALL_DEADLINE')
        return post(path,raw,left)
    return send
