"""MAP repair receives every finding's grounding failure without logging its prose."""

import json
import logging
from copy import deepcopy
from decimal import Decimal

import pytest
from jsonschema import Draft202012Validator
from jsonschema import ValidationError as JsonSchemaValidationError
from test_report_insight import output, request_body

from app.core.config import Settings
from app.core.errors import AgentError, OutputValidationError
from app.llm.base import ProviderResponse, ProviderUsage
from app.llm.prompt_data import prompt_json
from app.llm.report_insight_service import (
    ReportAssessmentValidationError,
    ReportSynthesisValidationError,
    _report_insight_prompt,
    _report_insight_repair_call,
    _report_insight_repair_prompt,
    _source_context,
    _validate_prose,
    _validated_map_output,
    _validated_output,
)
from app.llm.report_insight_service import (
    ReportInsightLegacyService as ReportInsightService,
)
from app.llm.request_contract import report_insight_map_schema
from app.llm.structured_call import structured_call
from app.schemas.report_insight import CLAIMLESS_ASSESSMENT_REASON, ReportInsightRequest


def title_only_facts():
    body = request_body(second=True)
    valid = output(second=True)
    for finding_id in (503, 504):
        finding = deepcopy(body["findings"][1])
        finding.update(id=finding_id, articleId=finding_id - 491)
        finding["claims"][0]["id"] = f"{finding_id}:0"
        body["findings"].append(finding)
        assessment = deepcopy(valid["insights"][0]["assessments"][1])
        assessment.update(findingId=finding_id, basisClaimIds=[f"{finding_id}:0"])
        valid["insights"][0]["assessments"].append(assessment)

    invalid = deepcopy(valid)
    title_facts = (
        ("NVIDIA", "30억원"),
        ("SK하이닉스", "40%"),
        ("AMD", "50억원"),
        ("TSMC", "60억원"),
    )
    for finding, assessment, (company, number) in zip(
        body["findings"], invalid["insights"][0]["assessments"], title_facts, strict=True
    ):
        finding["articleTitle"] = f"{company} {number} 검증 장비 도입 계획"
        assessment["reason"] = f"{company}의 {number} 투자 계획이 공정 검증 준비와 연결될 수 있다."
    return ReportInsightRequest.model_validate(body), valid, invalid


def stage_output(payload, *, stage="MAP"):
    return {
        "insights": [
            {key: value for key, value in insight.items() if key in {"audience", "assessments"}}
            if stage == "MAP"
            else {key: value for key, value in insight.items() if key != "assessments"}
            for insight in payload["insights"]
        ]
    }


def response(payload, *, usage=None):
    return ProviderResponse(
        text=json.dumps(payload, ensure_ascii=False),
        provider="openai",
        model="offline-repair-diagnostics",
        usage=usage or ProviderUsage(),
    )


def test_fact_mismatch_preserves_the_concrete_unsupported_values_for_repair():
    request, _, invalid = title_only_facts()
    claims, evidence = _source_context(request)
    assessment = invalid["insights"][0]["assessments"][0]

    with pytest.raises(OutputValidationError) as caught:
        _validate_prose(
            [assessment["reason"]],
            assessment["basisClaimIds"],
            evidence,
            claims,
            request=request,
        )

    assert caught.value.error_kinds == ("report_fact_mismatch",)
    diagnostic = str(caught.value)
    assert "생성 문장의 사실값이 basisClaimIds 근거와 일치하지 않습니다." in diagnostic
    assert "숫자" in diagnostic and "30" in diagnostic
    assert "기업명" in diagnostic and "엔비디아" in diagnostic


def test_map_reports_all_title_only_fact_failures_together():
    request, _, invalid = title_only_facts()

    with pytest.raises(OutputValidationError) as caught:
        _validated_map_output(response(stage_output(invalid)), request)

    assert caught.value.error_kinds == ("report_fact_mismatch",) * 4
    assert isinstance(caught.value, ReportAssessmentValidationError)
    assert caught.value.failed_finding_ids == (501, 502, 503, 504)
    diagnostic = str(caught.value)
    for finding_id, company in (
        (501, "엔비디아"),
        (502, "SK하이닉스"),
        (503, "AMD"),
        (504, "TSMC"),
    ):
        assert f"findingId={finding_id}" in diagnostic
        assert f"{finding_id}:0" in diagnostic
        assert company in diagnostic
    assert "제목" in diagnostic
    assert "숫자" in diagnostic and "회사" in diagnostic and "제품" in diagnostic


def test_cited_claim_correction_passes_without_removing_the_titles_or_findings():
    request, valid, _ = title_only_facts()
    snapshot = request.model_dump_json(by_alias=True)

    mapped = _validated_map_output(response(stage_output(valid)), request)

    assert [item.finding_id for item in mapped.insights[0].assessments] == [501, 502, 503, 504]
    assert mapped.model_dump(by_alias=True) == stage_output(valid)
    assert request.model_dump_json(by_alias=True) == snapshot


@pytest.mark.parametrize(
    "reason,urgency,field,time_error",
    [
        (
            "검증 장비 도입의 마감이 임박해 확인이 필요하다.",
            None,
            "assessments.reason",
            "이미 지난 근거 기한",
        ),
        (
            "검증 장비 도입의 마감에 따라 준비 일정을 확인할 필요가 있다.",
            3,
            "assessments.axes.urgency",
            "이미 지난 기한만으로 urgency=3",
        ),
    ],
)
def test_map_reports_fact_and_past_deadline_failures_in_the_same_repair(
    reason, urgency, field, time_error
):
    request, valid, invalid = title_only_facts()
    body = request.model_dump(by_alias=True, mode="json")
    deadline = "검증 장비 도입의 마감은 2026년 9월 20일이다."
    body["findings"][3]["claims"][0]["text"] = deadline
    body["findings"][3]["sentences"][0]["text"] = deadline
    request = ReportInsightRequest.model_validate(body)
    valid["insights"][0]["assessments"][0] = invalid["insights"][0]["assessments"][0]
    valid["insights"][0]["assessments"][3]["reason"] = reason
    valid["insights"][0]["assessments"][3]["axes"]["urgency"] = urgency

    with pytest.raises(OutputValidationError) as caught:
        _validated_map_output(response(stage_output(valid)), request)

    assert caught.value.error_kinds == ("report_fact_mismatch", "report_assessment_invalid")
    diagnostic = str(caught.value)
    assert "findingId=501" in diagnostic and "엔비디아" in diagnostic
    assert f"findingId=504 field={field}" in diagnostic and time_error in diagnostic


def test_one_map_repair_receives_all_causes_and_logs_only_safe_error_kinds(caplog):
    request, valid, invalid = title_only_facts()
    calls = []

    class RepairProvider:
        def generate(self, **kwargs):
            calls.append(kwargs)
            if len(calls) == 1:
                assert kwargs["response_schema"]["title"] == "ReportInsightMapOutput"
                return response(stage_output(invalid))
            if len(calls) == 2:
                assert kwargs["response_schema"] == calls[0]["response_schema"]
                diagnostic = (
                    kwargs["prompt"]
                    .split("<validation-error>", 1)[1]
                    .split("</validation-error>", 1)[0]
                )
                for finding_id, company in (
                    (501, "엔비디아"),
                    (502, "SK하이닉스"),
                    (503, "AMD"),
                    (504, "TSMC"),
                ):
                    assert f"findingId={finding_id}" in diagnostic
                    assert company in diagnostic
                return response(stage_output(valid))
            assert len(calls) == 3
            assert kwargs["response_schema"]["title"] == "ReportInsightReduceOutput"
            return response(stage_output(valid, stage="REDUCE"))

    with caplog.at_level(logging.WARNING, logger="app.llm.report_insight_service"):
        result = ReportInsightService(
            Settings(AGENT_MOCK=False, AGENT_SCHEMA_REPAIR_ATTEMPTS=1), RepairProvider()
        ).generate(request)

    assert len(calls) == 3
    assert [item.finding_id for item in result.insights[0].assessments] == [501, 502, 503, 504]
    assert result.insights[0].headline == valid["insights"][0]["headline"]
    assert "errorType=ReportAssessmentValidationError errorCount=4" in caplog.text
    assert "report_fact_mismatch" in caplog.text
    for private_detail in (
        "NVIDIA",
        "엔비디아",
        "SK하이닉스",
        "AMD",
        "TSMC",
        "findingId=",
        "30억원",
    ):
        assert private_detail not in caplog.text


def wire_input(prompt):
    return json.loads(
        prompt.split("<report-insight-input>", 1)[1].split("</report-insight-input>", 1)[0]
    )


def nested_keys(value):
    if isinstance(value, dict):
        return set(value).union(*(nested_keys(item) for item in value.values()))
    if isinstance(value, list):
        return set().union(*(nested_keys(item) for item in value))
    return set()


@pytest.mark.parametrize("repair_stage", ["MAP", "REDUCE"])
def test_report_repair_uses_original_grounding_without_metadata_or_previous_prose(repair_stage):
    request, valid, invalid = title_only_facts()
    body = request.model_dump(by_alias=True, mode="json")
    body["report"]["title"] = "REPORT_ONLY_TITLE"
    for finding in body["findings"]:
        finding["canonicalUrl"] = f"https://metadata-only.example.com/{finding['id']}"
        finding["topicName"] = f"TOPIC_ONLY_{finding['id']}"
    request = ReportInsightRequest.model_validate(body)
    if repair_stage == "REDUCE":
        invalid["insights"][0]["headline"] = (
            "NVIDIA의 999억원 투자 계획 때문에 공정 검증 준비가 달라질 수 있다."
        )
    calls = []
    attempts = {"MAP": 0, "REDUCE": 0}

    class RepairProjectionProvider:
        def generate(self, **kwargs):
            calls.append(kwargs)
            stage = (
                "MAP"
                if kwargs["response_schema"]["title"] == "ReportInsightMapOutput"
                else "REDUCE"
            )
            attempts[stage] += 1
            payload = invalid if stage == repair_stage and attempts[stage] == 1 else valid
            return response(stage_output(payload, stage=stage))

    snapshot = request.model_dump_json(by_alias=True)
    result = ReportInsightService(
        Settings(AGENT_MOCK=False, AGENT_SCHEMA_REPAIR_ATTEMPTS=1), RepairProjectionProvider()
    ).generate(request)

    assert len(calls) == 3
    initial, repair = [
        call
        for call in calls
        if call["response_schema"]["title"] == f"ReportInsight{repair_stage.title()}Output"
    ]
    assert repair["response_schema"] == initial["response_schema"]
    assert repair["system_instruction"] == initial["system_instruction"]
    original, grounded = wire_input(initial["prompt"]), wire_input(repair["prompt"])
    assert not {"title", "articleTitle", "canonicalUrl", "topicName", "score"} & nested_keys(
        grounded
    )
    assert grounded["report"] == {
        key: original["report"][key] for key in ("id", "reportScope", "reportDate", "reportEndDate")
    }
    assert grounded["audiences"] == original["audiences"]
    assert grounded["reportReferenceDate"] == original["reportReferenceDate"]
    if repair_stage == "MAP":
        assert "validatedAssessments" not in grounded
        assert grounded["idempotencyKey"] == original["idempotencyKey"]
        assert grounded["plan"] == original["plan"]
        for before, after in zip(original["findings"], grounded["findings"], strict=True):
            assert after == {
                key: before[key]
                for key in ("id", "articleId", "publishedAt", "claims", "sentences")
            }
        invalid_prose = [item["reason"] for item in invalid["insights"][0]["assessments"]]
    else:
        assert "validatedAssessments" not in grounded
        assert grounded["assessedPriorities"] == original["assessedPriorities"]
        for before, after in zip(
            original["retrievedEvidence"], grounded["retrievedEvidence"], strict=True
        ):
            assert after["audience"] == before["audience"]
            assert after["algorithm"] == before["algorithm"]
            assert len(after["evidence"]) == len(before["evidence"])
            for source, preserved in zip(before["evidence"], after["evidence"], strict=True):
                assert "score" in source
                assert preserved == {
                    key: source[key]
                    for key in (
                        "claimId",
                        "findingId",
                        "articleId",
                        "publishedAt",
                        "text",
                        "claimType",
                        "attributedTo",
                        "evidenceSentenceIds",
                        "sentences",
                    )
                }
        invalid_prose = [invalid["insights"][0]["headline"]]
    assert "<invalid-output>" not in repair["prompt"]
    assert "잘못된 결과를 복사하지 말고" in repair["prompt"]
    for metadata in [
        body["report"]["title"],
        *[
            finding[key]
            for finding in body["findings"]
            for key in ("articleTitle", "canonicalUrl", "topicName")
        ],
        *invalid_prose,
    ]:
        assert metadata not in repair["prompt"]
    assert request.model_dump_json(by_alias=True) == snapshot
    assert [item.finding_id for item in result.insights[0].assessments] == [501, 502, 503, 504]


class StageSequenceProvider:
    def __init__(self, *stages, usage=None):
        self.stages = list(stages)
        self.calls = []
        self.usage = usage

    def generate(self, **kwargs):
        self.calls.append(kwargs)
        stage, payload = self.stages.pop(0)
        assert kwargs["response_schema"]["title"] == f"ReportInsight{stage.title()}Output"
        return response(stage_output(payload, stage=stage), usage=self.usage)


def partial_map_fixture():
    request, valid, all_invalid = title_only_facts()
    invalid = deepcopy(valid)
    invalid["insights"][0]["assessments"][2] = all_invalid["insights"][0]["assessments"][2]
    # The previous answer's order cannot become the canonical request order.
    invalid["insights"][0]["assessments"] = [
        invalid["insights"][0]["assessments"][index] for index in (3, 2, 0, 1)
    ]
    subset = deepcopy(valid)
    subset["insights"][0]["assessments"] = [valid["insights"][0]["assessments"][2]]
    return request, valid, invalid, subset


def test_partial_map_repair_preserves_three_valid_assessments_and_only_reassesses_the_failure():
    request, valid, invalid, subset = partial_map_fixture()
    usage = ProviderUsage(
        input_tokens=11, output_tokens=7, cost_usd=Decimal("0.003"), credits=Decimal("0.2")
    )
    provider = StageSequenceProvider(
        ("MAP", invalid), ("MAP", subset), ("REDUCE", valid), usage=usage
    )
    snapshot = request.model_dump_json(by_alias=True)

    result = ReportInsightService(
        Settings(AGENT_MOCK=False, AGENT_SCHEMA_REPAIR_ATTEMPTS=1), provider
    ).generate(request)

    assert len(provider.calls) == 3
    first, retry = provider.calls[:2]
    assert first["response_schema"] == report_insight_map_schema(request)
    assert retry["response_schema"] != first["response_schema"]
    full_array = first["response_schema"]["$defs"]["ReportInsightMapAudience"]["properties"][
        "assessments"
    ]
    partial_array = retry["response_schema"]["$defs"]["ReportInsightMapAudience"]["properties"][
        "assessments"
    ]
    assert (full_array["minItems"], full_array["maxItems"]) == (4, 4)
    assert (partial_array["minItems"], partial_array["maxItems"]) == (1, 1)
    assert [
        branch["properties"]["findingId"]["const"]
        for branch in retry["response_schema"]["$defs"]["ReportInsightAssessment"]["anyOf"]
    ] == [503]
    Draft202012Validator(retry["response_schema"]).validate(stage_output(subset))
    with pytest.raises(JsonSchemaValidationError):
        Draft202012Validator(retry["response_schema"]).validate(stage_output(valid))
    original, repair = wire_input(first["prompt"]), wire_input(retry["prompt"])
    assert [finding["id"] for finding in repair["findings"]] == [503]
    assert repair["findings"][0] == {
        key: original["findings"][2][key]
        for key in ("id", "articleId", "publishedAt", "claims", "sentences")
    }
    assert "validatedAssessments" not in repair
    assert "assessedPriorities" not in repair
    assert valid["insights"][0]["assessments"][0]["reason"] not in retry["prompt"]
    assert original["findings"][0]["claims"][0]["text"] not in retry["prompt"]
    assert repair["reportReferenceDate"] == original["reportReferenceDate"]
    assert repair["audiences"] == original["audiences"]
    assert result.insights[0].model_dump(by_alias=True) == valid["insights"][0]
    assert result.meta.input_tokens == 33 and result.meta.output_tokens == 21
    assert result.meta.cost_usd == 0.009 and result.meta.credits == 0.6
    assert request.model_dump_json(by_alias=True) == snapshot


@pytest.mark.parametrize(
    "violation",
    [
        "missing",
        "duplicate",
        "preserved-id",
        "unknown-id",
        "ungrounded",
        "wrong-claim",
        "wrong-audience",
    ],
)
def test_partial_repair_rejects_invalid_subset_outputs_and_retains_both_calls_usage(violation):
    request, valid, invalid, subset = partial_map_fixture()
    tampered = deepcopy(subset)
    assessments = tampered["insights"][0]["assessments"]
    if violation == "missing":
        assessments.clear()
    elif violation == "duplicate":
        assessments.append(deepcopy(assessments[0]))
    elif violation == "preserved-id":
        changed = deepcopy(valid["insights"][0]["assessments"][1])
        changed["axes"]["directness"] = 0
        assessments.append(changed)
    elif violation == "unknown-id":
        assessments[0]["findingId"] = 999
    elif violation == "ungrounded":
        assessments[0]["reason"] = "NVIDIA의 999억원 투자 계획이 장비 검증 준비와 연결될 수 있다."
    elif violation == "wrong-claim":
        assessments[0]["basisClaimIds"] = ["501:0"]
    else:
        tampered["insights"][0]["audience"] = "EQUIPMENT_MAKER"
    usage = ProviderUsage(
        input_tokens=11, output_tokens=7, cost_usd=Decimal("0.003"), credits=Decimal("0.2")
    )
    provider = StageSequenceProvider(("MAP", invalid), ("MAP", tampered), usage=usage)
    snapshot = request.model_dump_json(by_alias=True)

    with pytest.raises(AgentError) as caught:
        ReportInsightService(
            Settings(AGENT_MOCK=False, AGENT_SCHEMA_REPAIR_ATTEMPTS=1), provider
        ).generate(request)

    assert caught.value.code == "SCHEMA_VIOLATION"
    assert len(provider.calls) == 2
    repair = wire_input(provider.calls[1]["prompt"])
    assert [item["id"] for item in repair["findings"]] == [503]
    assert "validatedAssessments" not in repair
    assert caught.value.details["usage"] == {
        "inputTokens": 22,
        "outputTokens": 14,
        "costUsd": 0.006,
        "credits": 0.4,
    }
    assert caught.value.details["executionMetadata"]["usageCompleteness"] == "COMPLETE"
    if violation == "ungrounded":
        assert isinstance(caught.value.__cause__, ReportAssessmentValidationError)
        assert caught.value.__cause__.failed_finding_ids == (503,)
        assert caught.value.__cause__.error_kinds == ("report_fact_mismatch",)
    assert request.model_dump_json(by_alias=True) == snapshot


def test_typed_failure_ids_select_the_subset_without_interpreting_diagnostic_text():
    request, _, invalid, _ = partial_map_fixture()
    prompt = _report_insight_prompt(request)
    schema = report_insight_map_schema(request)
    original_schema = deepcopy(schema)

    def validate(candidate):
        return _validated_map_output(candidate, request)

    error = ReportAssessmentValidationError(
        "findingId=501 field=assessments.reason: misleading diagnostic text",
        error_kinds=("report_fact_mismatch",),
        failed_finding_ids=(503,),
    )

    repair = _report_insight_repair_call(
        prompt, schema, response(stage_output(invalid)).text, error, validate
    )

    assert [item["id"] for item in wire_input(repair.prompt)["findings"]] == [503]
    assert schema == original_schema
    assert repair.response_schema is not schema
    assert repair.validate is not validate

    untyped = OutputValidationError(str(error), error_kinds=error.error_kinds)
    fallback = _report_insight_repair_call(
        prompt, schema, response(stage_output(invalid)).text, untyped, validate
    )
    assert [item["id"] for item in wire_input(fallback.prompt)["findings"]] == [501, 502, 503, 504]
    assert fallback.response_schema is schema
    assert fallback.validate is validate


@pytest.mark.parametrize("failure", ["all-assessments", "multiple-audiences", "structural"])
def test_map_repair_keeps_full_grounding_when_partial_preservation_is_not_applicable(failure):
    request, valid, all_invalid = title_only_facts()
    invalid = deepcopy(valid)
    if failure == "all-assessments":
        invalid = all_invalid
    elif failure == "multiple-audiences":
        body = request.model_dump(by_alias=True, mode="json")
        body["audiences"].append("EQUIPMENT_MAKER")
        request = ReportInsightRequest.model_validate(body)
        second = deepcopy(valid["insights"][0])
        second["audience"] = "EQUIPMENT_MAKER"
        valid["insights"].append(second)
        invalid = deepcopy(valid)
        invalid["insights"][0]["assessments"][2] = all_invalid["insights"][0]["assessments"][2]
    else:
        invalid["insights"][0]["assessments"].pop()
    provider = StageSequenceProvider(("MAP", invalid), ("MAP", valid), ("REDUCE", valid))
    snapshot = request.model_dump_json(by_alias=True)

    result = ReportInsightService(
        Settings(AGENT_MOCK=False, AGENT_SCHEMA_REPAIR_ATTEMPTS=1), provider
    ).generate(request)

    assert len(provider.calls) == 3
    assert provider.calls[1]["response_schema"] == provider.calls[0]["response_schema"]
    original, repair = [wire_input(call["prompt"]) for call in provider.calls[:2]]
    assert "validatedAssessments" not in repair
    assert [finding["id"] for finding in repair["findings"]] == [501, 502, 503, 504]
    assert repair["reportReferenceDate"] == original["reportReferenceDate"]
    assert repair["audiences"] == original["audiences"]
    assert result.model_dump(by_alias=True)["insights"] == valid["insights"]
    assert request.model_dump_json(by_alias=True) == snapshot


def empty_reduce(payload):
    candidate = deepcopy(payload)
    for insight in candidate["insights"]:
        insight.update(overview=[], implications=[], watchItems=[])
    return candidate


def test_only_required_related_empty_synthesis_reports_typed_audience_context():
    request = ReportInsightRequest.model_validate(request_body())
    empty = response(empty_reduce(output()))

    with pytest.raises(ReportSynthesisValidationError) as caught:
        _validated_output(empty, request)

    assert caught.value.error_kinds == ("report_synthesis_empty",)
    assert caught.value.audiences_requiring_overview == ("CHIP_MAKER",)
    assert _validated_output(empty, request, require_synthesis=False).insights[0].overview == []


def test_empty_related_reduce_repair_requires_grounded_overview_and_preserves_map_and_usage():
    request = ReportInsightRequest.model_validate(request_body(second=True))
    valid = output(second=True)
    corrected = deepcopy(valid)
    corrected["insights"][0].update(implications=[], watchItems=[])
    provider = StageSequenceProvider(
        ("MAP", valid),
        ("REDUCE", empty_reduce(valid)),
        ("REDUCE", corrected),
        usage=ProviderUsage(
            input_tokens=11, output_tokens=7, cost_usd=Decimal("0.003"), credits=Decimal("0.2")
        ),
    )
    snapshot = request.model_dump_json(by_alias=True)

    result = ReportInsightService(
        Settings(AGENT_MOCK=False, AGENT_SCHEMA_REPAIR_ATTEMPTS=1), provider
    ).generate(request)

    assert len(provider.calls) == 3
    initial, repair = provider.calls[1:]
    before = initial["response_schema"]["$defs"]["ReportInsightReduceAudience"]["anyOf"][0]
    after = repair["response_schema"]["$defs"]["ReportInsightReduceAudience"]["anyOf"][0]
    assert before["properties"]["overview"].get("minItems", 0) == 0
    assert after["properties"]["overview"]["minItems"] == 1
    assert after["properties"]["overview"]["maxItems"] == 3
    assert after["properties"]["overview"]["items"] == before["properties"]["overview"]["items"]
    for unchanged in ("audience", "headline", "implications", "watchItems"):
        assert after["properties"][unchanged] == before["properties"][unchanged]
    Draft202012Validator(repair["response_schema"]).validate(
        stage_output(corrected, stage="REDUCE")
    )
    with pytest.raises(JsonSchemaValidationError):
        Draft202012Validator(repair["response_schema"]).validate(
            stage_output(empty_reduce(valid), stage="REDUCE")
        )
    assert (
        wire_input(repair["prompt"])["assessedPriorities"]
        == wire_input(initial["prompt"])["assessedPriorities"]
    )
    assert result.model_dump(by_alias=True)["insights"] == corrected["insights"]
    assert result.meta.input_tokens == 33 and result.meta.output_tokens == 21
    assert result.meta.cost_usd == 0.009 and result.meta.credits == 0.6
    assert request.model_dump_json(by_alias=True) == snapshot


@pytest.mark.parametrize(
    "violation", ["empty", "unknown-ref", "empty-basis", "ungrounded", "assessments"]
)
def test_empty_related_reduce_repair_retains_grounding_and_output_contract_guards(violation):
    request = ReportInsightRequest.model_validate(request_body())
    valid = output()
    broken = deepcopy(valid)
    broken["insights"][0].update(implications=[], watchItems=[])
    if violation == "empty":
        broken = empty_reduce(valid)
    elif violation == "unknown-ref":
        broken["insights"][0]["overview"][0]["basisClaimIds"] = ["999:0"]
    elif violation == "empty-basis":
        broken["insights"][0]["overview"][0]["basisClaimIds"] = []
    elif violation == "ungrounded":
        broken["insights"][0]["overview"][0]["text"] = (
            "NVIDIA의 999억원 투자 계획이 준비에 영향을 줄 수 있다."
        )
    provider = StageSequenceProvider(
        ("MAP", valid),
        ("REDUCE", empty_reduce(valid)),
        ("REDUCE", broken),
        usage=ProviderUsage(
            input_tokens=11, output_tokens=7, cost_usd=Decimal("0.003"), credits=Decimal("0.2")
        ),
    )
    if violation == "assessments":
        original_generate = provider.generate

        def add_forbidden_assessment(**kwargs):
            generated = original_generate(**kwargs)
            if len(provider.calls) == 3:
                payload = json.loads(generated.text)
                payload["insights"][0]["assessments"] = valid["insights"][0]["assessments"]
                return response(payload, usage=provider.usage)
            return generated

        provider.generate = add_forbidden_assessment

    with pytest.raises(AgentError) as caught:
        ReportInsightService(
            Settings(AGENT_MOCK=False, AGENT_SCHEMA_REPAIR_ATTEMPTS=1), provider
        ).generate(request)

    assert caught.value.code == "SCHEMA_VIOLATION"
    assert len(provider.calls) == 3
    assert caught.value.details["usage"] == {
        "inputTokens": 33,
        "outputTokens": 21,
        "costUsd": 0.009,
        "credits": 0.6,
    }


@pytest.mark.parametrize("synthesis_field", ["overview", "implications", "watchItems"])
def test_any_grounded_nonempty_synthesis_field_is_sufficient_without_repair(synthesis_field):
    valid = output()
    for field in {"overview", "implications", "watchItems"} - {synthesis_field}:
        valid["insights"][0][field] = []
    provider = StageSequenceProvider(("MAP", valid), ("REDUCE", valid))

    result = ReportInsightService(Settings(AGENT_MOCK=False), provider).generate(
        ReportInsightRequest.model_validate(request_body())
    )

    assert len(provider.calls) == 2
    assert result.model_dump(by_alias=True)["insights"] == valid["insights"]


@pytest.mark.parametrize("claimless", [False, True])
def test_unrelated_or_claimless_map_remains_allowed_to_finish_with_empty_synthesis(claimless):
    body = request_body()
    valid = output()
    assessment = valid["insights"][0]["assessments"][0]
    if claimless:
        body["findings"][0]["claims"][0]["text"] = "삼성전자는 2028년 CPO 양산을 계획했다."
        assessment.update(
            reason=CLAIMLESS_ASSESSMENT_REASON,
            basisClaimIds=[],
            axes={"directness": None, "impact": None, "urgency": None, "novelty": None},
        )
    else:
        assessment["axes"]["directness"] = 0
    request = ReportInsightRequest.model_validate(body)
    snapshot = request.model_dump_json(by_alias=True)
    provider = StageSequenceProvider(("MAP", valid))

    result = ReportInsightService(Settings(AGENT_MOCK=False), provider).generate(request)

    assert len(provider.calls) == 1
    assert (
        result.insights[0].overview
        == result.insights[0].implications
        == result.insights[0].watch_items
        == []
    )
    assert result.insights[0].headline == "이 관점의 관련 근거가 부족합니다."
    assert result.insights[0].assessments[0].model_dump(by_alias=True) == assessment
    assert request.model_dump_json(by_alias=True) == snapshot


def test_empty_reduce_repair_binds_only_the_related_audience_and_keeps_unrelated_arrays_empty():
    valid = output()
    valid["insights"][0].update(implications=[], watchItems=[])
    unrelated = deepcopy(valid["insights"][0])
    unrelated.update(
        audience="IT_INFRA",
        headline="이 관점의 관련 근거가 부족합니다.",
        overview=[],
        implications=[],
        watchItems=[],
    )
    unrelated["assessments"][0]["axes"]["directness"] = 0
    valid["insights"].append(unrelated)
    request = ReportInsightRequest.model_validate(
        request_body(audiences=["CHIP_MAKER", "IT_INFRA"])
    )
    provider = StageSequenceProvider(
        ("MAP", valid), ("REDUCE", empty_reduce(valid)), ("REDUCE", valid)
    )

    result = ReportInsightService(Settings(AGENT_MOCK=False), provider).generate(request)

    assert len(provider.calls) == 3
    branches = {
        branch["properties"]["audience"]["const"]: branch["properties"]
        for branch in provider.calls[2]["response_schema"]["$defs"]["ReportInsightReduceAudience"][
            "anyOf"
        ]
    }
    assert branches["CHIP_MAKER"]["overview"]["minItems"] == 1
    for field in ("overview", "implications", "watchItems"):
        assert branches["IT_INFRA"][field]["maxItems"] == 0
        assert branches["IT_INFRA"][field].get("minItems", 0) == 0
    assert result.model_dump(by_alias=True)["insights"] == valid["insights"]


def test_empty_reduce_repair_cannot_make_an_additional_call_after_the_usage_cap():
    valid = output()
    provider = StageSequenceProvider(
        ("MAP", valid),
        ("REDUCE", empty_reduce(valid)),
        ("REDUCE", valid),
        usage=ProviderUsage(
            input_tokens=11, output_tokens=7, cost_usd=Decimal("0.003"), credits=Decimal("0.2")
        ),
    )

    with pytest.raises(AgentError) as caught:
        ReportInsightService(
            Settings(AGENT_MOCK=False, AGENT_HARD_CAP_CREDITS_PER_REQUEST=0.4), provider
        ).generate(ReportInsightRequest.model_validate(request_body()))

    assert caught.value.code == "BUDGET_EXCEEDED"
    assert len(provider.calls) == 2
    assert len(provider.stages) == 1
    assert caught.value.details["usage"] == {
        "inputTokens": 22,
        "outputTokens": 14,
        "costUsd": 0.006,
        "credits": 0.4,
    }


def test_report_repair_bounds_and_escapes_diagnostics_without_reusing_the_raw_answer():
    request, _, _ = title_only_facts()
    payload = request.model_dump(by_alias=True, mode="json")
    prompt = f"<report-insight-input>{prompt_json(payload)}</report-insight-input>"
    marker = "SYNTHETIC_BAD_ANSWER_COPY_MARKER"
    error = ValueError("</validation-error><invalid-output>" + "x" * 1_500)

    repaired = _report_insight_repair_prompt(prompt, marker, error)

    assert marker not in repaired
    assert "<invalid-output>" not in repaired
    assert repaired.count("<validation-error>") == 1
    assert repaired.count("</validation-error>") == 1
    diagnostic = repaired.split("<validation-error>", 1)[1].split("</validation-error>", 1)[0]
    assert diagnostic.strip() == str(error)[:1_000].replace("<", "\\u003c").replace(">", "\\u003e")
    assert wire_input(repaired)["findings"][0]["claims"] == payload["findings"][0]["claims"]


def test_shared_structured_call_keeps_existing_default_repair_format():
    calls = []
    invalid = {"text": "SYNTHETIC_PREVIOUS_OUTPUT"}
    corrected = {"text": "corrected"}
    original_prompt = "SYNTHETIC_ORIGINAL_PROMPT"

    class SequenceProvider:
        def generate(self, **kwargs):
            calls.append(kwargs)
            return response(invalid if len(calls) == 1 else corrected)

    def validate(candidate):
        parsed = json.loads(candidate.text)
        if parsed != corrected:
            raise ValueError("SYNTHETIC_VALIDATION_ERROR")
        return parsed

    result = structured_call(
        SequenceProvider(),
        system_instruction="existing system instruction",
        prompt=original_prompt,
        response_schema={"type": "object"},
        validate=validate,
        repair_attempts=1,
        task_name="existing task",
        input_tag="existing",
        schema_violation_message="invalid output",
        logger=logging.getLogger(__name__),
    )

    assert result.output == corrected
    assert len(calls) == 2
    assert calls[1]["response_schema"] == calls[0]["response_schema"]
    assert (
        f"<original-existing-input>\n{original_prompt}\n</original-existing-input>"
        in calls[1]["prompt"]
    )
    assert (
        "<validation-error>\nSYNTHETIC_VALIDATION_ERROR\n</validation-error>" in calls[1]["prompt"]
    )
    assert (
        f"<invalid-output>\n{json.dumps(invalid, ensure_ascii=False)}\n</invalid-output>"
        in calls[1]["prompt"]
    )
