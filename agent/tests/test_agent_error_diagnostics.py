import asyncio
import json
import logging
from types import SimpleNamespace

import pytest
from fastapi import Request
from fastapi.testclient import TestClient

from app.core.config import Settings, get_settings
from app.core.errors import AgentError
from app.core.security import require_agent_token
from app.main import create_app


def diagnostic_records(caplog):
    return [record for record in caplog.records if record.name == "app.main"]


def handle_error(error, *, route="/v1/topic-relevance", receive=None):
    application = create_app()
    request = Request(
        {
            "type": "http",
            "method": "POST",
            "path": "/private-path-marker",
            "query_string": b"token=private-query-marker",
            "headers": [(b"x-agent-token", b"private-header-marker")],
            "route": SimpleNamespace(path=route),
            "state": {"trace_id": "private-trace-marker"},
        },
        receive=receive,
    )
    return asyncio.run(application.exception_handlers[AgentError](request, error))


@pytest.mark.parametrize("reason", [
    "concurrencyLimited", "circuitOpen", "requestDeadlineExceeded",
    "pipelineCancelled", "rateLimited", "requestNotStarted",
])
def test_503_records_known_reasons_and_preserves_the_error_response(reason, caplog):
    error = AgentError(503, "PROVIDER_UNAVAILABLE", "original error", {reason: True})
    response = handle_error(error)

    assert response.status_code == 503
    assert json.loads(response.body) == {
        "error": {"code": error.code, "message": error.message, "details": error.details},
    }
    record, = diagnostic_records(caplog)
    assert record.agent_endpoint == "/v1/topic-relevance"
    assert record.agent_status == 503
    assert record.agent_error_code == "PROVIDER_UNAVAILABLE"
    assert record.agent_failure_reasons == {reason: True}


def test_503_redacts_every_untrusted_surface_without_reading_the_body(caplog):
    async def forbidden_receive():
        raise AssertionError("diagnostics must not read request bodies")

    error = AgentError(503, "private-code-marker", "private-message-marker", {
        "concurrencyLimited": True,
        "requestNotStarted": False,
        "article": "private-article-marker",
        "prompt": "private-prompt-marker",
        "traceId": "private-detail-trace-marker",
        "usage": {"inputTokens": 123456789},
        "private-key-marker": True,
    })
    error.__cause__ = RuntimeError("private-cause-marker")
    handle_error(error, route="/private-route-marker", receive=forbidden_receive)

    record, = diagnostic_records(caplog)
    assert record.agent_endpoint == "UNKNOWN"
    assert record.agent_error_code == "UNKNOWN"
    assert record.agent_failure_reasons == {
        "concurrencyLimited": True, "requestNotStarted": False,
    }
    assert record.exc_info is None
    assert "private-" not in caplog.text + repr(record.__dict__)
    assert "123456789" not in caplog.text + repr(record.__dict__)


@pytest.mark.parametrize("value", [1, 0, "true", "false", None, [], {"private": True}])
def test_failure_flags_require_boolean_values(value, caplog):
    handle_error(AgentError(503, "PROVIDER_UNAVAILABLE", "private-message", {
        "concurrencyLimited": value, "circuitOpen": value,
        "requestDeadlineExceeded": value, "pipelineCancelled": value,
        "rateLimited": value, "requestNotStarted": value,
    }))
    record, = diagnostic_records(caplog)
    assert record.agent_failure_reasons == {}
    assert "private" not in record.getMessage()


@pytest.mark.parametrize("details", [None, "private-details-marker", [True]])
def test_non_object_details_are_omitted(details, caplog):
    handle_error(AgentError(503, "API_KEY_MISSING", "private-message", details))
    record, = diagnostic_records(caplog)
    assert record.agent_failure_reasons == {}
    assert record.agent_error_code == "API_KEY_MISSING"
    assert "private" not in record.getMessage()


def test_non_503_errors_keep_existing_response_without_new_diagnostic_logs(caplog):
    response = handle_error(AgentError(422, "SCHEMA_VIOLATION", "invalid", {"field": "x"}))
    assert response.status_code == 422
    assert diagnostic_records(caplog) == []


def test_http_handler_uses_the_registered_route_without_logging_query_or_headers(caplog):
    application = create_app()
    settings = Settings(_env_file=None, AGENT_SHARED_SECRET="offline-test-token", AGENT_MOCK=True)
    application.dependency_overrides[get_settings] = lambda: settings

    def reject():
        raise AgentError(503, "PROVIDER_UNAVAILABLE", "private-message", {"circuitOpen": True})

    application.dependency_overrides[require_agent_token] = reject
    with caplog.at_level(logging.WARNING), TestClient(application) as client:
        response = client.post("/v1/analyze?private-query-marker=1",
                               headers={"X-Agent-Token": "offline-test-token"}, json={})

    assert response.status_code == 503
    record, = diagnostic_records(caplog)
    assert record.agent_endpoint == "/v1/analyze"
    assert record.agent_failure_reasons == {"circuitOpen": True}
    assert "private-" not in record.getMessage()
    assert "offline-test-token" not in repr(record.__dict__)
