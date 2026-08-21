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
        "perspective": "TECHNOLOGY",
    }


def evidence_request_body() -> dict[str, object]:
    return {
        "idempotencyKey": "finding:999:verify",
        "claim": "HBM4 양산 일정이 앞당겨졌다.",
        "sentences": [
            {"id": 1, "text": "HBM4 양산 일정이 앞당겨졌다."},
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
    assert payload["classification"]["riskLevel"] == "low"
    assert payload["meta"]["provider"] == "mock"
    assert payload["meta"]["mock"] is True


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
    assert payload["status"] == "grounded"
    assert payload["acceptedSentenceIds"] == [1]
    assert payload["meta"]["promptVersion"] == "evidence.rules.v1"


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
