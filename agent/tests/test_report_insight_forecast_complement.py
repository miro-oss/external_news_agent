"""A locally attached forecast is not an executed event or a field-wide exemption."""

import json
from copy import deepcopy
from dataclasses import replace

import pytest
from test_report_insight_assessment import response
from test_report_insight_connection_evidence_dependency import full_validate
from test_report_insight_reduce_partial_repair import repair_jobs
from test_report_insight_v4_pipeline import V4Provider, stages
from test_report_insight_year_unit_guidance import (
    AUDIENCE,
    SOURCE,
    forecast_case,
    run_pipeline,
)

from app.core.config import Settings
from app.core.errors import OutputValidationError
from app.llm import report_insight_service as service

# Synthetic values, not a replay or a rewritten copy of a user's saved output.
FORECAST = "엔비디아의 서버 CPU 매출이 2025년 70억달러에서 2028년 600억달러로 확대된다는 전망"
REASON = FORECAST + "은 성장 가정 검토와 연결된다."


@pytest.fixture(scope="module")
def full_reduce_context():
    source, provider, _, captured = run_pipeline("REDUCE-001")
    _, _, raw, _, validate = captured[0]
    value = json.loads(raw)
    for job in repair_jobs(provider.calls[-1]["prompt"]):
        assert job["group"] == "overview" and job["index"] == 0
        value["insights"][0]["overview"][0] = deepcopy(
            provider.wire_payloads[-1]["repairs"][job["key"]]
        )
    base = response(forecast_case()[1], source)
    validate(replace(base, text=json.dumps(value, ensure_ascii=False)))
    return source, base, value, validate


@pytest.mark.parametrize(
    "reason",
    [
        REASON,
        FORECAST + "에 따라 성장 가정의 적용 범위를 검토한다.",
        "시장 전망(" + FORECAST + ")은 성장 가정 검토와 연결된다.",
        REASON + " 실현 여부와 규모는 불확실하다.",
    ],
    ids=["attached-topic", "attached-basis", "parenthesized-complement", "uncertain-realization"],
)
def test_attached_nonpast_forecast_passes_full_native_and_reduce_without_rewriting(
    full_reduce_context, reason
):
    source, value = forecast_case()
    value["assessments"][AUDIENCE]["finding101"]["reason"] = reason
    raw = response(value, source)
    before = raw.text, source.model_dump_json(by_alias=True)
    draft = full_validate(raw, source)
    assert draft.evidence[AUDIENCE][101].reason == reason
    assert draft.evidence[AUDIENCE][101].relation_basis.quote == SOURCE
    assert before == (raw.text, source.model_dump_json(by_alias=True))

    source, base, original, validate = full_reduce_context
    value = deepcopy(original)
    value["insights"][0]["overview"][0]["text"] = reason
    raw = replace(base, text=json.dumps(value, ensure_ascii=False))
    before = raw.text, source.model_dump_json(by_alias=True)
    output = validate(raw)
    assert output.insights[0].overview[0].text == reason
    assert output.insights[0].overview[0].basis_claim_ids == ["101:0"]
    assert before == (raw.text, source.model_dump_json(by_alias=True))


@pytest.mark.parametrize(
    "reason,rule",
    [
        (FORECAST.replace("확대된다는 전망", "확대됐다."), "event_state"),
        (REASON.replace("확대된다는 전망", "확대됐다는 전망"), "event_state"),
        (REASON + " 엔비디아는 공장 건설을 완료했다.", "event_state"),
        ("엔비디아는 공장 건설을 완료했다. " + REASON, "event_state"),
        (FORECAST + "이며 다른 공장은 이미 완공됐다.", "event_state"),
        (FORECAST + "이 아니라 매출은 이미 확대됐다.", "event_state"),
        (FORECAST + "이 현실화됐다.", "event_state"),
        (FORECAST + "이 실현됐다.", "event_state"),
        (FORECAST + "이 사실로 확인됐다.", "event_state"),
        (FORECAST + "이 맞았다.", "event_state"),
        (FORECAST + "이 현실화 됐다.", "event_state"),
        (FORECAST + "이 적중했다.", "event_state"),
        (FORECAST + "이 맞아떨어졌다.", "event_state"),
        (FORECAST + "이다. 그 전망이 현실화됐다.", "event_state"),
        (FORECAST + "이다. 그 전망은 사실로 확인됐다.", "event_state"),
        ("그 전망이 현실화됐다. " + REASON, "event_state"),
        ("그 전망은 사실로 확인됐다. " + REASON, "event_state"),
        (FORECAST + "이론을 정립했다.", "event_state"),
        (REASON.replace("2028년", "2029년"), "unsupported_number"),
        (REASON.replace("600억달러", "900억달러"), "unsupported_number"),
        (REASON.replace("엔비디아", "삼성전자"), "company"),
        (REASON.replace("CPU", "HBM4"), "source_binding"),
        (REASON + " 지금 매수해야 한다.", None),
        (REASON + " 목표가를 10만원으로 제시한다.", None),
    ],
    ids=[
        "bare-assertion",
        "past-forecast-stays-conservative",
        "later-completion",
        "earlier-completion",
        "other-subject-same-clause",
        "forecast-denied-execution-asserted",
        "forecast-realized",
        "forecast-fulfilled",
        "forecast-confirmed-as-fact",
        "forecast-proven-right",
        "spaced-realization",
        "forecast-proven-accurate",
        "forecast-came-true",
        "later-forecast-realization",
        "later-forecast-confirmation",
        "earlier-forecast-realization",
        "earlier-forecast-confirmation",
        "forecast-word-prefix-is-not-a-complement",
        "wrong-year",
        "wrong-amount",
        "wrong-company",
        "unsupported-product-binding",
        "buy-advice",
        "target-price",
    ],
)
def test_forecast_never_waives_other_assertions_facts_or_investment_policy(
    full_reduce_context, reason, rule
):
    source, value = forecast_case()
    value["assessments"][AUDIENCE]["finding101"]["reason"] = reason
    raw = response(value, source)
    with pytest.raises(OutputValidationError) as native:
        full_validate(raw, source)
    if rule:
        assert rule in {issue.rule for issue in native.value.validation_issues}
    else:
        assert "report_expression_policy" in native.value.error_kinds

    _, base, original, validate = full_reduce_context
    value = deepcopy(original)
    value["insights"][0]["overview"][0]["text"] = reason
    with pytest.raises(OutputValidationError) as reduced:
        validate(replace(base, text=json.dumps(value, ensure_ascii=False)))
    if rule:
        assert rule in {issue.rule for issue in reduced.value.validation_issues}
    else:
        assert "report_synthesis_invalid" in reduced.value.error_kinds
        assert "투자 자문 표현을 포함할 수 없습니다" in str(reduced.value)


@pytest.mark.parametrize(
    "source_suffix,executed",
    [("이다.", False), ("이 현실화됐다.", True), ("이다. 그 전망이 현실화됐다.", True)],
    ids=["forecast-only", "realized-forecast", "later-realized-forecast"],
)
def test_source_forecast_requires_realization_to_authenticate_executed_event(
    source_suffix, executed
):
    source, value = forecast_case()
    forecast_source = FORECAST + source_suffix
    source.findings[0].claims[0].text = forecast_source
    source.findings[0].sentences[0].text = forecast_source
    entry = value["assessments"][AUDIENCE]["finding101"]
    for field in ("relationBasis", "urgencyBasis"):
        entry[field]["quote"] = forecast_source
    entry["reason"] = FORECAST.replace("확대된다는 전망", "확대됐다.")
    raw = response(value, source)
    before = raw.text, source.model_dump_json(by_alias=True)
    if executed:
        accepted = full_validate(raw, source)
        assert accepted.evidence[AUDIENCE][101].reason == entry["reason"]
    else:
        with pytest.raises(OutputValidationError) as caught:
            full_validate(raw, source)
        assert "event_state" in {issue.rule for issue in caught.value.validation_issues}
    assert before == (raw.text, source.model_dump_json(by_alias=True))


@pytest.mark.parametrize("failed_stage", [None, "MAP-001", "REVIEW-001", "REDUCE-001"])
def test_forecast_survives_actual_initial_and_repair_pipeline_with_unchanged_evidence(
    failed_stage,
):
    source, value = forecast_case()
    before = source.model_dump_json(by_alias=True), deepcopy(value)

    def hook(stage, occurrence, data, proposed):
        reason = (
            REASON.replace("2028년", "2029년")
            if stage == failed_stage and occurrence == 1
            else REASON
        )
        if stage.startswith(("MAP", "REVIEW")):
            proposed = deepcopy(value)
            proposed["assessments"][AUDIENCE]["finding101"]["reason"] = reason
        else:
            proposed["insights"][0].update(
                headline="서버 CPU 매출 전망의 성장 가정을 살펴본다.",
                overview=[
                    {
                        "text": reason,
                        "basisClaimIds": ["101:0"],
                        "assumption": "매출 확대 전망이 기업가치 판단에 적용되는 경우",
                    }
                ],
            )
        return proposed

    provider = V4Provider(source, hook=hook)
    engine = service.ReportInsightService(Settings(_env_file=None, AGENT_MOCK=False), provider)
    result = engine.generate(source)
    expected = ["MAP-001", "REVIEW-001", "REDUCE-001"]
    if failed_stage:
        expected.insert(expected.index(failed_stage), failed_stage)
    assert stages(provider) == expected
    assert all(provider.schema_validity)
    assert result.insights[0].assessments[0].reason == f"원문: 「{SOURCE}」 해석: {REASON}"
    assert result.insights[0].assessments[0].basis_claim_ids == ["101:0"]
    assert result.insights[0].overview[0].text == REASON
    assert result.insights[0].overview[0].basis_claim_ids == ["101:0"]
    assert result.meta.input_tokens == 11 * len(expected)
    assert result.meta.output_tokens == 7 * len(expected)
    assert before == (source.model_dump_json(by_alias=True), value)
