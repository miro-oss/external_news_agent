import asyncio
import json

import pytest

from app.core.config import Settings, get_settings
from app.main import create_app

TOKEN = "synthetic-agent-test-token"
PROTECTED_PATHS = (
    "/v1/analyze",
    "/v1/verify-evidence",
    "/v1/explore",
    "/v1/insight",
    "/v1/keyword-strategy",
    "/v1/topic-relevance",
    "/v1/report",
    "/v1/report-changes",
)


def application(*, limit: int = 1024, secret: str = TOKEN):
    app = create_app()
    settings = Settings(
        _env_file=None,
        AGENT_MOCK=True,
        AGENT_SHARED_SECRET=secret,
        AGENT_MAX_REQUEST_BODY_BYTES=limit,
        OPENAI_API_KEY="",
        MINDLOGIC_API_KEY="",
    )
    app.dependency_overrides[get_settings] = lambda: settings
    return app


def request(app, *, chunks=(), headers=(), path="/v1/analyze", method="POST"):
    messages = []
    calls = 0

    async def receive():
        nonlocal calls
        assert calls < len(chunks), "Application unexpectedly consumed more request data"
        chunk = chunks[calls]
        calls += 1
        return {
            "type": "http.request",
            "body": chunk,
            "more_body": calls < len(chunks),
        }

    async def send(message):
        messages.append(message)

    scope = {
        "type": "http",
        "asgi": {"version": "3.0"},
        "http_version": "1.1",
        "method": method,
        "scheme": "http",
        "path": path,
        "raw_path": path.encode(),
        "query_string": b"",
        "headers": [(b"content-type", b"application/json"), *headers],
        "client": ("127.0.0.1", 1234),
        "server": ("testserver", 80),
    }
    asyncio.run(app(scope, receive, send))
    status = next(
        message["status"] for message in messages if message["type"] == "http.response.start"
    )
    body = b"".join(message.get("body", b"") for message in messages)
    return status, json.loads(body) if body else None, calls


def authenticated_headers(*extra):
    return [(b"x-agent-token", TOKEN.encode()), *extra]


@pytest.mark.parametrize("path", PROTECTED_PATHS)
@pytest.mark.parametrize("token", [None, b"wrong-token", b"\xff"])
def test_unauthorized_requests_never_read_or_parse_the_body(path, token):
    headers = [] if token is None else [(b"x-agent-token", token)]
    status, body, calls = request(
        application(), path=path, chunks=[b'{"malformed":'], headers=headers
    )
    assert status == 401
    assert body["error"]["code"] == "UNAUTHORIZED"
    assert calls == 0


def test_unconfigured_token_fails_closed_before_reading():
    status, body, calls = request(
        application(secret=""), headers=authenticated_headers(), chunks=[b"{}"]
    )
    assert (status, body["error"]["code"], calls) == (401, "UNAUTHORIZED", 0)


def test_trailing_slash_is_authenticated_before_redirect():
    status, _, calls = request(application(), path="/v1/analyze/", chunks=[b"{}"])
    assert (status, calls) == (401, 0)


@pytest.mark.parametrize("declared_size", [b"1025", b"0001025", b"9" * 5000])
def test_oversized_content_length_is_rejected_without_reading(declared_size):
    status, body, calls = request(
        application(),
        headers=authenticated_headers((b"content-length", declared_size)),
        chunks=[b"{}"],
    )
    assert (status, body["error"]["code"], calls) == (413, "INPUT_TOO_LARGE", 0)


@pytest.mark.parametrize("declared_size", [None, b"1", b"invalid", b"1024"])
def test_actual_chunk_bytes_are_limited_even_without_a_truthful_content_length(declared_size):
    headers = authenticated_headers()
    if declared_size is not None:
        headers.append((b"content-length", declared_size))
    status, body, calls = request(
        application(), headers=headers, chunks=[b"x" * 512, b"x" * 513, b"never read"]
    )
    assert (status, body["error"]["code"], calls) == (413, "INPUT_TOO_LARGE", 2)


def analyze_body():
    return json.dumps({
        "idempotencyKey": "security-test:article:1",
        "plan": "FREE",
        "article": {
            "id": 1,
            "title": "반도체 생산 계획",
            "canonicalUrl": "https://example.test/article/1",
            "bodyText": "A사는 반도체 생산 계획을 발표했다.",
        },
        "topic": {"name": "반도체"},
    }, ensure_ascii=False).encode()


@pytest.mark.parametrize("with_content_length", [True, False])
def test_valid_utf8_request_at_byte_limit_keeps_the_normal_response(with_content_length):
    payload = analyze_body()
    headers = authenticated_headers()
    if with_content_length:
        headers.append((b"content-length", str(len(payload)).encode()))
    status, body, calls = request(
        application(limit=len(payload)),
        headers=headers,
        chunks=[payload[:150], payload[150:]],
    )
    assert status == 200
    assert body["meta"]["mock"] is True
    assert calls == 2


def test_limit_counts_utf8_bytes_instead_of_characters():
    payload = analyze_body()
    status, body, _ = request(
        application(limit=len(payload.decode())),
        headers=authenticated_headers(),
        chunks=[payload],
    )
    assert (status, body["error"]["code"]) == (413, "INPUT_TOO_LARGE")


def test_authenticated_malformed_json_keeps_the_validation_contract():
    status, body, calls = request(
        application(), headers=authenticated_headers(), chunks=[b'{"malformed":']
    )
    assert (status, body["error"]["code"], calls) == (422, "SCHEMA_VIOLATION", 1)


def test_public_health_still_needs_neither_token_nor_body_read():
    status, body, calls = request(application(), path="/v1/health", method="GET")
    assert (status, body, calls) == (200, {"status": "ok", "mock": True}, 0)
