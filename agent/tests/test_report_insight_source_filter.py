"""Stored source errors remove claims locally, never a whole report finding."""

import json
from copy import deepcopy

import pytest
from jsonschema import Draft202012Validator
from jsonschema import ValidationError as JsonSchemaValidationError
from pydantic import ValidationError
from pydantic_ai.profiles.openai import OpenAIJsonSchemaTransformer

from app.core.config import Settings
from app.llm.base import ProviderResponse, ProviderUsage
from app.llm.openai_contract import output_contract
from app.llm.report_insight_service import (
    ReportInsightService,
    _eligible_report_request,
    _source_context,
    _validated_output,
)
from app.llm.request_contract import report_insight_map_schema, report_insight_schema
from app.schemas.report_insight import CLAIMLESS_ASSESSMENT_REASON, ReportInsightRequest
from tests.test_report_insight import output, request_body


def mixed_request():
    body = request_body(second=True)
    body["findings"][1]["claims"][0]["text"] = "검증 장비 도입을 완료했다."
    # Unreferenced prose is not an additional source for importance judgements.
    body["findings"][1]["sentences"].append(
        {"index": 1, "text": "NVIDIA는 2028년 매출이 300억원이라고 밝혔다."}
    )
    return ReportInsightRequest.model_validate(body)


def mixed_output():
    payload = output(second=True)
    payload["insights"][0]["assessments"][1].update(
        reason=CLAIMLESS_ASSESSMENT_REASON,
        basisClaimIds=[],
        axes={"directness": None, "impact": None, "urgency": None, "novelty": None},
    )
    return payload


def stage_payload(payload, stage):
    retained = {"audience", "assessments"}
    return {
        "insights": [
            {key: value for key, value in insight.items() if key in retained}
            if stage == "MAP"
            else {key: value for key, value in insight.items() if key != "assessments"}
            for insight in payload["insights"]
        ]
    }


class StageProvider:
    def __init__(self, stages):
        self.stages = list(stages)
        self.calls = []

    def generate(self, **kwargs):
        self.calls.append(kwargs)
        stage, payload = self.stages.pop(0)
        assert kwargs["response_schema"]["title"] == f"ReportInsight{stage.title()}Output"
        return ProviderResponse(
            text=json.dumps(stage_payload(payload, stage), ensure_ascii=False),
            provider="openai",
            model="offline-source-filter-test",
            usage=ProviderUsage(),
        )


def generate(request, provider, **settings):
    return ReportInsightService(Settings(AGENT_MOCK=False, **settings), provider).generate(request)


def wire_input(call):
    return json.loads(
        call["prompt"].split("<report-insight-input>", 1)[1].split("</report-insight-input>", 1)[0]
    )


def test_source_filter_preserves_valid_claims_and_original_snapshot_exactly():
    body = request_body()
    finding = body["findings"][0]
    invalid = deepcopy(finding["claims"][0])
    invalid.update(id="501:1", text="삼성전자는 2028년 CPO 양산을 완료했다.")
    later_valid = deepcopy(finding["claims"][0])
    later_valid.update(id="501:2", text="  삼성전자는 2027년 CPO 양산을 계획했다.  ")
    finding["claims"].extend([invalid, later_valid])
    finding["sentences"].append({"index": 1, "text": "관점 분석에 연결되지 않은 문장이다."})
    request = ReportInsightRequest.model_validate(body)
    original = request.model_dump_json(by_alias=True)

    eligible = _eligible_report_request(request)

    assert request.model_dump_json(by_alias=True) == original
    assert eligible is not request
    assert [claim.id for claim in eligible.findings[0].claims] == ["501:0", "501:2"]
    assert eligible.findings[0].claims[0].model_dump() == request.findings[0].claims[0].model_dump()
    assert eligible.findings[0].claims[1].model_dump() == request.findings[0].claims[2].model_dump()
    assert [sentence.index for sentence in eligible.findings[0].sentences] == [0]
    assert _eligible_report_request(eligible).model_dump() == eligible.model_dump()
    assert list(_source_context(eligible)[0]) == ["501:0", "501:2"]
    native = OpenAIJsonSchemaTransformer(
        output_contract(report_insight_map_schema(eligible)).schema, strict=True
    ).walk()
    validator = Draft202012Validator(native)
    candidate = stage_payload(output(), "MAP")
    assessment = candidate["insights"][0]["assessments"][0]
    candidate["insights"][0]["assessments"] = {"finding501": assessment}
    assessment["basisClaimIds"] = ["501:2"]
    validator.validate(candidate)
    assessment["basisClaimIds"] = ["501:1"]
    with pytest.raises(JsonSchemaValidationError):
        validator.validate(candidate)


def test_claimless_finding_remains_assessed_without_exposing_its_sources_to_any_stage():
    request = mixed_request()
    original = request.model_dump_json(by_alias=True)
    provider = StageProvider([("MAP", mixed_output()), ("REDUCE", mixed_output())])

    result = generate(request, provider)

    assert request.model_dump_json(by_alias=True) == original
    assert len(provider.calls) == 2
    map_input, reduce_input = [wire_input(call) for call in provider.calls]
    assert map_input["findings"][1]["id"] == 502
    assert map_input["findings"][1]["claims"] == []
    assert map_input["findings"][1]["sentences"] == []
    retrieved = reduce_input["retrievedEvidence"][0]["evidence"]
    assert [entry["claimId"] for entry in retrieved] == ["501:0"]
    for call in provider.calls:
        assert "도입을 완료했다" not in call["prompt"]
        assert "NVIDIA" not in call["prompt"]
    assert [item.finding_id for item in result.insights[0].assessments] == [501, 502]
    unavailable = result.insights[0].assessments[1]
    assert unavailable.reason == CLAIMLESS_ASSESSMENT_REASON
    assert unavailable.basis_claim_ids == []
    assert set(unavailable.axes.model_dump().values()) == {None}


@pytest.mark.parametrize("axis", ["directness", "impact", "urgency"])
def test_native_map_contract_for_claimless_finding_rejects_scores_refs_and_factual_reason(axis):
    request = _eligible_report_request(mixed_request())
    schema = report_insight_map_schema(request)
    contract = output_contract(schema)
    transformed = OpenAIJsonSchemaTransformer(contract.schema, strict=True).walk()
    validator = Draft202012Validator(transformed)
    payload = stage_payload(mixed_output(), "MAP")
    public_payload = deepcopy(payload)
    payload["insights"][0]["assessments"] = {
        f"finding{assessment['findingId']}": assessment
        for assessment in payload["insights"][0]["assessments"]
    }
    validator.validate(payload)
    assert json.loads(contract.public_text(json.dumps(payload))) == public_payload

    for change in (
        {"axes": {"directness": None, "impact": None, "urgency": None, "novelty": None, axis: 0}},
        {"basisClaimIds": ["502:0"]},
        {"reason": "기사 제목에 따르면 장비 도입이 완료됐다."},
    ):
        invalid = deepcopy(payload)
        invalid["insights"][0]["assessments"]["finding502"].update(change)
        with pytest.raises(JsonSchemaValidationError):
            validator.validate(invalid)


@pytest.mark.parametrize(
    "change",
    [
        {"basisClaimIds": ["502:0"]},
        {"axes": {"directness": 0, "impact": None, "urgency": None, "novelty": None}},
        {"reason": "기사 제목에 따르면 장비 도입이 완료됐다."},
        {"reason": "제목을 보면 설비 고객 수요가 확대되어 직접 관련성이 높다."},
    ],
)
def test_claimless_output_cannot_infer_facts_from_title_or_reuse_filtered_refs(change):
    request = _eligible_report_request(mixed_request())
    payload = mixed_output()
    payload["insights"][0]["assessments"][1].update(change)
    with pytest.raises(ValueError):
        _validated_output(
            ProviderResponse(
                text=json.dumps(payload), provider="openai", model="offline", usage=ProviderUsage()
            ),
            request,
        )


@pytest.mark.parametrize("stage", ["MAP", "REDUCE"])
def test_filtered_refs_are_rejected_and_repaired_with_same_eligible_bound_schema(stage):
    request = mixed_request()
    fixed = mixed_output()
    invalid = deepcopy(fixed)
    if stage == "MAP":
        invalid["insights"][0]["assessments"][1]["basisClaimIds"] = ["502:0"]
        stages = [("MAP", invalid), ("MAP", fixed), ("REDUCE", fixed)]
        repair_indices = (0, 1)
    else:
        invalid["insights"][0]["overview"][0]["basisClaimIds"] = ["502:0"]
        stages = [("MAP", fixed), ("REDUCE", invalid), ("REDUCE", fixed)]
        repair_indices = (1, 2)
    provider = StageProvider(stages)

    result = generate(request, provider, AGENT_SCHEMA_REPAIR_ATTEMPTS=1)

    assert len(provider.calls) == 3
    first, repair = [provider.calls[index] for index in repair_indices]
    assert first["response_schema"] == repair["response_schema"]
    preserved_input = (
        repair["prompt"]
        .split("<report-insight-input>", 1)[1]
        .split("</report-insight-input>", 1)[0]
    )
    original_input = json.loads(
        first["prompt"].split("<report-insight-input>", 1)[1].split("</report-insight-input>", 1)[0]
    )

    def without_metadata(value):
        if isinstance(value, dict):
            return {
                key: without_metadata(item)
                for key, item in value.items()
                if key not in {"title", "articleTitle", "canonicalUrl", "topicName", "score"}
            }
        if isinstance(value, list):
            return [without_metadata(item) for item in value]
        return value

    assert json.loads(preserved_input) == without_metadata(original_input)
    assert "<invalid-output>" not in repair["prompt"]
    assert result.insights[0].assessments[1].basis_claim_ids == []
    assert result.insights[0].overview[0].basis_claim_ids == ["501:0"]


def test_every_claim_filtered_still_returns_every_finding_and_skips_reduce():
    body = request_body(second=True)
    for finding in body["findings"]:
        finding["claims"][0]["text"] = "삼성전자는 2028년 양산을 완료했다."
    request = ReportInsightRequest.model_validate(body)
    payload = mixed_output()
    for assessment in payload["insights"][0]["assessments"]:
        assessment.update(
            reason=CLAIMLESS_ASSESSMENT_REASON,
            basisClaimIds=[],
            axes={"directness": None, "impact": None, "urgency": None, "novelty": None},
        )
    provider = StageProvider([("MAP", payload)])

    result = generate(request, provider)

    assert len(provider.calls) == 1
    assert result.meta.provider == "openai"
    assert result.meta.mock is False
    assert [item.finding_id for item in result.insights[0].assessments] == [501, 502]
    assert result.insights[0].overview == []
    assert result.insights[0].implications == []
    assert result.insights[0].watch_items == []
    eligible = _eligible_report_request(request)
    native = OpenAIJsonSchemaTransformer(
        output_contract(report_insight_schema(eligible)).schema, strict=True
    ).walk()
    validator = Draft202012Validator(native)
    candidate = {"insights": [insight.model_dump(by_alias=True) for insight in result.insights]}
    validator.validate(candidate)
    for field in ("headline", "overview", "implications", "watchItems"):
        invented = deepcopy(candidate)
        invented["insights"][0][field] = output()["insights"][0][field]
        with pytest.raises(JsonSchemaValidationError):
            validator.validate(invented)
        with pytest.raises(ValueError):
            _validated_output(
                ProviderResponse(
                    text=json.dumps(invented),
                    provider="openai",
                    model="offline",
                    usage=ProviderUsage(),
                ),
                eligible,
            )


def test_mock_mode_keeps_claimless_assessment_limitation_and_public_input_stays_strict():
    request = mixed_request()
    result = ReportInsightService(Settings(AGENT_MOCK=True)).generate(request)
    assert result.insights[0].assessments[1].reason == CLAIMLESS_ASSESSMENT_REASON
    assert result.insights[0].assessments[1].basis_claim_ids == []
    for field in ("claims", "sentences"):
        body = request_body()
        body["findings"][0][field] = []
        with pytest.raises(ValidationError):
            ReportInsightRequest.model_validate(body)
