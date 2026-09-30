"""Local HTTP exchanges verify cancellation, wire fidelity and owned clients."""

import gzip
import json
import socket
import threading
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from time import monotonic, sleep

import httpx
import httpx2
import pytest

from app.core.config import Settings
from app.core.errors import AgentError
from app.llm.deadline_transport import DeadlineHttpx2Transport, DeadlineHttpxTransport
from app.llm.mindlogic_provider import MindlogicAnalyzeProvider
from app.llm.openai_provider import OpenAIAnalyzeProvider
from tests.test_openai_provider import response_body

TRANSPORTS = [(httpx, DeadlineHttpxTransport), (httpx2, DeadlineHttpx2Transport)]


@pytest.fixture(autouse=True)
def local_requests_do_not_use_environment_proxies(monkeypatch):
    for name in ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY"):
        monkeypatch.delenv(name, raising=False)
        monkeypatch.delenv(name.lower(), raising=False)


@contextmanager
def local_server(
    body, *, chunk_delay=0.0, chunk_size=64, compressed=False, status=200, redirect_delay=None
):
    requests = []
    disconnected = threading.Event()
    completed = threading.Event()
    wire_body = gzip.compress(body) if compressed else body

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def do_POST(self):
            requests.append(
                {
                    "path": self.path,
                    "headers": list(self.headers.raw_items()),
                    "body": self.rfile.read(int(self.headers.get("Content-Length", "0"))),
                }
            )
            if redirect_delay is not None and self.path == "/start":
                sleep(redirect_delay)
                self.send_response(307)
                self.send_header("Location", "/finish")
                self.send_header("Content-Length", "0")
                self.end_headers()
                self.close_connection = True
                return
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Transfer-Encoding", "chunked")
            self.send_header("Set-Cookie", "first=1")
            self.send_header("Set-Cookie", "second=2")
            self.send_header("Retry-After", "7")
            if compressed:
                self.send_header("Content-Encoding", "gzip")
            self.end_headers()
            try:
                for offset in range(0, len(wire_body), chunk_size):
                    chunk = wire_body[offset : offset + chunk_size]
                    self.wfile.write(f"{len(chunk):x}\r\n".encode() + chunk + b"\r\n")
                    self.wfile.flush()
                    sleep(chunk_delay)
                self.wfile.write(b"0\r\n\r\n")
                self.wfile.flush()
                completed.set()
            except (BrokenPipeError, ConnectionResetError):
                disconnected.set()
            self.close_connection = True

        def log_message(self, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    worker = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.01})
    worker.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}", requests, disconnected, completed
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=1)


@pytest.mark.parametrize("library,transport", TRANSPORTS)
def test_deadline_cancels_body_even_while_chunks_keep_arriving(library, transport):
    with local_server(
        b'{"ok":true,"padding":"' + b"x" * 80 + b'"}', chunk_delay=0.02, chunk_size=1
    ) as (url, requests, disconnected, completed):
        with library.Client(transport=transport(monotonic() + 0.25), timeout=0.15) as client:
            started = monotonic()
            with pytest.raises(library.ReadTimeout, match="deadline exceeded"):
                client.post(url + "/slow", content=b"exact request")
            elapsed = monotonic() - started
        assert elapsed < 0.65
        assert len(requests) == 1
        assert disconnected.wait(timeout=0.5)
        assert not completed.is_set()


@pytest.mark.parametrize("library,transport", TRANSPORTS)
def test_preserves_exact_wire_headers_body_status_compression_and_response_headers(
    library, transport
):
    body = '{"message":"검증 완료"}'.encode()
    with local_server(body, compressed=True, status=201) as (url, requests, _, completed):
        with library.Client(transport=transport(monotonic() + 1), timeout=0.5) as client:
            request = library.Request(
                "POST",
                url + "/exact?source=report",
                content=b'{"exact": " spaced "}',
                headers=[("X-Test", "first"), ("X-Test", "second"), ("Authorization", "offline")],
            )
            original_headers = [
                (name.decode(), value.decode()) for name, value in request.headers.raw
            ]
            response = client.send(request)
        assert requests == [
            {
                "path": "/exact?source=report",
                "headers": original_headers,
                "body": b'{"exact": " spaced "}',
            }
        ]
        assert response.status_code == 201
        assert response.content == body
        assert response.headers.get_list("set-cookie") == ["first=1", "second=2"]
        assert response.headers["content-encoding"] == "gzip"
        assert response.http_version == "HTTP/1.1"
        assert response.json() == {"message": "검증 완료"}
        assert completed.is_set()


@pytest.mark.parametrize("library,transport", TRANSPORTS)
def test_expired_deadline_sends_nothing(library, transport):
    with local_server(b"{}") as (url, requests, _, _):
        with library.Client(transport=transport(monotonic() - 1)) as client:
            with pytest.raises(library.ReadTimeout, match="deadline exceeded"):
                client.post(url, content=b"never sent")
        assert requests == []


@pytest.mark.parametrize("library,transport", TRANSPORTS)
def test_deadline_cancels_tls_handshake_and_closes_the_socket(library, transport):
    listener = socket.create_server(("127.0.0.1", 0))
    accepted = threading.Event()
    disconnected = threading.Event()

    def stalled_tls_peer():
        connection, _ = listener.accept()
        with connection:
            connection.settimeout(1)
            accepted.set()
            # Read the ClientHello but deliberately send no handshake response.
            connection.recv(4096)
            if connection.recv(4096) == b"":
                disconnected.set()

    peer = threading.Thread(target=stalled_tls_peer)
    peer.start()
    try:
        with library.Client(transport=transport(monotonic() + 0.25), timeout=1) as client:
            started = monotonic()
            with pytest.raises(library.ReadTimeout, match="deadline exceeded"):
                client.post(
                    f"https://127.0.0.1:{listener.getsockname()[1]}/tls", content=b"offline"
                )
            assert monotonic() - started < 0.65
        assert accepted.is_set()
        assert disconnected.wait(timeout=0.5)
    finally:
        listener.close()
        peer.join(timeout=1)


@pytest.mark.parametrize("library,transport", TRANSPORTS)
def test_redirect_exchanges_share_one_deadline(library, transport):
    with local_server(b"x" * 100, chunk_delay=0.02, chunk_size=1, redirect_delay=0.12) as (
        url,
        requests,
        disconnected,
        completed,
    ):
        with library.Client(
            transport=transport(monotonic() + 0.3), timeout=0.5, follow_redirects=True
        ) as client:
            started = monotonic()
            with pytest.raises(library.ReadTimeout, match="deadline exceeded"):
                client.post(url + "/start", content=b"preserved redirect body")
            assert monotonic() - started < 0.65
        assert [request["path"] for request in requests] == ["/start", "/finish"]
        assert all(request["body"] == b"preserved redirect body" for request in requests)
        assert disconnected.wait(timeout=0.5)
        assert not completed.is_set()


@pytest.mark.parametrize("library,transport", TRANSPORTS)
def test_real_async_client_preserves_environment_proxy_routing(monkeypatch, library, transport):
    with local_server(b'{"proxy":true}') as (proxy, requests, _, _):
        monkeypatch.setenv("HTTP_PROXY", proxy)
        with library.Client(transport=transport(monotonic() + 1)) as client:
            # The destination is never resolved or contacted; only our local proxy is.
            response = client.post("http://deadline-test.invalid/path", content=b"exact proxy body")
        assert response.json() == {"proxy": True}
        assert requests[0]["path"] == "http://deadline-test.invalid/path"
        assert requests[0]["body"] == b"exact proxy body"


@pytest.mark.parametrize("library,transport", TRANSPORTS)
def test_slow_dns_does_not_delay_return_or_send_request_after_cancellation(
    monkeypatch, library, transport
):
    entered = threading.Event()
    release = threading.Event()
    finished = threading.Event()
    original = socket.getaddrinfo

    def blocked_lookup(host, *args, **kwargs):
        if host in ("localhost", b"localhost"):
            entered.set()
            try:
                release.wait(timeout=2)
                return original(host, *args, **kwargs)
            finally:
                finished.set()
        return original(host, *args, **kwargs)

    monkeypatch.setattr(socket, "getaddrinfo", blocked_lookup)
    with local_server(b"{}") as (url, requests, _, _):
        with library.Client(transport=transport(monotonic() + 0.2), timeout=1) as client:
            started = monotonic()
            try:
                with pytest.raises(library.ReadTimeout, match="deadline exceeded"):
                    client.post(url.replace("127.0.0.1", "localhost"), content=b"never sent")
                assert monotonic() - started < 0.6
                assert entered.is_set()
                assert not finished.is_set()
                assert requests == []
            finally:
                release.set()
                assert finished.wait(timeout=1)
            # Completing the detached DNS lookup cannot revive the cancelled HTTP coroutine.
            sleep(0.02)
            assert requests == []


def make_provider(plan, url, deadline):
    if plan == "FREE":
        provider = OpenAIAnalyzeProvider(
            Settings(OPENAI_API_KEY="offline-test-only", AGENT_PROVIDER_TIMEOUT_SECONDS=0.15),
            request_deadline=deadline,
        )
        provider._client.base_url = url + "/v1"
        return provider
    return MindlogicAnalyzeProvider(
        Settings(
            MINDLOGIC_API_KEY="offline-test-only",
            MINDLOGIC_BASE_URL=url + "/v1",
            AGENT_PROVIDER_TIMEOUT_SECONDS=0.15,
        ),
        request_deadline=deadline,
    )


@pytest.mark.parametrize("plan", ["FREE", "PAID"])
def test_native_providers_parse_success_usage_and_own_the_deadline_clients(plan):
    body = (
        response_body()
        if plan == "FREE"
        else {
            "choices": [{"message": {"content": '{"ok":true}'}}],
            "usage": {"prompt_tokens": 1000, "completion_tokens": 100, "credits": "1.5"},
        }
    )
    with local_server(json.dumps(body).encode(), compressed=True) as (url, requests, _, _):
        provider = make_provider(plan, url, monotonic() + 1)
        try:
            result = provider.generate(
                system_instruction="offline",
                prompt="exact prompt",
                response_schema={"type": "object", "properties": {"ok": {"type": "boolean"}}},
            )
        finally:
            provider.close()
        assert provider._owns_client is True
        assert provider._client.is_closed() if plan == "FREE" else provider._client.is_closed
        assert len(requests) == 1
        payload = json.loads(requests[0]["body"])
        assert (
            payload["input"] == "exact prompt"
            if plan == "FREE"
            else payload["messages"][1]["content"] == "exact prompt"
        )
        if plan == "FREE":
            assert payload["text"]["format"]["strict"] is True
            assert payload["text"]["format"]["schema"]["properties"]["ok"] == {"type": "boolean"}
        else:
            assert payload["response_format"]["json_schema"]["strict"] is True
        assert result.text == '{"ok":true}'
        assert result.usage.input_tokens == 1000
        assert result.usage.output_tokens == 100
        assert result.provider == ("openai" if plan == "FREE" else "mindlogic-claude")
        assert result.usage.cost_usd > 0 if plan == "FREE" else result.usage.credits == 1.5


@pytest.mark.parametrize("plan", ["FREE", "PAID"])
def test_native_provider_deadline_failure_has_no_hidden_retry_or_unknown_usage_claim(plan):
    with local_server(b"x" * 100, chunk_delay=0.02, chunk_size=1) as (
        url,
        requests,
        disconnected,
        completed,
    ):
        provider = make_provider(plan, url, monotonic() + 0.25)
        try:
            with pytest.raises(AgentError) as error:
                provider.generate(
                    system_instruction="offline", prompt="offline", response_schema={}
                )
        finally:
            provider.close()
        assert error.value.code == "PROVIDER_UNAVAILABLE"
        assert not isinstance(error.value.details, dict) or "usage" not in error.value.details
        assert len(requests) == 1
        assert disconnected.wait(timeout=0.5)
        assert not completed.is_set()


@pytest.mark.parametrize("plan", ["FREE", "PAID"])
def test_native_http_error_and_retry_after_are_preserved_without_retries(plan):
    body = {"error": {"message": "offline rate limit", "code": "rate_limit_exceeded"}}
    with local_server(json.dumps(body).encode(), status=429) as (url, requests, _, _):
        provider = make_provider(plan, url, monotonic() + 1)
        try:
            with pytest.raises(AgentError) as error:
                provider.generate(
                    system_instruction="offline", prompt="offline", response_schema={}
                )
        finally:
            provider.close()
        assert error.value.code == "PROVIDER_UNAVAILABLE"
        assert len(requests) == 1
        if plan == "FREE":
            assert error.value.details["providerStatusCode"] == 429
            assert error.value.details["rateLimited"] is True
            assert error.value.details["retryAfterSeconds"] == 7
