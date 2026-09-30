"""Offline stage/source contracts; these do not measure a model's editorial quality."""

import json
from copy import deepcopy

import pytest

from app.core.config import Settings
from app.llm.base import ProviderResponse, ProviderUsage
from app.llm.report_insight_service import (
    PROMPT_VERSION,
    RUBRIC_VERSION,
    ReportInsightService,
    importance_grade,
)
from app.schemas.report_insight import ReportInsightRequest


def source_request():
    sources = [
        (701, "A사는 공정 검증 준비 계획을 발표했다.", "FORECAST", "2026-09-22"),
        (702, "B사는 A사와 검증 장비 공급 계약을 체결했다고 발표했다.", "FACT", "2026-09-25"),
    ]
    return {
        "idempotencyKey": "offline:stage-source-contract",
        "plan": "FREE",
        "audiences": ["EQUIPMENT_MAKER"],
        "report": {
            "id": 91,
            "title": "제목에 있는 미확인 생산능력은 사실 근거가 아니다",
            "reportScope": "WEEKLY",
            "reportDate": "2026-09-21",
            "reportEndDate": "2026-09-30",
        },
        "findings": [
            {
                "id": finding_id,
                "articleId": finding_id + 100,
                "articleTitle": "제목에 있는 미확인 장비 납품은 사실 근거가 아니다",
                "canonicalUrl": f"https://example.invalid/stage-contract/{finding_id}",
                "publishedAt": published_at,
                "topicName": "공정 검증",
                "claims": [
                    {
                        "id": f"{finding_id}:0",
                        "text": text,
                        "claimType": claim_type,
                        "attributedTo": None,
                        "evidenceSentenceIds": [0],
                    }
                ],
                "sentences": [{"index": 0, "text": text}],
            }
            for finding_id, text, claim_type, published_at in sources
        ],
    }


def source_candidate():
    return {
        "insights": [
            {
                "audience": "EQUIPMENT_MAKER",
                "headline": "검증 준비와 계약 이행 조건의 연결을 확인할 필요가 있다.",
                "overview": [
                    {
                        "text": (
                            "검증 준비 계획과 공급 계약이 연결된다면 이행 조건을 확인해야 한다."
                        ),
                        "basisClaimIds": ["701:0", "702:0"],
                        "assumption": "공급 계약이 발표된 공정 검증에 사용되는 경우",
                    }
                ],
                "assessments": [
                    {
                        "findingId": finding_id,
                        "reason": reason,
                        "basisClaimIds": [f"{finding_id}:0"],
                        "axes": {
                            "directness": directness,
                            "impact": None,
                            "urgency": None,
                            "novelty": None,
                        },
                    }
                    for finding_id, directness, reason in (
                        (
                            701,
                            2,
                            "공정 검증 준비와 연결되나 영향 범위와 준비 시점 정보가 부족하다.",
                        ),
                        (
                            702,
                            3,
                            "공급 계약 이행과 연결되나 영향 범위와 이행 시점 정보가 부족하다.",
                        ),
                    )
                ],
                "implications": [],
                "watchItems": [
                    {
                        "topic": "검증 장비 계약 이행 조건",
                        "indicator": "검증 대상과 공급 계약의 이행 상태",
                        "trigger": "공정 검증에 필요한 계약 이행이 확인되면 준비 판단을 갱신한다.",
                        "basisClaimIds": ["701:0", "702:0"],
                    }
                ],
            }
        ]
    }


class StageSourceProvider:
    def __init__(self, candidate=None):
        self.candidate = candidate or source_candidate()
        self.calls = []

    def generate(self, **kwargs):
        self.calls.append(kwargs)
        payload = deepcopy(self.candidate)
        if kwargs["response_schema"]["title"] == "ReportInsightMapOutput":
            payload = {
                "insights": [
                    {"audience": item["audience"], "assessments": item["assessments"]}
                    for item in payload["insights"]
                ]
            }
        else:
            for item in payload["insights"]:
                item.pop("assessments")
        return ProviderResponse(
            text=json.dumps(payload, ensure_ascii=False),
            provider="openai",
            model="offline-contract-fixture",
            usage=ProviderUsage(),
        )


def generate(provider, body):
    return ReportInsightService(Settings(AGENT_MOCK=False, _env_file=None), provider).generate(
        ReportInsightRequest.model_validate(body)
    )


def input_payload(call):
    text = call["prompt"].split("<report-insight-input>\n", 1)[1]
    return json.loads(text.split("\n</report-insight-input>", 1)[0])


def test_runtime_versions_distinguish_v3_from_existing_measurements():
    provider = StageSourceProvider()
    result = generate(provider, source_request())
    assert PROMPT_VERSION == "report-insight.ko.v3"
    assert RUBRIC_VERSION == "report-importance.v2"
    assert result.meta.prompt_version == PROMPT_VERSION
    assert provider.calls[0]["system_instruction"] != provider.calls[1]["system_instruction"]


@pytest.mark.parametrize(
    ("report_date", "report_end_date", "published_dates", "expected"),
    [
        ("2026-09-21", "2026-09-30", ["2026-09-22", "2026-09-25"], "2026-09-30"),
        ("2026-09-21", None, ["2026-09-22", "2026-09-25"], "2026-09-21"),
        (None, None, ["2026-09-22", "2026-09-25"], "2026-09-25"),
        (None, None, [None, None], None),
    ],
)
def test_both_stages_receive_same_server_selected_report_date(
    report_date, report_end_date, published_dates, expected
):
    body = source_request()
    body["report"].update(reportDate=report_date, reportEndDate=report_end_date)
    for finding, published_at in zip(body["findings"], published_dates, strict=True):
        finding["publishedAt"] = published_at
    provider = StageSourceProvider()
    generate(provider, body)
    assert len(provider.calls) == 2
    assert [input_payload(call)["reportReferenceDate"] for call in provider.calls] == [
        expected,
        expected,
    ]


def test_reduce_receives_original_claims_and_sentences_in_its_actual_source_shape():
    body = source_request()
    provider = StageSourceProvider()
    generate(provider, body)
    mapped = input_payload(provider.calls[0])
    reduced = input_payload(provider.calls[1])
    assert mapped["findings"] == body["findings"]
    assert "findings" not in reduced
    assert reduced["retrievedEvidence"][0]["audience"] == "EQUIPMENT_MAKER"
    evidence = {item["claimId"]: item for item in reduced["retrievedEvidence"][0]["evidence"]}
    for finding in body["findings"]:
        original = finding["claims"][0]
        transported = evidence[original["id"]]
        assert transported["text"] == original["text"]
        assert transported["claimType"] == original["claimType"]
        assert transported["sentences"] == finding["sentences"]
        assert transported["publishedAt"] == finding["publishedAt"]
    assert "annotations" not in reduced
    assert "reviewNote" not in reduced


def test_unknown_effect_size_does_not_remove_known_related_sources_or_synthesis():
    provider = StageSourceProvider()
    result = generate(provider, source_request())
    assert len(provider.calls) == 2
    insight = result.insights[0]
    assert all(importance_grade(item.axes) == "unavailable" for item in insight.assessments)
    assert insight.overview[0].basis_claim_ids == ["701:0", "702:0"]
    assert insight.implications == []
    assert insight.watch_items[0].basis_claim_ids == ["701:0", "702:0"]
    assert [item.model_dump(by_alias=True) for item in insight.assessments] == source_candidate()[
        "insights"
    ][0]["assessments"]


def test_unrelated_audience_still_skips_reduce_without_fabricating_insights():
    body = source_request()
    body["audiences"] = ["IT_INFRA"]
    candidate = source_candidate()
    insight = candidate["insights"][0]
    insight["audience"] = "IT_INFRA"
    for assessment in insight["assessments"]:
        assessment["axes"]["directness"] = 0
        assessment["reason"] = "공정 검증과 장비 계약이며 시스템 조달·운영 연결 근거가 부족하다."
    provider = StageSourceProvider(candidate)
    result = generate(provider, body)
    assert len(provider.calls) == 1
    assert result.insights[0].overview == []
    assert result.insights[0].implications == []
    assert result.insights[0].watch_items == []
