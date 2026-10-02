"""Independent report facts retain their own numeric owners, without a live model."""

import json
from types import SimpleNamespace

import pytest
from test_report_insight import request_body

from app.core.evidence import factual_mismatches
from app.llm.base import ProviderResponse, ProviderUsage
from app.llm.report_insight_service import _validate_prose, _validated_map_output
from app.schemas.report_insight import ReportInsightRequest

MASK_SOURCE = (
    "12인치 포토마스크 도입으로 생산성 향상과 비용 절감이 기대된다.\n"
    "TSMC intends to use ASML's High NA technology in high-volume manufacturing "
    "for advanced nodes starting in 2030."
)
PARALLEL = (
    "원문은 12인치 포토마스크 전환에 따른 생산성 개선 기대와 "
    "ASML의 High NA 기술 활용 계획을 각각 언급한다."
)


def validate(value, source=MASK_SOURCE):
    _validate_prose(
        [value],
        ["501:0"],
        {"501:0": source},
        {"501:0": SimpleNamespace(text=source)},
    )


@pytest.mark.parametrize("subject", ["원문은", "기사 내용이", "자료는"])
def test_explicit_parallel_summary_does_not_assign_mask_size_to_technology_vendor(subject):
    value = PARALLEL.replace("원문은", subject)
    # The article guard remains conservative; the exception is report-only.
    assert any("연결이 다른 숫자" in error for error in factual_mismatches(value, MASK_SOURCE))
    validate(value)


@pytest.mark.parametrize(
    ("value", "source"),
    [
        (
            "보고서는 2.5배 처리량 증가 기대와 아마존의 서버 전환 계획을 각각 설명했다.",
            "처리량이 2.5배 늘어날 것으로 기대된다. Amazon plans to transition servers in 2029.",
        ),
        (
            "자료는 64 GB 메모리 도입 전망과 마이크로소프트의 서비스 전환 계획을 각각 제시한다.",
            "64 GB 메모리 도입 전망이 제시됐다. Microsoft plans a service transition in 2028.",
        ),
    ],
)
def test_parallel_fact_scope_is_not_specific_to_one_company_or_quantity(value, source):
    assert any("연결이 다른 숫자" in error for error in factual_mismatches(value, source))
    validate(value, source)


@pytest.mark.parametrize(
    "value",
    [
        # Possessive ownership must remain on the first fact.
        "원문은 ASML의 12인치 포토마스크 도입 기대와 "
        "ASML의 High NA 기술 활용 계획을 각각 언급한다.",
        # A company subject can govern the whole coordination.
        "ASML은 12인치 포토마스크 전환 기대와 High NA 기술 활용 계획을 각각 언급한다.",
        # The second fact has no explicit owner; it may inherit the first.
        "원문은 ASML의 12인치 포토마스크 전환 기대와 High NA 기술 활용 계획을 각각 언급한다.",
        # Quotation and attribution are not independent event subjects.
        '원문은 "12인치 포토마스크 전환 기대"와 ASML의 High NA 기술 활용 계획을 각각 언급한다.',
        "원문은 ASML이 밝힌 12인치 포토마스크 전환 기대와 "
        "ASML의 High NA 기술 활용 계획을 각각 언급한다.",
        # An ordinary conjunction alone does not establish independent ownership.
        PARALLEL.replace("각각 ", ""),
    ],
)
def test_ambiguous_or_explicit_shared_company_scope_keeps_contextual_rejection(value):
    with pytest.raises(ValueError, match="연결이 다른 숫자"):
        validate(value)


@pytest.mark.parametrize(
    "reference",
    ["같은 크기", "동일한", "해당 크기", "그 크기", "상기", "전자의", "이러한"],
)
def test_second_fact_cannot_inherit_first_fact_size_through_a_back_reference(reference):
    value = (
        "원문은 12인치 포토마스크 전환 계획과 "
        f"ASML의 {reference} 포토마스크 도입 계획을 각각 언급한다."
    )
    with pytest.raises(ValueError, match="연결이 다른 숫자"):
        validate(value)


@pytest.mark.parametrize(
    ("value", "source", "error"),
    [
        (PARALLEL.replace("12인치", "24인치"), MASK_SOURCE, "숫자"),
        (PARALLEL.replace("ASML", "엔비디아"), MASK_SOURCE, "기업명"),
        (PARALLEL.replace("기술 활용 계획", "2031년 기술 활용 계획"), MASK_SOURCE, "숫자"),
        (PARALLEL.replace("기술 활용 계획", "claimId=501:0 기술 활용 계획"), MASK_SOURCE, "숫자"),
        (
            PARALLEL.replace("12인치", "2030년 12인치"),
            "12인치 포토마스크 전환이 기대된다. ASML은 2030년 장비 10개 도입을 계획했다.",
            "연결이 다른 숫자",
        ),
        (
            PARALLEL.replace("12인치", "내년 12인치"),
            "12인치 포토마스크 전환이 기대된다. ASML은 내년 장비 10개 도입을 계획했다.",
            "연결이 다른 숫자",
        ),
        (
            PARALLEL.replace("12인치", "2030-10-02 12인치"),
            "12인치 포토마스크 전환이 기대된다. ASML은 2030-10-02에 장비 10개 도입을 계획했다.",
            "연결이 다른 숫자",
        ),
    ],
)
def test_parallel_wording_cannot_erase_missing_facts_or_shared_date_context(value, source, error):
    with pytest.raises(ValueError, match=error):
        validate(value, source)


def test_second_independent_actor_cannot_borrow_first_fact_quantity():
    source = (
        "삼성전자는 장비 12개 도입을 계획했다. ASML은 장비 10개 도입을 계획했다. "
        "삼성전자와 ASML은 공동 장비 20개 도입을 계획했다."
    )
    value = "원문은 삼성전자의 장비 12개 도입 계획과 ASML의 장비 12개 도입 계획을 각각 언급한다."
    with pytest.raises(ValueError, match="연결이 다른 숫자"):
        validate(value, source)


def test_actual_failed_repair_reason_is_not_approved_by_the_bounded_parallel_exception():
    value = (
        "기사 내용이 12인치 포토마스크 도입과 ASML의 High NA 기술 활용 계획을 언급하였으나, "
        "구체적 영향 대상과 범위, 시점이 명확하지 않으며, "
        "원문에 명시된 기업명과 기술 내용이 사실과 일치하지 않음."
    )
    with pytest.raises(ValueError, match="연결이 다른 숫자"):
        validate(value)


def test_public_assessment_uses_only_its_selected_claims_for_parallel_fact_check():
    body = request_body()
    finding = body["findings"][0]
    texts = MASK_SOURCE.split("\n")
    finding["claims"] = [
        {
            "id": f"501:{index}",
            "text": text,
            "claimType": "FACT",
            "attributedTo": None,
            "evidenceSentenceIds": [index],
        }
        for index, text in enumerate(texts)
    ]
    finding["sentences"] = [{"index": index, "text": text} for index, text in enumerate(texts)]
    request = ReportInsightRequest.model_validate(body)
    assessment = {
        "findingId": 501,
        "reason": PARALLEL,
        "basisClaimIds": [],
        "axes": {"directness": None, "impact": None, "urgency": None, "novelty": None},
    }

    def response():
        return ProviderResponse(
            json.dumps({"insights": [{"audience": "CHIP_MAKER", "assessments": [assessment]}]}),
            "openai",
            "offline",
            ProviderUsage(),
        )

    # All unknown axes use the existing same-finding source fallback, not a new citation.
    result = _validated_map_output(response(), request)
    assert result.insights[0].assessments[0].basis_claim_ids == []
    assert result.insights[0].assessments[0].axes.impact is None
    assessment["basisClaimIds"] = ["501:0"]
    assessment["axes"]["directness"] = 3
    with pytest.raises(ValueError, match="기업명"):
        _validated_map_output(response(), request)
