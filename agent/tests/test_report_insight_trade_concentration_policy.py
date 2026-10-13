"""New trading observation forms keep clause, subject and advice boundaries."""

import pytest
from test_report_insight_assessment import request
from test_report_insight_investment_quote_scope import AUDIENCE, REDUCE_FIELDS, full_synthesis
from test_report_insight_v4_pipeline import V4Provider, generate, stages

from app.llm import report_insight_service as service
from app.llm.report_insight_investment_policy import has_investment_advice

RAW = (
    "생산라인 가동 중단이 현재 계속된다. 외국인은 10억원을 순매도했다. "
    "삼성전자에 외국인 매도가 집중됐다."
)
OBSERVATIONS = (
    "외국인 순매도(10억원)는 수급을 점검할 관찰 정보다.",
    "삼성전자 중심의 매도 집중은 수급을 점검할 관찰 정보다.",
)


def field_errors(value, raw=RAW):
    source = request(text=raw, audiences=(AUDIENCE,))
    claims, evidence = service._source_context(source)
    return service._assessment_field_errors(
        value, ["101:0"], evidence, claims, source, audience=AUDIENCE
    )


@pytest.mark.parametrize(
    "value",
    [
        *OBSERVATIONS,
        "매도가 삼성전자에 집중되어 수급을 점검한다.",
        "삼성전자에 외국인 매도가 집중됐다.",
        "외국인 순매도(10억원) 기록은 수급을 점검할 관찰 정보다.",
    ],
)
def test_new_observation_forms_pass_policy_and_full_field_validation(value):
    assert not has_investment_advice(value, RAW)
    assert field_errors(value) == []


@pytest.mark.parametrize("trade", ["매수", "매도"])
def test_concentration_direction_and_net_currency_forms_have_symmetric_support(trade):
    raw = f"삼성전자에 외국인 {trade}가 집중됐다. 외국인은 10억원을 순{trade}했다."
    assert not has_investment_advice(f"삼성전자 중심의 {trade} 집중은 관찰 정보다.", raw)
    assert not has_investment_advice(f"외국인 순{trade}(10억원)는 관찰 정보다.", raw)
    opposite = "매도" if trade == "매수" else "매수"
    assert has_investment_advice(f"삼성전자 중심의 {opposite} 집중은 관찰 정보다.", raw)


@pytest.mark.parametrize(
    "value",
    [
        "삼성전자 매도 집중이 좋다.",
        "삼성전자 매도 집중은 유리하다.",
        "삼성전자 매도 집중 필요",
        "삼성전자 매도 집중 전략",
        "삼성전자에 매도가 집중되어야 한다.",
        "삼성전자에 매도가 집중되어 야만 한다.",
        "삼성전자 매도에 집중하면 유리하다.",
        "삼성전자 매도에 집중하는 것이 좋다.",
        "외국인 순매도(10억원)는 필요하다.",
        "외국인 순매도(10억원)를 권한다.",
        "외국인 순매도(전략)는 관찰 정보다.",
        "외국인 순매도(10억원 매수)는 관찰 정보다.",
        "외국인 순매도(10억원)는 실행하자.",
        "매도가 10만원이다.",
        "삼성전자 매도 집중은 관찰 정보다. 매수한다.",
        "삼성전자 매도 집중은 관찰 정보다. 목표가를 10만원으로 제시한다.",
        "원문: 「삼성전자 매도에 집중해야 한다.」",
    ],
)
def test_new_observation_forms_never_exempt_recommendations_or_unknown_context(value):
    assert has_investment_advice(value, RAW)
    assert any("report_expression_policy" in error.error_kinds for error in field_errors(value))


@pytest.mark.parametrize("company", ["현대차", "AMD", "SK하이닉스"])
@pytest.mark.parametrize(
    "form", ["{company}에 매도가 집중됐다.", "{company} 중심의 매도 집중은 관찰 정보다."]
)
def test_concentration_cannot_borrow_company_from_a_different_source_clause(company, form):
    raw = f"삼성전자에 외국인 매도가 집중됐다. {company}의 실적이 늘었다."
    value = form.format(company=company)
    assert has_investment_advice(value, raw)
    assert any(
        "report_expression_policy" in error.error_kinds for error in field_errors(value, raw)
    )


@pytest.mark.parametrize(
    "raw,value",
    [
        ("기관은 10억원을 순매도했다.", "외국인 순매도(10억원)는 관찰 정보다."),
        (
            "삼성전자는 10억원을 순매도했다. AMD의 실적이 늘었다.",
            "AMD 순매도(10억원)는 관찰 정보다.",
        ),
        (
            "코스닥에서 외국인은 10억원을 순매도했다.",
            "코스피에서 외국인 순매도(10억원)는 관찰 정보다.",
        ),
        ("삼성전자 매도는 줄고 현대차에 매도가 집중됐다.", "삼성전자에 매도가 집중됐다."),
        ("삼성전자에 외국인 순매도가 집중됐다.", "삼성전자에 외국인 매도가 집중됐다."),
        ("삼성전자에 외국인 매도가 집중될 전망이다.", "삼성전자에 외국인 매도가 집중됐다."),
        ("삼성전자에 외국인 매도가 집중되어 있지 않다.", "삼성전자에 외국인 매도가 집중됐다."),
        ("삼성전자 매도 집중이 필요하다.", "삼성전자 매도 집중은 관찰 정보다."),
        ("", "삼성전자 매도 집중은 관찰 정보다."),
    ],
)
def test_new_allowances_require_the_matching_source_event(raw, value):
    assert has_investment_advice(value, raw)


@pytest.mark.parametrize(
    "value",
    ["외국인 순매도(11억원)는 관찰 정보다.", "TSMC 순매도(10억원)는 관찰 정보다."],
)
def test_new_observation_grammar_keeps_company_and_numeric_guards(value):
    assert any("report_evidence_insufficient" in error.error_kinds for error in field_errors(value))


@pytest.mark.parametrize("observation", OBSERVATIONS)
def test_native_map_review_and_final_projection_accept_observation_without_source_changes(
    observation,
):
    source = request(ids=(101, 102), text=RAW, audiences=(AUDIENCE,))
    before = source.model_dump_json()

    def wire(stage, occurrence, data, value):
        if stage.startswith(("MAP", "REVIEW")):
            for record in value["assessments"][AUDIENCE].values():
                record["reason"] = observation
        return value

    provider = V4Provider(source, wire_hook=wire)
    result = generate(provider, source)
    assert stages(provider) == ["MAP-001", "REVIEW-001", "REDUCE-001"]
    assert observation in result.insights[0].assessments[0].reason
    assert result.meta.input_tokens == 33 and result.meta.output_tokens == 21
    assert source.model_dump_json() == before


@pytest.mark.parametrize("group,field", REDUCE_FIELDS)
def test_reduce_and_final_validation_accept_each_grounded_prose_field(group, field):
    source = request(ids=(101, 102), text=RAW, audiences=(AUDIENCE,))
    observation = OBSERVATIONS[1]

    def hook(stage, occurrence, data, value):
        value = full_synthesis(stage, occurrence, data, value)
        if stage == "REDUCE-001":
            insight = value["insights"][0]
            (insight if group is None else insight[group][0])[field] = observation
        return value

    provider = V4Provider(source, hook=hook)
    result = generate(provider, source)
    assert stages(provider) == ["MAP-001", "REVIEW-001", "REDUCE-001"]
    public = result.insights[0].model_dump(by_alias=True)
    assert (public if group is None else public[group][0])[field] == observation


@pytest.mark.parametrize("stage", ["MAP-001", "REVIEW-001"])
def test_mixed_advice_repairs_once_and_preserves_decisions_and_other_finding(stage):
    source = request(ids=(101, 102), text=RAW, audiences=(AUDIENCE,))

    def wire(current, occurrence, data, value):
        if current.startswith(("MAP", "REVIEW")):
            for record in value["assessments"][AUDIENCE].values():
                record["reason"] = OBSERVATIONS[1]
            if current == stage and occurrence == 1:
                value["assessments"][AUDIENCE]["finding101"]["reason"] += " 매수해야 한다."
        return value

    provider = V4Provider(source, wire_hook=wire)
    result = generate(provider, source)
    expected = ["MAP-001", "REVIEW-001", "REDUCE-001"]
    expected.insert(expected.index(stage), stage)
    assert stages(provider) == expected
    index = expected.index(stage)
    initial = provider.wire_payloads[index]["assessments"][AUDIENCE]
    repaired = provider.wire_payloads[index + 1]["assessments"][AUDIENCE]
    assert set(repaired) == {"finding101"}
    assert repaired["finding101"]["decision"] == initial["finding101"]["decision"]
    assert OBSERVATIONS[1] in result.insights[0].assessments[1].reason
    assert result.meta.input_tokens == 44 and result.meta.output_tokens == 28
