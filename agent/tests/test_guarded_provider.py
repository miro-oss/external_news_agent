from decimal import Decimal
from threading import Event, Thread

import pytest

from app.core.errors import AgentError
from app.llm.base import ProviderResponse, ProviderUsage
from app.llm.guarded_provider import GuardedAnalyzeProvider


class Provider:
    def __init__(self, response: ProviderResponse | None = None) -> None:
        self.response = response or ProviderResponse(
            text='{"ok":true}',
            provider="mindlogic-claude",
            model="model",
            usage=ProviderUsage(),
        )

    def generate(self, **_: object) -> ProviderResponse:
        return self.response


def guarded(delegate: object, **overrides: object) -> GuardedAnalyzeProvider:
    values = {
        "concurrency": 1,
        "acquire_timeout_seconds": 0.01,
        "failure_threshold": 2,
        "cooldown_seconds": 60.0,
        "hard_cap_credits": Decimal("5"),
    }
    values.update(overrides)
    return GuardedAnalyzeProvider(delegate, **values)  # type: ignore[arg-type]


def test_rejects_usage_over_hard_cap_with_usage_details() -> None:
    provider = Provider(ProviderResponse(
        text='{"ok":true}',
        provider="mindlogic-claude",
        model="model",
        usage=ProviderUsage(credits=Decimal("6")),
    ))

    with pytest.raises(AgentError) as error:
        guarded(provider).generate(
            system_instruction="system",
            prompt="prompt",
            response_schema={},
        )

    assert error.value.code == "BUDGET_EXCEEDED"
    assert error.value.details["usage"]["credits"] == 6.0


def test_concurrency_limit_rejects_second_call_without_calling_delegate() -> None:
    entered = Event()
    release = Event()

    class BlockingProvider(Provider):
        def generate(self, **_: object) -> ProviderResponse:
            entered.set()
            release.wait(timeout=1)
            return self.response

    provider = guarded(BlockingProvider())
    thread = Thread(target=lambda: provider.generate(
        system_instruction="system", prompt="first", response_schema={}
    ))
    thread.start()
    entered.wait(timeout=1)
    try:
        with pytest.raises(AgentError) as error:
            provider.generate(
                system_instruction="system",
                prompt="second",
                response_schema={},
            )
        assert error.value.details == {"concurrencyLimited": True}
    finally:
        release.set()
        thread.join(timeout=1)
