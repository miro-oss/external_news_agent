from copy import deepcopy

import pytest
from pydantic import ValidationError

from app.eval.report_insight_corpus import load_corpus, review_candidates, review_case


def candidate_for(case, *, refs=None, urgency=None):
    refs = refs if refs is not None else case.annotations.required_synthesis_claim_ids
    return {
        "insights": [
            {
                "audience": case.request.audiences[0],
                "headline": "원문 근거와 실행 조건을 확인해야 한다.",
                "overview": [
                    {
                        "text": "원문 근거의 범위와 실행 조건을 확인해야 한다.",
                        "assumption": "원문이 같은 제품과 인증 범위를 다루는 경우",
                        "basisClaimIds": refs,
                    }
                ]
                if refs
                else [],
                "assessments": [
                    {
                        "findingId": finding.id,
                        "reason": "원문에 명시된 범위에 따라 공정·운영 판단을 확인해야 한다.",
                        "basisClaimIds": [finding.claims[0].id],
                        "axes": {
                            "directness": 1,
                            "impact": None,
                            "urgency": urgency,
                            "novelty": None,
                        },
                    }
                    for finding in case.request.findings
                ],
                "implications": [],
                "watchItems": [],
            }
        ]
    }


def test_versioned_corpus_covers_six_scenarios_and_all_four_audiences():
    corpus = load_corpus()
    assert corpus.version == "report-insight-cases.ko.v1"
    assert corpus.synthetic is True and corpus.quality_measured is False
    assert len(corpus.cases) == 24
    assert len({case.scenario for case in corpus.cases}) == 6
    for scenario in {case.scenario for case in corpus.cases}:
        assert {
            case.request.audiences[0] for case in corpus.cases if case.scenario == scenario
        } == {"CHIP_MAKER", "EQUIPMENT_MAKER", "MARKET_INVESTOR", "IT_INFRA"}


def test_saved_candidate_contract_and_annotation_coverage_are_distinct_from_quality():
    corpus = load_corpus()
    case = corpus.cases[0]
    report = review_candidates(corpus, {case.case_id: candidate_for(case)})
    assert report["reviewedCount"] == 1 and len(report["missingCaseIds"]) == 23
    assert report["qualityMeasured"] is False and report["requiresHumanReview"] is True
    assert report["results"][0]["contractPassed"] is True
    assert report["results"][0]["annotationFlags"] == []


def test_contract_passing_candidate_can_still_drop_a_contradictory_source():
    case = next(
        case
        for case in load_corpus().cases
        if case.scenario == "conflicting-certification" and case.request.audiences == ["CHIP_MAKER"]
    )
    result = review_case(
        case, candidate_for(case, refs=case.annotations.required_synthesis_claim_ids[:1])
    )
    assert result["contractPassed"] is True
    assert result["annotationFlags"] == [
        "missing_synthesis_claim:" + case.annotations.required_synthesis_claim_ids[1]
    ]


def test_past_deadline_has_independent_urgency_label_and_no_real_model_claim():
    case = next(
        case
        for case in load_corpus().cases
        if case.scenario == "past-deadline-and-forecast"
        and case.request.audiences == ["CHIP_MAKER"]
    )
    result = review_case(case, candidate_for(case, urgency=3))
    # The independent past-deadline label now also has a runtime release guard.
    # A rejected contract is not scored as an accepted model-quality candidate.
    assert result["contractPassed"] is False
    assert result["annotationFlags"] == []
    assert result["qualityMeasured"] is False
    assert case.annotations.not_urgent_finding_ids == [case.request.findings[0].id]


def test_wrong_sources_and_unknown_case_ids_fail_before_editorial_scoring():
    corpus = load_corpus()
    case = corpus.cases[0]
    result = review_case(case, candidate_for(case, refs=["999:0"]))
    assert result["contractPassed"] is False
    with pytest.raises(ValueError):
        review_candidates(corpus, {"unknown-case": candidate_for(case)})
    invalid = deepcopy(case.model_dump())
    invalid["annotations"]["required_synthesis_claim_ids"] = ["999:0"]
    with pytest.raises(ValidationError):
        type(case).model_validate(invalid)


def test_independent_importance_labels_detect_boosted_unrelated_and_downgraded_core_issue():
    case = next(
        case
        for case in load_corpus().cases
        if case.scenario == "production-halt-and-unrelated-ad"
        and case.request.audiences == ["CHIP_MAKER"]
    )
    candidate = candidate_for(case)
    core, unrelated = candidate["insights"][0]["assessments"]
    core["axes"].update(directness=1, impact=1, urgency=1)
    unrelated["axes"].update(directness=3, impact=3, urgency=3)
    result = review_case(case, candidate)
    assert result["contractPassed"] is True
    assert "importance_calibration:1051:expected_high:actual_low" in result["annotationFlags"]
    assert "importance_calibration:1052:expected_low:actual_high" in result["annotationFlags"]
    core["axes"].update(directness=3, impact=2, urgency=3)
    unrelated["axes"].update(directness=0, impact=0, urgency=None)
    assert review_case(case, candidate)["annotationFlags"] == []


def test_importance_labels_are_audience_specific_and_bound_to_stored_findings():
    corpus = load_corpus()
    halted = [case for case in corpus.cases if case.scenario == "production-halt-and-unrelated-ad"]
    assert all(case.annotations.expected_importance_by_finding[1052] == "low" for case in halted)
    assert [
        case.request.audiences[0]
        for case in halted
        if case.annotations.expected_importance_by_finding.get(1051) == "high"
    ] == ["CHIP_MAKER"]
    invalid = deepcopy(halted[0].model_dump())
    invalid["annotations"]["expected_importance_by_finding"] = {999: "high"}
    with pytest.raises(ValidationError):
        type(halted[0]).model_validate(invalid)
