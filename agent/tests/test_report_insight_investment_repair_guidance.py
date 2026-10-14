"""Investment repair explains selected-source gaps without inviting disclaimers."""

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
from app.llm import report_insight_instructions as instructions
from app.llm import report_insight_service as service

AUDIENCE = "MARKET_INVESTOR"
SOURCE_RULE = "report_trading_observation_source"
PLAIN_SOURCE = "생산라인 전체의 가동 중단이 현재 계속된다."
TRADE_SOURCE = PLAIN_SOURCE + " 기관 순매수 기록이 관찰됐다."
GOOD_REASON = "생산 제약은 시장 전망 업무를 검토할 근거다."
OBSERVATION = "기관 순매수 기록은 수급을 검토할 관찰 정보다."


def source_case():
    # Synthetic source: the trade exists in this finding but is not selected.
    source = request(ids=(101, 102), audiences=(AUDIENCE,), text=PLAIN_SOURCE)
    finding = source.findings[0]
    finding.claims.append(
        finding.claims[0].model_copy(
            update={"id": "101:1", "text": TRADE_SOURCE, "evidence_sentence_ids": [1]}
        )
    )
    finding.sentences.append(
        finding.sentences[0].model_copy(update={"index": 1, "text": TRADE_SOURCE})
    )
    value = payload(source)
    for entry in value["assessments"][AUDIENCE].values():
        entry["reason"] = GOOD_REASON
    return source, value


def test_only_selected_trade_evidence_can_authorize_the_same_finding_observation():
    source, value = source_case()
    entry = value["assessments"][AUDIENCE]["finding101"]
    entry["reason"] = OBSERVATION
    original = source.model_dump_json(), deepcopy(value)
    with pytest.raises(service.ReportAssessmentValidationError) as caught:
        full_validate(response(value, source), source)
    (issue,) = caught.value.validation_issues
    assert issue.field == "assessments[101].reason"
    assert issue.claim_ids == ("101:0",)
    assert issue.error_kind == "report_expression_policy"
    assert issue.rule == SOURCE_RULE
    assert service._INVESTMENT_OBSERVATION_SOURCE_MESSAGE in str(caught.value)
    assert original == (source.model_dump_json(), value)

    selected = deepcopy(value)
    for field in ("relationBasis", "impactBasis", "urgencyBasis"):
        selected["assessments"][AUDIENCE]["finding101"][field] = {
            "claimId": "101:1",
            "quote": TRADE_SOURCE,
        }
    accepted = full_validate(response(selected, source), source)
    assert accepted.evidence[AUDIENCE][101].reason == OBSERVATION
    assert accepted.mapped.insights[0].assessments[0].basis_claim_ids == ["101:1"]
    assert original == (source.model_dump_json(), value)


def run_pipeline(failed_stage):
    source, value = source_case()
    original = source.model_dump_json(), deepcopy(value)

    def hook(stage, occurrence, data, proposed):
        if stage.startswith(("MAP", "REVIEW", "EXPLAIN")):
            proposed = deepcopy(value)
            wanted = {f"finding{finding['id']}" for finding in data["findings"]}
            proposed["assessments"][AUDIENCE] = {
                key: entry
                for key, entry in proposed["assessments"][AUDIENCE].items()
                if key in wanted
            }
            if stage == failed_stage and occurrence == 1:
                proposed["assessments"][AUDIENCE]["finding101"]["reason"] = OBSERVATION
        else:
            proposed["insights"][0].update(
                headline="생산 제약의 확인 사항을 검토한다.",
                overview=[
                    {
                        "text": OBSERVATION
                        if stage == failed_stage and occurrence == 1
                        else GOOD_REASON,
                        "basisClaimIds": ["101:0"],
                        "assumption": "생산 제약이 시장 전망 판단에 적용되는 경우",
                    }
                ],
            )
        return proposed

    provider = V4Provider(source, hook=hook)
    engine = service.ReportInsightService(Settings(_env_file=None, AGENT_MOCK=False), provider)
    failures = []
    factory = engine._repair_call

    def capture(prompt, schema, raw, error, validate):
        failures.append((prompt, schema, raw, error, validate))
        return factory(prompt, schema, raw, error, validate)

    engine._repair_call = capture
    result = engine.generate(source)
    assert original == (source.model_dump_json(), value)
    return source, provider, result, failures


@pytest.mark.parametrize("stage", ["MAP-001", "REVIEW-001", "REDUCE-001"])
def test_source_specific_repair_guidance_reaches_actual_provider_and_preserves_scope(stage):
    source, provider, result, failures = run_pipeline(stage)
    expected = ["MAP-001", "REVIEW-001", "EXPLAIN-001", "REDUCE-001"]
    expected.insert(expected.index(stage), stage)
    assert stages(provider) == expected
    assert len(failures) == 1
    error = failures[0][3]
    (issue,) = error.validation_issues
    assert issue.rule == SOURCE_RULE
    assert issue.claim_ids == ("101:0",)
    assert issue.error_kind == (
        "report_synthesis_invalid" if stage == "REDUCE-001" else "report_expression_policy"
    )
    index = expected.index(stage)
    initial, retry = provider.calls[index : index + 2]
    if stage == "REDUCE-001":
        assert issue.reason in retry["prompt"]
    else:
        assert service._INVESTMENT_OBSERVATION_SOURCE_MESSAGE in retry["prompt"]
        assert instructions.REPORT_ANALYSIS_ONLY_RULE in retry["prompt"]
    for call in provider.calls:
        assert instructions.REPORT_ANALYSIS_ONLY_RULE in (
            call["prompt"] + call["system_instruction"]
        )
        assert len(call["system_instruction"]) <= 3500
    if stage != "REDUCE-001":
        (finding,) = framed(retry["prompt"])["findings"]
        assert finding["id"] == 101
        assert [claim["id"] for claim in finding["claims"]] == ["101:0"]
        assert finding["sentences"] == [{"index": 0, "text": PLAIN_SOURCE}]
        assert TRADE_SOURCE not in json.dumps(finding, ensure_ascii=False)
        assert finding["proseRepair"]["editableFields"] == ["reason"]
        before = provider.wire_payloads[index]["assessments"][AUDIENCE]
        after = provider.wire_payloads[index + 1]["assessments"][AUDIENCE]
        assert set(after) == {"finding101"}
        assert after["finding101"]["decision"] == before["finding101"]["decision"]
        assert after["finding101"]["reason"] == GOOD_REASON
    else:
        assert framed(retry["prompt"]) == framed(initial["prompt"])
        (job,) = repair_jobs(retry["prompt"])
        assert (job["group"], job["index"]) == ("overview", 0)
        assert any(
            rule["rule"] == SOURCE_RULE
            for diagnostic in job["diagnostics"]
            for rule in diagnostic["rules"]
        )
    assert result.insights[0].overview[0].text == GOOD_REASON
    for finding_id, assessment in zip((101, 102), result.insights[0].assessments, strict=True):
        assert assessment.reason == f"원문: 「{PLAIN_SOURCE}」 해석: {GOOD_REASON}"
        assert assessment.basis_claim_ids == [f"{finding_id}:0"]
    assert result.meta.input_tokens == 55 and result.meta.output_tokens == 35
    assert all(provider.schema_validity)


@pytest.mark.parametrize(
    "prose",
    [
        OBSERVATION + " 지금 매수해야 한다.",
        "목표주가를 제시한다.",
        "매수·매도 권고나 목표가 제시는 하지 않는다.",
    ],
    ids=["recommendation-overrides-source-gap", "target-price", "policy-disclaimer"],
)
def test_real_advice_and_meta_disclaimers_keep_existing_policy_rejection(prose):
    source, value = source_case()
    value["assessments"][AUDIENCE]["finding101"]["reason"] = prose
    with pytest.raises(service.ReportAssessmentValidationError) as caught:
        full_validate(response(value, source), source)
    assert any(
        issue.error_kind == "report_expression_policy" and issue.rule == "report_expression_policy"
        for issue in caught.value.validation_issues
    )
    assert all(issue.rule != SOURCE_RULE for issue in caught.value.validation_issues)


@pytest.fixture(scope="module")
def native_repair_context():
    source, provider, _, failures = run_pipeline("MAP-001")
    prompt, schema, raw, error, validate = failures[0]
    repair = object.__new__(service.ReportInsightService)._repair_call(
        prompt, schema, raw, error, validate
    )
    return response(source_case()[1], source), deepcopy(provider.wire_payloads[1]), repair


@pytest.mark.parametrize(
    "prose,rule",
    [
        (OBSERVATION, SOURCE_RULE),
        ("매수·매도 권고나 목표가 제시는 하지 않는다.", "report_expression_policy"),
        ("지금 매수해야 한다.", "report_expression_policy"),
    ],
    ids=["unsupported-observation-retained", "disclaimer-repair", "recommendation-repair"],
)
def test_partial_native_repair_still_rejects_invalid_replacements(
    native_repair_context, prose, rule
):
    base, corrected, repair = native_repair_context
    value = deepcopy(corrected)
    value["assessments"][AUDIENCE]["finding101"]["reason"] = prose
    raw = replace(base, text=json.dumps(value, ensure_ascii=False))
    before = raw.text, deepcopy(value)
    with pytest.raises(OutputValidationError) as caught:
        repair.validate(raw)
    assert any(issue.rule == rule for issue in caught.value.validation_issues)
    assert before == (raw.text, value)


@pytest.fixture(scope="module")
def reduce_validation_context():
    source, provider, _, failures = run_pipeline("REDUCE-001")
    _, _, raw, _, validate = failures[0]
    corrected = json.loads(raw)
    (job,) = repair_jobs(provider.calls[-1]["prompt"])
    corrected["insights"][0]["overview"][0] = deepcopy(
        provider.wire_payloads[-1]["repairs"][job["key"]]
    )
    base = response(source_case()[1], source)
    validate(replace(base, text=json.dumps(corrected, ensure_ascii=False)))
    return base, corrected, validate


@pytest.mark.parametrize(
    "prose",
    ["지금 매수해야 한다.", "목표주가를 제시한다.", "매수·매도 권고나 목표가 제시는 하지 않는다."],
)
def test_reduce_full_validator_keeps_policy_and_disclaimer_rejection(
    reduce_validation_context, prose
):
    base, corrected, validate = reduce_validation_context
    value = deepcopy(corrected)
    value["insights"][0]["overview"][0]["text"] = prose
    with pytest.raises(OutputValidationError) as caught:
        validate(replace(base, text=json.dumps(value, ensure_ascii=False)))
    assert any(
        issue.error_kind == "report_synthesis_invalid" and issue.rule != SOURCE_RULE
        for issue in caught.value.validation_issues
    )
