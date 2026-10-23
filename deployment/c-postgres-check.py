"""New owned Linux PG-only C storage Gate. No host ports, old DBs, Anvil or API calls.

Do not run until the C4b source/tests/harness review is complete. All resources
and private failure evidence are retained; only this run's labelled containers
are stopped. Cached immutable images are mandatory, never pulled.
"""
import datetime
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import time
import uuid
from c_gate_network import select_subnet, postgres_readiness_args, java_test_args

PG_IMAGE = "sha256:c1b3783309b6499c795eed7c20135a1a4d25cae1b575c3d52c6f536129a1b109"
JAVA_IMAGE = "sha256:35be56d41389c9ad515c089dfded911ae9a8bdb362c8a303224666cd358bead3"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="backslashreplace")
    root = Path(__file__).resolve().parents[1]
    # Consume only an already compiled, source-unchanged C offline check.
    if len(sys.argv) != 2:
        raise SystemExit("USAGE: c-postgres-check.py <c-projection-run-id>")
    import re
    run_arg = sys.argv[1]
    if not re.fullmatch(r"\d{8}T\d{6}Z-[0-9a-f]{8}", run_arg):
        raise SystemExit("C_COMPILE_RUN_ID")
    compiled = root / "deployment/runtime/c-projection" / run_arg
    prior = json.loads((compiled / "result.json").read_text(encoding="utf-8"))
    if prior["compileExit"] != 0 or prior["testExit"] != 0 or not prior["sourcesUnchanged"]:
        raise SystemExit("C_OFFLINE_PRECONDITION")
    for rel, digest in prior["sources"].items():
        if sha(root / rel) != digest:
            raise SystemExit("C_SOURCE_CHANGED")
    actual_classes = {p.relative_to(compiled / "classes").as_posix(): sha(p)
                      for p in sorted((compiled / "classes").rglob("*.class"))}
    if not actual_classes or actual_classes != prior.get("classHashes"):
        raise SystemExit("C_COMPILED_ARTIFACT_CHANGED")
    # No downloading; use the exact cached JDBC driver and JUnit jars already checked.
    classpath = prior["testCommand"][prior["testCommand"].index("-cp") + 1].split(os.pathsep)
    junit = [Path(p) for p in classpath[1:]]
    if len(junit) != 7 or any(sha(p) != prior["dependencies"].get(p.name) for p in junit):
        raise SystemExit("C_DEPENDENCY_CHANGED")
    cache = root.parents[2] / "deployment/runtime/dapp-audit-development-worktree/deployment/runtime/dapp-stage2-offline/java-classpath.args"
    entries = cache.read_text(encoding="utf-8-sig").splitlines()[1].strip('"').split(os.pathsep)
    drivers = [Path(p) for p in entries if Path(p).name == "postgresql-42.7.4.jar"]
    if len(drivers) != 1 or not drivers[0].is_file():
        raise SystemExit("C_CACHED_JDBC_MISSING")
    if shutil.disk_usage(root).free < 2 * 1024 ** 3:
        raise SystemExit("C_HOST_DISK_LOW")
    run = "cpg-" + uuid.uuid4().hex[:16]
    evidence = root / "deployment/runtime/c-postgres" / run
    fixture = evidence / "fixture"
    fixture.mkdir(parents=True, exist_ok=False)
    env = {k: v for k, v in os.environ.items() if k.upper() in {
        "PATH", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "USERPROFILE", "LOCALAPPDATA", "PATHEXT", "COMSPEC"}}
    commands = []
    status = "FAILED"
    safe_error = None
    names = {"network": run + "-network", "volume": run + "-data", "pg": run + "-pg", "java": run + "-java"}
    label = "tms.c.owner=" + run
    stopped = {}
    network_allocation = None

    def execute(args, *, timeout=15, check=True, input_text=None):
        command = ["docker", *args]
        # Commands never contain passwords/raw keys. Environment file contents are private.
        item = {"command": command}
        commands.append(item)
        try:
            result = subprocess.run(command, cwd=root, env=env, capture_output=True,
                                    text=True, encoding="utf-8", errors="replace",
                                    input=input_text, timeout=timeout)
            item["exitCode"] = result.returncode
            item["stdout"] = result.stdout
            item["stderr"] = result.stderr
            if check and result.returncode:
                raise RuntimeError("C_DOCKER_COMMAND_FAILED:" + str(len(commands)))
            return result
        except subprocess.TimeoutExpired:
            item["timeout"] = True
            raise RuntimeError("C_DOCKER_TIMEOUT:" + str(len(commands))) from None

    def inspect_owned(kind, name):
        value = execute([kind, "inspect", name, "--format", '{{index .Labels "tms.c.owner"}}']
                        if kind != "container" else
                        ["container", "inspect", name, "--format", '{{index .Config.Labels "tms.c.owner"}}'], check=False)
        return value.returncode == 0 and value.stdout.strip() == run

    try:
        execute(["version", "--format", "{{.Server.Version}}"])
        execute(["ps", "-a", "--format", "{{.ID}}|{{.Names}}|{{.Image}}|{{.Status}}"])
        network_list = execute(["network", "ls", "--format", "{{.ID}}|{{.Name}}|{{.Driver}}"])
        execute(["volume", "ls", "--format", "{{.Name}}"])
        network_ids = [line.split("|")[0] for line in network_list.stdout.splitlines() if line]
        if len(network_ids) > 1024 or any(not re.fullmatch(r"[0-9a-f]{12,64}", n) for n in network_ids):
            raise RuntimeError("C_NETWORK_LIST_LIMIT_OR_FORMAT")
        occupied = []
        for first in range(0, len(network_ids), 64):
            configs = execute(["network", "inspect", *network_ids[first:first+64], "--format", "{{json .IPAM.Config}}"])
            for line in configs.stdout.splitlines():
                for config in json.loads(line) or []:
                    if config.get("Subnet"):
                        occupied.append(config["Subnet"])
        route_command = ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                         "@(Get-NetRoute -AddressFamily IPv4 -ErrorAction Stop | Select-Object -ExpandProperty DestinationPrefix) | ConvertTo-Json -Compress"]
        routes = subprocess.run(route_command, env=env, capture_output=True, text=True, timeout=10)
        if routes.returncode:
            raise RuntimeError("C_HOST_ROUTE_INVENTORY_UNAVAILABLE")
        route_values = json.loads(routes.stdout)
        if isinstance(route_values, str):
            route_values = [route_values]
        selected_subnet = select_subnet(occupied, route_values)
        network_allocation = {"subnet": selected_subnet, "dockerSubnets": occupied, "windowsRoutes": route_values}
        for image in (PG_IMAGE, JAVA_IMAGE):
            r = execute(["image", "inspect", image, "--format", "{{.Id}}|{{.Os}}|{{.Architecture}}"])
            if r.stdout.strip() != image + "|linux|amd64":
                raise RuntimeError("C_IMAGE_IDENTITY")
        for kind, name in (("network", names["network"]), ("volume", names["volume"]),
                           ("container", names["pg"]), ("container", names["java"])):
            if execute([kind, "inspect", name, "--format", "{{.Id}}" if kind != "volume" else "{{.Name}}"], check=False).returncode == 0:
                raise RuntimeError("C_RESOURCE_ALREADY_EXISTS")
        for key in ("classes", "jars"):
            (fixture / key).mkdir()
        shutil.copytree(compiled / "classes", fixture / "classes", dirs_exist_ok=True)
        for jar in [*junit, drivers[0]]:
            shutil.copyfile(jar, fixture / "jars" / jar.name)
        schema = root / "backend/src/main/resources/c-custody-schema.sql"
        shutil.copyfile(schema, fixture / "c-custody-schema.sql")
        shutil.copyfile(schema, fixture / "classes/c-custody-schema.sql")
        suffix = run[4:]
        database, user, marker = "cpg_" + suffix, "cowner_" + suffix, secrets.token_hex(32)
        password = secrets.token_hex(32)
        properties = {"scope": "C4B_PG_ONLY", "runId": run, "database": database,
                      "databaseUser": user, "marker": marker, "pgHost": "pg",
                      "jdbcUrl": "jdbc:postgresql://pg:5432/" + database + "?sslmode=disable&connectTimeout=3&socketTimeout=8",
                      "schemaPath": "/fixture/c-custody-schema.sql"}
        with (fixture / "manifest.properties").open("x", encoding="ascii") as out:
            for k, v in properties.items():
                out.write(k + "=" + v + "\n")
        secret_file = evidence / "fixture.private.env"
        with secret_file.open("x", encoding="ascii") as out:
            out.write("POSTGRES_DB=" + database + "\nPOSTGRES_USER=" + user + "\nPOSTGRES_PASSWORD=" + password
                      + "\nPOSTGRES_INITDB_ARGS=--auth-host=scram-sha-256\nC_CUSTODY_PG_PASSWORD=" + password
                      + "\nC_CUSTODY_PG_TESTS=true\nC_CUSTODY_FIXTURE=/fixture/manifest.properties\n")
        # One explicit non-overlapping allocation. No global default-pool edit or retries.
        execute(["network", "create", "--internal", "--subnet", selected_subnet, "--label", label, names["network"]])
        execute(["volume", "create", "--label", label, names["volume"]])
        if not inspect_owned("network", names["network"]) or not inspect_owned("volume", names["volume"]):
            raise RuntimeError("C_CREATED_RESOURCE_OWNER")
        if execute(["network", "inspect", names["network"], "--format", "{{.Internal}}"]).stdout.strip() != "true":
            raise RuntimeError("C_NETWORK_NOT_INTERNAL")
        subnet_check = json.loads(execute(["network", "inspect", names["network"], "--format", "{{json .IPAM.Config}}"]).stdout)
        if len(subnet_check) != 1 or subnet_check[0].get("Subnet") != selected_subnet:
            raise RuntimeError("C_NETWORK_SUBNET_IDENTITY")
        execute(["run", "-d", "--pull=never", "--name", names["pg"], "--label", label,
                 "--network", names["network"], "--network-alias", "pg", "--restart=no",
                 "--memory=192m", "--cpus=1", "--pids-limit=128", "--shm-size=32m",
                 "--cap-drop=NET_RAW", "--env-file", str(secret_file),
                 "--mount", "type=volume,source=" + names["volume"] + ",target=/var/lib/postgresql/data",
                 PG_IMAGE, "-c", "shared_buffers=16MB", "-c", "max_connections=12",
                 "-c", "max_wal_size=64MB"], timeout=30)
        pg_state = json.loads(execute(["container", "inspect", names["pg"], "--format",
                            '{"ports":{{json .HostConfig.PortBindings}},"mounts":{{json .Mounts}},"networks":{{json .NetworkSettings.Networks}}}']).stdout)
        if pg_state["ports"] or set(pg_state["networks"]) != {names["network"]}:
            raise RuntimeError("C_PG_ISOLATION")
        if len(pg_state["mounts"]) != 1 or pg_state["mounts"][0].get("Name") != names["volume"]:
            raise RuntimeError("C_PG_MOUNT")
        ready = False
        for _ in range(20):
            r = execute(postgres_readiness_args(names["pg"], user, database), timeout=5, check=False)
            if r.returncode == 0:
                ready = True
                break
            time.sleep(0.5)
        if not ready:
            raise RuntimeError("C_PG_NOT_READY")
        # Only this freshly created DB is touched; password never appears in SQL or logs.
        sql = ("CREATE TABLE public.c_fixture_identity(scope text NOT NULL,run_id text NOT NULL,marker text NOT NULL);"
               "INSERT INTO public.c_fixture_identity VALUES ('C4B_PG_ONLY','" + run + "','" + marker + "');"
               "CREATE TABLE public.c_preservation_sentinel(value text PRIMARY KEY);"
               "INSERT INTO public.c_preservation_sentinel VALUES ('preserve');")
        execute(["exec", "-i", names["pg"], "psql", "-v", "ON_ERROR_STOP=1", "-U", user, "-d", database], input_text=sql)
        java_args = java_test_args(JAVA_IMAGE, run, secret_file, fixture)
        execute(java_args, timeout=30)
        jstate = json.loads(execute(["container", "inspect", names["java"], "--format",
                            '{"ports":{{json .HostConfig.PortBindings}},"networks":{{json .NetworkSettings.Networks}}}']).stdout)
        if jstate["ports"] or set(jstate["networks"]) != {names["network"]}:
            raise RuntimeError("C_JAVA_ISOLATION")
        r = execute(["wait", names["java"]], timeout=150)
        execute(["logs", names["java"]], check=False)
        if r.stdout.strip() != "0":
            raise RuntimeError("C_PG_TEST_FAILED")
        sentinel = execute(["exec", names["pg"], "psql", "-X", "-tAc",
                            "SELECT value FROM public.c_preservation_sentinel", "-U", user, "-d", database])
        if sentinel.stdout.strip() != "preserve":
            raise RuntimeError("C_SENTINEL_CHANGED")
        for rel, digest in prior["sources"].items():
            if sha(root / rel) != digest:
                raise RuntimeError("C_SOURCE_CHANGED_DURING_GATE")
        status = "PASS"
    except Exception as error:
        # No connection strings, provider payloads or secret values in public summaries.
        safe_error = type(error).__name__ + ":" + str(error) if isinstance(error, RuntimeError) else type(error).__name__
    finally:
        for key in ("java", "pg"):
            try:
                if inspect_owned("container", names[key]):
                    execute(["logs", names[key]], check=False)
                    stopped[key] = execute(["stop", "--time=10", names[key]], timeout=20, check=False).returncode == 0
            except Exception:
                stopped[key] = False
        if status == "PASS" and stopped != {"java": True, "pg": True}:
            status, safe_error = "BLOCKED", "C_OWNED_STOP_UNCONFIRMED"
        record = {"run": run, "status": status, "error": safe_error, "resources": names,
                  "stopped": stopped, "sourceHash": prior["sourceHash"], "sources": prior["sources"],
                  "schemaHash": sha(root / "backend/src/main/resources/c-custody-schema.sql"),
                  "images": {"postgres": PG_IMAGE, "java": JAVA_IMAGE}, "commands": commands,
                  "networkAllocation": network_allocation,
                  "scope": "NEW_OWNED_PG_TYPED_STORAGE_ONLY_NOT_CHAIN_PROOF", "paidCalls": 0,
                  "hostPorts": False, "oldResourceMutationsRequested": 0}
        with (evidence / "result.private.json").open("x", encoding="utf-8") as out:
            json.dump(record, out, indent=2)
        print(json.dumps({k: record[k] for k in ("run", "status", "error", "stopped", "sourceHash")}))
    return 0 if status == "PASS" and all(stopped.values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
