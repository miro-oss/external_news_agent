from dataclasses import replace

import pytest
from test_topic_relevance import FakeProvider, article_output, provider_response, request

from app.core.config import Settings
from app.core.errors import AgentError
from app.llm.base import ProviderUsage
from app.llm.rate_limit_provider import (
    PacedRetryProvider,
    ProviderRequestCoordinator,
    ProviderRequestPolicy,
)
from app.llm.topic_relevance_service import TopicRelevanceService

ADMISSION = {
    "scope": "REQUEST", "reason": "CAPACITY", "providerInvoked": False, "retryable": True,
}


def busy(**extra):
    return AgentError(503, "PROVIDER_UNAVAILABLE", "offline capacity", {
        "concurrencyLimited": True, "requestNotStarted": True, **extra,
    })


def classify(provider, articles=None):
    value = request()
    if articles is not None:
        value = value.model_copy(update={"articles": articles})
    settings = Settings(_env_file=None, AGENT_MOCK=False, AGENT_SCHEMA_REPAIR_ATTEMPTS=1)
    return TopicRelevanceService(settings, provider).classify(value)


def test_first_capacity_rejection_certifies_a_request_with_no_provider_invocation():
    error = busy(admission={"scope": "UNTRUSTED"}, privateDetail="unchanged")
    provider = FakeProvider(error)
    with pytest.raises(AgentError) as caught:
        classify(provider)
    assert caught.value is error
    assert error.details["admission"] == ADMISSION
    assert error.details["privateDetail"] == "unchanged"
    assert len(provider.calls) == 1


@pytest.mark.parametrize("stage", ["repair", "negative-review", "next-article"])
def test_prior_zero_usage_response_prevents_request_replay_in_every_stage(stage):
    if stage == "next-article":
        articles = request().articles[1:]
        first = provider_response(article_output(502))
    else:
        articles = request().articles[:1]
        first = provider_response(article_output(501))
    first = replace(first, usage=ProviderUsage())
    if stage == "repair":
        first = replace(first, text="invalid-json")
    error = busy(admission=ADMISSION.copy())
    provider = FakeProvider(first, error)
    with pytest.raises(AgentError) as caught:
        classify(provider, articles)
    assert caught.value is error
    assert "admission" not in error.details
    assert error.details["concurrencyLimited"] is True
    assert len(provider.calls) == 2


@pytest.mark.parametrize("changes", [
    {"concurrencyLimited": "true"}, {"concurrencyLimited": 1},
    {"requestNotStarted": "true"}, {"requestNotStarted": 1},
    {"requestNotStarted": False}, {"retryable": False}, {"retryable": "true"},
    {"circuitOpen": True}, {"circuitOpen": "true"},
    {"requestDeadlineExceeded": True}, {"pipelineCancelled": True}, {"rateLimited": True},
    {"usage": {"inputTokens": 0, "outputTokens": 0, "costUsd": 0, "credits": 0}},
])
def test_ambiguous_or_contradictory_details_never_certify_admission(changes):
    error = busy(admission=ADMISSION.copy(), **changes)
    with pytest.raises(AgentError) as caught:
        classify(FakeProvider(error))
    assert caught.value is error
    assert "admission" not in error.details


@pytest.mark.parametrize("status,code,details", [
    (503, "PROVIDER_UNAVAILABLE", None),
    (503, "PROVIDER_UNAVAILABLE", {}),
    (503, "PROVIDER_UNAVAILABLE", {"providerStatusCode": 0, "rateLimited": False}),
    (503, "PROVIDER_UNAVAILABLE", {"circuitOpen": True, "requestNotStarted": True}),
    (503, "API_KEY_MISSING", {"concurrencyLimited": True, "requestNotStarted": True}),
    (502, "PROVIDER_UNAVAILABLE", {"concurrencyLimited": True, "requestNotStarted": True}),
])
def test_other_failures_and_forged_admission_do_not_certify_replay(status, code, details):
    error = AgentError(status, code, "offline failure", {
        **(details or {}), "admission": ADMISSION.copy(),
    })
    with pytest.raises(AgentError) as caught:
        classify(FakeProvider(error))
    assert caught.value is error
    assert "admission" not in error.details


def test_rate_limit_then_capacity_rejection_does_not_certify_request_replay():
    clock = [0.0]
    sleeps = []

    def advance(seconds):
        sleeps.append(seconds)
        clock[0] += seconds

    coordinator = ProviderRequestCoordinator(
        ProviderRequestPolicy(0), clock=lambda: clock[0], sleeper=advance, jitter=lambda _: 0,
    )
    limited = AgentError(503, "PROVIDER_UNAVAILABLE", "offline rate limit", {
        "rateLimited": True, "retryable": True, "providerStatusCode": 429,
    })
    error = busy(admission=ADMISSION.copy(), privateDetail="unchanged")
    original_details = error.details
    delegate = FakeProvider(limited, error)
    with pytest.raises(AgentError) as caught:
        classify(PacedRetryProvider(delegate, coordinator))
    assert caught.value is error
    assert error.details["requestNotStarted"] is False
    assert "admission" not in error.details
    assert error.details["privateDetail"] == "unchanged"
    assert original_details["requestNotStarted"] is True
    assert len(delegate.calls) == 2
    assert sleeps == [4]
