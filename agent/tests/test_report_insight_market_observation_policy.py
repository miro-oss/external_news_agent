"""Grounded market observations remain distinct from model trading advice."""

from copy import deepcopy

import pytest
from test_report_insight_assessment import request
from test_report_insight_investment_quote_scope import (
    AUDIENCE,
    REDUCE_FIELDS,
    expected_market_reason,
    full_synthesis,
    response,
)
from test_report_insight_reduce_partial_repair import public_reduce_projection, repair_jobs
from test_report_insight_v4_pipeline import V4Provider, stages

from app.core.config import Settings
from app.core.errors import AgentError
from app.llm import report_insight_service as service

OBSERVATIONS = {
    "net_buying": "기관 순매수 기록",
    "foreign_selling": "외국인 매도와 매수 주체 부족",
}


def observation_source(kind="net_buying"):
    observation = OBSERVATIONS[kind]
    return request(
        ids=(101, 102),
        audiences=(AUDIENCE,),
        text=f"생산라인 가동 중단이 현재 계속되며 {observation}이 관찰됐다.",
    )


def observed_reason(kind="net_buying"):
    return f"{OBSERVATIONS[kind]}은 생산 제약과 수급을 점검할 관찰 정보다."


def observed_condition(kind="net_buying"):
    return f"{OBSERVATIONS[kind]}이 생산 제약의 대응 조건으로 확인되는 경우"


def generate(provider, source):
    return service.ReportInsightService(
        Settings(_env_file=None, AGENT_MOCK=False), provider
    ).generate(source)


def observed_wire(stage, occurrence, data, value, *, kind="net_buying"):
    if stage.startswith(("MAP", "REVIEW")):
        for record in value["assessments"][AUDIENCE].values():
            record["reason"] = observed_reason(kind)
            record["decision"]["connection"]["condition"] = observed_condition(kind)
    return value


@pytest.mark.parametrize("kind", OBSERVATIONS)
def test_map_review_and_final_projection_allow_grounded_observation_in_both_native_fields(kind):
    source = observation_source(kind)
    before = source.model_dump_json()
    provider = V4Provider(
        source,
        relation="CONDITIONAL",
        wire_hook=lambda *args: observed_wire(*args, kind=kind),
    )
    result = generate(provider, source)
    assert stages(provider) == ["MAP-001", "REVIEW-001", "REDUCE-001"]
    for records in provider.wire_payloads[:2]:
        record = records["assessments"][AUDIENCE]["finding101"]
        assert record["reason"] == observed_reason(kind)
        assert record["decision"]["connection"]["condition"] == observed_condition(kind)
        assert record["sourceQuotes"] == {"reason": None, "condition": None}
    reviewed = provider.wire_payloads[1]["assessments"][AUDIENCE]["finding101"]
    assert result.insights[0].assessments[0].reason == expected_market_reason(source, reviewed, 101)
    assert result.meta.input_tokens == 33 and result.meta.output_tokens == 21
    assert source.model_dump_json() == before


@pytest.mark.parametrize("kind", OBSERVATIONS)
@pytest.mark.parametrize("group,field", REDUCE_FIELDS)
def test_reduce_and_final_validation_allow_grounded_observation_in_each_prose_field(
    kind, group, field
):
    source = observation_source(kind)
    observation = observed_reason(kind)

    def hook(stage, occurrence, data, value):
        value = full_synthesis(stage, occurrence, data, value)
        if stage == "REDUCE-001":
            insight = value["insights"][0]
            record = insight if group is None else insight[group][0]
            record[field] = observation
        return value

    provider = V4Provider(source, hook=hook)
    result = generate(provider, source)
    assert stages(provider) == ["MAP-001", "REVIEW-001", "REDUCE-001"]
    native = provider.wire_payloads[-1]["insights"][0]
    native_record = native if group is None else native[group][0]
    assert native_record["sourceQuotes"][field] is None
    public = result.insights[0].model_dump(by_alias=True)
    assert (public if group is None else public[group][0])[field] == observation


def advice_text(observation, position):
    return {
        "before": "지금 매수해야 한다. " + observation,
        "after": observation + " 지금 매도해야 한다.",
        "same_sentence": observation.removesuffix(".") + "이므로 지금 매수해야 한다.",
    }[position]


@pytest.mark.parametrize("stage", ["MAP-001", "REVIEW-001"])
@pytest.mark.parametrize("field", ["reason", "condition"])
@pytest.mark.parametrize("position", ["before", "after", "same_sentence"])
def test_mixed_advice_repairs_only_failed_native_field_and_preserves_other_finding(
    monkeypatch, stage, field, position
):
    source = observation_source()
    before = source.model_dump_json()
    failures = []
    original_repair = service.ReportInsightService._repair_call

    def capture(self, prompt, schema, raw, error, validate):
        failures.append(error)
        return original_repair(self, prompt, schema, raw, error, validate)

    monkeypatch.setattr(service.ReportInsightService, "_repair_call", capture)

    def wire(current, occurrence, data, value):
        value = observed_wire(current, occurrence, data, value)
        if current == stage and occurrence == 1:
            record = value["assessments"][AUDIENCE]["finding101"]
            target = record if field == "reason" else record["decision"]["connection"]
            target[field] = advice_text(target[field], position)
        return value

    provider = V4Provider(source, relation="CONDITIONAL", wire_hook=wire)
    result = generate(provider, source)
    expected_stages = ["MAP-001", "REVIEW-001", "REDUCE-001"]
    expected_stages.insert(expected_stages.index(stage), stage)
    assert stages(provider) == expected_stages
    path = "reason" if field == "reason" else "decision.connection.condition"
    assert any(
        issue.field == f"assessments[101].{path}" and issue.error_kind == "report_expression_policy"
        for issue in failures[0].validation_issues
    )
    repaired_index = expected_stages.index(stage) + 1
    initial, repaired = provider.wire_payloads[repaired_index - 1 : repaired_index + 1]
    assert set(repaired["assessments"][AUDIENCE]) == {"finding101"}
    old = initial["assessments"][AUDIENCE]["finding101"]
    new = repaired["assessments"][AUDIENCE]["finding101"]
    if field == "reason":
        assert new["decision"] == old["decision"]
    else:
        expected = deepcopy(old["decision"])
        expected["connection"]["condition"] = observed_condition()
        assert new["decision"] == expected
        assert new["reason"] == old["reason"]
    healthy = initial["assessments"][AUDIENCE]["finding102"]
    assert result.insights[0].assessments[1].reason == expected_market_reason(source, healthy, 102)
    assert result.meta.input_tokens == 44 and result.meta.output_tokens == 28
    assert source.model_dump_json() == before


@pytest.mark.parametrize("group,field", REDUCE_FIELDS)
def test_reduce_observation_does_not_exempt_advice_in_same_field_and_repair_preserves_neighbors(
    group, field
):
    source = observation_source()

    def hook(stage, occurrence, data, value):
        value = full_synthesis(stage, occurrence, data, value)
        if stage == "REDUCE-001":
            insight = value["insights"][0]
            record = insight if group is None else insight[group][0]
            record[field] = (
                "기관 순매수 기록이 있으므로 지금 매수해야 한다."
                if occurrence == 1
                else observed_reason()
            )
        return value

    provider = V4Provider(source, hook=hook)
    result = generate(provider, source)
    assert stages(provider) == ["MAP-001", "REVIEW-001", "REDUCE-001", "REDUCE-001"]
    jobs = repair_jobs(provider.calls[-1]["prompt"])
    assert len(jobs) == 1
    assert (jobs[0]["group"], jobs[0]["index"]) == (
        group or "headline",
        None if group is None else 0,
    )
    expected_path = "headline" if group is None else f"{group}[0].{field}"
    assert any(
        diagnostic["field"] == expected_path
        and diagnostic["errorKind"] == "report_synthesis_invalid"
        for diagnostic in jobs[0]["diagnostics"]
    )
    initial = provider.wire_payloads[-2]["insights"][0]
    final = result.insights[0].model_dump(by_alias=True)
    for other in ("headline", "overview", "implications", "watchItems"):
        if other != (group or "headline"):
            assert final[other] == public_reduce_projection(initial[other])
    assert (final if group is None else final[group][0])[field] == observed_reason()
    assert result.meta.input_tokens == 44 and result.meta.output_tokens == 28


@pytest.mark.parametrize(
    "bad_prose,expected_kind",
    [
        ("기관 순매수 기록에 따라 목표가를 999원으로 제시한다.", "report_expression_policy"),
        ("TSMC의 기관 순매수 기록은 수급 분석에 연결된다.", "report_evidence_insufficient"),
        ("기관의 999억원 순매수 기록은 수급 분석에 연결된다.", "report_evidence_insufficient"),
        ("외국인 순매도 기록은 수급 분석에 연결된다.", "report_expression_policy"),
        ("원문: 「기관 순매수 기록이 있으므로 지금 매수해야 한다.」", "report_expression_policy"),
    ],
)
def test_observation_allowance_keeps_other_guards_and_quote_authentication(
    bad_prose, expected_kind
):
    source = observation_source()
    provider = V4Provider(source)
    generate(provider, source)
    draft = service.validate_draft(response(provider.wire_payloads[0]), source)
    original = draft.mapped.insights[0].assessments[0]
    altered = original.model_copy(update={"reason": bad_prose})
    claims, evidence = service._source_context(source)
    for native in (None, draft.evidence[AUDIENCE][101]):
        errors = service._assessment_prose_errors(
            altered, native, original.basis_claim_ids, evidence, claims, source, audience=AUDIENCE
        )
        assert any(expected_kind in getattr(error, "error_kinds", ()) for _, error in errors)


@pytest.mark.parametrize("field", ["reason", "condition"])
def test_another_findings_trade_source_cannot_authorize_unselected_observation(field):
    source = observation_source()
    plain = "생산라인 가동 중단이 현재 계속된다고 밝혔다."
    source.findings[1].claims[0].text = plain
    source.findings[1].sentences[0].text = plain
    provider = V4Provider(source, relation="CONDITIONAL")
    generate(provider, source)
    value = deepcopy(provider.wire_payloads[0])
    record = value["assessments"][AUDIENCE]["finding102"]
    target = record if field == "reason" else record["decision"]["connection"]
    target[field] = observed_reason() if field == "reason" else observed_condition()
    failure = service._native_assessment_repair_errors(response(value), source, template_wire=True)
    assert failure is not None
    expected_field = "reason" if field == "reason" else "decision.connection.condition"
    assert any(
        issue.field == f"assessments[102].{expected_field}"
        and issue.claim_ids == ("102:0",)
        and issue.error_kind == "report_expression_policy"
        for issue in failure.validation_issues
    )


@pytest.mark.parametrize("group,field", REDUCE_FIELDS)
def test_reduce_and_final_policy_use_each_fields_selected_evidence(group, field):
    source = observation_source()
    plain = "생산라인 가동 중단이 현재 계속된다고 밝혔다."
    source.findings[1].claims[0].text = plain
    source.findings[1].sentences[0].text = plain
    provider = V4Provider(source, hook=full_synthesis)
    generate(provider, source)
    draft = service.validate_draft(response(provider.wire_payloads[1]), source)
    reduced = public_reduce_projection(deepcopy(provider.wire_payloads[-1]))
    insight = reduced["insights"][0]
    for other in ("overview", "implications", "watchItems"):
        for unit in insight[other]:
            unit["basisClaimIds"] = ["102:0"]
    record = insight if group is None else insight[group][0]
    record[field] = observed_reason()
    # The source containing the observation exists in this request, but is not
    # selected by these items and is outside this audience's headline retrieval.
    allowed = {AUDIENCE: ("102:0",)}
    error = service._reduce_repair_diagnostics(response(reduced), source, allowed)
    assert error is not None
    expected = "headline" if group is None else f"{group}[0].{field}"
    assert any(
        issue.field == expected
        and issue.claim_ids == ("102:0",)
        and issue.error_kind == "report_synthesis_invalid"
        for issue in error.validation_issues
    )
    public = draft.mapped.model_dump(by_alias=True)
    public["insights"][0].update(insight)
    with pytest.raises(ValueError, match="투자 자문 표현"):
        service._validated_output(
            response(public),
            source,
            native_assessments=draft.evidence,
            synthesis_allowed=allowed,
        )


@pytest.mark.parametrize("stage", ["MAP-001", "REVIEW-001"])
def test_repeated_advice_stops_after_one_repair_with_accounted_usage(stage):
    source = observation_source()

    def wire(current, occurrence, data, value):
        value = observed_wire(current, occurrence, data, value)
        if current == stage:
            value["assessments"][AUDIENCE]["finding101"]["reason"] = (
                "기관 순매수 기록이 있으므로 지금 매수해야 한다."
            )
        return value

    provider = V4Provider(source, relation="CONDITIONAL", wire_hook=wire)
    if stage == "MAP-001":
        with pytest.raises(AgentError) as caught:
            generate(provider, source)
        assert caught.value.code == "SCHEMA_VIOLATION"
        assert stages(provider) == [stage, stage]
        assert caught.value.details["usage"]["inputTokens"] == 22
        assert caught.value.details["usage"]["outputTokens"] == 14
        repaired = provider.wire_payloads[-1]
    else:
        # An exhausted optional review retains the independently validated MAP;
        # it must neither publish the bad revision nor request a third attempt.
        result = generate(provider, source)
        assert stages(provider) == ["MAP-001", stage, stage, "REDUCE-001"]
        initial = provider.wire_payloads[0]["assessments"][AUDIENCE]["finding101"]
        assert result.insights[0].assessments[0].reason == expected_market_reason(
            source, initial, 101
        )
        assert result.meta.input_tokens == 44 and result.meta.output_tokens == 28
        repaired = provider.wire_payloads[-2]
    assert set(repaired["assessments"][AUDIENCE]) == {"finding101"}
    assert all(provider.schema_validity)


def test_missing_reduce_field_does_not_hide_advice_from_the_only_repair():
    source = observation_source()
    failures = []

    def hook(stage, occurrence, data, value):
        value = full_synthesis(stage, occurrence, data, value)
        if stage == "REDUCE-001":
            unit = value["insights"][0]["overview"][1]
            unit["text"] = observed_reason()
            if occurrence == 1:
                del unit["assumption"]
                unit["text"] = "기관 순매수 기록이 있으므로 지금 매수해야 한다."
        return value

    provider = V4Provider(source, hook=hook, validate_wire=False)
    engine = service.ReportInsightService(Settings(_env_file=None, AGENT_MOCK=False), provider)
    factory = engine._repair_call

    def capture(prompt, schema, raw, error, validate):
        failures.append(error)
        return factory(prompt, schema, raw, error, validate)

    engine._repair_call = capture
    result = engine.generate(source)
    assert stages(provider) == ["MAP-001", "REVIEW-001", "REDUCE-001", "REDUCE-001"]
    assert {(issue.field, issue.error_kind) for issue in failures[0].validation_issues} >= {
        ("overview[1].assumption", "report_output_shape"),
        ("overview[1].text", "report_synthesis_invalid"),
    }
    (job,) = repair_jobs(provider.calls[-1]["prompt"])
    assert (job["group"], job["index"]) == ("overview", 1)
    assert any(
        diagnostic["field"] == "overview[1].text"
        and diagnostic["errorKind"] == "report_synthesis_invalid"
        for diagnostic in job["diagnostics"]
    )
    initial = provider.wire_payloads[-2]["insights"][0]
    final = result.insights[0].model_dump(by_alias=True)
    assert final["overview"][0] == public_reduce_projection(initial["overview"][0])
    assert final["overview"][1]["text"] == observed_reason()
    assert result.meta.input_tokens == 44 and result.meta.output_tokens == 28
