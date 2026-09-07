import json
from copy import deepcopy

import httpx2
import pytest
from jsonschema import Draft202012Validator, ValidationError
from pydantic import ValidationError as ModelValidationError
from pydantic_ai.profiles.openai import OpenAIJsonSchemaTransformer
from test_analyze_service import request
from test_openai_contract import wire_analysis
from test_openai_provider import client_for, response_body

from app.core.config import Settings
from app.llm.analyze_service import ArticleAnalyzeService
from app.llm.openai_contract import ANALYZE_WIRE_VERSION, output_contract
from app.llm.openai_provider import OpenAIAnalyzeProvider
from app.llm.request_contract import analysis_schema
from app.schemas.analyze import AnalyzeOutput, IssueMemberInput


def issue_request():
    value = request()
    value.issue_members = [
        IssueMemberInput(
            id=11, title="HBM4 production starts in 2027", summary="The schedule is 2027."
        ),
        IssueMemberInput(id=22, title="HBM4 packaging report"),
    ]
    return value


def promotion_wire():
    wire = wire_analysis()
    wire.pop("promoteCandidates")
    wire["promotionConflict"] = {
        "articleId": 11,
        "otherArticleIds": [10],
        "text": "양산 일정이 현재와 2027년으로 서로 다르다.",
    }
    return wire


def contract():
    return output_contract(analysis_schema(issue_request(), 2, {11}))


@pytest.mark.parametrize(
    "case",
    [
        "standalone",
        "multiple",
        "empty-peers",
        "self-peer",
        "unknown-peer",
        "ineligible",
        "representative",
        "blank",
    ],
)
def test_schema_requires_one_eligible_member_and_its_conflict(case):
    validator = Draft202012Validator(
        OpenAIJsonSchemaTransformer(contract().schema, strict=True).walk()
    )
    wire = promotion_wire()
    validator.validate(wire)
    value = wire["promotionConflict"]
    if case == "standalone":
        wire["promotionConflict"] = None
        wire["promoteCandidates"] = [11]
    elif case == "multiple":
        wire["promotionConflict"] = [value, deepcopy(value)]
    elif case in ("empty-peers", "self-peer", "unknown-peer"):
        value["otherArticleIds"] = {"empty-peers": [], "self-peer": [11], "unknown-peer": [999]}[
            case
        ]
    elif case in ("ineligible", "representative"):
        value["articleId"] = 22 if case == "ineligible" else 10
    else:
        value["text"] = " "
    with pytest.raises(ValidationError):
        validator.validate(wire)


def test_public_conversion_keeps_existing_observations_and_promotion_membership():
    wire = promotion_wire()
    original = {"articleIds": [10, 22], "text": "포장 일정에 관한 별도의 차이가 있다."}
    wire["crossSource"]["conflicts"].append(original)
    output = AnalyzeOutput.model_validate_json(contract().public_text(json.dumps(wire)))
    assert output.promote_candidates == [11]
    assert output.cross_source.conflicts[0].model_dump(by_alias=True) == original
    assert output.cross_source.conflicts[1].article_ids == [11, 10]
    assert output.cross_source.conflicts[1].text == wire["promotionConflict"]["text"]


@pytest.mark.parametrize(
    "case", ["missing-text", "empty-peers", "boolean-id", "duplicate-peers", "both-formats"]
)
def test_malformed_promotions_are_not_repaired_by_inventing_or_dropping_evidence(case):
    wire = promotion_wire()
    value = wire["promotionConflict"]
    if case == "missing-text":
        value.pop("text")
    elif case == "empty-peers":
        value["otherArticleIds"] = []
    elif case == "boolean-id":
        value["articleId"] = True
    elif case == "duplicate-peers":
        value["otherArticleIds"] = [10, 10]
    else:
        wire["promoteCandidates"] = []
    with pytest.raises(ModelValidationError):
        AnalyzeOutput.model_validate_json(contract().public_text(json.dumps(wire)))


def test_no_eligible_member_requires_null_and_paid_schema_stays_public():
    value = issue_request()
    schema = analysis_schema(value, 2, set())
    constrained = output_contract(schema)
    validator = Draft202012Validator(
        OpenAIJsonSchemaTransformer(constrained.schema, strict=True).walk()
    )
    wire = promotion_wire()
    with pytest.raises(ValidationError):
        validator.validate(wire)
    wire["promotionConflict"] = None
    validator.validate(wire)
    assert (
        AnalyzeOutput.model_validate_json(
            constrained.public_text(json.dumps(wire))
        ).promote_candidates
        == []
    )
    value.plan = "PAID"
    assert analysis_schema(value, 2, {11}) == AnalyzeOutput.model_json_schema(by_alias=True)


def test_service_preserves_conflict_and_usage_without_a_repair_call():
    calls = []
    wire = promotion_wire()

    def handler(http_request):
        payload = json.loads(http_request.content)
        Draft202012Validator(payload["text"]["format"]["schema"]).validate(wire)
        calls.append(payload)
        return httpx2.Response(200, json=response_body(json.dumps(wire)))

    with client_for(handler) as client:
        settings = Settings(AGENT_MOCK=False)
        response = ArticleAnalyzeService(settings, OpenAIAnalyzeProvider(settings, client)).analyze(
            issue_request()
        )
    assert len(calls) == 1
    assert response.promote_candidates == [11]
    assert response.cross_source.conflicts[0].article_ids == [11, 10]
    assert response.meta.prompt_version == ANALYZE_WIRE_VERSION
    assert response.meta.input_tokens == 1000
    assert response.meta.cost_usd == 0.000125
