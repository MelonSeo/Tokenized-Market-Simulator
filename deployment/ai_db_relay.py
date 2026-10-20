"""Bounded loopback -> owned Docker exec -> container localhost:5432.
No container egress, Docker socket mount, shell, credential logging or API calls.
Requires the pinned pgvector image's bundled Perl (no installation/pull).
"""
import argparse
import json
import re
import socket
import subprocess
import threading
import time
from pathlib import Path

# Fixed destination. PostgreSQL protocol bytes remain opaque; never logged.
PERL = r'''use strict; use warnings; use IO::Socket::INET; use IO::Select;
my $limit=shift @ARGV; exit 5 unless defined($limit) && $limit =~ /^\d+$/ && $limit>=1 && $limit<=14400;
$SIG{ALRM}=sub {exit 4}; alarm $limit;
binmode STDIN; binmode STDOUT;
my $s=IO::Socket::INET->new(PeerAddr=>'127.0.0.1',PeerPort=>5432,Proto=>'tcp',Timeout=>3) or exit 2;
my $r=IO::Select->new(\*STDIN,$s);
while (1) { for my $f ($r->can_read(1)) {
 my $n=sysread($f,my $b,65536); exit 0 unless defined($n) && $n>0;
 my $out=fileno($f)==fileno(STDIN) ? $s : \*STDOUT;
 my $p=0; while($p<$n) { my $w=syswrite($out,$b,$n-$p,$p); exit 3 unless defined($w) && $w>0; $p+=$w; }
} }'''


def docker_json(*args):
    result = subprocess.run(['docker', *args], capture_output=True, timeout=20, check=True)
    return json.loads(result.stdout)


def verify_target(meta):
    rid = meta.get('runId', '')
    if not re.fullmatch(r'(?:eval_|ai_db_probe_)[0-9a-f]{32}', rid):
        raise ValueError('RELAY_IDENTITY_INVALID')
    if type(meta.get('dbPort')) is not int or not 49152 <= meta['dbPort'] <= 65535:
        raise ValueError('RELAY_PORT_INVALID')
    db = 'exchange_ai_eval_' + rid.rsplit('_', 1)[1]
    if meta.get('dbName') != db or meta.get('dbUser') != db:
        raise ValueError('RELAY_DATABASE_INVALID')
    name = rid + '-ai-postgres-1'
    c = docker_json('inspect', name)[0]
    # Use immutable ID after verification, not a name that may be reassigned.
    if (c['Config']['Labels'].get('ai-evaluation.run') != rid
            or not c['State']['Running'] or c['HostConfig']['NetworkMode'] != 'none'
            or c['Image'] != meta.get('dbImage')
            or c['HostConfig'].get('Privileged') or c['HostConfig'].get('CapAdd')
            or c['HostConfig'].get('PortBindings')
            or any(m.get('Type') != 'volume' for m in c.get('Mounts', []))):
        raise ValueError('RELAY_TARGET_UNSAFE')
    mounts = c.get('Mounts', [])
    if len(mounts) != 1 or mounts[0]['Destination'] != '/var/lib/postgresql/data':
        raise ValueError('RELAY_MOUNT_INVALID')
    v = docker_json('volume', 'inspect', mounts[0]['Name'])[0]
    if (v.get('Labels') or {}).get('ai-evaluation.run') != rid:
        raise ValueError('RELAY_VOLUME_OWNER_INVALID')
    return c['Id']


class Relay:
    def __init__(self, meta, seconds=600, max_connections=32):
        if not 1 <= seconds <= 14400 or not 1 <= max_connections <= 128:
            raise ValueError('RELAY_LIMIT_INVALID')
        self.meta, self.seconds, self.max_connections = meta, seconds, max_connections
        self.done = threading.Event()
        self.lock = threading.Lock()
        self.clients, self.children, self.threads = [], [], []
        self.accepted = 0
        self.listener = None

    def start(self):
        self.target = verify_target(self.meta)
        # Check required built-in capability before opening the listener.
        subprocess.run(['docker', 'exec', self.target, 'perl', '-MIO::Socket::INET', '-MIO::Select', '-e', 'exit 0'],
                       check=True, timeout=20, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        # No SO_REUSEADDR: do not share/take over an existing Windows listener.
        self.listener.bind(('127.0.0.1', self.meta['dbPort']))
        self.listener.listen(8)
        self.listener.settimeout(.25)
        self.thread = threading.Thread(target=self.serve, daemon=True)
        self.thread.start()
        return self

    def serve(self):
        end = time.monotonic() + self.seconds
        try:
            while not self.done.is_set() and time.monotonic() < end:
                try:
                    client, _ = self.listener.accept()
                except socket.timeout:
                    continue
                except OSError:
                    break
                self.accepted += 1
                if self.accepted > self.max_connections:
                    client.close()
                    break
                with self.lock:
                    self.clients.append(client)
                t = threading.Thread(target=self.bridge, args=(client,), daemon=True)
                self.threads.append(t)
                t.start()
        finally:
            self.close()

    def bridge(self, client):
        proc = None
        def upload():
            try:
                while not self.done.is_set():
                    data = client.recv(65536)
                    if not data:
                        break
                    remaining=memoryview(data)
                    while remaining:
                        count=proc.stdin.write(remaining)
                        if not count: raise OSError('PIPE_CLOSED')
                        remaining=remaining[count:]
                    proc.stdin.flush()
            except (OSError, ValueError):
                pass
            finally:
                try: proc.stdin.close()
                except (OSError, ValueError): pass
        try:
            with self.lock:
                if self.done.is_set(): return
                proc = subprocess.Popen(['docker', 'exec', '-i', self.target, 'perl', '-e', PERL, str(self.seconds)],
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.DEVNULL, bufsize=0)
                self.children.append(proc)
            t = threading.Thread(target=upload, daemon=True)
            t.start()
            while not self.done.is_set():
                data = proc.stdout.read(65536)
                if not data: break
                client.sendall(data)
        except (OSError, ValueError):
            pass
        finally:
            try: client.shutdown(socket.SHUT_RDWR)
            except OSError: pass
            client.close()
            if proc:
                if proc.poll() is None: proc.terminate()
                try: proc.wait(timeout=3)
                except subprocess.TimeoutExpired: proc.kill(); proc.wait(timeout=3)
                proc.stdout.close()

    def close(self):
        with self.lock:
            self.done.set()
            if self.listener:
                self.listener.close()
            for client in self.clients:
                try: client.shutdown(socket.SHUT_RDWR)
                except OSError: pass
            for p in self.children:
                if p.poll() is None: p.terminate()

    def wait(self):
        self.thread.join(self.seconds + 5)
        self.close()
        for t in self.threads: t.join(5)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--metadata', required=True)
    p.add_argument('--seconds', type=int, default=600)
    p.add_argument('--max-connections', type=int, default=32)
    args = p.parse_args()
    meta = json.loads(Path(args.metadata).read_text(encoding='utf-8'))
    try:
        relay = Relay(meta, args.seconds, args.max_connections).start()
        print(json.dumps({'status':'READY','port':meta['dbPort'],'egress':'NONE','runId':meta['runId']}), flush=True)
        relay.wait()
        print(json.dumps({'status':'STOPPED','connections':relay.accepted}), flush=True)
    except Exception:
        raise SystemExit('RELAY_STOPPED_SAFE') from None


if __name__ == '__main__':
    main()
