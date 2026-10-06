"""Keep source-only reference drafts auditable without claiming measured quality."""

import hashlib
import itertools
import json
from pathlib import Path
from urllib.parse import urlsplit

import pytest

from app.llm.report_insight_assessment import RELATION_SCORES, ROLE_WORK, source_span_choices
from app.llm.report_insight_guard import report_reference_date
from app.llm.report_insight_service import _eligible_report_request, importance_grade
from app.schemas.report_insight import ReportImportanceAxes, ReportInsightRequest

FIXTURE = (
    Path(__file__).resolve().parents[1]
    / "app/eval/golden/report-insight.real-source-labels.v1.json"
)
AUDIENCES = {"CHIP_MAKER", "EQUIPMENT_MAKER", "MARKET_INVESTOR", "IT_INFRA"}


@pytest.fixture(scope="module")
def source_labels():
    return json.loads(FIXTURE.read_text(encoding="utf-8"))


def _assert_basis(basis, eligible):
    spans = {
        claim_id: claim_spans
        for finding in eligible.findings
        for claim_id, claim_spans in source_span_choices(finding).items()
    }
    ids = [(item["claimId"], item["sourceSpanId"]) for item in basis]
    assert len(ids) == len(set(ids))
    for item in basis:
        assert spans[item["claimId"]][item["sourceSpanId"]] == item["quote"]
        assert item["quote"].strip()


def test_real_reference_labels_disclose_ai_draft_and_unmeasured_quality(source_labels):
    assert source_labels["version"] == "report-insight.real-source-labels.v1"
    provenance = source_labels["provenance"]
    assert provenance["labelStatus"] == "expert_draft"
    assert provenance["authorKind"] == "AI_SOURCE_ONLY_REVIEW"
    assert provenance["humanVerified"] is False
    assert provenance["qualityMeasured"] is False
    assert provenance["candidateOutputsReadBeforeLabeling"] is False
    assert provenance["sourceKind"] == "EXISTING_REPORT_REQUEST_SNAPSHOT"
    assert len(provenance["limitations"]) >= 3
    assert provenance["importancePolicy"].startswith("#291: directness=0 derives low")


def test_split_is_whole_report_and_shared_event_family_is_disclosed(source_labels):
    policy = source_labels["splitPolicy"]
    assert policy["unit"] == "reportId"
    assert policy["reportDisjoint"] is True
    assert policy["eventFamilyDisjoint"] is False
    assert set(policy["developmentReportIds"]).isdisjoint(policy["holdoutReportIds"])
    splits = {
        report["request"]["report"]["id"]: report["split"] for report in source_labels["reports"]
    }
    assert splits == {1867: "development", 1866: "holdout"}
    families = [
        {label["eventFamily"] for label in report["assessmentLabels"]}
        for report in source_labels["reports"]
    ]
    assert "micron-fy2026-earnings" in families[0] & families[1]
    assert "Evaluation only" in policy["holdoutUse"]


def test_sanitized_requests_preserve_source_evidence_fingerprint(source_labels):
    for report in source_labels["reports"]:
        request = ReportInsightRequest.model_validate(report["request"])
        assert set(request.audiences) == AUDIENCES
        assert request.plan == "FREE"
        assert request.idempotency_key.startswith("eval:real-source-labels:")
        assert report_reference_date(request).isoformat() == report["labelReferenceDate"]
        assert all(
            urlsplit(f.canonical_url).hostname == "example.invalid" for f in request.findings
        )
        raw = report["request"]
        evidence = {
            "report": raw["report"],
            "findings": [
                {key: value for key, value in finding.items() if key != "canonicalUrl"}
                for finding in raw["findings"]
            ],
        }
        fingerprint = hashlib.sha256(
            json.dumps(evidence, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
        ).hexdigest()
        assert fingerprint == report["sourceEvidenceSha256"]
        assert len(report["originalRequestSha256"]) == 64


def test_all_findings_have_four_source_bound_role_assessments(source_labels):
    total = 0
    for report in source_labels["reports"]:
        original = ReportInsightRequest.model_validate(report["request"])
        eligible = _eligible_report_request(original)
        originals = {f.id: {c.id for c in f.claims} for f in original.findings}
        usable = {f.id: {c.id for c in f.claims} for f in eligible.findings}
        labels = report["assessmentLabels"]
        label_ids = [label["findingId"] for label in labels]
        assert len(label_ids) == len(set(label_ids))
        assert set(label_ids) == set(originals)
        for label in labels:
            fid = label["findingId"]
            assert label["sourceSummary"].strip()
            assert set(label["usableClaimIds"]) == usable[fid]
            assert set(label["excludedClaimIds"]) == originals[fid] - usable[fid]
            assert set(label["audiences"]) == AUDIENCES
            _assert_basis(label["basis"], eligible)
            assert {basis["claimId"] for basis in label["basis"]} == usable[fid]
            for audience, expectation in label["audiences"].items():
                assert expectation["rationale"].strip()
                assert expectation["labelConfidence"] in {"strong", "interpretive"}
                assert set(expectation["works"]) <= set(ROLE_WORK[audience])
                assert set(expectation["directness"]) == {
                    RELATION_SCORES[relation] for relation in expectation["relations"]
                }
                for axis in ("directness", "impact", "urgency"):
                    assert expectation[axis]
                    assert all(
                        value is None or type(value) is int and 0 <= value <= 3
                        for value in expectation[axis]
                    )
                possible_grades = {
                    importance_grade(
                        ReportImportanceAxes(
                            directness=directness, impact=impact, urgency=urgency, novelty=None
                        )
                    )
                    for directness, impact, urgency in itertools.product(
                        expectation["directness"], expectation["impact"], expectation["urgency"]
                    )
                }
                assert set(expectation["importance"]) == possible_grades
                if not usable[fid]:
                    assert expectation["relations"] == ["UNDETERMINED"]
                    assert expectation["works"] == []
                    assert all(
                        expectation[axis] == [None] for axis in ("directness", "impact", "urgency")
                    )
                total += 1
    assert total == 208


def test_priority_and_watch_claims_are_usable_and_roles_are_not_collapsed(source_labels):
    case_ids = []
    for report in source_labels["reports"]:
        eligible = _eligible_report_request(ReportInsightRequest.model_validate(report["request"]))
        claims = {c.id for f in eligible.findings for c in f.claims}
        all_labels = report["audienceSynthesisLabels"]
        assert {label["audience"] for label in all_labels} == AUDIENCES
        assert len(all_labels) == 4
        for label in all_labels:
            case_ids.append(label["caseId"])
            assert label["caseId"] == f"real-{eligible.report.id}-{label['audience'].lower()}"
            assert label["allowedConnections"]
            assert all(
                isinstance(value, str) and value.strip() for value in label["allowedConnections"]
            )
            assert label["evaluationNote"].strip()
            issues = [group["issueId"] for group in label["priorityClaimGroups"]]
            assert len(issues) == len(set(issues))
            assert any(group["priority"] == "core" for group in label["priorityClaimGroups"])
            for group in label["priorityClaimGroups"]:
                assert group["priority"] in {"core", "supporting"}
                assert group["claimIds"] and set(group["claimIds"]) <= claims
                assert len(group["claimIds"]) == len(set(group["claimIds"]))
                assert group["rationale"].strip()
            assert label["watchSignals"]
            for watch in label["watchSignals"]:
                assert watch["basisClaimIds"] and set(watch["basisClaimIds"]) <= claims
                assert all(watch[field].strip() for field in ("indicator", "trigger", "rationale"))
        for constraint in report["factConstraints"]:
            _assert_basis(constraint["basis"], eligible)
            assert constraint["criticalFact"].strip()
            assert constraint["timeOrUncertainty"].strip()
            assert constraint["forbiddenPromotions"]
    assert len(case_ids) == len(set(case_ids)) == 8


def test_stored_claim_conflicts_remain_raw_and_excluded_from_evaluation_refs(source_labels):
    reports = {r["request"]["report"]["id"]: r for r in source_labels["reports"]}
    mint = next(f for f in reports[1867]["request"]["findings"] if f["id"] == 7756)
    assert "2025년" in next(c["text"] for c in mint["claims"] if c["id"] == "7756:1")
    assert any("2031년" in sentence["text"] for sentence in mint["sentences"])
    mint_label = next(
        label for label in reports[1867]["assessmentLabels"] if label["findingId"] == 7756
    )
    assert mint_label["excludedClaimIds"] == ["7756:1"]
    packaging = next(
        label for label in reports[1866]["assessmentLabels"] if label["findingId"] == 7678
    )
    assert packaging["excludedClaimIds"] == ["7678:2"] and packaging["basis"] == []
    # Known relationships survive absent process/size/deadline information.
    memory = next(
        label for label in reports[1867]["assessmentLabels"] if label["findingId"] == 7741
    )
    assert memory["audiences"]["CHIP_MAKER"]["directness"] == [3]
    assert "CUSTOMER_REQUIREMENTS" in memory["audiences"]["CHIP_MAKER"]["works"]
    mor = next(label for label in reports[1866]["assessmentLabels"] if label["findingId"] == 7679)
    assert set(mor["audiences"]["IT_INFRA"]["directness"]) == {1, None}
    assert mor["audiences"]["IT_INFRA"]["labelConfidence"] == "interpretive"
    assert 3 not in mor["audiences"]["IT_INFRA"]["urgency"]
