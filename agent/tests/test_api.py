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


def test_health_does_not_require_agent_token() -> None:
    response = client.get("/v1/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok", "mock": True}


def test_analyze_requires_agent_token() -> None:
    response = client.post("/v1/analyze", json=request_body())

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


def test_validation_failure_uses_json_error_contract() -> None:
    invalid = request_body()
    invalid["plan"] = "UNKNOWN"

    response = client.post(
        "/v1/analyze",
        headers={"X-Agent-Token": "local-dev-agent-token"},
        json=invalid,
    )

    assert response.status_code == 422
    assert response.json()["error"]["code"] == "SCHEMA_VIOLATION"


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
    )
    try:
        response = client.post(
            "/v1/analyze",
            headers={"X-Agent-Token": "local-dev-agent-token"},
            json=request_body(),
        )
    finally:
        app.dependency_overrides[get_settings] = lambda: Settings(
            AGENT_SHARED_SECRET="local-dev-agent-token"
        )

    assert response.status_code == 503
    assert response.json()["error"]["code"] == "API_KEY_MISSING"
