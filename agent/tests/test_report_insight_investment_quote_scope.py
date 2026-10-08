"""Investment policy examines interpretation, never authenticated source quotes."""

import json

import pytest
from test_report_insight_assessment import request
from test_report_insight_reduce_partial_repair import repair_jobs, synthesis
from test_report_insight_v4_pipeline import V4Provider, generate, stages

from app.llm import report_insight_service as service
from app.llm.base import ProviderResponse, ProviderUsage
from app.llm.report_insight_assessment import validate_draft

AUDIENCE = "MARKET_INVESTOR"
REDUCE_FIELDS = [
    (None, "headline"),
    ("overview", "text"),
    ("overview", "assumption"),
    ("implications", "text"),
    ("implications", "mechanism"),
    ("implications", "assumption"),
    ("implications", "falsifiedBy"),
    ("watchItems", "topic"),
    ("watchItems", "indicator"),
    ("watchItems", "trigger"),
]


def market_source(term="순매수"):
    return request(
        ids=(101, 102),
        audiences=(AUDIENCE,),
        text=f"제조사는 생산라인 전체의 가동 중단이 현재 계속되며 기관 {term}가 이어진다고 밝혔다.",
    )


def full_synthesis(stage, occurrence, data, value):
    value = synthesis(stage, occurrence, data, value)
    if stage == "REDUCE-001":
        value["insights"][0]["implications"] = [
            {
                "text": "생산 제약의 지속 여부에 따라 검증 준비의 순서가 달라질 수 있다.",
                "mechanism": "생산 제약이 준비 일정에 연결될 수 있다.",
                "assumption": "같은 생산 제약이 검증 준비에 연결되는 경우",
                "falsifiedBy": "같은 생산라인이 정상 가동을 재개하면 제약의 지속 해석이 약해진다.",
                "basisClaimIds": ["101:0"],
            }
        ]
    return value


def response(wire):
    return ProviderResponse(
        json.dumps(wire, ensure_ascii=False), "openai", "offline", ProviderUsage()
    )


@pytest.mark.parametrize("term", ["순매수", "순매도"])
@pytest.mark.parametrize("field", ["reason", "condition"])
def test_map_and_review_allow_source_market_flow_without_treating_it_as_advice(term, field):
    source = market_source(term)

    def wire(stage, occurrence, data, value):
        if stage.startswith(("MAP", "REVIEW")):
            record = value["assessments"][AUDIENCE]["finding101"]
            record["sourceQuotes"][field] = data["findings"][0]["factTextSlots"][0]["slotId"]
        return value

    provider = V4Provider(source, relation="CONDITIONAL", wire_hook=wire)
    result = generate(provider, source)
    assert stages(provider) == ["MAP-001", "REVIEW-001", "REDUCE-001"]
    assert (
        f"원문: 「{source.findings[0].sentences[0].text}」"
        in result.insights[0].assessments[0].reason
    )


@pytest.mark.parametrize("term", ["순매수", "순매도"])
@pytest.mark.parametrize("group,field", REDUCE_FIELDS)
def test_all_reduce_fields_allow_authenticated_market_flow_quotes(term, group, field):
    source = market_source(term)

    def hook(stage, occurrence, data, value):
        value = full_synthesis(stage, occurrence, data, value)
        if stage == "REDUCE-001":
            insight = value["insights"][0]
            record = insight if group is None else insight[group][0]
            fields = [field] if group is None else [key for key in record if key != "basisClaimIds"]
            record["sourceQuotes"] = dict.fromkeys(fields)
            record["sourceQuotes"][field] = data["factTextSlots"][AUDIENCE][0]["slotId"]
        return value

    provider = V4Provider(source, hook=hook)
    result = generate(provider, source)
    assert stages(provider) == ["MAP-001", "REVIEW-001", "REDUCE-001"]
    insight = result.insights[0].model_dump(by_alias=True)
    record = insight if group is None else insight[group][0]
    assert f"원문: 「{source.findings[0].sentences[0].text}」" in record[field]


@pytest.mark.parametrize("field", ["reason", "condition"])
@pytest.mark.parametrize("position", ["before", "after"])
def test_map_advice_is_localized_and_repaired_without_replacing_other_finding(
    monkeypatch, field, position
):
    source = market_source()
    failures = []
    original_repair = service.ReportInsightService._repair_call

    def capture(self, prompt, schema, raw, error, validate):
        failures.append(error)
        return original_repair(self, prompt, schema, raw, error, validate)

    monkeypatch.setattr(service.ReportInsightService, "_repair_call", capture)

    def wire(stage, occurrence, data, value):
        if stage == "MAP-001" and occurrence == 1:
            record = value["assessments"][AUDIENCE]["finding101"]
            target = record if field == "reason" else record["decision"]["connection"]
            original = target[field]
            target[field] = (
                "매수해야 한다. " + original
                if position == "before"
                else original + " 매수해야 한다."
            )
            record["sourceQuotes"][field] = data["findings"][0]["factTextSlots"][0]["slotId"]
        return value

    provider = V4Provider(source, relation="CONDITIONAL", wire_hook=wire)
    result = generate(provider, source)
    assert stages(provider) == ["MAP-001", "MAP-001", "REVIEW-001", "REDUCE-001"]
    path = "reason" if field == "reason" else "decision.connection.condition"
    assert any(
        issue.field == f"assessments[101].{path}" and issue.error_kind == "report_expression_policy"
        for issue in failures[0].validation_issues
    )
    repair = provider.calls[1]
    assert "투자 자문 표현" in repair["prompt"]
    records = repair["response_schema"]["properties"]["assessments"]["properties"][AUDIENCE]
    assert set(records["properties"]) == {"finding101"}
    assert {item.finding_id for item in result.insights[0].assessments} == {101, 102}


@pytest.mark.parametrize("group,field", REDUCE_FIELDS)
def test_reduce_advice_remains_localized_when_the_same_field_quotes_source(group, field):
    source = market_source()

    def hook(stage, occurrence, data, value):
        value = full_synthesis(stage, occurrence, data, value)
        if stage == "REDUCE-001" and occurrence == 1:
            insight = value["insights"][0]
            record = insight if group is None else insight[group][0]
            record[field] = "매도해야 한다."
            fields = [field] if group is None else [key for key in record if key != "basisClaimIds"]
            record["sourceQuotes"] = dict.fromkeys(fields)
            record["sourceQuotes"][field] = data["factTextSlots"][AUDIENCE][0]["slotId"]
        return value

    provider = V4Provider(source, hook=hook)
    generate(provider, source)
    assert stages(provider) == ["MAP-001", "REVIEW-001", "REDUCE-001", "REDUCE-001"]
    repair = provider.calls[-1]
    assert repair["response_schema"]["title"] == "ReportInsightReduceRepair"
    jobs = repair_jobs(repair["prompt"])
    expected = "headline" if group is None else f"{group}[0].{field}"
    assert any(
        diagnostic["field"] == expected and diagnostic["errorKind"] == "report_synthesis_invalid"
        for job in jobs
        for diagnostic in job["diagnostics"]
    )


@pytest.mark.parametrize("position", ["before", "after"])
def test_quote_shaped_or_modified_public_prose_cannot_hide_investment_advice(position):
    source = market_source()
    provider = V4Provider(source)
    generate(provider, source)
    draft = validate_draft(response(provider.wire_payloads[0]), source)
    assessment = draft.mapped.insights[0].assessments[0]
    quote = f"원문: 「{source.findings[0].sentences[0].text}」 해석: 생산 제약을 확인한다."
    altered = assessment.model_copy(
        update={
            "reason": "매수해야 한다. " + quote
            if position == "before"
            else quote + " 매수해야 한다."
        }
    )
    claims, evidence = service._source_context(source)
    for native in (None, draft.evidence[AUDIENCE][101]):
        errors = service._assessment_prose_errors(
            altered, native, assessment.basis_claim_ids, evidence, claims, source, audience=AUDIENCE
        )
        assert any("투자 자문 표현" in str(error) for _, error in errors)
        assert all(field == "reason" for field, _ in errors)
