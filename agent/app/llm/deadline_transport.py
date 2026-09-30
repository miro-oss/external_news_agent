"""Cancel complete HTTP exchanges at a report-insight's absolute deadline.

HTTPX's normal timeout bounds individual I/O waits, not a response whose chunks
keep arriving. These sync transports await cancellable async I/O in the sync
route's worker thread; they never leave a provider request in a background task.
"""

import asyncio
from time import monotonic
from types import ModuleType
from typing import Any

import httpx
import httpx2


class DeadlineHttpxTransport(httpx.BaseTransport):
    def __init__(self, deadline: float) -> None:
        self.deadline = deadline

    def handle_request(self, request: httpx.Request) -> httpx.Response:
        return _handle_request(request, self.deadline, httpx)


class DeadlineHttpx2Transport(httpx2.BaseTransport):
    def __init__(self, deadline: float) -> None:
        self.deadline = deadline

    def handle_request(self, request: httpx2.Request) -> httpx2.Response:
        return _handle_request(request, self.deadline, httpx2)


def _handle_request(request: Any, deadline: float, library: ModuleType) -> Any:
    if monotonic() >= deadline:
        raise library.ReadTimeout("Report insight HTTP deadline exceeded.", request=request)
    # Report prompts are already bounded, in-memory request bodies. Keep the
    # SDK-prepared method, URL, headers and exact encoded bytes on the wire.
    body = request.read()
    loop = asyncio.new_event_loop()
    connections: list[asyncio.Transport] = []
    create_connection = loop.create_connection

    async def tracked_connection(*args, **kwargs):
        connection, protocol = await create_connection(*args, **kwargs)
        connections.append(connection)
        return connection, protocol

    # A cancelled TLS handshake can leave its raw stream outside HTTPCore's
    # connection pool. Track this loop's public TCP transports so teardown also
    # aborts those partially initialized sockets, without awaiting a handshake.
    loop.create_connection = tracked_connection
    try:
        return loop.run_until_complete(_send_request(request, body, deadline, library))
    except TimeoutError as error:
        raise library.ReadTimeout(
            "Report insight HTTP deadline exceeded.", request=request
        ) from error
    finally:
        try:
            for connection in connections:
                connection.abort()
            loop.run_until_complete(asyncio.sleep(0))
            loop.run_until_complete(loop.shutdown_asyncgens())
        finally:
            # asyncio.run waits for OS DNS resolver executor threads at shutdown.
            # Cancellation has already closed HTTP I/O; a slow resolver must not
            # keep the sync route waiting after its deadline or send a later request.
            loop.close()


async def _send_request(request: Any, body: bytes, deadline: float, library: ModuleType) -> Any:
    async with asyncio.timeout(max(0.0, deadline - monotonic())):
        # The outer sync client handles redirects/auth, so each resulting
        # exchange shares the same deadline. Default TLS/proxy settings remain
        # with the real async client, rather than bypassing configured proxies.
        async with library.AsyncClient(timeout=None, follow_redirects=False) as client:
            outgoing = library.Request(
                request.method,
                request.url,
                headers=request.headers.raw,
                content=body,
                extensions=request.extensions,
            )
            response = await client.send(outgoing, stream=True)
            try:
                # Preserve compressed bytes and encoding headers together. The
                # sync Response then performs normal decoding exactly once.
                content = b"".join([chunk async for chunk in response.aiter_raw()])
                return library.Response(
                    response.status_code,
                    headers=response.headers.raw,
                    content=content,
                    extensions={
                        key: value
                        for key, value in response.extensions.items()
                        if key in {"http_version", "reason_phrase"}
                    },
                )
            finally:
                await response.aclose()
