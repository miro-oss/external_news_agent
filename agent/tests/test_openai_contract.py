import json
from copy import deepcopy
from decimal import Decimal

import httpx2
import pytest
from fastapi.testclient import TestClient
from jsonschema import Draft202012Validator, ValidationError
from pydantic_ai.profiles.openai import OpenAIJsonSchemaTransformer
from test_analyze_service import request as analyze_request
from test_analyze_service import valid_output
from test_explore_service import request as explore_request
from test_openai_provider import client_for, response_body

from app.core.config import Settings, get_settings
from app.core.errors import AgentError
from app.llm.analyze_service import ArticleAnalyzeService
from app.llm.explore_service import ExploreService
from app.llm.openai_contract import ANALYZE_WIRE_VERSION, output_contract
from app.llm.openai_provider import OpenAIAnalyzeProvider
from app.main import create_app
from app.schemas.analyze import AnalyzeOutput
from app.schemas.explore import ExploreProposal


def wire_analysis():
    payload = json.loads(valid_output())
    payload["perspectiveTags"] = {
        tag["audience"]: {key: value for key, value in tag.items() if key != "audience"}
        for tag in payload["perspectiveTags"]
    }
    return payload


def validator():
    contract = output_contract(AnalyzeOutput.model_json_schema(by_alias=True))
    return Draft202012Validator(OpenAIJsonSchemaTransformer(contract.schema, strict=True).walk())


@pytest.mark.parametrize(
    "axis_name", ["customerMove", "dealSignal", "competitorThreat", "industryShift"]
)
@pytest.mark.parametrize("score", [0, 3])
def test_any_one_axis_can_be_available_but_needs_actual_evidence(axis_name, score):
    payload = wire_analysis()
    axes = payload["classification"]["sensitivity"]
    for axis in axes.values():
        axis.update(score=None, evidenceSentenceIds=[])
    axes[axis_name].update(score=score, evidenceSentenceIds=[1])
    validator().validate(payload)
    public = output_contract(AnalyzeOutput.model_json_schema(by_alias=True)).public_text(
        json.dumps(payload)
    )
    AnalyzeOutput.model_validate_json(public)


@pytest.mark.parametrize(
    "invalid_case",
    [
        "all-unavailable",
        "zero-without-evidence",
        "unavailable-with-evidence",
        "relevant-without-evidence",
        "relevant-without-hook",
        "none-with-hook",
        "none-with-evidence",
        "missing-audience",
        "unknown-audience",
        "duplicate-audiences",
    ],
)
def test_wire_schema_rejects_the_reported_contract_failures(invalid_case):
    payload = wire_analysis()
    axes = payload["classification"]["sensitivity"]
    tags = payload["perspectiveTags"]
    if invalid_case == "all-unavailable":
        for axis in axes.values():
            axis.update(score=None, evidenceSentenceIds=[])
    elif invalid_case == "zero-without-evidence":
        axes["customerMove"].update(score=0, evidenceSentenceIds=[])
    elif invalid_case == "unavailable-with-evidence":
        axes["customerMove"]["score"] = None
    elif invalid_case == "relevant-without-evidence":
        tags["CHIP_MAKER"]["evidenceSentenceIds"] = []
    elif invalid_case == "relevant-without-hook":
        tags["CHIP_MAKER"]["hook"] = None
    elif invalid_case == "none-with-hook":
        tags["IT_INFRA"]["hook"] = "unsupported"
    elif invalid_case == "none-with-evidence":
        tags["IT_INFRA"]["evidenceSentenceIds"] = [1]
    elif invalid_case == "missing-audience":
        del tags["IT_INFRA"]
    elif invalid_case == "unknown-audience":
        tags["UNKNOWN"] = tags.pop("IT_INFRA")
    else:
        payload["perspectiveTags"] = [json.loads(valid_output())["perspectiveTags"][0]] * 4
    with pytest.raises(ValidationError):
        validator().validate(payload)


@pytest.mark.parametrize(
    "proposal",
    [
        {
            "action": "SEARCH_MORE",
            "sourceKey": "NAVER",
            "query": "공급 협상",
            "reason": "입장 확인",
        },
        {"action": "READ_FULLTEXT", "articleId": 1024, "reason": "본문 확인"},
        {"action": "COMPARE_HISTORY", "entities": ["A사"], "days": 30, "reason": "이력 확인"},
        {"action": "CONCLUDE", "reason": "조사 완료"},
    ],
)
def test_explore_uses_an_object_root_and_returns_the_original_public_proposal(proposal):
    requests = []

    def handler(request):
        payload = json.loads(request.content)
        schema = payload["text"]["format"]["schema"]
        assert schema["type"] == "object" and "anyOf" not in schema
        wire = {"result": proposal}
        Draft202012Validator(schema).validate(wire)
        requests.append(payload)
        return httpx2.Response(200, json=response_body(json.dumps(wire)))

    with client_for(handler) as client:
        response = ExploreService(
            Settings(AGENT_MOCK=False), OpenAIAnalyzeProvider(Settings(), client)
        ).propose(explore_request())
    assert response.proposal.model_dump(by_alias=True) == proposal
    assert len(requests) == 1
    assert response.meta.input_tokens == 1000
    assert response.meta.cost_usd == 0.000125
    assert response.meta.prompt_version == "explore.ko.v2"


def test_analysis_wire_conversion_preserves_evidence_and_public_contract():
    original_schema = AnalyzeOutput.model_json_schema(by_alias=True)
    before = deepcopy(original_schema)
    wire = wire_analysis()

    def handler(request):
        payload = json.loads(request.content)
        Draft202012Validator(payload["text"]["format"]["schema"]).validate(wire)
        assert "목록 번호" in payload["instructions"]
        return httpx2.Response(200, json=response_body(json.dumps(wire)))

    with client_for(handler) as client:
        response = ArticleAnalyzeService(
            Settings(), OpenAIAnalyzeProvider(Settings(), client)
        ).analyze(analyze_request())
    assert response.meta.prompt_version == ANALYZE_WIRE_VERSION
    assert response.meta.input_tokens == 1000
    assert (
        response.classification.model_dump(by_alias=True)
        == json.loads(valid_output())["classification"]
    )
    assert [tag.model_dump(by_alias=True) for tag in response.perspective_tags] == json.loads(
        valid_output()
    )["perspectiveTags"]
    assert response.sections[0].bullets[0].evidence_sentence_ids == [1]
    assert original_schema == before


@pytest.mark.parametrize("violation", ["out-of-range", "duplicate-evidence", "three-high"])
def test_public_validation_still_repairs_and_rejects_semantic_errors(violation):
    wire = wire_analysis()
    if violation == "out-of-range":
        wire["classification"]["sensitivity"]["customerMove"]["evidenceSentenceIds"] = [999]
    elif violation == "duplicate-evidence":
        wire["classification"]["sensitivity"]["customerMove"]["evidenceSentenceIds"] = [1, 1]
    else:
        for name in ("CHIP_MAKER", "EQUIPMENT_MAKER", "IT_INFRA"):
            wire["perspectiveTags"][name] = {
                "relevance": "high",
                "hook": "원문 근거",
                "evidenceSentenceIds": [1],
            }
    calls = []

    def handler(request):
        calls.append(request)
        return httpx2.Response(200, json=response_body(json.dumps(wire)))

    with client_for(handler) as client:
        service = ArticleAnalyzeService(Settings(), OpenAIAnalyzeProvider(Settings(), client))
        with pytest.raises(AgentError) as error:
            service.analyze(analyze_request())
    assert error.value.status_code == 502
    assert error.value.code == "SCHEMA_VIOLATION"
    assert len(calls) == 2


def test_repair_after_wire_conversion_accumulates_usage():
    calls = []

    def handler(request):
        calls.append(request)
        return httpx2.Response(
            200,
            json=response_body('{"partial":' if len(calls) == 1 else json.dumps(wire_analysis())),
        )

    with client_for(handler) as client:
        response = ArticleAnalyzeService(
            Settings(), OpenAIAnalyzeProvider(Settings(), client)
        ).analyze(analyze_request())
    assert len(calls) == 2
    assert response.meta.input_tokens == 2000
    assert response.meta.output_tokens == 200
    assert Decimal(str(response.meta.cost_usd)) == Decimal("0.00025")


def test_wrapping_keeps_refs_and_does_not_mutate_schema_or_hide_extra_fields():
    schema = ExploreProposal.model_json_schema(by_alias=True)
    before = deepcopy(schema)
    contract = output_contract(schema)
    assert schema == before
    assert contract.schema["$defs"]
    bad = '{"result":{"action":"CONCLUDE","reason":"done"},"extra":true}'
    assert contract.public_text(bad) == bad
    assert contract.public_text('{"partial":') == '{"partial":'


@pytest.mark.parametrize("endpoint", ["analyze", "explore"])
def test_http_routes_with_openai_transport_keep_the_public_response(endpoint, monkeypatch):
    wire = (
        wire_analysis()
        if endpoint == "analyze"
        else {
            "result": {"action": "CONCLUDE", "reason": "현재 근거로 조사 완료"},
        }
    )

    def handler(request):
        schema = json.loads(request.content)["text"]["format"]["schema"]
        if schema.get("type") != "object" or "anyOf" in schema:
            return httpx2.Response(400, json={"error": {"code": "invalid_json_schema"}})
        Draft202012Validator(schema).validate(wire)
        return httpx2.Response(200, json=response_body(json.dumps(wire)))

    with client_for(handler) as upstream:
        provider = OpenAIAnalyzeProvider(Settings(), upstream)
        monkeypatch.setattr(
            f"app.llm.{endpoint}_service.get_analyze_provider", lambda *_args, **_kwargs: provider
        )
        application = create_app()
        application.dependency_overrides[get_settings] = lambda: Settings(
            AGENT_MOCK=False,
            AGENT_SHARED_SECRET="test-secret",
        )
        request = analyze_request() if endpoint == "analyze" else explore_request()
        with TestClient(application) as client:
            response = client.post(
                f"/v1/{endpoint}",
                json=request.model_dump(by_alias=True, mode="json"),
                headers={"X-Agent-Token": "test-secret"},
            )
    assert response.status_code == 200, response.text
    assert response.json()["meta"]["provider"] == "openai"
    if endpoint == "analyze":
        assert len(response.json()["perspectiveTags"]) == 4
    else:
        assert response.json()["proposal"] == wire["result"]
