import logging
from decimal import Decimal

import pytest

from app.core.errors import AgentError
from app.llm.base import ProviderResponse, ProviderUsage
from app.llm.structured_call import structured_call


class SequenceProvider:
    def __init__(self, *results: ProviderResponse | AgentError) -> None:
        self.results = list(results)
        self.calls = 0

    def generate(self, **kwargs) -> ProviderResponse:
        self.calls += 1
        result = self.results.pop(0)
        if isinstance(result, AgentError):
            raise result
        return result


def response(model: str = "observed-model", *, truncated: bool = False) -> ProviderResponse:
    return ProviderResponse(
        "invalid", "openai", model,
        ProviderUsage(10, 4, Decimal("0.02"), Decimal("1")),
        truncated,
    )


def reject(_response: ProviderResponse) -> None:
    raise ValueError("invalid fixture")


def invoke(provider: SequenceProvider, *, prompt_version: str | None = "insight.v2"):
    return structured_call(
        provider,
        system_instruction="unchanged system",
        prompt="unchanged prompt",
        response_schema={},
        validate=reject,
        repair_attempts=1,
        task_name="fixture",
        input_tag="fixture",
        schema_violation_message="invalid output",
        logger=logging.getLogger(__name__),
        failure_prompt_version=prompt_version,
    )


def test_schema_failure_keeps_last_observed_identity_and_accumulated_usage() -> None:
    provider = SequenceProvider(response("first-model"), response("last-model"))

    with pytest.raises(AgentError) as caught:
        invoke(provider)

    assert provider.calls == 2
    assert caught.value.details == {
        "usage": {"inputTokens": 20, "outputTokens": 8, "costUsd": 0.04, "credits": 2.0},
        "truncated": False,
        "executionMetadata": {
            "provider": "openai", "model": "last-model",
            "promptVersion": "insight.v2", "source": "AGENT_ERROR",
            "usageCompleteness": "COMPLETE",
        },
    }


def test_other_tasks_keep_existing_schema_failure_details() -> None:
    provider = SequenceProvider(response(), response())

    with pytest.raises(AgentError) as caught:
        invoke(provider, prompt_version=None)

    assert caught.value.details == {
        "usage": {"inputTokens": 20, "outputTokens": 8, "costUsd": 0.04, "credits": 2.0},
        "truncated": False,
    }


def test_failure_before_first_response_has_no_inferred_provider_or_usage() -> None:
    failure = AgentError(503, "PROVIDER_UNAVAILABLE", "unavailable", {"retryable": False})
    provider = SequenceProvider(failure)

    with pytest.raises(AgentError) as caught:
        invoke(provider)

    assert caught.value is failure
    assert provider.calls == 1
    assert caught.value.details == {
        "retryable": False,
        "executionMetadata": {
            "provider": None, "model": None,
            "promptVersion": "insight.v2", "source": "AGENT_ERROR",
            "usageCompleteness": "UNKNOWN",
        },
    }


def test_repair_provider_failure_preserves_prior_and_failure_usage_without_retry() -> None:
    failure = AgentError(502, "PROVIDER_UNAVAILABLE", "failed", {
        "usage": {"inputTokens": 3, "outputTokens": 2, "costUsd": 0.01, "credits": 0.5},
        "retryable": False,
    })
    provider = SequenceProvider(response(truncated=True), failure)

    with pytest.raises(AgentError) as caught:
        invoke(provider)

    assert caught.value is failure
    assert provider.calls == 2
    assert caught.value.details["usage"] == {
        "inputTokens": 13, "outputTokens": 6, "costUsd": 0.03, "credits": 1.5,
    }
    assert caught.value.details["retryable"] is False
    assert caught.value.details["truncated"] is True
    assert caught.value.details["executionMetadata"]["model"] == "observed-model"
    assert caught.value.details["executionMetadata"]["usageCompleteness"] == "PARTIAL"


def test_repair_failure_with_unknown_usage_keeps_only_already_observed_usage() -> None:
    provider = SequenceProvider(response(), AgentError(503, "PROVIDER_UNAVAILABLE", "failed"))

    with pytest.raises(AgentError) as caught:
        invoke(provider)

    assert caught.value.details["usage"] == {
        "inputTokens": 10, "outputTokens": 4, "costUsd": 0.02, "credits": 1.0,
    }
    assert provider.calls == 2
    assert caught.value.details["executionMetadata"]["usageCompleteness"] == "PARTIAL"


@pytest.mark.parametrize("usage", [
    {"inputTokens": 3},
    {"inputTokens": 0, "outputTokens": 0, "costUsd": 0, "credits": 0},
])
def test_first_provider_failure_with_known_usage_is_conservatively_partial(usage) -> None:
    provider = SequenceProvider(AgentError(503, "PROVIDER_UNAVAILABLE", "failed", {
        "usage": usage,
    }))

    with pytest.raises(AgentError) as caught:
        invoke(provider)

    assert caught.value.details["executionMetadata"]["usageCompleteness"] == "PARTIAL"
    assert caught.value.details["usage"] == usage
    assert provider.calls == 1


def test_invalid_usage_fields_do_not_claim_partial_observation() -> None:
    provider = SequenceProvider(AgentError(503, "PROVIDER_UNAVAILABLE", "failed", {
        "usage": {"inputTokens": True, "outputTokens": "invalid", "costUsd": "NaN"},
    }))

    with pytest.raises(AgentError) as caught:
        invoke(provider)

    assert caught.value.details["executionMetadata"]["usageCompleteness"] == "UNKNOWN"


def test_other_tasks_leave_provider_error_unchanged() -> None:
    failure = AgentError(503, "PROVIDER_UNAVAILABLE", "failed", {"retryable": False})
    provider = SequenceProvider(response(), failure)

    with pytest.raises(AgentError) as caught:
        invoke(provider, prompt_version=None)

    assert caught.value is failure
    assert caught.value.details == {"retryable": False}
