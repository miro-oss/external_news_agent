"""OpenAI MAP requires each finding once without changing the public assessment list."""

import json
from copy import deepcopy

import pytest
from jsonschema import Draft202012Validator
from jsonschema import ValidationError as JsonSchemaValidationError
from pydantic_ai.profiles.openai import OpenAIJsonSchemaTransformer

from app.llm.base import ProviderResponse, ProviderUsage
from app.llm.openai_contract import output_contract
from app.llm.report_insight_service import _eligible_report_request, _validated_map_output
from app.llm.request_contract import (
    report_insight_map_schema,
    report_insight_reduce_schema,
    report_insight_schema,
)
from app.schemas.report_insight import (
    CLAIMLESS_ASSESSMENT_REASON,
    ReportInsightMapOutput,
    ReportInsightRequest,
)
from tests.test_report_insight import output, request_body


def bound_case(*, claimless=False):
    body = request_body(second=True)
    public = output(second=True)
    if claimless:
        body["findings"][1]["claims"][0]["text"] = "검증 장비 도입을 완료했다."
        public["insights"][0]["assessments"][1].update(
            reason=CLAIMLESS_ASSESSMENT_REASON,
            basisClaimIds=[],
            axes={"directness": None, "impact": None, "urgency": None, "novelty": None},
        )
    request = _eligible_report_request(ReportInsightRequest.model_validate(body))
    public = {
        "insights": [
            {key: value for key, value in insight.items() if key in {"audience", "assessments"}}
            for insight in public["insights"]
        ]
    }
    wire = deepcopy(public)
    for insight in wire["insights"]:
        insight["assessments"] = {
            f"finding{assessment['findingId']}": assessment for assessment in insight["assessments"]
        }
    schema = report_insight_map_schema(request)
    contract = output_contract(schema)
    native = OpenAIJsonSchemaTransformer(deepcopy(contract.schema), strict=True).walk()
    return request, public, wire, schema, contract, Draft202012Validator(native)


def test_map_wire_requires_all_findings_and_preserves_each_request_bound_branch():
    _, _, wire, schema, _, validator = bound_case()
    before = deepcopy(schema)
    contract = output_contract(schema)
    branches = schema["$defs"]["ReportInsightAssessment"]["anyOf"]
    assessments = contract.schema["$defs"]["ReportInsightMapAudience"]["properties"]["assessments"]

    assert assessments["required"] == ["finding501", "finding502"]
    assert assessments["additionalProperties"] is False
    assert assessments["properties"] == {
        f"finding{branch['properties']['findingId']['const']}": branch for branch in branches
    }
    assert "ReportInsightAssessment" not in contract.schema["$defs"]
    assert (
        contract.schema["$defs"]["ReportImportanceAxes"] == schema["$defs"]["ReportImportanceAxes"]
    )
    validator.validate(wire)
    assert schema == before


@pytest.mark.parametrize(
    "invalid_case",
    ["missing", "extra", "cross-swap", "duplicate-id", "wrong-basis", "array"],
)
def test_native_map_schema_rejects_missing_duplicate_and_cross_finding_output(invalid_case):
    _, _, wire, _, _, validator = bound_case()
    assessments = wire["insights"][0]["assessments"]
    if invalid_case == "missing":
        del assessments["finding502"]
    elif invalid_case == "extra":
        assessments["finding999"] = deepcopy(assessments["finding501"])
    elif invalid_case == "cross-swap":
        assessments["finding501"], assessments["finding502"] = (
            assessments["finding502"],
            assessments["finding501"],
        )
    elif invalid_case == "duplicate-id":
        assessments["finding502"]["findingId"] = 501
    elif invalid_case == "wrong-basis":
        assessments["finding501"]["basisClaimIds"] = ["502:0"]
    else:
        wire["insights"][0]["assessments"] = list(assessments.values())

    with pytest.raises(JsonSchemaValidationError):
        validator.validate(wire)


def test_public_map_round_trip_uses_schema_order_and_preserves_assessments_and_request():
    request, public, wire, _, contract, validator = bound_case()
    snapshot = request.model_dump_json(by_alias=True)
    wire["insights"][0]["assessments"] = dict(
        reversed(list(wire["insights"][0]["assessments"].items()))
    )
    validator.validate(wire)
    converted = contract.public_text(json.dumps(wire, ensure_ascii=False))

    assert json.loads(converted) == public
    mapped = _validated_map_output(
        ProviderResponse(converted, "openai", "offline-native-map", ProviderUsage()), request
    )
    assert mapped.model_dump(mode="json", by_alias=True) == public
    assert request.model_dump_json(by_alias=True) == snapshot


@pytest.mark.parametrize(
    "invalid_case",
    [
        "missing",
        "extra",
        "non-object-assessment",
        "wrong-id",
        "bool-id",
        "cross-swap",
        "array",
        "non-object-insight",
        "missing-insights",
        "empty-insights",
        "second-audience-invalid",
    ],
)
def test_public_map_never_normalizes_malformed_key_or_identity_output(invalid_case):
    _, _, wire, _, contract, _ = bound_case()
    assessments = wire["insights"][0]["assessments"]
    if invalid_case == "missing":
        del assessments["finding502"]
    elif invalid_case == "extra":
        assessments["finding999"] = deepcopy(assessments["finding501"])
    elif invalid_case == "non-object-assessment":
        assessments["finding501"] = None
    elif invalid_case == "wrong-id":
        assessments["finding501"]["findingId"] = 999
    elif invalid_case == "bool-id":
        assessments["finding501"]["findingId"] = True
    elif invalid_case == "cross-swap":
        assessments["finding501"], assessments["finding502"] = (
            assessments["finding502"],
            assessments["finding501"],
        )
    elif invalid_case == "array":
        wire["insights"][0]["assessments"] = list(assessments.values())
    elif invalid_case == "non-object-insight":
        wire["insights"] = [None]
    elif invalid_case == "missing-insights":
        del wire["insights"]
    elif invalid_case == "empty-insights":
        wire["insights"] = []
    else:
        invalid = deepcopy(wire["insights"][0])
        invalid["audience"] = "IT_INFRA"
        del invalid["assessments"]["finding502"]
        wire["insights"].append(invalid)
    raw = json.dumps(wire, indent=2, ensure_ascii=False)

    assert contract.public_text(raw) == raw


@pytest.mark.parametrize("raw", ['{"insights":', "null", "[]"])
def test_public_map_keeps_unparseable_or_non_object_response(raw):
    _, _, _, _, contract, _ = bound_case()
    assert contract.public_text(raw) == raw


def test_public_map_rejects_duplicate_json_keys_before_conversion():
    _, _, wire, _, contract, _ = bound_case()
    raw = json.dumps(wire)
    key = '"finding501":'
    assessment = json.dumps(wire["insights"][0]["assessments"]["finding501"])
    raw = raw.replace(key, f"{key} {assessment}, {key}", 1)
    assert contract.public_text(raw) == raw


@pytest.mark.parametrize("invalid_case", ["reason", "basis", "axis"])
def test_native_map_claimless_branch_requires_exact_hold_reason_empty_basis_and_null_axes(
    invalid_case,
):
    request, public, wire, _, contract, validator = bound_case(claimless=True)
    assert request.findings[1].claims == []
    validator.validate(wire)
    assert json.loads(contract.public_text(json.dumps(wire, ensure_ascii=False))) == public
    assessment = wire["insights"][0]["assessments"]["finding502"]
    if invalid_case == "reason":
        assessment["reason"] = "근거는 부족하지만 중요하다."
    elif invalid_case == "basis":
        assessment["basisClaimIds"] = ["502:0"]
    else:
        assessment["axes"]["directness"] = 1

    with pytest.raises(JsonSchemaValidationError):
        validator.validate(wire)


def test_map_instructions_explain_native_object_and_server_array_conversion():
    _, _, _, _, contract, _ = bound_case()
    instructions = contract.instructions("기존 MAP 지시")
    assert instructions.startswith("기존 MAP 지시")
    assert "finding<ID>" in instructions
    assert "고정 findingId" in instructions
    assert "basisClaimIds" in instructions
    assert "입력 finding 순서대로 assessments 배열" in instructions


def test_generic_map_and_single_reduce_contracts_are_unchanged():
    request, _, _, _, _, _ = bound_case()
    schemas = [
        ReportInsightMapOutput.model_json_schema(by_alias=True),
        report_insight_schema(request),
        report_insight_reduce_schema(request, {"CHIP_MAKER": ("501:0", "502:0")}),
    ]
    raw = '{"unchanged": true}'
    for schema in schemas:
        before = deepcopy(schema)
        contract = output_contract(schema)
        assert contract.report_insight_keys == ()
        assert contract.schema == before
        assert schema == before
        assert contract.public_text(raw) == raw
        assert contract.instructions("기존 지시") == "기존 지시"
