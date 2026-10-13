"""Year-unit guidance changes generated prose, never evidence or guard authority."""

import json
from copy import deepcopy
from dataclasses import replace

import pytest
from test_report_insight_assessment import framed, payload, request, response
from test_report_insight_connection_evidence_dependency import full_validate
from test_report_insight_reduce_partial_repair import repair_jobs
from test_report_insight_v4_pipeline import V4Provider, stages

from app.core.config import Settings
from app.core.errors import OutputValidationError
from app.llm import report_insight_service as service
from app.llm.report_insight_assessment import draft_prompt
from app.llm.report_insight_instructions import REPORT_YEAR_UNIT_RULE

AUDIENCE = "MARKET_INVESTOR"
# Synthetic source and interpretation; this is not a user's report snapshot.
SOURCE = "엔비디아는 서버 CPU 매출이 2025년 70억달러에서 2028년 600억달러로 확대될 것으로 전망했다."
GOOD_REASON = "엔비디아의 서버 CPU 매출 2025년→2028년 확대 전망은 성장 가정 검토와 연결된다."
BARE_REASON = GOOD_REASON.replace("2025년→2028년", "2025→2028")


def forecast_case():
    source = request(audiences=(AUDIENCE,), text=SOURCE)
    source.findings[0].claims[0].claim_type = "FORECAST"
    value = payload(source)
    value["assessments"][AUDIENCE]["finding101"].update(
        impactScope="UNDETERMINED",
        impactBasis=None,
        urgencyState="MONITOR",
        reason=GOOD_REASON,
    )
    return source, value


def run_pipeline(failed_stage, *, malformed=False):
    source, value = forecast_case()
    before = source.model_dump_json(by_alias=True), deepcopy(value)

    def hook(stage, occurrence, data, proposed):
        reason = (
            BARE_REASON
            if stage == failed_stage and occurrence == 1 and not malformed
            else GOOD_REASON
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

    def raw(stage, occurrence, data, text):
        return "{" if malformed and stage == failed_stage and occurrence == 1 else text

    provider = V4Provider(source, hook=hook, raw_hook=raw)
    engine = service.ReportInsightService(Settings(_env_file=None, AGENT_MOCK=False), provider)
    captured = []
    factory = engine._repair_call

    def capture(prompt, schema, raw, error, validate):
        captured.append((prompt, schema, raw, error, validate))
        return factory(prompt, schema, raw, error, validate)

    engine._repair_call = capture
    result = engine.generate(source)
    assert before == (source.model_dump_json(by_alias=True), value)
    return source, provider, result, captured


@pytest.mark.parametrize(
    "stage,malformed",
    [("MAP-001", False), ("REVIEW-001", False), ("REDUCE-001", False), ("REDUCE-001", True)],
    ids=["MAP-prose-repair", "REVIEW-prose-repair", "REDUCE-partial-repair", "REDUCE-full-retry"],
)
def test_year_guidance_reaches_actual_initial_and_retry_calls_without_rewriting_sources(
    stage, malformed
):
    source, provider, result, captured = run_pipeline(stage, malformed=malformed)
    expected = ["MAP-001", "REVIEW-001", "REDUCE-001"]
    expected.insert(expected.index(stage), stage)
    assert stages(provider) == expected
    assert len(captured) == 1
    original_finding = framed(draft_prompt(source))["findings"][0]
    for call in provider.calls:
        prefix = call["prompt"].split("<report-insight-input>", 1)[0]
        assert prefix.count(REPORT_YEAR_UNIT_RULE) == 1
        assert REPORT_YEAR_UNIT_RULE not in call["system_instruction"]
        assert len(call["system_instruction"]) <= 3500
        data = framed(call["prompt"])
        if "findings" in data:
            (finding,) = data["findings"]
            for field in ("id", "claims", "sentences", "sourceQuoteChoices", "sourceFactIndex"):
                assert finding[field] == original_finding[field]
            assert finding["sourceQuoteChoices"] == {"101:0": {"s101_0_0": SOURCE}}
    reduce_calls = [
        call for call in provider.calls if "REDUCE" in call["response_schema"]["description"]
    ]
    reduce_data = framed(reduce_calls[0]["prompt"])
    (evidence,) = reduce_data["retrievedEvidence"][0]["evidence"]
    assert evidence["claimId"] == "101:0"
    assert SOURCE in json.dumps(evidence, ensure_ascii=False)
    if len(reduce_calls) == 2:
        assert framed(reduce_calls[1]["prompt"]) == reduce_data
        assert reduce_calls[1]["response_schema"]["title"] == (
            "ReportInsightReduceOutput" if malformed else "ReportInsightReduceRepair"
        )
    assessment = result.insights[0].assessments[0]
    assert assessment.reason == f"원문: 「{SOURCE}」 해석: {GOOD_REASON}"
    assert assessment.basis_claim_ids == ["101:0"]
    assert result.insights[0].overview[0].text == GOOD_REASON
    assert result.insights[0].overview[0].basis_claim_ids == ["101:0"]
    assert result.meta.input_tokens == 44 and result.meta.output_tokens == 28
    assert result.meta.cost_usd == pytest.approx(0.012)
    assert result.meta.credits == pytest.approx(0.8)
    assert all(provider.schema_validity)
    if not malformed:
        assert "numeric_context" in {issue.rule for issue in captured[0][3].validation_issues}


@pytest.fixture(scope="module")
def reduce_validator():
    source, provider, _, captured = run_pipeline("REDUCE-001")
    _, _, raw, _, validate = captured[0]
    valid = json.loads(raw)
    patches = provider.wire_payloads[-1]["repairs"]
    for job in repair_jobs(provider.calls[-1]["prompt"]):
        assert job["group"] == "overview" and job["index"] == 0
        valid["insights"][0]["overview"][0] = deepcopy(patches[job["key"]])
    base = response(forecast_case()[1], source)
    validate(replace(base, text=json.dumps(valid, ensure_ascii=False)))
    return source, base, valid, validate


@pytest.mark.parametrize(
    "reason,expected_rule",
    [
        (BARE_REASON, "numeric_context"),
        (GOOD_REASON.replace("2028년", "2029년"), "unsupported_number"),
        (
            "엔비디아의 서버 CPU 매출 900억달러 전망은 성장 가정 검토와 연결된다.",
            "unsupported_number",
        ),
        (GOOD_REASON.replace("엔비디아", "삼성전자"), "company"),
        (GOOD_REASON + " 목표가를 10만원으로 제시한다.", None),
        (GOOD_REASON + " 지금 매수해야 한다.", None),
    ],
    ids=["bare-year", "wrong-year", "wrong-amount", "wrong-company", "target-price", "buy-advice"],
)
def test_year_guidance_keeps_native_and_full_reduce_fact_and_policy_guards(
    reduce_validator, reason, expected_rule
):
    source, value = forecast_case()
    value["assessments"][AUDIENCE]["finding101"]["reason"] = reason
    candidate = response(value, source)
    before = candidate.text, source.model_dump_json(by_alias=True)
    with pytest.raises(OutputValidationError) as native:
        full_validate(candidate, source)
    if expected_rule:
        assert expected_rule in {issue.rule for issue in native.value.validation_issues}
    else:
        assert "report_expression_policy" in native.value.error_kinds
    assert before == (candidate.text, source.model_dump_json(by_alias=True))

    source, base, valid, validate = reduce_validator
    proposed = deepcopy(valid)
    proposed["insights"][0]["overview"][0]["text"] = reason
    candidate = replace(base, text=json.dumps(proposed, ensure_ascii=False))
    before = candidate.text, source.model_dump_json(by_alias=True)
    with pytest.raises(OutputValidationError) as reduced:
        validate(candidate)
    if expected_rule:
        assert expected_rule in {issue.rule for issue in reduced.value.validation_issues}
    else:
        assert "report_synthesis_invalid" in reduced.value.error_kinds
        assert "투자 자문 표현을 포함할 수 없습니다" in str(reduced.value)
    assert before == (candidate.text, source.model_dump_json(by_alias=True))
