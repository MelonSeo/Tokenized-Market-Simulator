"""No network, Docker, key reads, model calls or preserved-ledger mutation."""
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch

import ai_pilot_preflight as p


class PilotV2PreflightTests(unittest.TestCase):
    def setUp(self):
        self.budget = p.read_json(p.ROOT / p.POLICY_PATH)
        self.plan = p.read_json(p.ROOT / p.PLAN_PATH)

    def used(self):
        return dict.fromkeys(("llmCalls", "embeddingCalls", "llmInputTokens",
                              "llmOutputTokens", "embeddingInputTokens", "reservedNusd"), 0)

    def test_exact_v2_is_not_an_approval(self):
        self.assertEqual(17168384000, p.validate_budget(self.budget))
        self.assertFalse(self.budget["activationAllowed"])
        self.assertEqual("PROPOSAL_ONLY_NOT_APPROVED", self.budget["status"])

    def test_old_v1_budget_cannot_be_loaded_as_v2(self):
        old = p.read_json(p.ROOT / p.RESOURCE / "pilot-acceptance-v1.json")
        with self.assertRaisesRegex(ValueError, "V2_BUDGET_FIELDS"):
            p.validate_budget(old["budgetProposalNotApproval"])

    def test_each_budget_value_is_immutable_including_types(self):
        for key, replacement in [("activationAllowed", True), ("llmCalls", 93),
                                 ("embeddingCalls", 71), ("inputNusdPerToken", 2000),
                                 ("budgetAuthorities", 2), ("regionalEndpoint", True),
                                 ("extendedCacheRetention", True), ("automaticRetries", False)]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                changed = dict(self.budget)
                changed[key] = replacement
                p.validate_budget(changed)

    def test_full_wire_bound_matches_server_estimate_and_total_cap(self):
        used = self.used()
        for _ in range(92):
            used = p.dispatch_preview(self.budget, used, "LLM", 65536, 1600)
        for _ in range(70):
            used = p.dispatch_preview(self.budget, used, "EMBEDDING", 65536)
        self.assertEqual(17168384000, used["reservedNusd"])
        self.assertEqual(6123520, used["llmInputTokens"])
        self.assertEqual(147200, used["llmOutputTokens"])
        self.assertEqual(4659200, used["embeddingInputTokens"])
        with self.assertRaisesRegex(ValueError, "V2_PRE_DISPATCH_LIMIT"):
            p.dispatch_preview(self.budget, used, "LLM", 1, 1)
        with self.assertRaisesRegex(ValueError, "V2_PRE_DISPATCH_LIMIT"):
            p.dispatch_preview(self.budget, used, "EMBEDDING", 1)

    def test_cost_equality_is_allowed_and_one_nano_over_is_not(self):
        cost = (10 + 1024) * 2500 + 1600 * 12000
        used = self.used()
        used["reservedNusd"] = self.budget["capNusd"] - cost
        self.assertEqual(self.budget["capNusd"], p.dispatch_preview(self.budget, used, "LLM", 10, 1600)["reservedNusd"])
        used["reservedNusd"] += 1
        with self.assertRaisesRegex(ValueError, "V2_PRE_DISPATCH_LIMIT"):
            p.dispatch_preview(self.budget, used, "LLM", 10, 1600)

    def test_no_negative_unknown_or_boolean_usage(self):
        for value in (-1, True, "1"):
            used = self.used()
            used["reservedNusd"] = value
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, "USAGE_INVALID"):
                p.dispatch_preview(self.budget, used, "LLM", 1, 1)

    def test_every_token_limit_is_checked_in_preview(self):
        for key, kind, output in [("llmInputTokens", "LLM", 1), ("llmOutputTokens", "LLM", 1),
                                  ("embeddingInputTokens", "EMBEDDING", 0),
                                  ("llmCalls", "LLM", 1), ("embeddingCalls", "EMBEDDING", 0)]:
            used = self.used()
            used[key] = self.budget[key]
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, "V2_PRE_DISPATCH_LIMIT"):
                p.dispatch_preview(self.budget, used, kind, 1, output)

    def test_request_size_output_and_kind_fail_before_a_sender_exists(self):
        for kind, size, output in [("LLM", 65537, 1), ("LLM", 0, 1), ("LLM", True, 1),
                                   ("LLM", 1, 1601), ("LLM", 1, 0),
                                   ("EMBEDDING", 1, 1), ("OTHER", 1, 0)]:
            with self.subTest(kind=kind, size=size, output=output), self.assertRaises(ValueError):
                p.dispatch_preview(self.budget, self.used(), kind, size, output)

    def test_frozen_cases_and_two_full_indices_fit_without_copy_assumptions(self):
        counts = p.validate_plan(p.ROOT, self.plan, self.budget)
        self.assertEqual(57, counts["agentRequests"])
        self.assertEqual(12, counts["fullIndexCalls"])
        self.assertEqual(57, counts["queryEmbeddingCalls"])
        self.assertEqual(1, counts["embeddingHeadroom"])
        self.assertEqual(70, 12 + 57 + 1)
        self.assertFalse(self.plan["indexStrategy"]["unchangedVectorReuseImplemented"])

    def test_pilot_is_explicitly_reserved_not_leftovers(self):
        row = next(x for x in self.plan["phases"] if x["id"] == "WEB_PILOT")
        self.assertEqual((6, 12, 6), (row["requests"], row["llm"], row["embedding"]))
        self.assertEqual(["PA2a", "PF1b", "PCa"] * 2, row["cases"])

    def test_phase_budget_oracle_and_corpus_changes_reject(self):
        mutations = [lambda x: x["phases"][1].update(llm=9),
                     lambda x: x["phases"][2]["cases"].reverse(),
                     lambda x: x["phases"][2].update(corpus="pilot10"),
                     lambda x: x["phases"][6]["cases"].__setitem__(0, "PF1a"),
                     lambda x: x["evaluation"].update(extraRetrievalProbes=True),
                     lambda x: x["executionAdapter"].update(status="READY")]
        for mutate in mutations:
            changed = copy.deepcopy(self.plan)
            mutate(changed)
            with self.subTest(change=mutations.index(mutate)), self.assertRaises(ValueError):
                p.validate_plan(p.ROOT, changed, self.budget)

    def test_all_phase_corpora_and_required_safety_boundaries_are_checked(self):
        mutations = [lambda x: x["requiredEnvironment"].update(singleSharedBudgetAuthority=False),
                     lambda x: x["requestBinding"].update(expectedAnswersInModelInput=True),
                     lambda x: x["stopConditions"].pop(),
                     lambda x: x["indexStrategy"].update(legacyFreshBatches=3),
                     lambda x: x["fixtures"].update(legacyIncludedFee="1")]
        for mutate in mutations:
            changed = copy.deepcopy(self.plan)
            mutate(changed)
            with self.subTest(change=mutations.index(mutate)), self.assertRaises(ValueError):
                p.validate_plan(p.ROOT, changed, self.budget)
        for index in range(len(self.plan["phases"])):
            changed = copy.deepcopy(self.plan)
            changed["phases"][index]["corpus"] = "none" if index < 7 else "pilot10"
            with self.subTest(phase=index), self.assertRaisesRegex(ValueError, "PHASE_CORPUS_CHANGED"):
                p.validate_plan(p.ROOT, changed, self.budget)

    def test_old_files_preserved_and_zero_approval_even_when_audit_passes(self):
        before = {name: hashlib.sha256((p.ROOT / name).read_bytes()).hexdigest() for name in p.FROZEN}
        result = p.preflight()
        self.assertEqual(before, result["frozenHashes"])
        self.assertFalse(result["readyForPaidExecution"])
        self.assertFalse(result["paidApproval"])
        self.assertFalse(result["v1BudgetActivationAllowed"])
        self.assertEqual((0, 0, 0), (result["providerRequests"], result["secretReads"], result["ledgerWrites"]))
        self.assertEqual(4, len(result["blockingItems"]))

    def test_v2_raw_hashes_pin_all_plan_fields_and_windows_lf(self):
        for name, digest in p.V2_FILE_HASHES.items():
            raw = (p.ROOT / name).read_bytes()
            self.assertEqual(digest, hashlib.sha256(raw).hexdigest())
            self.assertNotIn(b"\r", raw)
        attributes = (p.ROOT / ".gitattributes").read_text(encoding="utf-8")
        for name in p.V2_FILE_HASHES:
            self.assertIn(name + " text eol=lf", attributes)

    def test_cli_cannot_silently_treat_plan_pass_as_execution_ready(self):
        done = subprocess.run([sys.executable, "-B", str(Path(p.__file__)), "check"],
                              capture_output=True, timeout=30, check=False)
        self.assertEqual(2, done.returncode)
        self.assertFalse(json.loads(done.stdout)["readyForPaidExecution"])
        denied = subprocess.run([sys.executable, "-B", str(Path(p.__file__)), "execute"],
                                capture_output=True, timeout=10, check=False)
        self.assertNotEqual(0, denied.returncode)

    def test_source_has_no_live_side_effect_capabilities(self):
        import ast
        tree = ast.parse(Path(p.__file__).read_text(encoding="utf-8"))
        imports = {n.names[0].name.split(".")[0] for n in ast.walk(tree) if isinstance(n, ast.Import)}
        self.assertFalse(imports & {"http", "requests", "urllib", "sqlite3", "socket", "subprocess", "os"})
        self.assertFalse(any(isinstance(n, ast.Attribute) and n.attr in
                             {"write_text", "write_bytes", "unlink", "mkdir", "rmdir"}
                             for n in ast.walk(tree)))


if __name__ == "__main__":
    unittest.main()
