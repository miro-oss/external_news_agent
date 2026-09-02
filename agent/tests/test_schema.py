import pytest
from pydantic import ValidationError

from app.schemas.analyze import (
    AnalyzeOutput,
    AnalyzeRequest,
    AnalyzeResponse,
    SelfCritiqueResponse,
)


def response(evidence_sentence_ids: list[int]) -> dict[str, object]:
    return {
        "sentences": ["근거 문장."],
        "sections": [
            {
                "heading": "핵심",
                "bullets": [
                    {
                        "text": "핵심 주장",
                        "evidenceSentenceIds": evidence_sentence_ids,
                        "groundedness": "grounded",
                        "confidence": 1,
                        "claimType": "FACT",
                        "attributedTo": None,
                    }
                ],
            }
        ],
        "summaryKo": "핵심 내용을 정리한 요약입니다.",
        "classification": {
            "intent": "산업 동향 보도",
            "sentiment": "neutral",
            "sensitivity": {
                "customerMove": {"score": 1, "evidenceSentenceIds": [1]},
                "dealSignal": {"score": None, "evidenceSentenceIds": []},
                "competitorThreat": {"score": 0, "evidenceSentenceIds": [1]},
                "industryShift": {"score": 0, "evidenceSentenceIds": [1]},
            },
            "relevance": "reference",
            "category": "제품/공정",
        },
        "entities": {"companies": [], "products": [], "technologies": []},
        "perspectiveTags": [
            {
                "audience": "CHIP_MAKER",
                "relevance": "low",
                "hook": "핵심 주장",
                "evidenceSentenceIds": [1],
            },
            {
                "audience": "EQUIPMENT_MAKER",
                "relevance": "none",
                "hook": None,
                "evidenceSentenceIds": [],
            },
            {
                "audience": "MARKET_INVESTOR",
                "relevance": "none",
                "hook": None,
                "evidenceSentenceIds": [],
            },
            {"audience": "IT_INFRA", "relevance": "none", "hook": None, "evidenceSentenceIds": []},
        ],
        "meta": {
            "provider": "mock",
            "model": "mock",
            "promptVersion": "analyze.mock.v1",
            "inputTokens": 0,
            "outputTokens": 0,
            "costUsd": 0,
            "credits": 0,
            "mock": True,
            "truncated": False,
        },
    }


@pytest.mark.parametrize("evidence_ids", [[], [0], [2]])
def test_rejects_evidence_ids_outside_one_based_sentence_range(
    evidence_ids: list[int],
) -> None:
    with pytest.raises(ValidationError):
        AnalyzeResponse.model_validate(response(evidence_ids))


@pytest.mark.parametrize(
    "mutate",
    [
        lambda tags: tags.pop(),
        lambda tags: tags.__setitem__(1, tags[0].copy()),
        lambda tags: tags[0].update({"relevance": "none"}),
        lambda tags: tags[0].update({"evidenceSentenceIds": [2]}),
    ],
)
def test_rejects_invalid_perspective_tag_contract(mutate) -> None:
    payload = response([1])
    mutate(payload["perspectiveTags"])

    with pytest.raises(ValidationError):
        AnalyzeResponse.model_validate(payload)


def test_provider_schema_requires_nullable_perspective_hook() -> None:
    schema = AnalyzeOutput.model_json_schema(by_alias=True)
    perspective_tag = schema["$defs"]["PerspectiveTag"]

    assert "hook" in perspective_tag["required"]
    assert {option.get("type") for option in perspective_tag["properties"]["hook"]["anyOf"]} == {
        "string",
        "null",
    }


@pytest.mark.parametrize("summary", ["짧은 요약", "가" * 121])
def test_rejects_analysis_summary_outside_readable_length(summary: str) -> None:
    payload = response([1])
    payload["summaryKo"] = summary

    with pytest.raises(ValidationError):
        AnalyzeResponse.model_validate(payload)


def test_rejects_more_than_three_bullets_or_eighty_character_bullet() -> None:
    payload = response([1])
    bullet = payload["sections"][0]["bullets"][0]
    payload["sections"][0]["bullets"] = [bullet.copy() for _ in range(4)]
    with pytest.raises(ValidationError):
        AnalyzeResponse.model_validate(payload)

    payload = response([1])
    payload["sections"][0]["bullets"][0]["text"] = "가" * 81
    with pytest.raises(ValidationError):
        AnalyzeResponse.model_validate(payload)


@pytest.mark.parametrize(
    "mutate",
    [
        lambda axis: axis.update({"score": 4}),
        lambda axis: axis.update({"score": 2, "evidenceSentenceIds": []}),
        lambda axis: axis.update({"score": None, "evidenceSentenceIds": [1]}),
    ],
)
def test_rejects_invalid_sensitivity_axis_contract(mutate) -> None:
    payload = response([1])
    mutate(payload["classification"]["sensitivity"]["customerMove"])

    with pytest.raises(ValidationError):
        AnalyzeResponse.model_validate(payload)


def test_rejects_sensitivity_when_every_axis_is_unavailable() -> None:
    payload = response([1])
    for axis in payload["classification"]["sensitivity"].values():
        axis.update({"score": None, "evidenceSentenceIds": []})

    with pytest.raises(ValidationError):
        AnalyzeResponse.model_validate(payload)


def test_rejects_sensitivity_evidence_outside_sentence_range() -> None:
    payload = response([1])
    payload["classification"]["sensitivity"]["customerMove"]["evidenceSentenceIds"] = [2]

    with pytest.raises(ValidationError):
        AnalyzeResponse.model_validate(payload)


def test_self_critique_requires_typed_previous_finding() -> None:
    with pytest.raises(ValidationError):
        AnalyzeRequest.model_validate(
            {
                "idempotencyKey": "run:42:issue:88:self-critique",
                "plan": "FREE",
                "article": {
                    "id": 10,
                    "title": "기사",
                    "canonicalUrl": "https://example.com/10",
                },
                "topic": {"name": "반도체"},
                "selfCritique": True,
            }
        )


def test_self_critique_revised_count_cannot_exceed_target_count() -> None:
    with pytest.raises(ValidationError):
        SelfCritiqueResponse.model_validate(
            {
                "sections": [
                    {
                        "heading": "핵심",
                        "bullets": [
                            {
                                "text": "검토된 주장",
                                "evidenceSentenceIds": [1],
                                "groundedness": "grounded",
                                "confidence": 1,
                                "groundingReason": "원문에서 직접 확인됩니다.",
                                "claimType": "FACT",
                                "attributedTo": None,
                            }
                        ],
                    }
                ],
                "summaryKo": "최초 검증을 마친 한국어 요약입니다.",
                "targetClaimCount": 0,
                "revisedClaimCount": 1,
                "unsupportedExpressions": [],
                "meta": {
                    "provider": "mock",
                    "model": "mock",
                    "promptVersion": "self-critique.mock.v1",
                    "inputTokens": 0,
                    "outputTokens": 0,
                    "costUsd": 0,
                    "credits": 0,
                    "mock": True,
                    "truncated": False,
                },
            }
        )
