import json
import logging
from decimal import Decimal

import pytest
from pydantic import BaseModel, ConfigDict, field_validator
from pydantic_core import PydanticCustomError

from app.core.errors import AgentError, OutputValidationError
from app.core.parser import JsonObjectParseError
from app.llm.base import ProviderResponse, ProviderUsage
from app.llm.structured_call import _log_validation_failure, structured_call


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
        "invalid",
        "openai",
        model,
        ProviderUsage(10, 4, Decimal("0.02"), Decimal("1")),
        truncated,
    )


def reject(_response: ProviderResponse) -> None:
    raise ValueError("invalid fixture")


def test_validation_logs_omit_provider_values_and_untrusted_field_names(caplog) -> None:
    class Output(BaseModel):
        model_config = ConfigDict(extra="forbid")
        count: int

    marker = "SYNTHETIC_PRIVATE_PROVIDER_VALUE"
    with pytest.raises(ValueError) as caught:
        Output.model_validate({"count": marker, marker: "extra value"})

    _log_validation_failure(
        logging.getLogger(__name__),
        response(),
        caught.value,
        task_name="fixture",
        attempt=1,
    )

    assert marker not in caplog.text
    assert "input_value" not in caplog.text
    assert "errorType=ValidationError" in caplog.text
    assert "errorCount=2" in caplog.text
    assert "int_parsing" in caplog.text


def test_custom_validation_logs_omit_exception_messages(caplog) -> None:
    marker = "SYNTHETIC_PRIVATE_CUSTOM_ERROR"
    _log_validation_failure(
        logging.getLogger(__name__),
        response(),
        ValueError(marker),
        task_name="fixture",
        attempt=1,
    )
    assert marker not in caplog.text
    assert "errorType=ValueError" in caplog.text


def test_output_validation_logs_count_violations_and_only_unique_safe_kinds(caplog) -> None:
    marker = "SYNTHETIC_PRIVATE_OUTPUT_ERROR"
    error = OutputValidationError(
        marker,
        error_kinds=("REMOVE_NOT_FOUND", "ADD_ALREADY_EXISTS", "REMOVE_NOT_FOUND"),
    )

    _log_validation_failure(
        logging.getLogger(__name__),
        response(),
        error,
        task_name="fixture",
        attempt=1,
    )

    assert marker not in caplog.text
    assert "errorType=OutputValidationError" in caplog.text
    assert "errorCount=3" in caplog.text
    assert "errorKinds=['ADD_ALREADY_EXISTS', 'REMOVE_NOT_FOUND']" in caplog.text


def invoke(
    provider: SequenceProvider,
    *,
    prompt_version: str | None = "insight.v2",
    failure_stage: str | None = None,
    validator=reject,
    include_failure_details: bool = True,
):
    return structured_call(
        provider,
        system_instruction="unchanged system",
        prompt="unchanged prompt",
        response_schema={},
        validate=validator,
        repair_attempts=1,
        task_name="fixture",
        input_tag="fixture",
        schema_violation_message="invalid output",
        logger=logging.getLogger(__name__),
        failure_prompt_version=prompt_version,
        failure_stage=failure_stage,
        include_failure_details=include_failure_details,
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
            "provider": "openai",
            "model": "last-model",
            "promptVersion": "insight.v2",
            "source": "AGENT_ERROR",
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
            "provider": None,
            "model": None,
            "promptVersion": "insight.v2",
            "source": "AGENT_ERROR",
            "usageCompleteness": "UNKNOWN",
        },
    }


def test_repair_provider_failure_preserves_prior_and_failure_usage_without_retry() -> None:
    failure = AgentError(
        502,
        "PROVIDER_UNAVAILABLE",
        "failed",
        {
            "usage": {"inputTokens": 3, "outputTokens": 2, "costUsd": 0.01, "credits": 0.5},
            "retryable": False,
        },
    )
    provider = SequenceProvider(response(truncated=True), failure)

    with pytest.raises(AgentError) as caught:
        invoke(provider)

    assert caught.value is failure
    assert provider.calls == 2
    assert caught.value.details["usage"] == {
        "inputTokens": 13,
        "outputTokens": 6,
        "costUsd": 0.03,
        "credits": 1.5,
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
        "inputTokens": 10,
        "outputTokens": 4,
        "costUsd": 0.02,
        "credits": 1.0,
    }
    assert provider.calls == 2
    assert caught.value.details["executionMetadata"]["usageCompleteness"] == "PARTIAL"


@pytest.mark.parametrize(
    "usage",
    [
        {"inputTokens": 3},
        {"inputTokens": 0, "outputTokens": 0, "costUsd": 0, "credits": 0},
    ],
)
def test_first_provider_failure_with_known_usage_is_conservatively_partial(usage) -> None:
    provider = SequenceProvider(
        AgentError(
            503,
            "PROVIDER_UNAVAILABLE",
            "failed",
            {
                "usage": usage,
            },
        )
    )

    with pytest.raises(AgentError) as caught:
        invoke(provider)

    assert caught.value.details["executionMetadata"]["usageCompleteness"] == "PARTIAL"
    assert caught.value.details["usage"] == usage
    assert provider.calls == 1


def test_invalid_usage_fields_do_not_claim_partial_observation() -> None:
    provider = SequenceProvider(
        AgentError(
            503,
            "PROVIDER_UNAVAILABLE",
            "failed",
            {
                "usage": {"inputTokens": True, "outputTokens": "invalid", "costUsd": "NaN"},
            },
        )
    )

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


def test_opted_in_schema_failure_reports_last_attempt_with_allowlisted_kinds_only() -> None:
    marker = "SYNTHETIC_PRIVATE_DIAGNOSTIC"
    dynamic_error = type(marker, (OutputValidationError,), {})
    errors = iter(
        (
            OutputValidationError(marker, error_kinds=("report_fact_mismatch",)),
            dynamic_error(
                marker,
                error_kinds=(
                    "report_assessment_invalid",
                    marker,
                    "report_assessment_invalid",
                    "report_work_physical_module_unsupported",
                ),
            ),
        )
    )

    def validate(_):
        raise next(errors)

    with pytest.raises(AgentError) as caught:
        invoke(
            SequenceProvider(response(), response()),
            failure_stage="MAP-002",
            validator=validate,
        )

    assert caught.value.details["validationFailure"] == {
        "stage": "MAP-002",
        "attempt": 2,
        "errorType": "OutputValidationError",
        "errorCount": 4,
        "errorKinds": ["report_assessment_invalid", "report_work_physical_module_unsupported"],
    }
    assert marker not in json.dumps(caught.value.details)
    assert next(iter(caught.value.details)) == "validationFailure"
    assert caught.value.details["usage"]["inputTokens"] == 20
    assert caught.value.details["executionMetadata"]["usageCompleteness"] == "COMPLETE"


def test_schema_failure_diagnostics_omit_pydantic_values_locations_and_custom_types() -> None:
    marker = "SYNTHETIC_PRIVATE_PYDANTIC_TYPE"

    class Output(BaseModel):
        model_config = ConfigDict(extra="forbid")
        count: int
        custom: int

        @field_validator("custom")
        @classmethod
        def reject_custom(cls, value):
            raise PydanticCustomError(marker, marker, {"private": marker})

    def validate(_):
        return Output.model_validate({"count": marker, "custom": 1, marker: marker})

    with pytest.raises(AgentError) as caught:
        invoke(
            SequenceProvider(response(), response()),
            failure_stage="REDUCE-001",
            validator=validate,
        )

    assert caught.value.details["validationFailure"] == {
        "stage": "REDUCE-001",
        "attempt": 2,
        "errorType": "ValidationError",
        "errorCount": 3,
        "errorKinds": ["extra_forbidden", "int_parsing"],
    }
    assert marker not in json.dumps(caught.value.details)


def test_unknown_output_kinds_are_counted_without_claiming_a_known_cause():
    def validate(_):
        raise OutputValidationError("private detail", error_kinds=("PRIVATE_KIND", "PRIVATE_KIND"))

    with pytest.raises(AgentError) as caught:
        invoke(
            SequenceProvider(response(), response()),
            failure_stage="REDUCE-001",
            validator=validate,
        )

    assert caught.value.details["validationFailure"]["errorKinds"] == []
    assert caught.value.details["validationFailure"]["errorCount"] == 2
    assert caught.value.details["validationFailure"]["errorType"] == "OutputValidationError"
    assert "PRIVATE_KIND" not in json.dumps(caught.value.details)


@pytest.mark.parametrize(
    "error,error_type,kinds",
    [
        (JsonObjectParseError("private response"), "JsonObjectParseError", ["json_object_parse"]),
        (ValueError("private response"), "ValueError", ["value_error"]),
    ],
)
def test_unstructured_validation_failure_uses_fixed_categories(error, error_type, kinds):
    def validate(_):
        raise error

    with pytest.raises(AgentError) as caught:
        invoke(
            SequenceProvider(response(), response()),
            failure_stage="MAP-001",
            validator=validate,
        )

    assert caught.value.details["validationFailure"]["errorType"] == error_type
    assert caught.value.details["validationFailure"]["errorKinds"] == kinds
    assert "private response" not in json.dumps(caught.value.details)


@pytest.mark.parametrize("stage", [None, "private stage", "MAP-001\nprivate", "MAP-1000"])
def test_failure_context_is_omitted_without_a_valid_server_stage(stage):
    with pytest.raises(AgentError) as caught:
        invoke(SequenceProvider(response(), response()), failure_stage=stage)
    assert "validationFailure" not in caught.value.details


def test_opted_in_provider_error_never_reuses_validation_failure_diagnostics():
    failure = AgentError(503, "PROVIDER_UNAVAILABLE", "unavailable")
    with pytest.raises(AgentError) as caught:
        invoke(SequenceProvider(response(), failure), failure_stage="MAP-001")
    assert caught.value is failure
    assert "validationFailure" not in caught.value.details


def test_omitted_failure_details_also_omit_validation_diagnostics():
    with pytest.raises(AgentError) as caught:
        invoke(
            SequenceProvider(response(), response()),
            failure_stage="MAP-001",
            include_failure_details=False,
        )
    assert caught.value.details is None
