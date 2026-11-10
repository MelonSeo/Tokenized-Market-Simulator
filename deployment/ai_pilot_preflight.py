"""Offline v2 plan audit only. No network, secret, DB, Docker or ledger access.

This is deliberately NOT a live runner or an approval-file generator. In
particular a correct budget calculation does not repair the old paid adapters.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re

from ai_novel_question_audit import audit, read_json, skill_domains

ROOT = Path(__file__).resolve().parents[1]
POLICY_PATH = Path("deployment/ai-pilot-budget-v2.json")
PLAN_PATH = Path("deployment/ai-pilot-execution-v2.json")
V2_FILE_HASHES = {
    POLICY_PATH.as_posix(): "baef5ee9df9dd242645e159454d68e5782c6cefecc2b0228ea00d70494cd381f",
    PLAN_PATH.as_posix(): "15277536a0f0db81a2cd77cd8042d93911df7a3a28b8537c583303697d2395d8",
}
RESOURCE = Path("backend/src/test/resources/ai")
EXPECTED_BUDGET = {
    "version": "tms-pilot-budget-v2", "status": "PROPOSAL_ONLY_NOT_APPROVED",
    "activationAllowed": False, "currency": "USD", "capNusd": 18000000000,
    "dailyCapNusd": 18000000000, "llmModel": "gpt-5.6-terra",
    "embeddingModel": "text-embedding-3-small", "llmCalls": 92,
    "embeddingCalls": 70, "llmInputTokens": 6123520,
    "llmOutputTokens": 147200, "embeddingInputTokens": 4659200,
    "wireBytesPerCall": 65536, "framingTokensPerCall": 1024,
    "outputTokensPerCall": 1600, "inputNusdPerToken": 2500,
    "outputNusdPerToken": 12000, "embeddingNusdPerToken": 20,
    "maximumReservedNusd": 17168384000, "serviceTier": "default",
    "store": False, "reasoningEffort": "none", "automaticRetries": 0,
    "hostedTools": False, "previousResponseId": False,
    "extendedCacheRetention": False, "regionalEndpoint": False,
    "budgetAuthorities": 1, "reusePriorAllocation": False, "refundUnknownUsage": False,
}
FROZEN = {
    (RESOURCE / "pilot-acceptance-v1.json").as_posix(): "08c034ac7f50ecffbbc3d0f6ae9da0c7e611b4c948a0849533fddbe21983e3cb",
    (RESOURCE / "novel-30-v2.json").as_posix(): "8666064a7f9be316204bcdb6045e59a7e99f95676dd0eafafa86605abe9aaa40",
    (RESOURCE / "novel-30-v2.lock.json").as_posix(): "e4c2080b3d13cfc478f4597fd88ddde2d40fe367ad76f711857c6f58cfe1983e",
    "docs/ai/ingest-manifest-pilot.json": "13d64f3e3cadd7446e30015c2cb2054e2c0046d543df0a3ebbc28fd67e2a5191",
}
PHASE_QUOTAS = {
    "INDEX_LEGACY": (0, 0, 6), "SAFETY_LEGACY": (6, 8, 6),
    "NOVEL30": (30, 51, 30), "INDEX_PILOT": (0, 0, 6),
    "SAFETY_C": (3, 3, 3), "LATENCY": (12, 18, 12),
    "WEB_PILOT": (6, 12, 6), "EMBEDDING_HEADROOM": (0, 0, 1),
}
PHASE_CORPORA = ("legacy9", "legacy9", "legacy9", "pilot10", "pilot10", "legacy9", "pilot10", "none")
STOP_CONDITIONS = ["EXPLICIT_APPROVAL_MISSING", "SOURCE_OR_FROZEN_HASH_DRIFT",
                   "RESOURCE_GUARD_BLOCKED", "OWNERSHIP_OR_ISOLATION_UNVERIFIED",
                   "PRICE_OR_MODEL_ACCESS_UNVERIFIED", "BUDGET_OR_PHASE_QUOTA_EXHAUSTED",
                   "UNKNOWN_USAGE_OR_UNCERTAIN_TRANSPORT", "WRONG_FEE_RELATION",
                   "FOREIGN_DATA_EXPOSURE", "UNAPPROVED_POLICY", "INVENTED_EXECUTION_SUCCESS",
                   "FINANCIAL_FINGERPRINT_CHANGED", "C_LEGACY_SCOPE_CONFUSION"]


def need(condition, code):
    if not condition:
        raise ValueError(code)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True,
                      separators=(",", ":"), allow_nan=False).encode("utf-8")


def validate_budget(budget):
    # Strict types: bool is a subclass of int in Python, not an approved quota.
    need(set(budget) == set(EXPECTED_BUDGET), "V2_BUDGET_FIELDS")
    need(all(type(budget[k]) is type(v) and budget[k] == v
             for k, v in EXPECTED_BUDGET.items()), "V2_BUDGET_NOT_APPROVED_FOR_ACTIVATION")
    bound = budget["wireBytesPerCall"] + budget["framingTokensPerCall"]
    need(bound < 272000, "LONG_CONTEXT_SURCHARGE_UNBOUNDED")
    need(budget["llmInputTokens"] == budget["llmCalls"] * bound, "INPUT_BOUND")
    need(budget["llmOutputTokens"] == budget["llmCalls"] * budget["outputTokensPerCall"], "OUTPUT_BOUND")
    need(budget["embeddingInputTokens"] == budget["embeddingCalls"] * bound, "EMBEDDING_BOUND")
    maximum = (budget["llmInputTokens"] * budget["inputNusdPerToken"]
               + budget["llmOutputTokens"] * budget["outputNusdPerToken"]
               + budget["embeddingInputTokens"] * budget["embeddingNusdPerToken"])
    need(maximum == budget["maximumReservedNusd"] <= budget["capNusd"], "V2_COST_BOUND")
    return maximum


def dispatch_preview(budget, used, kind, wire_bytes, output=0):
    """Pure estimate, NOT durable reservation or authority to call a supplier."""
    validate_budget(budget)
    keys = {"llmCalls", "embeddingCalls", "llmInputTokens", "llmOutputTokens",
            "embeddingInputTokens", "reservedNusd"}
    need(set(used) == keys and all(type(v) is int and v >= 0 for v in used.values()), "USAGE_INVALID")
    need(kind in {"LLM", "EMBEDDING"}, "KIND_INVALID")
    need(type(wire_bytes) is int and 0 < wire_bytes <= budget["wireBytesPerCall"], "WIRE_LIMIT")
    need(type(output) is int and (1 <= output <= budget["outputTokensPerCall"]
         if kind == "LLM" else output == 0), "OUTPUT_LIMIT")
    tokens = wire_bytes + budget["framingTokensPerCall"]
    additions = dict.fromkeys(keys, 0)
    additions["llmCalls" if kind == "LLM" else "embeddingCalls"] = 1
    additions["llmInputTokens" if kind == "LLM" else "embeddingInputTokens"] = tokens
    additions["llmOutputTokens"] = output
    additions["reservedNusd"] = tokens * budget["inputNusdPerToken" if kind == "LLM" else "embeddingNusdPerToken"] + output * budget["outputNusdPerToken"]
    totals = {k: used[k] + additions[k] for k in keys}
    need(all(v <= budget["capNusd" if k == "reservedNusd" else k]
             for k, v in totals.items()), "V2_PRE_DISPATCH_LIMIT")
    return totals


def documents(root, manifest):
    value = read_json(root / manifest)
    result = {}
    for entry in value["documents"]:
        name = entry["path"]
        need(bool(re.fullmatch(r"[a-z0-9-]+\.md", name)) and name not in result, "DOCUMENT_PATH")
        path = root / "docs/ai-knowledge" / name
        need(not path.is_symlink(), "DOCUMENT_LINK")
        text = path.read_text(encoding="utf-8").replace("\r\n", "\n").replace("\r", "\n")
        need(sha(text.encode("utf-8")) == entry["sha256"], "APPROVAL_HASH")
        metadata = text.split("\n---\n", 1)[0]
        role = re.search(r"^minimum_role: (USER|ADMIN)$", metadata, re.M)
        domain = re.search(r"^domain: ([a-z]+)$", metadata, re.M)
        version = re.search(r"^version: ['\"]?([0-9]+)['\"]?$", metadata, re.M)
        need(role and domain and version and version[1] == entry["documentVersion"], "DOCUMENT_METADATA")
        result[name] = {"sha256": entry["sha256"], "role": role[1], "domain": domain[1]}
    return result


def validate_plan(root, plan, budget):
    need(plan["version"] == "tms-pilot-execution-v2" and plan["status"] == "OFFLINE_PREPARATION_NOT_EXECUTION_APPROVAL", "PLAN_VERSION")
    need(plan["frozen"] == FROZEN, "FROZEN_IDENTITY_POLICY")
    for name, digest in FROZEN.items():
        path = root / name
        need(not path.is_symlink() and sha(path.read_bytes()) == digest, "FROZEN_BYTES_CHANGED")
    old = read_json(root / RESOURCE / "pilot-acceptance-v1.json")
    novel = read_json(root / RESOURCE / "novel-30-v2.json")
    # v1 remains historical; there is no automatic extraction of its budget.
    need(old["budgetProposalNotApproval"]["requestedUsdCap"] == "15.00", "V1_HISTORY_CHANGED")
    targets = {c["id"]: c for c in old["cases"]}
    need(len(targets) == 9 and len(novel["cases"]) == 30, "CASE_COUNT")
    phases = plan["phases"]
    need([p["id"] for p in phases] == list(PHASE_QUOTAS), "PHASE_ORDER")
    ids = set(targets) | {c["id"] for c in novel["cases"]}
    for p, corpus in zip(phases, PHASE_CORPORA):
        need(p["corpus"] == corpus, "PHASE_CORPUS_CHANGED")
        need(all(type(p[k]) is int for k in ("requests", "llm", "embedding")), "PHASE_QUOTA_TYPE")
        need((p["requests"], p["llm"], p["embedding"]) == PHASE_QUOTAS[p["id"]], "PHASE_QUOTA_CHANGED")
        need(len(p["cases"]) == p["requests"] and all(c in ids for c in p["cases"]), "PHASE_CASES")
    by_id = {p["id"]: p for p in phases}
    need(by_id["NOVEL30"]["cases"] == [c["id"] for c in novel["cases"]], "NOVEL_ORDER_CHANGED")
    need(by_id["SAFETY_LEGACY"]["cases"] + by_id["SAFETY_C"]["cases"] == list(targets), "TARGET_CASES_CHANGED")
    need(by_id["LATENCY"]["cases"] == ["A1"] * 6 + ["PF1b"] * 6, "REPEATED_LATENCY_SET")
    need(by_id["WEB_PILOT"]["cases"] == ["PA2a", "PF1b", "PCa"] * 2, "PILOT_SET")
    need(sum(p["llm"] for p in phases) == budget["llmCalls"] and
         sum(p["embedding"] for p in phases) == budget["embeddingCalls"], "PHASE_TOTALS")
    need(plan["pilot"]["accounts"] == 2 and plan["pilot"]["requestsPerAccount"] == 3 and
         plan["pilot"]["llmReserved"] == 12 and plan["pilot"]["embeddingReserved"] == 6, "PILOT_NOT_RESERVED")
    need(by_id["NOVEL30"]["corpus"] == "legacy9" and by_id["SAFETY_C"]["corpus"] == "pilot10", "CORPUS_RESULTS_MIXED")
    need(not plan["evaluation"]["extraRetrievalProbes"] and not plan["evaluation"]["twoClauseComparisons"]
         and not plan["evaluation"]["autoRetry"] and not plan["evaluation"]["duplicateReplay"], "UNBUDGETED_COMPARISONS")
    need(plan["requestBinding"] == {"symbolicTargetMappingOnlyInRunner": True, "serverJwtPrincipalRequired": True,
         "foreignMissingControlPaidCalls": 0, "expectedAnswersInModelInput": False,
         "advanceOnlyAfterEvidenceReview": True}, "REQUEST_BINDING_CHANGED")
    need(plan["requiredEnvironment"] == {"platform": "linux", "minimumRamMiB": 1920, "minimumDiskGiB": 2,
         "loopbackOnly": True, "freshOwnedTradePg": True, "freshOwnedAnvil": True, "freshOwnedAiPgvector": True,
         "sameOriginWeb": True, "readOnlyAfterFixtureFreeze": True, "userPrivateKeyInApp": False,
         "separateCorpusIdentities": True, "singleSharedBudgetAuthority": True}, "ENVIRONMENT_BOUNDARY_CHANGED")
    need(plan["stopConditions"] == STOP_CONDITIONS, "STOP_CONDITIONS_CHANGED")
    need(plan["fixtures"] == {"legacyOwnerAndQuoteInput": "75000", "legacyIncludedFee": "75",
         "legacyFeeMustBeObservedNotConfiguredToPass": True, "bothPilotAccountsNeedOwnQuotesAndProvedCWallets": True,
         "legacyAdverseDtosExplicitlyLabeled": True, "cClaimsEnumerationSupported": False,
         "cFixture": "NEW_PUBLIC_RECIPE_NOT_USER_CHECKPOINT"}, "FIXTURE_BOUNDARY_CHANGED")
    need(plan["indexStrategy"]["legacyFreshBatches"] == 6 and plan["indexStrategy"]["pilotFullFreshBatches"] == 6
         and plan["indexStrategy"]["fullReindexBatchesPlanned"] == 12 and
         plan["indexStrategy"]["unchangedVectorReuseImplemented"] is False and
         plan["indexStrategy"]["approvedVectorsCopiedFromPriorRuns"] is False, "INDEX_STRATEGY_CHANGED")
    legacy = documents(root, "docs/ai/ingest-manifest.json")
    pilot = documents(root, "docs/ai/ingest-manifest-pilot.json")
    need(len(legacy) == 9 and len(pilot) == 10 and all(pilot.get(k) == v for k, v in legacy.items()), "CORPUS_EXTENSION_CHANGED")
    need(pilot["c-vault-read-policy.md"]["role"] == "USER" and pilot["c-vault-read-policy.md"]["domain"] == "custody", "C_POLICY_SCOPE")
    need(all(d["domain"] != "custody" for d in legacy.values()), "LEGACY_C_SCOPE_MIXED")
    need(plan["executionAdapter"]["status"] == "NOT_IMPLEMENTED_OR_UNPAID_VERIFIED", "UNVERIFIED_ADAPTER_CLAIM")
    return {"fixedQuestions": 30, "targetQuestions": 9, "agentRequests": sum(p["requests"] for p in phases),
            "fullIndexCalls": 12, "queryEmbeddingCalls": 57, "embeddingHeadroom": 1,
            "legacyDocuments": len(legacy), "pilotDocuments": len(pilot)}


def preflight(root=ROOT):
    for name, expected in V2_FILE_HASHES.items():
        path = root / name
        need(not path.is_symlink() and sha(path.read_bytes()) == expected, "V2_FROZEN_PLAN_CHANGED")
    budget = read_json(root / POLICY_PATH)
    plan = read_json(root / PLAN_PATH)
    maximum = validate_budget(budget)
    counts = validate_plan(root, plan, budget)
    audit_result = audit(root)
    domains = skill_domains(root)
    need(len(domains) == 4, "SKILL_SET_CHANGED")
    source = (root / "backend/src/main/java/com/pricetrack/exchange/ai/tool/ToolRegistry.java").read_text(encoding="utf-8")
    registry = re.findall(r'"([A-Za-z]+)"', source.split("NAMES = Set.of(", 1)[1].split(");", 1)[0])
    need(len(registry) == 12 and {"getCAccount", "getCPosition"} <= set(registry), "TOOL_SET_CHANGED")
    return {
        "status": "PASS_OFFLINE_PLAN_INTEGRITY_NOT_EXECUTION_READY",
        "readyForPaidExecution": False, "paidApproval": False,
        "providerRequests": 0, "secretReads": 0, "ledgerWrites": 0,
        "budgetSha256": sha((root / POLICY_PATH).read_bytes()),
        "planSha256": sha((root / PLAN_PATH).read_bytes()),
        "frozenHashes": dict(FROZEN), "counts": counts,
        "maximumReservedNusd": maximum, "requestedCapNusd": budget["capNusd"],
        "v1BudgetActivationAllowed": False, "priorAllocationsReused": False,
        "actualCorpusLoader": "PRIOR_SAME_SOURCE_LINUX_PASS_9_88_AND_10_93_NOT_RERUN",
        "modelDocumentation": "OFFICIAL_MODEL_PAGES_CHECKED_SEPARATELY",
        "accountModelAccess": "NOT_RUN_NO_PROVIDER_REQUEST_BEFORE_APPROVAL",
        "sourceEvidenceAvailability": audit_result["status"],
        "blockingItems": [
            "V2_DUAL_RESERVATION_ACTUAL_POSTGRES_NOT_RUN",
            "LEGACY_AND_C_REAL_FIXTURE_BINDING_NOT_RUN",
            "SPRING_HTTP_AND_SIX_CHROME_SUBMISSIONS_NOT_RUN",
            "EXPLICIT_NEW_PAID_APPROVAL_MISSING",
        ],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["check"])
    args = parser.parse_args()
    try:
        result = preflight()
    except (ValueError, OSError, KeyError, TypeError) as error:
        code = str(error) if re.fullmatch(r"[A-Z0-9_]+", str(error)) else "OFFLINE_PREFLIGHT_FAILED"
        print(json.dumps({"status": "FAIL", "code": code, "providerRequests": 0}))
        return 1
    print(json.dumps(result, ensure_ascii=False, indent=2))
    # Plan checks passed, but intentionally nonzero for automation demanding a runnable gate.
    return 2 if not result["readyForPaidExecution"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
