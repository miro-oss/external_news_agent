"""Strict provider schemas retain the existing local assessment reason bound."""

import json
from copy import deepcopy

import httpx2
import pytest
from jsonschema import Draft202012Validator
from pydantic_ai.profiles.openai import OpenAIJsonSchemaTransformer
from test_openai_provider import client_for, response_body
from test_report_insight_assessment import payload, request

from app.core.config import Settings
from app.llm.openai_contract import output_contract
from app.llm.openai_provider import OpenAIAnalyzeProvider
from app.llm.report_insight_assessment import draft_schema, draft_to_wire
from app.schemas.report_insight import CLAIMLESS_ASSESSMENT_REASON


@pytest.mark.parametrize(
    "reason,valid",
    [
        ("가", True),
        ("가" * 180, True),
        ("a" * 180, True),
        ("가" * 181, False),
        ("a" * 181, False),
        (("The facility supports production scheduling. " * 5)[:187], False),
        ("", False),
        ("   ", False),
        ("\n\t", False),
        ("공정 준비를 확인한다.\n영향 범위는 미확인이다.", True),
        ("가" + "\n" * 178 + "나", True),
        ("가" + "\n" * 179 + "나", False),
    ],
)
def test_sdk_transformed_assessment_schema_enforces_reason_length_and_nonblank_text(reason, valid):
    source = request()
    schema = draft_schema(source)
    original = deepcopy(schema)
    wire_schema = OpenAIJsonSchemaTransformer(output_contract(schema).schema, strict=True).walk()
    native = draft_to_wire(payload(source), source)
    native["assessments"]["CHIP_MAKER"]["finding101"]["reason"] = reason

    # Test the actual strict transformation: the SDK removes maxLength, so a
    # local Pydantic-bound-only test would miss the provider failure.
    wire_reason = wire_schema["properties"]["assessments"]["properties"]["CHIP_MAKER"][
        "properties"
    ]["finding101"]["properties"]["reason"]
    assert "maxLength" not in wire_reason
    assert Draft202012Validator(wire_schema).is_valid(native) is valid
    assert schema == original


def test_wire_bound_changes_only_claimful_reasons_and_preserves_exact_claimless_branch():
    source = request(ids=(101, 102), audiences=("CHIP_MAKER", "IT_INFRA"))
    source.findings[1].claims = []
    schema = draft_schema(source)
    snapshot = deepcopy(schema)
    contract = output_contract(schema)

    for audience in source.audiences:
        records = contract.schema["properties"]["assessments"]["properties"][audience]["properties"]
        assert records["finding102"]["properties"]["reason"] == {
            "type": "string",
            "const": CLAIMLESS_ASSESSMENT_REASON,
        }
        records["finding101"]["properties"]["reason"].pop("pattern")
    assert contract.schema == snapshot
    assert schema == snapshot


def test_provider_sends_reason_bound_without_transforming_the_model_response():
    source = request()
    native = draft_to_wire(payload(source), source)
    response_text = json.dumps(native, ensure_ascii=False)
    calls = []

    def handler(http_request):
        data = json.loads(http_request.content)
        validator = Draft202012Validator(data["text"]["format"]["schema"])
        validator.validate(native)
        too_long = deepcopy(native)
        too_long["assessments"]["CHIP_MAKER"]["finding101"]["reason"] = "가" * 181
        assert not validator.is_valid(too_long)
        calls.append(data)
        return httpx2.Response(200, json=response_body(response_text))

    with client_for(handler) as client:
        response = OpenAIAnalyzeProvider(Settings(), client).generate(
            system_instruction="제공된 근거만 사용한다.",
            prompt="고정된 오프라인 요청",
            response_schema=draft_schema(source),
        )

    assert len(calls) == 1
    assert response.text == response_text
