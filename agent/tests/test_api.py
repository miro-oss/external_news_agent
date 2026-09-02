import pytest
from fastapi.testclient import TestClient

from app.core.config import Settings, get_settings
from app.llm.mock_provider import MockAnalyzeProvider
from app.main import app

app.dependency_overrides[get_settings] = lambda: Settings(
    AGENT_SHARED_SECRET="local-dev-agent-token"
)
client = TestClient(app)


def request_body(body_text: str = "첫 문장입니다. 두 번째 문장입니다.") -> dict[str, object]:
    return {
        "idempotencyKey": "run:42:article:10",
        "plan": "FREE",
        "article": {
            "id": 10,
            "title": "HBM4 양산 일정 단축",
            "canonicalUrl": "https://example.com/news/10",
            "language": "ko",
            "publishedAt": "2026-08-10T09:00:00+09:00",
            "bodyText": body_text,
        },
        "topic": {
            "name": "HBM",
            "queryText": "HBM",
            "requiredKeywords": ["HBM"],
            "optionalKeywords": [],
            "excludedKeywords": [],
        },
        "previousFinding": None,
    }


def report_request_body() -> dict[str, object]:
    return {
        "idempotencyKey": "run:42:report",
        "plan": "FREE",
        "run": {
            "id": 42,
            "startedAt": "2026-08-10T09:00:00+09:00",
            "finishedAt": "2026-08-10T09:03:00+09:00",
            "topics": ["HBM"],
        },
        "findings": [],
        "events": [],
        "sourceStats": {
            "collected": 2,
            "blocked": 1,
            "failed": 0,
            "paywalled": 1,
            "stubExcluded": 2,
        },
        "sourceNotes": ["수집 제약: STUB 분석 2건 제외, 페이월 1건."],
    }


def evidence_request_body() -> dict[str, object]:
    return {
        "idempotencyKey": "finding:999:verify",
        "claims": [
            {
                "claimId": "0:0",
                "claim": "HBM4 양산 일정이 앞당겨졌다.",
                "claimType": "FACT",
                "attributedTo": None,
                "sentences": [
                    {"id": 1, "text": "HBM4 양산 일정이 앞당겨졌다."},
                ],
            }
        ],
    }


def insight_request_body() -> dict[str, object]:
    return {
        "idempotencyKey": "insight:issue:77:chip-maker",
        "plan": "FREE",
        "audiences": ["CHIP_MAKER"],
        "target": {"type": "ISSUE", "id": 77},
        "topic": {"name": "CPO", "queryText": "CPO"},
        "findings": [
            {
                "id": 501,
                "articleTitle": "CPO 양산 일정",
                "canonicalUrl": "https://example.com/501",
                "summaryKo": "CPO 양산 일정을 다룬 기사입니다.",
                "sentences": [{"id": 1, "text": "A사가 CPO 양산 일정을 발표했다."}],
            }
        ],
    }


def test_health_does_not_require_agent_token() -> None:
    response = client.get("/v1/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok", "mock": True}


def test_analyze_requires_agent_token() -> None:
    response = client.post("/v1/analyze", json=request_body())

    assert response.status_code == 401
    assert response.json()["error"]["code"] == "UNAUTHORIZED"


def test_report_requires_agent_token() -> None:
    response = client.post("/v1/report", json=report_request_body())

    assert response.status_code == 401
    assert response.json()["error"]["code"] == "UNAUTHORIZED"


def test_verify_evidence_requires_agent_token() -> None:
    response = client.post("/v1/verify-evidence", json=evidence_request_body())

    assert response.status_code == 401
    assert response.json()["error"]["code"] == "UNAUTHORIZED"


def test_insight_requires_agent_token() -> None:
    response = client.post("/v1/insight", json=insight_request_body())

    assert response.status_code == 401
    assert response.json()["error"]["code"] == "UNAUTHORIZED"


def test_insight_returns_deterministic_mock_contract() -> None:
    response = client.post(
        "/v1/insight",
        headers={"X-Agent-Token": "local-dev-agent-token"},
        json=insight_request_body(),
    )

    assert response.status_code == 200
    payload = response.json()
    assert [item["audience"] for item in payload["insights"]] == ["CHIP_MAKER"]
    assert payload["insights"][0]["facts"][0]["claimType"] == "FACT"
    assert payload["insights"][0]["implications"][0]["falsifiedBy"]
    assert payload["meta"]["promptVersion"] == "insight.ko.v1+perspective.ko.v1"


def test_analyze_returns_deterministic_mock_contract() -> None:
    response = client.post(
        "/v1/analyze",
        headers={"X-Agent-Token": "local-dev-agent-token"},
        json=request_body(),
    )

    assert response.status_code == 200
    payload = response.json()
    assert payload["sentences"] == ["첫 문장입니다.", "두 번째 문장입니다."]
    assert payload["sections"][0]["bullets"][0]["evidenceSentenceIds"] == [1]
    assert payload["classification"]["sensitivity"]["customerMove"]["score"] == 1
    assert payload["classification"]["sensitivity"]["dealSignal"]["score"] is None
    assert payload["meta"]["provider"] == "mock"
    assert payload["meta"]["mock"] is True
    assert payload["crossSource"] == {
        "consensus": [],
        "soleSource": [],
        "conflicts": [],
        "missingStakeholders": [],
    }
    assert payload["promoteCandidates"] == []
    assert payload["memberStances"] == []
    assert [tag["audience"] for tag in payload["perspectiveTags"]] == [
        "CHIP_MAKER",
        "EQUIPMENT_MAKER",
        "MARKET_INVESTOR",
        "IT_INFRA",
    ]


def test_analyze_self_critique_flag_reuses_same_endpoint() -> None:
    body = request_body("A사는 투자를 발표했다.")
    body["idempotencyKey"] = "run:42:issue:88:self-critique"
    body["selfCritique"] = True
    body["previousFinding"] = {
        "summaryKo": "회사가 투자를 확정한 기사입니다.",
        "sensitivity": {
            "customerMove": {"score": 3, "evidenceSentenceIds": [1]},
            "dealSignal": {"score": None, "evidenceSentenceIds": []},
            "competitorThreat": {"score": 3, "evidenceSentenceIds": [1]},
            "industryShift": {"score": 3, "evidenceSentenceIds": [1]},
        },
        "sections": [
            {
                "heading": "핵심",
                "bullets": [
                    {
                        "text": "A사는 투자를 승인했다.",
                        "evidenceSentenceIds": [1],
                        "groundedness": "weak",
                        "confidence": 0.6,
                        "groundingReason": "표현 강도를 추가로 확인해야 합니다.",
                        "claimType": "FACT",
                        "attributedTo": None,
                    }
                ],
            }
        ],
        "crossSource": {
            "consensus": [],
            "soleSource": [],
            "conflicts": [],
            "missingStakeholders": [],
        },
    }

    response = client.post(
        "/v1/analyze",
        headers={"X-Agent-Token": "local-dev-agent-token"},
        json=body,
    )

    assert response.status_code == 200
    payload = response.json()
    assert payload["targetClaimCount"] == 1
    assert payload["revisedClaimCount"] == 0
    assert payload["meta"]["promptVersion"] == "self-critique.mock.v1"


def test_mock_analysis_compares_issue_members_and_promotes_at_most_one() -> None:
    body = request_body("A사는 투자 규모를 3조원으로 발표했다.")
    body["article"]["summary"] = "투자 규모는 3조원이다."
    body["issueMembers"] = [
        {
            "id": 11,
            "title": "A사 투자 규모 5조원",
            "summary": "투자 규모를 5조원으로 보도했다.",
            "publisher": "다른경제",
        }
    ]

    response = client.post(
        "/v1/analyze",
        headers={"X-Agent-Token": "local-dev-agent-token"},
        json=body,
    )

    assert response.status_code == 200
    payload = response.json()
    assert payload["crossSource"]["conflicts"][0]["articleIds"] == [10, 11]
    assert payload["promoteCandidates"] == [11]
    assert payload["memberStances"] == [{"articleId": 11, "stance": "DISPUTES", "confidence": 0.85}]


def test_rejects_more_than_ten_issue_members() -> None:
    body = request_body()
    body["issueMembers"] = [
        {
            "id": article_id,
            "title": f"비교 기사 {article_id}",
            "summary": None,
            "publisher": "테스트 매체",
        }
        for article_id in range(11, 22)
    ]

    response = client.post(
        "/v1/analyze",
        headers={"X-Agent-Token": "local-dev-agent-token"},
        json=body,
    )

    assert response.status_code == 422


def test_mock_analysis_respects_summary_and_bullet_length_limits() -> None:
    body = request_body()
    body["article"]["title"] = "가" * 200

    response = client.post(
        "/v1/analyze",
        headers={"X-Agent-Token": "local-dev-agent-token"},
        json=body,
    )

    assert response.status_code == 200
    payload = response.json()
    assert len(payload["summaryKo"]) == 120
    assert len(payload["sections"][0]["bullets"][0]["text"]) == 80


def test_report_returns_deterministic_mock_contract() -> None:
    response = client.post(
        "/v1/report",
        headers={"X-Agent-Token": "local-dev-agent-token"},
        json=report_request_body(),
    )

    assert response.status_code == 200
    payload = response.json()
    assert payload["title"] == "2026-08-10 HBM 뉴스 모니터링 보고서"
    assert payload["importantEvents"] == []
    assert payload["watchItems"] == []
    assert "STUB 분석 2건" in payload["sourceNotes"][0]
    assert "## 수집 및 출처 참고" in payload["markdownBody"]
    assert payload["meta"]["provider"] == "mock"


def test_verify_evidence_returns_deterministic_mock_contract() -> None:
    response = client.post(
        "/v1/verify-evidence",
        headers={"X-Agent-Token": "local-dev-agent-token"},
        json=evidence_request_body(),
    )

    assert response.status_code == 200
    payload = response.json()
    assert payload["results"] == [
        {
            "claimId": "0:0",
            "status": "grounded",
            "acceptedSentenceIds": [1],
            "reason": "주장의 핵심 표현과 사실값이 근거 문장에서 확인됩니다.",
        }
    ]
    assert payload["meta"]["promptVersion"] == "evidence.rules.v3"


def test_validation_failure_uses_json_error_contract() -> None:
    invalid = request_body()
    invalid["plan"] = "UNKNOWN"

    response = client.post(
        "/v1/analyze",
        headers={"X-Agent-Token": "local-dev-agent-token"},
        json=invalid,
    )

    assert response.status_code == 422
    payload = response.json()
    assert payload["error"]["code"] == "SCHEMA_VIOLATION"
    assert payload["error"]["details"][0]["loc"] == ["body", "plan"]
    assert "input" not in payload["error"]["details"][0]


def test_analyze_truncates_body_over_configured_limit() -> None:
    app.dependency_overrides[get_settings] = lambda: Settings(
        AGENT_SHARED_SECRET="local-dev-agent-token",
        AGENT_MAX_BODY_CHARS=3,
    )
    try:
        response = client.post(
            "/v1/analyze",
            headers={"X-Agent-Token": "local-dev-agent-token"},
            json=request_body("1234"),
        )
    finally:
        app.dependency_overrides[get_settings] = lambda: Settings(
            AGENT_SHARED_SECRET="local-dev-agent-token"
        )

    assert response.status_code == 200
    assert response.json()["sentences"] == ["123"]
    assert response.json()["meta"]["truncated"] is True


def test_unexpected_failure_uses_json_error_contract(monkeypatch: pytest.MonkeyPatch) -> None:
    def fail(*_: object, **__: object) -> None:
        raise RuntimeError("sensitive internal failure")

    monkeypatch.setattr(MockAnalyzeProvider, "analyze", fail)
    non_raising_client = TestClient(app, raise_server_exceptions=False)

    response = non_raising_client.post(
        "/v1/analyze",
        headers={"X-Agent-Token": "local-dev-agent-token"},
        json=request_body(),
    )

    assert response.status_code == 500
    assert response.json() == {
        "error": {
            "code": "INTERNAL_ERROR",
            "message": "Agent 내부 오류가 발생했습니다.",
            "details": None,
        }
    }
    assert "sensitive internal failure" not in response.text


def test_non_mock_mode_requires_selected_provider_configuration() -> None:
    app.dependency_overrides[get_settings] = lambda: Settings(
        AGENT_SHARED_SECRET="local-dev-agent-token",
        AGENT_MOCK=False,
        GEMINI_API_KEY="",
        GEMINI_MODEL="",
    )
    try:
        response = client.post(
            "/v1/analyze",
            headers={"X-Agent-Token": "local-dev-agent-token"},
            json=request_body(),
        )
    finally:
        app.dependency_overrides[get_settings] = lambda: Settings(
            AGENT_SHARED_SECRET="local-dev-agent-token",
            GEMINI_API_KEY="",
            GEMINI_MODEL="",
        )

    assert response.status_code == 503
    assert response.json()["error"]["code"] == "API_KEY_MISSING"
