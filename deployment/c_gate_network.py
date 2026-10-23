"""Pure bounded address selection, never changes host routes or old Docker networks."""
import ipaddress
import re


def postgres_readiness_args(container, user, database):
    # The official image's init server listens on a Unix socket only, then
    # shuts down. TCP readiness selects the final server, not that transient one.
    if (not re.fullmatch(r"cpg-[0-9a-f]{16}-pg", container)
            or not re.fullmatch(r"cowner_[0-9a-f]{16}", user)
            or not re.fullmatch(r"cpg_[0-9a-f]{16}", database)):
        raise ValueError("C_PG_READINESS_IDENTITY")
    suffix = container[4:-3]
    if user != "cowner_" + suffix or database != "cpg_" + suffix:
        raise ValueError("C_PG_READINESS_IDENTITY")
    return ["exec", container, "pg_isready", "-h", "127.0.0.1", "-p", "5432", "-U", user, "-d", database]


def java_test_args(image, run, secret_file, fixture):
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", image) or not re.fullmatch(r"cpg-[0-9a-f]{16}", run):
        raise ValueError("C_JAVA_LAUNCH_IDENTITY")
    return ["run", "-d", "--pull=never", "--name", run + "-java", "--label", "tms.c.owner=" + run,
            "--network", run + "-network", "--restart=no", "--read-only", "--user=65534:65534",
            "--cap-drop=ALL", "--security-opt=no-new-privileges", "--memory=384m", "--cpus=1",
            "--pids-limit=128", "--tmpfs", "/tmp:rw,noexec,nosuid,size=32m", "--env-file", str(secret_file),
            "--mount", "type=bind,source=" + str(fixture) + ",target=/fixture,readonly",
            "--entrypoint", "java", image, "-Xms32m", "-Xmx192m", "-XX:MaxMetaspaceSize=128m",
            "-Dfile.encoding=UTF-8", "-cp", "/fixture/classes:/fixture/jars/*", "CPostgresTests"]


def select_subnet(docker_subnets, windows_routes):
    def parse(value):
        try:
            return ipaddress.ip_network(value, strict=False)
        except (ValueError, TypeError):
            raise ValueError("C_NETWORK_INVENTORY_FORMAT") from None
    if not isinstance(docker_subnets, list) or not isinstance(windows_routes, list) or not windows_routes:
        raise ValueError("C_NETWORK_INVENTORY_MISSING")
    if len(docker_subnets) > 4096 or len(windows_routes) > 4096:
        raise ValueError("C_NETWORK_INVENTORY_LIMIT")
    docker = [parse(x) for x in docker_subnets]
    routes = [parse(x) for x in windows_routes]
    # The default route is an egress fallback, not an allocated local subnet.
    blocked = [x for x in docker if x.version == 4] + [x for x in routes if x.version == 4 and x.prefixlen > 0]
    for candidate in ipaddress.ip_network("10.254.0.0/16").subnets(new_prefix=28):
        if not any(candidate.overlaps(x) for x in blocked):
            return str(candidate)
    raise ValueError("C_NO_NONOVERLAPPING_TEST_SUBNET")
