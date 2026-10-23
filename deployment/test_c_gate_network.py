import unittest
from deployment.c_gate_network import select_subnet, postgres_readiness_args, java_test_args


class CNetworkSelectionTest(unittest.TestCase):
    def test_default_pool_exhaustion_does_not_need_old_network_deletion(self):
        old = [f"172.{n}.0.0/16" for n in range(16, 32)]
        self.assertEqual("10.254.0.0/28", select_subnet(old, ["0.0.0.0/0", "192.168.1.0/24"]))

    def test_existing_docker_and_host_ranges_are_both_excluded(self):
        self.assertEqual("10.254.0.32/28", select_subnet(["10.254.0.0/28"], ["10.254.0.16/28"]))

    def test_broad_corporate_route_fails_closed_without_alternate_retry(self):
        with self.assertRaisesRegex(ValueError, "C_NO_NONOVERLAPPING"):
            select_subnet([], ["10.0.0.0/8"])

    def test_malformed_or_missing_inventory_is_not_ignored(self):
        for docker, routes in [([], []), (["not-cidr"], ["0.0.0.0/0"]), ([], ["?"]), (None, ["0.0.0.0/0"])]:
            with self.assertRaises(ValueError):
                select_subnet(docker, routes)

    def test_ipv6_and_loopback_are_not_false_ipv4_overlaps(self):
        self.assertEqual("10.254.0.0/28", select_subnet(["fd00::/64"], ["127.0.0.0/8", "::/0"]))

    def test_bounded_inventory_and_exactly_one_chosen_subnet(self):
        with self.assertRaisesRegex(ValueError, "C_NETWORK_INVENTORY_LIMIT"):
            select_subnet(["172.16.0.0/16"] * 4097, ["0.0.0.0/0"])
        self.assertEqual("10.254.1.0/28", select_subnet(["10.254.0.0/24"], ["0.0.0.0/0"]))

    def test_pg_readiness_requires_final_tcp_server_not_init_unix_socket(self):
        suffix = "a" * 16
        args = postgres_readiness_args("cpg-" + suffix + "-pg", "cowner_" + suffix, "cpg_" + suffix)
        self.assertEqual(["-h", "127.0.0.1", "-p", "5432"], args[3:7])
        with self.assertRaisesRegex(ValueError, "C_PG_READINESS_IDENTITY"):
            postgres_readiness_args("old-pg", "postgres", "existing-db")
        with self.assertRaisesRegex(ValueError, "C_PG_READINESS_IDENTITY"):
            postgres_readiness_args("cpg-" + suffix + "-pg", "cowner_" + "b" * 16, "cpg_" + suffix)

    def test_explicit_java_entrypoint_replaces_cached_spring_image_entrypoint(self):
        image = "sha256:" + "1" * 64
        args = java_test_args(image, "cpg-" + "a" * 16, "/owned/secret.env", "/owned/fixture")
        entrypoint = args[args.index("--entrypoint") + 1]
        effective = [entrypoint, *args[args.index(image) + 1:]]
        self.assertEqual("java", effective[0])
        self.assertEqual("CPostgresTests", effective[-1])
        self.assertNotIn("-jar", effective)
        self.assertNotIn("/app/app.jar", effective)
        self.assertIn("--read-only", args)
        self.assertEqual("/fixture/classes:/fixture/jars/*", effective[effective.index("-cp") + 1])
        with self.assertRaisesRegex(ValueError, "C_JAVA_LAUNCH_IDENTITY"):
            java_test_args("postgres:latest", "existing-java", "/secret", "/fixture")


if __name__ == "__main__":
    unittest.main()
