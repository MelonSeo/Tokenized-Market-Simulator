import copy
import json
from pathlib import Path
import shutil
import tempfile
import unittest

import ai_novel_question_audit as audit


class NovelQuestionAuditTest(unittest.TestCase):
    def test_frozen_thirty_and_fifty_six_previous_questions(self):
        result = audit.audit()
        self.assertEqual(result["categories"], {k: 5 for k in "ABCDEF"})
        self.assertEqual(result["historicalQuestions"], 56)
        self.assertEqual(result["providerCalls"], 0)
        self.assertTrue(all(r["rankedRetrieval"] == r["answerQuality"] == r["jwtHttp"] == "NOT_RUN" for r in result["cases"]))

    def test_proposed_bounds_do_not_reauthorize_the_old_ledger(self):
        p = audit.audit()["budgetProposal"]
        self.assertEqual((p["baselineLlmMaximum"], p["targetedComparisonLlmMaximum"], p["llmCalls"]), (51, 5, 56))
        self.assertEqual(p["worstCaseNusd"], 5665920000)
        self.assertLess(p["worstCaseNusd"], p["requestedCapNusd"])
        self.assertEqual(p["priorUnknownReservationNusdPreserved"], 21330000)
        self.assertEqual(p["existingPaidCallsAllowed"], 0)
        self.assertEqual(p["automaticRetries"], 0)
        self.assertEqual(p["status"], "PROPOSAL_ONLY_NOT_AUTHORIZED")

    def test_hit_does_not_hide_missing_multihop_evidence(self):
        atoms = [{"path": "a.md", "needle": "first condition"}, {"path": "b.md", "needle": "second condition"}]
        hits = [{"path": "x.md", "content": "unrelated"}, {"path": "a.md", "content": "first condition"}]
        m = audit.retrieval_metrics(hits, atoms)
        self.assertEqual((m["hitAt5"], m["mrrAt5"], m["supportRecallAt5"], m["allSupportAt5"]), (1, .5, .5, False))
        self.assertEqual(m["nonMatchingCandidateCount"], 1)
        self.assertTrue(m["nonMatchingIsNotHallucination"])

    def test_correct_document_without_required_chunk_is_not_a_hit(self):
        m = audit.retrieval_metrics([{"path": "a.md", "content": "unrelated heading"}], [{"path": "a.md", "needle": "critical condition"}])
        self.assertEqual((m["hitAt5"], m["mrrAt5"], m["supportRecallAt5"]), (0, 0, 0))

    def test_complete_support_and_empty_results(self):
        atoms = [{"path": "a.md", "needle": "one"}, {"path": "b.md", "needle": "two"}]
        m = audit.retrieval_metrics([{"path": "a.md", "content": "one"}, {"path": "b.md", "content": "two"}], atoms)
        self.assertTrue(m["allSupportAt5"])
        self.assertEqual(m["supportRecallAt5"], 1)
        self.assertEqual(audit.retrieval_metrics([], atoms)["hitAt5"], 0)
        for hits, expected in (([{}] * 6, atoms), ([], [])):
            with self.assertRaises(ValueError):
                audit.retrieval_metrics(hits, expected)

    def test_normalization_catches_cosmetic_duplicates(self):
        self.assertEqual(audit.normalized("Ｑuote ID? 한 글!"), audit.normalized("quote_id 한 글"))

    def test_skill_domain_cannot_be_widened_by_an_expected_answer(self):
        case = {"actor": "USER", "skill": "signed-quote-diagnosis"}
        domains = audit.skill_domains(audit.ROOT)
        self.assertFalse(audit.evidence_allowed(case, ("policy", "USER", "security"), domains))
        self.assertTrue(audit.evidence_allowed(case, ("policy", "USER", "trading"), domains))
        self.assertFalse(audit.evidence_allowed(case, ("policy", "ADMIN", "trading"), domains))
        case["actor"] = "ADMIN"
        self.assertFalse(audit.evidence_allowed(case, ("policy", "ADMIN", "security"), domains))
        self.assertTrue(audit.evidence_allowed(case, ("policy", "ADMIN", "trading"), domains))

    def test_changed_questions_cannot_silently_change_frozen_expectations(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for rel in (audit.DATASET, audit.LOCK):
                (root / rel).parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(audit.ROOT / rel, root / rel)
            data = copy.deepcopy(audit.read_json(root / audit.DATASET))
            data["cases"][0]["facts"] = ["changed after seeing a response"]
            (root / audit.DATASET).write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "FROZEN_DATASET_CHANGED"):
                audit.audit(root)

    def test_duplicate_json_oracles_fail_closed(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "bad.json"
            path.write_text('{"expected":1,"expected":2}', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "DUPLICATE_JSON_KEY"):
                audit.read_json(path)


if __name__ == "__main__":
    unittest.main()
