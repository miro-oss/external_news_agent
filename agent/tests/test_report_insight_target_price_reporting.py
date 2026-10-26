"""Price-adjustment reporting stays source scoped and never authorizes advice."""

from copy import deepcopy
from dataclasses import replace

import pytest
from test_report_insight_assessment import request
from test_report_insight_investment_quote_scope import (
    AUDIENCE,
    REDUCE_FIELDS,
    full_synthesis,
    response,
)
from test_report_insight_market_observation_policy import generate
from test_report_insight_reduce_partial_repair import repair_jobs
from test_report_insight_v4_pipeline import V4Provider, stages

from app.llm import report_insight_service as service

SOURCE = "제조사는 생산라인 가동 중단이 계속되며 증권사가 목표주가를 상향 조정했다고 밝혔다."
REPORT = "증권사의 목표주가 조정 보도는 기업가치 전망을 살펴볼 관찰 정보다."
CONDITION = "증권사의 목표주가 조정 보도가 기업가치 판단의 입력으로 적용되는 경우"


def source_request():
    return request(ids=(101, 102), audiences=(AUDIENCE,), text=SOURCE)


@pytest.mark.parametrize("field", ["reason", "condition"])
def test_map_review_and_final_validation_allow_grounded_price_adjustment_reporting(field):
    source = source_request()
    before = source.model_dump_json()

    def wire(stage, occurrence, data, value):
        if stage.startswith(("MAP", "REVIEW")):
            record = value["assessments"][AUDIENCE]["finding101"]
            target = record if field == "reason" else record["decision"]["connection"]
            target[field] = REPORT if field == "reason" else CONDITION
            assert record["sourceQuotes"][field] is None
        return value

    provider = V4Provider(source, relation="CONDITIONAL", wire_hook=wire)
    result = generate(provider, source)
    assert stages(provider) == ["MAP-001", "REVIEW-001", "REDUCE-001"]
    assert (REPORT if field == "reason" else CONDITION) in result.insights[0].assessments[0].reason
    assert result.meta.input_tokens == 33 and result.meta.output_tokens == 21
    assert source.model_dump_json() == before


@pytest.mark.parametrize("group,field", REDUCE_FIELDS)
def test_reduce_and_final_validation_allow_reporting_in_each_field(group, field):
    source = source_request()

    def hook(stage, occurrence, data, value):
        value = full_synthesis(stage, occurrence, data, value)
        if stage == "REDUCE-001":
            insight = value["insights"][0]
            record = insight if group is None else insight[group][0]
            record[field] = REPORT
        return value

    provider = V4Provider(source, hook=hook)
    result = generate(provider, source)
    assert stages(provider) == ["MAP-001", "REVIEW-001", "REDUCE-001"]
    public = result.insights[0].model_dump(by_alias=True)
    assert (public if group is None else public[group][0])[field] == REPORT


@pytest.mark.parametrize("field", ["reason", "condition"])
@pytest.mark.parametrize("stage", ["MAP-001", "REVIEW-001"])
def test_report_with_advice_gets_one_local_repair_without_changing_other_records(stage, field):
    source = source_request()

    def wire(current, occurrence, data, value):
        if current == stage:
            record = value["assessments"][AUDIENCE]["finding101"]
            target = record if field == "reason" else record["decision"]["connection"]
            target[field] = REPORT if field == "reason" else CONDITION
            if occurrence == 1:
                target[field] += " 비중을 늘리세요."
        return value

    provider = V4Provider(source, relation="CONDITIONAL", wire_hook=wire)
    result = generate(provider, source)
    expected = ["MAP-001", "REVIEW-001", "REDUCE-001"]
    expected.insert(expected.index(stage), stage)
    assert stages(provider) == expected
    repair_index = expected.index(stage) + 1
    initial, fixed = provider.wire_payloads[repair_index - 1 : repair_index + 1]
    assert set(fixed["assessments"][AUDIENCE]) == {"finding101"}
    old = initial["assessments"][AUDIENCE]["finding101"]
    new = fixed["assessments"][AUDIENCE]["finding101"]
    if field == "reason":
        assert new["decision"] == old["decision"]
    else:
        decision = deepcopy(old["decision"])
        decision["connection"]["condition"] = CONDITION
        assert new["decision"] == decision
        assert new["reason"] == old["reason"]
    assert {item.finding_id for item in result.insights[0].assessments} == {101, 102}
    assert result.meta.input_tokens == 44 and result.meta.output_tokens == 28


@pytest.mark.parametrize("group,field", REDUCE_FIELDS)
def test_report_cannot_exempt_trade_advice_in_any_reduce_field(group, field):
    source = source_request()

    def hook(stage, occurrence, data, value):
        value = full_synthesis(stage, occurrence, data, value)
        if stage == "REDUCE-001":
            insight = value["insights"][0]
            record = insight if group is None else insight[group][0]
            record[field] = REPORT + (" 지금 매수해야 한다." if occurrence == 1 else "")
        return value

    provider = V4Provider(source, hook=hook)
    generate(provider, source)
    assert stages(provider) == ["MAP-001", "REVIEW-001", "REDUCE-001", "REDUCE-001"]
    jobs = repair_jobs(provider.calls[-1]["prompt"])
    assert len(jobs) == 1
    assert jobs[0]["group"] == (group or "headline")
    assert any(x["errorKind"] == "report_synthesis_invalid" for x in jobs[0]["diagnostics"])


@pytest.mark.parametrize("field", ["reason", "condition"])
def test_other_finding_source_does_not_authorize_a_price_adjustment_report(field):
    source = source_request()
    plain = "제조사는 생산라인 가동 중단이 현재 계속된다고 밝혔다."
    source.findings[1].claims[0].text = plain
    source.findings[1].sentences[0].text = plain
    claims, evidence = service._source_context(source)
    value = REPORT if field == "reason" else CONDITION
    errors = service._assessment_field_errors(
        value,
        ["102:0"],
        evidence,
        claims,
        source,
        audience=AUDIENCE,
        conditional=field == "condition",
    )
    assert any("report_expression_policy" in error.error_kinds for error in errors)


@pytest.mark.parametrize(
    "value",
    [
        REPORT + " 목표주가를 10만원으로 제시한다.",
        REPORT + " 지금 매도해야 한다.",
        "비중을 늘리세요. " + REPORT,
        "원문: 「목표주가를 10만원으로 제시한다.」 해석: " + REPORT,
    ],
)
def test_reporting_does_not_exempt_supported_target_prices_or_fake_quotes(value):
    source = source_request()
    source.findings[0].claims[0].text += " 증권사는 목표주가를 10만원으로 제시했다."
    source.findings[0].sentences[0].text = source.findings[0].claims[0].text
    claims, evidence = service._source_context(source)
    errors = service._assessment_field_errors(
        value, ["101:0"], evidence, claims, source, audience=AUDIENCE
    )
    assert any("report_expression_policy" in error.error_kinds for error in errors)


def test_claim_summary_cannot_authorize_price_reporting_missing_from_linked_sentences():
    source = source_request()
    source.findings[0].sentences[0].text = "제조사는 생산라인 가동 중단이 계속된다고 밝혔다."
    claims, evidence = service._source_context(source)
    errors = service._assessment_field_errors(
        REPORT, ["101:0"], evidence, claims, source, audience=AUDIENCE
    )
    assert any("report_expression_policy" in error.error_kinds for error in errors)


@pytest.mark.parametrize("group,field", REDUCE_FIELDS)
def test_each_reduce_field_uses_only_its_own_selected_source(group, field):
    source = source_request()
    plain = "제조사는 생산라인 가동 중단이 현재 계속된다고 밝혔다."
    source.findings[1].claims[0].text = plain
    source.findings[1].sentences[0].text = plain

    def hook(stage, occurrence, data, value):
        return full_synthesis(stage, occurrence, data, value)

    provider = V4Provider(source, hook=hook)
    generate(provider, source)
    draft = service.validate_draft(response(provider.wire_payloads[0]), source)
    raw = response(provider.wire_payloads[-1])
    native = service.ReportInsightStructuredReduceOutput.model_validate_json(raw.text)
    item = native.insights[0]
    record = (
        item if group is None else getattr(item, {"watchItems": "watch_items"}.get(group, group))[0]
    )
    setattr(record, field if field != "falsifiedBy" else "falsified_by", REPORT)
    if group is not None:
        record.basis_claim_ids = ["102:0"]
    candidate = replace(raw, text=native.model_dump_json(by_alias=True))
    if group is None:
        for items in (item.overview, item.implications, item.watch_items):
            for other in items:
                other.basis_claim_ids = ["102:0"]
        candidate = replace(raw, text=native.model_dump_json(by_alias=True))
    with pytest.raises(ValueError, match="목표주가 조정 사실·방향") as caught:
        service._validated_v4_reduce_output(
            candidate,
            source,
            draft.mapped,
            {AUDIENCE: ["102:0"] if group is None else ["101:0", "102:0"]},
            native_assessments=draft.evidence,
            template_wire=True,
        )
    expected_field = "headline" if group is None else f"{group}[0].{field}"
    assert any(
        issue.rule == "report_target_price_observation_source"
        and issue.field == expected_field
        and issue.claim_ids == ("102:0",)
        for issue in caught.value.validation_issues
    )
