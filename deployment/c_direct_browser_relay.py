"""Loopback-only, byte-opaque access to one NEW owned C App and its fixed internal RPC.

Not a Docker publisher or an HTTP retry layer. No credentials/payload bytes are logged.
The fresh browser launcher must write the identity metadata after bootstrap/readiness.
This module never starts/restarts/stops a container or changes a network.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import socket
import subprocess
import threading
import time

JAVA_IMAGE = "sha256:35be56d41389c9ad515c089dfded911ae9a8bdb362c8a303224666cd358bead3"


def validate_plan(meta):
    if meta.get("scope") != "C_BROWSER_NEW_OWNED_INTERNAL_RELAY":
        raise ValueError("C_BROWSER_RELAY_SCOPE")
    run = meta.get("runId", "")
    if not re.fullmatch(r"cdemo-[0-9a-f]{16}", run):
        raise ValueError("C_BROWSER_RELAY_RUN")
    for key in ("appId", "networkId", "sourceIdentity", "helperSha256"):
        if not re.fullmatch(r"[0-9a-f]{64}", meta.get(key, "")):
            raise ValueError("C_BROWSER_RELAY_IDENTITY")
    ports = [meta.get("appPort"), meta.get("rpcPort")]
    if any(type(p) is not int or not 1024 <= p <= 65535 for p in ports) or ports[0] == ports[1]:
        raise ValueError("C_BROWSER_RELAY_PORTS")
    if type(meta.get("seconds")) is not int or not 30 <= meta["seconds"] <= 3600:
        raise ValueError("C_BROWSER_RELAY_LIFETIME")
    if meta.get("origin") != "http://127.0.0.1:" + str(ports[0]):
        raise ValueError("C_BROWSER_RELAY_ORIGIN")
    return run


def validate_target(meta, app, network):
    run = validate_plan(meta)
    c, h, n = app.get("Config", {}), app.get("HostConfig", {}), app.get("NetworkSettings", {})
    nets = n.get("Networks", {})
    if app.get("Id") != meta["appId"] or app.get("Name") != "/" + run + "-app" or app.get("Image") != JAVA_IMAGE:
        raise ValueError("C_BROWSER_RELAY_APP_IDENTITY")
    if c.get("Labels", {}).get("tms.c.owner") != run or app.get("State", {}).get("Running") is not True:
        raise ValueError("C_BROWSER_RELAY_OWNER_STATE")
    if h.get("NetworkMode") != run + "-network" or set(nets) != {run + "-network"} or nets[run + "-network"].get("NetworkID") != meta["networkId"]:
        raise ValueError("C_BROWSER_RELAY_NETWORK")
    if network.get("Id") != meta["networkId"] or network.get("Internal") is not True or network.get("Labels", {}).get("tms.c.owner") != run:
        raise ValueError("C_BROWSER_RELAY_INTERNAL")
    if h.get("Privileged") or h.get("CapAdd") or h.get("PortBindings") or any((n.get("Ports") or {}).values()):
        raise ValueError("C_BROWSER_RELAY_ISOLATION")
    if c.get("User") != "10001:10001" or h.get("ReadonlyRootfs") is not True or h.get("CapDrop") != ["ALL"]:
        raise ValueError("C_BROWSER_RELAY_USER")
    if not any(v in {"no-new-privileges", "no-new-privileges:true"} for v in h.get("SecurityOpt", [])):
        raise ValueError("C_BROWSER_RELAY_PRIVILEGES")
    mounts = [m for m in app.get("Mounts", []) if m.get("Destination") == "/server-fixture"]
    if len(mounts) != 1 or mounts[0].get("Type") != "bind" or mounts[0].get("RW") is not False:
        raise ValueError("C_BROWSER_RELAY_FIXTURE")
    return Path(mounts[0]["Source"]) / "classes/CDirectBrowserPipe.class"


def pipe_command(app_id, role):
    if not re.fullmatch(r"[0-9a-f]{64}", app_id) or role not in {"APP", "RPC"}:
        raise ValueError("C_BROWSER_RELAY_PIPE_SCOPE")
    return ["docker", "exec", "-i", "--user", "10001:10001", app_id,
            "java", "-Xms4m", "-Xmx8m", "-XX:MaxMetaspaceSize=16m", "-XX:ActiveProcessorCount=1",
            "-cp", "/server-fixture/classes", "CDirectBrowserPipe", role, "120"]


class BrowserRelay:
    def __init__(self, meta, control):
        validate_plan(meta)
        self.meta, self.control = meta, Path(control)
        if not self.control.is_dir() or (self.control / "STOP").exists():
            raise ValueError("C_BROWSER_RELAY_CONTROL")
        self.done = threading.Event()
        self.lock = threading.RLock()
        # RPC keep-alive must not consume APP capacity. At most four helper JVMs,
        # inside the separately budgeted 768 MiB browser App container.
        self.slots = {role: threading.BoundedSemaphore(2) for role in ("APP", "RPC")}
        self.listeners, self.clients, self.children, self.threads = [], [], [], []
        self.accepted = 0
        self.max_connections = 512 * ((meta["seconds"] + 899) // 900)

    def start(self):
        def inspect(kind, identity):
            p = subprocess.run(["docker", kind, "inspect", identity], capture_output=True, timeout=10, check=True)
            values = json.loads(p.stdout)
            if len(values) != 1:
                raise ValueError("C_BROWSER_RELAY_INSPECT")
            return values[0]
        app = inspect("container", self.meta["appId"])
        network = inspect("network", self.meta["networkId"])
        helper = validate_target(self.meta, app, network)
        if not helper.is_file() or hashlib.sha256(helper.read_bytes()).hexdigest() != self.meta["helperSha256"]:
            raise ValueError("C_BROWSER_RELAY_HELPER_HASH")
        # Fail on either occupied/EFAULT socket, never choose another port or reuse a listener.
        try:
            for role, key in (("APP", "appPort"), ("RPC", "rpcPort")):
                listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                self.listeners.append(listener)
                if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
                    listener.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
                listener.bind(("127.0.0.1", self.meta[key]))
                listener.listen(32)
                listener.settimeout(.25)
                self.threads.append(threading.Thread(target=self.accept, args=(listener, role), daemon=True))
            self.end = time.monotonic() + self.meta["seconds"]
            for thread in self.threads:
                thread.start()
        except Exception:
            self.close()
            raise
        return self

    def accept(self, listener, role):
        try:
            while not self.done.is_set() and time.monotonic() < self.end and not (self.control / "STOP").exists():
                try:
                    client, _ = listener.accept()
                except socket.timeout:
                    continue
                except OSError:
                    break
                with self.lock:
                    self.accepted += 1
                    if self.accepted > self.max_connections:
                        client.close()
                        break
                    client.settimeout(120)
                    self.clients.append(client)
                    thread = threading.Thread(target=self.bridge, args=(client, role), daemon=True)
                    self.threads.append(thread)
                    thread.start()
        finally:
            self.close()

    def bridge(self, client, role):
        process, acquired = None, False
        try:
            # Queue a short burst of static/browser requests without spawning unbounded JVMs.
            slots = self.slots[role]
            acquired = slots.acquire(timeout=10)
            if not acquired or self.done.is_set():
                return
            with self.lock:
                if self.done.is_set():
                    return
                process = subprocess.Popen(pipe_command(self.meta["appId"], role), stdin=subprocess.PIPE,
                                           stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=0)
                self.children.append(process)
            def upload():
                try:
                    while not self.done.is_set():
                        data = client.recv(65536)
                        if not data:
                            break
                        remaining = memoryview(data)
                        while remaining:
                            count = process.stdin.write(remaining)
                            if not count:
                                raise OSError("C_BROWSER_RELAY_PIPE_CLOSED")
                            remaining = remaining[count:]
                        process.stdin.flush()
                except (OSError, ValueError):
                    pass
                finally:
                    try: process.stdin.close()
                    except (OSError, ValueError): pass
            threading.Thread(target=upload, daemon=True).start()
            while not self.done.is_set():
                data = process.stdout.read(65536)
                if not data:
                    break
                client.sendall(data)
        except (OSError, ValueError):
            pass
        finally:
            try: client.shutdown(socket.SHUT_RDWR)
            except OSError: pass
            client.close()
            if process:
                if process.poll() is None: process.terminate()
                try: process.wait(timeout=3)
                except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=3)
                process.stdout.close()
            if acquired:
                slots.release()

    def close(self):
        with self.lock:
            self.done.set()
            for listener in self.listeners:
                listener.close()
            for client in self.clients:
                try: client.shutdown(socket.SHUT_RDWR)
                except OSError: pass
            for child in self.children:
                if child.poll() is None: child.terminate()

    def wait(self):
        try:
            while not self.done.wait(.25) and time.monotonic() < self.end and not (self.control / "STOP").exists():
                pass
        finally:
            self.close()
        end = time.monotonic() + 3
        for thread in list(self.threads):
            if thread is not threading.current_thread(): thread.join(max(0, end - time.monotonic()))
        # Termination is only for our exec children, never Docker/App/other host processes.
        # A still-running exec must not be silently treated as normal shutdown.
        if any(child.poll() is None for child in self.children):
            raise ValueError("C_BROWSER_RELAY_EXEC_STOP_UNCONFIRMED")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("metadata", type=Path)
    parser.add_argument("control", type=Path)
    args = parser.parse_args()
    relay = None
    try:
        relay = BrowserRelay(json.loads(args.metadata.read_text(encoding="utf-8")), args.control).start()
        print(json.dumps({"status": "RELAY_READY_NOT_BROWSER_ACCEPTED", "runId": relay.meta["runId"],
                          "web": relay.meta["origin"] + "/c-demo.html", "rpc": "http://127.0.0.1:" + str(relay.meta["rpcPort"]), "chainId": 31337}), flush=True)
        relay.wait()
    except Exception as safe:
        print(json.dumps({"status": "BLOCKED", "exceptionType": type(safe).__name__}), flush=True)
        return 1
    finally:
        if relay: relay.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
