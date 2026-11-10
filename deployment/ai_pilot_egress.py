"""One-destination bounded TLS tunnel, no decryption, credentials, payload logs or retries.

Default is DENY ALL. --approved-egress is ONLY for a separately approved owned
relay, unreachable from the host (no published ports). This flag is network
capability, not authority to spend; the sender still requires both budget commits.
"""
import argparse
import ipaddress
import json
import os
import select
import socket
import socketserver
import subprocess
import sys
import threading
import time

HOST, PORT = 'api.openai.com', 443


def parse_connect(header):
    if len(header) > 4096 or not header.endswith(b'\r\n\r\n'):
        raise ValueError('EGRESS_HEADER')
    lines = header[:-4].split(b'\r\n')
    if lines[0] not in (b'CONNECT api.openai.com:443 HTTP/1.0', b'CONNECT api.openai.com:443 HTTP/1.1'):
        raise ValueError('EGRESS_TARGET')
    # Accept exactly the standard Host header, never arbitrary forwarded headers.
    if lines[1:] not in ([], [b'Host: api.openai.com:443']):
        raise ValueError('EGRESS_HEADER')


def resolve_provider(timeout, *, spawn=subprocess.Popen):
    """Killable owned DNS child. A resolver thread cannot be safely cancelled.

    No payload, credential, proxy environment or arbitrary host reaches this child.
    Output is at most 16 address records; resolver errors go to DEVNULL.
    """
    end=time.monotonic()+timeout
    code=('import socket,json;print(json.dumps(socket.getaddrinfo('
          '"api.openai.com",443,type=socket.SOCK_STREAM)[:16]))')
    child=spawn([sys.executable,'-I','-B','-S','-c',code],stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,
                env={k:os.environ[k] for k in ('SYSTEMROOT',) if k in os.environ})
    try:
        left=end-time.monotonic()
        if left<=0:raise TimeoutError('EGRESS_DNS_DEADLINE')
        try:
            raw,_=child.communicate(timeout=left)
        except subprocess.TimeoutExpired:
            raise TimeoutError('EGRESS_DNS_DEADLINE') from None
        if child.returncode or len(raw)>16384:raise OSError('EGRESS_DNS_FAILED')
        infos=json.loads(raw)
        if not isinstance(infos,list) or not 1<=len(infos)<=16:raise OSError('EGRESS_DNS_FAILED')
        return infos
    except (ValueError,TypeError):
        raise OSError('EGRESS_DNS_FAILED') from None
    finally:
        # Reap only this child, even on timeout/cancellation. Never leave DNS worker
        # threads permanently occupying the relay's two admission slots.
        try:
            if child.poll() is None:child.kill()
            child.wait(timeout=.5)
        finally:
            if child.stdout:child.stdout.close()


def dial_provider(timeout):
    # DNS may return multiple addresses. One chosen public address, one attempt.
    # TLS hostname/certificate validation happens in the caller, not in this relay.
    end=time.monotonic()+timeout
    infos = resolve_provider(timeout)
    candidates = [i for i in infos if ipaddress.ip_address(i[4][0]).is_global]
    if not candidates:
        raise OSError('EGRESS_NO_PUBLIC_ADDRESS')
    family, kind, proto, _, address = candidates[0]
    s = socket.socket(family, kind, proto)
    try:
        left=end-time.monotonic()
        if left<=0:raise TimeoutError('EGRESS_CONNECT_DEADLINE')
        s.settimeout(left); s.connect(tuple(address))
        return s
    except BaseException:
        s.close(); raise


def handle(client, *, approved=False, dial=dial_provider, limit=60):
    end = time.monotonic() + limit
    remote = None
    try:
        client.settimeout(min(3, limit))
        header = bytearray()
        while not header.endswith(b'\r\n\r\n') and len(header) <= 4096:
            left = end-time.monotonic()
            if left <= 0:
                return
            client.settimeout(min(3, left))
            part = client.recv(1)
            if not part:
                return
            header.extend(part)
        parse_connect(bytes(header))
        if not approved:
            client.sendall(b'HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n'); return
        remote = dial(max(.001, end - time.monotonic()))
        client.sendall(b'HTTP/1.1 200 Connection Established\r\n\r\n')
        client.setblocking(False); remote.setblocking(False)
        sockets = [client, remote]
        # Bounded copy; the application still enforces its shorter per-call deadline.
        while time.monotonic() < end:
            ready, _, _ = select.select(sockets, [], [], max(0, end-time.monotonic()))
            if not ready:
                return
            for src in ready:
                data = src.recv(16384)
                if not data:
                    return
                dst = remote if src is client else client
                view = memoryview(data)
                while view and time.monotonic() < end:
                    _, writable, _ = select.select([], [dst], [], max(0, end-time.monotonic()))
                    if not writable:
                        return
                    try:
                        sent = dst.send(view)
                        if sent == 0:
                            return
                        view = view[sent:]
                    except BlockingIOError:
                        pass
    except (OSError, ValueError):
        # Do not expose exception text, tunnel bytes, keys or local addresses.
        try:
            if remote is None:
                client.sendall(b'HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n')
        except OSError:
            pass
    finally:
        if remote is not None:
            remote.close()


class Relay(socketserver.ThreadingTCPServer):
    allow_reuse_address = False
    daemon_threads = True
    request_queue_size = 2

    def __init__(self, address, approved=False):
        self.approved = approved
        self.slots = threading.BoundedSemaphore(2)
        super().__init__(address, Handler)

    def process_request(self, request, address):
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(request); return
        try:
            super().process_request(request, address)
        except BaseException:
            self.slots.release(); raise

    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self.slots.release()

    def handle_error(self, request, client_address):
        pass  # socketserver's default traceback contains private request context.


class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        handle(self.request, approved=self.server.approved)


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--approved-egress', action='store_true')
    args = p.parse_args()
    with Relay(('0.0.0.0', 8443), args.approved_egress) as server:
        server.serve_forever(poll_interval=.25)
