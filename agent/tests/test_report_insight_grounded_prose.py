"""Grounded identifiers survive MAP/REDUCE while source errors remain rejected."""

import json
from copy import deepcopy

import pytest
from test_report_insight_assessment import payload, request, response

from app.llm.base import ProviderResponse, ProviderUsage
from app.llm.report_insight_assessment import validate_template_draft
from app.llm.report_insight_fact_rendering import render_reduce_templates
from app.llm.report_insight_service import _validated_map_output, _validated_reduce_output
from app.schemas.report_insight import ReportInsightReduceOutput

SOURCE = "삼성전자는 HBM4 생산라인 전체의 가동 중단이 현재 계속된다고 밝혔다."


def _provider_response(value):
    return ProviderResponse(
        text=json.dumps(value, ensure_ascii=False),
        provider="mock",
        model="offline-grounded-prose",
        usage=ProviderUsage(),
    )


def _checked_map(source, value):
    native = validate_template_draft(response(value, source), source)
    mapped = _validated_map_output(
        _provider_response(native.mapped.model_dump(by_alias=True)),
        source,
        native_assessments=native.evidence,
    )
    return native, mapped


def _reduce_payload():
    return {
        "insights": [
            {
                "audience": "CHIP_MAKER",
                "headline": "생산 준비의 제약과 확인 조건을 점검한다.",
                "overview": [
                    {
                        "text": "생산 제약의 지속 여부와 검증 준비 조건을 확인해야 한다.",
                        "basisClaimIds": ["101:0"],
                        "assumption": "같은 생산 제약이 검증 준비에 연결되는 경우",
                    }
                ],
                "implications": [],
                "watchItems": [],
            }
        ]
    }


def _checked_reduce(source, value):
    native, mapped = _checked_map(source, payload(source))
    allowed = {"CHIP_MAKER": ["101:0"]}
    rendered, issues = render_reduce_templates(value, source, allowed)
    assert issues == (), "These fixtures have valid template syntax and source handles."
    return _validated_reduce_output(
        _provider_response(rendered),
        source,
        mapped,
        allowed,
        native_assessments=native.evidence,
        native_synthesis=ReportInsightReduceOutput.model_validate(rendered),
    )


@pytest.mark.parametrize(
    ("field", "prose"),
    [
        ("reason", "삼성전자의 공정 검증 준비 영향을 확인한다."),
        ("reason", "HBM4 공정 검증 준비 영향을 확인한다."),
        (
            "condition",
            "HBM4 공급 여력에 따라 공정 검증 준비 일정을 조정해야 하는 경우",
        ),
        ("condition", "삼성전자의 생산 제약이 공정 검증 일정에 영향을 주는 경우"),
        ("condition", "가동 중단이 해소되는 경우 공정 검증 준비 일정을 조정한다."),
        ("reason", "생산라인의 정상 가동 여부와 공정 검증 영향을 확인한다."),
    ],
)
def test_map_accepts_source_identifiers_in_work_interpretations_and_conditions(field, prose):
    source = request(text=SOURCE)
    value = payload(source, relation="CONDITIONAL" if field == "condition" else "DIRECT")
    value["assessments"]["CHIP_MAKER"]["finding101"][field] = prose
    before = deepcopy((source.model_dump(), value))

    native, mapped = _checked_map(source, value)

    assert prose in mapped.insights[0].assessments[0].reason
    assert getattr(native.evidence["CHIP_MAKER"][101], field) == prose
    assert (source.model_dump(), value) == before


@pytest.mark.parametrize("stage", ["MAP", "REDUCE"])
def test_grounded_factual_quantity_is_not_rejected_merely_for_containing_digits(stage):
    source = request(text="제조사는 생산라인 2개의 가동 중단이 현재 계속된다고 밝혔다.")
    prose = "생산라인 2개의 가동 중단에 따른 공정 검증 영향을 확인한다."
    if stage == "MAP":
        value = payload(source)
        value["assessments"]["CHIP_MAKER"]["finding101"]["reason"] = prose
        _, mapped = _checked_map(source, value)
        assert mapped.insights[0].assessments[0].reason == prose
    else:
        value = _reduce_payload()
        value["insights"][0]["overview"][0]["text"] = prose
        result = _checked_reduce(source, value)
        assert result.insights[0].overview[0].text == prose


@pytest.mark.parametrize(
    "prose",
    [
        "기업 Acme의 공정 검증 준비 영향을 확인한다.",
        "TSMC의 공정 검증 준비 영향을 확인한다.",
        "생산량은 99개다. 공정 검증 영향을 확인한다.",
    ],
)
def test_map_still_rejects_unsupported_companies_and_factual_quantities(prose):
    source = request(text=SOURCE)
    value = payload(source)
    _checked_map(source, value)
    value["assessments"]["CHIP_MAKER"]["finding101"]["reason"] = prose

    with pytest.raises(ValueError):
        _checked_map(source, value)


@pytest.mark.parametrize("defect", ["altered_quote", "foreign_finding", "unknown_claim"])
def test_map_preserves_exact_quote_and_finding_scoped_evidence_guards(defect):
    source = request(ids=(101, 102), text=SOURCE)
    value = payload(source)
    _checked_map(source, value)
    basis = value["assessments"]["CHIP_MAKER"]["finding101"]["relationBasis"]
    if defect == "altered_quote":
        basis["quote"] = SOURCE.replace("중단", "재개")
    elif defect == "foreign_finding":
        basis["claimId"] = "102:0"
    else:
        basis["claimId"] = "101:99"

    with pytest.raises(ValueError):
        _checked_map(source, value)


@pytest.mark.parametrize(
    "prose",
    [
        "가동 중단은 해소됐다. 공정 검증 영향을 확인한다.",
        "생산라인은 정상 가동 상태다. 공정 검증 영향을 확인한다.",
    ],
)
def test_map_rejects_asserted_recovery_while_cited_source_says_halt_continues(prose):
    source = request()
    value = payload(source)
    _checked_map(source, value)
    value["assessments"]["CHIP_MAKER"]["finding101"]["reason"] = prose

    with pytest.raises(ValueError):
        _checked_map(source, value)


@pytest.mark.parametrize(
    ("field", "prose"),
    [
        ("text", "삼성전자의 공정 검증 준비 영향을 확인한다."),
        ("text", "HBM4 공정 검증 준비 영향을 확인한다."),
        ("assumption", "HBM4 생산 제약이 검증 준비에 영향을 주는 경우"),
    ],
)
def test_reduce_accepts_grounded_identifiers_through_rendering_and_public_validation(field, prose):
    source = request(text=SOURCE)
    value = _reduce_payload()
    value["insights"][0]["overview"][0][field] = prose
    before = deepcopy((source.model_dump(), value))

    result = _checked_reduce(source, value)

    assert getattr(result.insights[0].overview[0], field) == prose
    assert (source.model_dump(), value) == before


@pytest.mark.parametrize(
    "prose",
    [
        "기업 Acme의 공정 검증 준비 영향을 확인한다.",
        "생산량은 99개다. 공정 검증 영향을 확인한다.",
        "가동 중단은 해소됐다. 공정 검증 영향을 확인한다.",
        "생산라인은 정상 가동 상태다. 공정 검증 영향을 확인한다.",
    ],
)
def test_reduce_rejects_unsupported_facts_and_opposite_source_state(prose):
    source = request()
    value = _reduce_payload()
    _checked_reduce(source, value)
    value["insights"][0]["overview"][0]["text"] = prose

    with pytest.raises(ValueError):
        _checked_reduce(source, value)


def test_reduce_cannot_use_a_claim_outside_its_retrieved_evidence_scope():
    source = request(ids=(101, 102))
    value = _reduce_payload()
    _checked_reduce(source, value)
    value["insights"][0]["overview"][0]["basisClaimIds"] = ["102:0"]

    with pytest.raises(ValueError, match="검색 근거"):
        _checked_reduce(source, value)
