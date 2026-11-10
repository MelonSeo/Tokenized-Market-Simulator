"""Unpaid, local-only evaluation-design audit. No provider, DB or Docker access.

Evidence availability is NOT ranked retrieval accuracy or generated-answer quality.
The lock is authored separately; this tool never creates or updates its own oracle.
"""
import argparse
from collections import Counter
from difflib import SequenceMatcher
import hashlib
import json
from pathlib import Path
import re
import unicodedata

ROOT = Path(__file__).resolve().parents[1]
RESOURCE = Path("backend/src/test/resources/ai")
DATASET = RESOURCE / "novel-30-v2.json"
LOCK = RESOURCE / "novel-30-v2.lock.json"


def normalized(text):
    return re.sub(r"[\W_]+", "", unicodedata.normalize("NFKC", text).casefold())


def read_json(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("DUPLICATE_JSON_KEY")
            result[key] = value
        return result
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique)


def java_strings(text):
    return [json.loads('"' + x + '"') for x in re.findall(r'"((?:[^"\\]|\\.)*)"', text)]


def historical_questions(root):
    source = (root / "backend/src/test/java/com/pricetrack/exchange/ai/evaluation/ApprovedAiModelEvaluationTest.java").read_text(encoding="utf-8")
    fixed = source.split("static List<String> fixedQuestions(){return List.of(", 1)[1].split(");}", 1)[0]
    rows = [{"family": "Q", "question": s} for s in java_strings(fixed)]
    for name, family in (("retrieval-golden.json", "golden"), ("latest-acceptance-supplement.json", "H")):
        values = read_json(root / RESOURCE / name)
        for row in values if isinstance(values, list) else values["cases"]:
            rows.append({"family": family, "question": row["question"]})
    supplement = read_json(root / RESOURCE / "retrieval-supplement.json")
    rows += [{"family": "negative", "question": s} for s in supplement["negative"]]
    rows += [{"family": "identifier", "question": r["question"]} for r in supplement["identifiers"]]
    benchmark = (root / "backend/src/test/java/com/pricetrack/exchange/ai/LatestRetrievalBenchmark.java").read_text(encoding="utf-8")
    additional = benchmark.split("for(String q:List.of(", 1)[1].split("))", 1)[0]
    rows += [{"family": "additional", "question": s} for s in java_strings(additional)]
    return rows


def skill_domains(root):
    resources = root / "backend/src/main/resources/ai/skills"
    manifest = read_json(resources / "manifest.json")
    result = {}
    for name, entry in manifest.items():
        if not re.fullmatch(r"[a-z-]+", name):
            raise ValueError("INVALID_SKILL_ID")
        text = (resources / (name + ".md")).read_text(encoding="utf-8").replace("\r\n", "\n").replace("\r", "\n")
        if hashlib.sha256(text.encode()).hexdigest() != entry["hash"]:
            raise ValueError("SKILL_APPROVAL_MISMATCH")
        domains = re.search(r"^retrievalDomains: \[([a-z, -]+)\]$", text, re.M)
        if not domains:
            raise ValueError("SKILL_DOMAINS_INVALID")
        result[name] = set(domains.group(1).split(", "))
    return result


def evidence_allowed(case, document, domains):
    _, role, domain = document
    return (case["actor"] == "ADMIN" or role == "USER") and (not case["skill"] or domain in domains[case["skill"]])


def proposal(cases):
    # Every normal Agent request <=2 LLM, explicit Skill <=1, preflight denial 0.
    baseline = sum(0 if c.get("expectedHttp", 200) >= 400 else 1 if c["skill"] else 2 for c in cases)
    comparison = 5  # Only failed B cases, same captured inputs, at most one synthesis each.
    calls = baseline + comparison
    inp, out, emb = calls * 32768, calls * 1600, 160000
    bound = inp * 2500 + out * 12000 + emb * 20
    return {"status": "PROPOSAL_ONLY_NOT_AUTHORIZED", "model": "gpt-5.6-terra",
            "embeddingModel": "text-embedding-3-small", "baselineLlmMaximum": baseline,
            "targetedComparisonLlmMaximum": comparison, "llmCalls": calls,
            "embeddingCalls": 80, "inputTokens": inp, "outputTokens": out,
            "embeddingTokens": emb, "inputNusdPerToken": 2500,
            "outputNusdPerToken": 12000, "embeddingNusdPerToken": 20,
            "worstCaseNusd": bound, "requestedCapNusd": 5700000000,
            "automaticRetries": 0, "existingPaidCallsAllowed": 0,
            "priorReservedNusdPreserved": 4777963380,
            "priorUnknownReservationNusdPreserved": 21330000,
            "bothApprovalCeilingsNusd": 4777963380 + 5700000000}


def retrieval_metrics(hits, atoms):
    """Ranked actual hits only; missing evidence groups reduce all-support recall.

    Caller must supply the selected <=5 chunks, not an oracle-ordered source scan.
    Match exact evidence needle, not only the right document filename.
    """
    if len(hits) > 5 or not atoms:
        raise ValueError("INVALID_RETRIEVAL_METRIC_INPUT")
    ranks = [i + 1 for i, h in enumerate(hits)
             if any(h["path"] == a["path"] and a["needle"] in h["content"] for a in atoms)]
    support = [any(h["path"] == a["path"] and a["needle"] in h["content"] for h in hits) for a in atoms]
    return {"hitAt5": int(bool(ranks)), "mrrAt5": 0 if not ranks else 1 / ranks[0],
            "supportRecallAt5": sum(support) / len(support),
            "allSupportAt5": all(support),
            "nonMatchingCandidateCount": sum(not any(h["path"] == a["path"] and a["needle"] in h["content"] for a in atoms) for h in hits),
            "nonMatchingIsNotHallucination": True}


def audit(root=ROOT):
    data = read_json(root / DATASET)
    lock = read_json(root / LOCK)
    digest = hashlib.sha256((root / DATASET).read_bytes()).hexdigest()
    if digest != lock["datasetSha256"]:
        raise ValueError("FROZEN_DATASET_CHANGED")
    previous = root / RESOURCE / "novel-30-v1.json"
    if hashlib.sha256(previous.read_bytes()).hexdigest() != data["predecessor"]["sha256"]:
        raise ValueError("FROZEN_PREDECESSOR_CHANGED")
    cases = data["cases"]
    if Counter(c["category"] for c in cases) != Counter({k: 5 for k in "ABCDEF"}):
        raise ValueError("INVALID_CATEGORY_COUNTS")
    if {c["id"] for c in cases} != {f"{k}{i}" for k in "ABCDEF" for i in range(1, 6)}:
        raise ValueError("INVALID_CASE_IDS")
    manifest = read_json(root / "docs/ai/ingest-manifest.json")
    if len(manifest["documents"]) != 9:
        raise ValueError("CORPUS_CHANGED")
    documents = {}
    for entry in manifest["documents"]:
        text = (root / "docs/ai-knowledge" / entry["path"]).read_text(encoding="utf-8").replace("\r\n", "\n").replace("\r", "\n")
        if hashlib.sha256(text.encode()).hexdigest() != entry["sha256"]:
            raise ValueError("APPROVAL_HASH_MISMATCH")
        role = re.search(r"^minimum_role: (USER|ADMIN)$", text, re.M).group(1)
        domain = re.search(r"^domain: ([a-z]+)$", text, re.M).group(1)
        documents[entry["path"]] = (text, role, domain)
    for atom in data["atoms"].values():
        if atom["needle"] not in documents[atom["path"]][0]:
            raise ValueError("MISSING_SOURCE_ANCHOR:" + atom["path"])
    old = historical_questions(root)
    old_hash = hashlib.sha256(json.dumps(old, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    if old_hash != lock["historySha256"]:
        raise ValueError("HISTORICAL_SET_CHANGED")
    domains = skill_domains(root)
    seen, rows = set(), []
    for c in cases:
        key = normalized(c["question"])
        if key in seen or any(key == normalized(h["question"]) for h in old):
            raise ValueError("DUPLICATE_QUESTION")
        seen.add(key)
        for field in ("facts", "forbidden", "uncertainty", "baseline", "novelty", "atoms"):
            if not c[field]:
                raise ValueError("MISSING_ORACLE_FIELD")
        for name in c["atoms"]:
            if not evidence_allowed(c, documents[data["atoms"][name]["path"]], domains):
                raise ValueError("EXPECTED_UNAUTHORIZED_SOURCE")
        if c["category"] == "B" and len({data["atoms"][a]["path"] for a in c["atoms"]}) < 2:
            raise ValueError("B_REQUIRES_MULTI_DOCUMENT_SUPPORT")
        nearest = max(old, key=lambda h: SequenceMatcher(None, key, normalized(h["question"])).ratio())
        rows.append({"id": c["id"], "sourceEvidenceAvailable": True,
                     "nearestOldQuestion": nearest["question"], "nearestFamily": nearest["family"],
                     "lexicalSimilarity": round(SequenceMatcher(None, key, normalized(nearest["question"])).ratio(), 4),
                     "manualNoveltyBasis": c["novelty"],
                     "rankedRetrieval": "NOT_RUN", "answerQuality": "NOT_RUN", "jwtHttp": "NOT_RUN"})
    return {"status": "UNPAID_DESIGN_AUDIT_PASS_NOT_LIVE_QUALITY_PASS",
            "datasetSha256": digest, "historicalQuestions": len(old), "historySha256": old_hash,
            "cases": rows, "categories": dict(Counter(c["category"] for c in cases)),
            "corpusDocuments": len(json.loads((root/"docs/ai/ingest-manifest.json").read_text(encoding="utf-8"))["documents"]), "sourceAnchors": len(data["atoms"]),
            "providerCalls": 0, "budgetProposal": proposal(cases)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = audit()
    if args.output:
        # New evidence only; cannot overwrite old runs or a preexisting report.
        with args.output.open("x", encoding="utf-8") as stream:
            json.dump(result, stream, ensure_ascii=False, indent=2)
    print(json.dumps({k: v for k, v in result.items() if k != "cases"}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
