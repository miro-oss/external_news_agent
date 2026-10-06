"""Failed independent reviews retain only revalidated MAP records, never failed output."""

from copy import deepcopy
from dataclasses import replace
from datetime import date

import pytest
from test_report_insight_assessment import framed
from test_report_insight_review_batches import source_request
from test_report_insight_v4_pipeline import V4Provider, generate, stages
from test_structured_call import SequenceProvider, invoke, response

from app.core.errors import AgentError, StructuredOutputExhaustedError
from app.llm import report_insight_service as service


class ReviewProvider(V4Provider):
    def __init__(self, source, *, failed_stages=(), failure="native"):
        self.failed_stages = set(failed_stages)
        self.failure = failure
        super().__init__(source, hook=self.prepare, raw_hook=self.raw)

    def prepare(self, stage, _occurrence, _data, value):
        if stage.startswith("MAP"):
            for entry in value["assessments"]["CHIP_MAKER"].values():
                entry.update(impactScope="UNDETERMINED", impactBasis=None)
        elif stage in self.failed_stages:
            for entry in value["assessments"]["CHIP_MAKER"].values():
                if self.failure == "native":
                    entry["reason"] = "영향 범위와 시점은 미확인이다."
                elif self.failure == "public":
                    entry["reason"] = "2031년 생산 중단에 따른 검증 준비를 확인한다."
        return value

    def raw(self, stage, _occurrence, _data, raw):
        if stage in self.failed_stages and self.failure in {"malformed", "truncated"}:
            return '{"assessments":{"CHIP_MAKER":{"finding101":{"reason":"SENSITIVE_RAW'
        return raw

    def generate(self, **kwargs):
        result = super().generate(**kwargs)
        stage = kwargs["response_schema"]["description"].split(":", 1)[1]
        if stage in self.failed_stages and self.failure == "truncated":
            return replace(result, truncated=True)
        return result


@pytest.mark.parametrize("failure", ["native", "public", "malformed", "truncated"])
@pytest.mark.parametrize(
    "failed_stages",
    [("REVIEW-001",), ("REVIEW-004",), ("REVIEW-001", "REVIEW-004")],
)
def test_review_exhaustion_keeps_exact_map_and_valid_reviews_with_one_budget(
    monkeypatch, caplog, failure, failed_stages
):
    source = source_request()
    original_source = source.model_dump_json(by_alias=True)
    selected, map_snapshots, contexts = [], [], []
    original_select = service.select_review
    original_validate = service._validated_map_output

    def select(request, draft):
        selected.append(request)
        map_snapshots.append(deepcopy(draft.mapped.insights[0].assessments))
        return original_select(request, draft)

    def validate(response, request, **kwargs):
        contexts.append(
            ([finding.id for finding in request.findings], request.report.report_end_date)
        )
        return original_validate(response, request, **kwargs)

    monkeypatch.setattr(service, "select_review", select)
    monkeypatch.setattr(service, "_validated_map_output", validate)
    provider = ReviewProvider(source, failed_stages=failed_stages, failure=failure)
    output = generate(provider, source)
    expected_stages = ["MAP-001", "MAP-002", "MAP-003"]
    for stage in ("REVIEW-001", "REVIEW-002", "REVIEW-003", "REVIEW-004"):
        expected_stages.extend([stage] * (2 if stage in failed_stages else 1))
    expected_stages.append("REDUCE-001")
    assert stages(provider) == expected_stages
    assert len(selected) == 1
    final = output.insights[0].assessments
    assert [entry.finding_id for entry in final] == list(range(101, 114))
    for index, entry in enumerate(final):
        review_stage = f"REVIEW-{index // 3 + 1:03d}"
        if index == 12 or review_stage in failed_stages:
            assert entry == map_snapshots[0][index]
            assert entry.axes.impact is None
        else:
            assert entry.axes.impact == 3
    assert contexts.count((list(range(101, 114)), date(2026, 9, 30))) == 2 + len(failed_stages)
    for call in provider.calls[:-1]:
        assert framed(call["prompt"])["reportReferenceDate"] == "2026-09-30"
    assert source.model_dump_json(by_alias=True) == original_source
    count = len(expected_stages)
    assert output.meta.input_tokens == 11 * count
    assert output.meta.output_tokens == 7 * count
    assert output.meta.cost_usd == pytest.approx(0.003 * count)
    assert output.meta.credits == pytest.approx(0.2 * count)
    assert output.meta.truncated is False  # Complete final output, not a successful review.
    for stage in failed_stages:
        assert (
            f"stage={stage} outcome=VALIDATION_FAILED fallback=VALIDATED_MAP_RETAINED"
            in caplog.text
        )
    assert "SENSITIVE_RAW" not in caplog.text


@pytest.mark.parametrize("failure", ["fingerprint", "native", "public"])
def test_mutated_original_map_is_revalidated_and_cannot_be_fallback(monkeypatch, failure):
    source = source_request()
    original_select = service.select_review

    def corrupt_after_selection(request, draft):
        selected = original_select(request, draft)
        entry = draft.draft.assessments["CHIP_MAKER"]["finding101"]
        if failure == "fingerprint":
            draft.finding_fingerprints[101] = "different-source"
        elif failure == "native":
            entry.impact_scope = "CORE_CONSTRAINT"  # Unknown original has no basis.
        else:
            entry.reason = "2031년 생산 중단에 따른 검증 준비를 확인한다."
        return selected

    monkeypatch.setattr(service, "select_review", corrupt_after_selection)
    provider = ReviewProvider(source, failed_stages=("REVIEW-001",))
    with pytest.raises(ValueError):
        generate(provider, source)
    assert stages(provider) == ["MAP-001", "MAP-002", "MAP-003", "REVIEW-001", "REVIEW-001"]
    assert "REDUCE-001" not in stages(provider)


@pytest.mark.parametrize("failure", ["native", "public"])
def test_complete_map_is_revalidated_before_any_review(monkeypatch, failure):
    source = source_request()
    original_merge = service.merge_drafts

    def check_entry(request, *parts):
        entry = parts[0].draft.assessments["CHIP_MAKER"]["finding101"]
        if failure == "native":
            entry.impact_scope = "CORE_CONSTRAINT"
        else:
            entry.reason = "2031년 생산 중단에 따른 검증 준비를 확인한다."
        return original_merge(request, *parts)

    monkeypatch.setattr(service, "merge_drafts", check_entry)
    provider = ReviewProvider(source)
    with pytest.raises(ValueError):
        generate(provider, source)
    assert stages(provider) == ["MAP-001", "MAP-002", "MAP-003"]


@pytest.mark.parametrize(
    "code,details",
    [
        ("SCHEMA_VIOLATION", None),
        ("PROVIDER_UNAVAILABLE", {"requestDeadlineExceeded": True}),
        ("PROVIDER_RATE_LIMITED", None),
        ("BUDGET_EXCEEDED", {"hardCapCredits": 1}),
        ("PROVIDER_UNAVAILABLE", {"usage": {"inputTokens": 5}}),
    ],
)
def test_provider_errors_even_same_schema_code_never_become_review_fallback(code, details, caplog):
    source = source_request()
    error = AgentError(502, code, "SENSITIVE_PROVIDER_ERROR", deepcopy(details))

    class FailingProvider(ReviewProvider):
        def generate(self, **kwargs):
            if kwargs["response_schema"]["description"].endswith("REVIEW-001"):
                self.calls.append(kwargs)
                raise error
            return super().generate(**kwargs)

    provider = FailingProvider(source)
    with pytest.raises(AgentError) as caught:
        generate(provider, source)
    assert caught.value is error
    assert not isinstance(caught.value, StructuredOutputExhaustedError)
    assert stages(provider) == ["MAP-001", "MAP-002", "MAP-003", "REVIEW-001"]
    assert caught.value.details["usage"]["inputTokens"] == 33 + (
        5 if details and "usage" in details else 0
    )
    assert "VALIDATED_MAP_RETAINED" not in caplog.text
    assert "SENSITIVE_PROVIDER_ERROR" not in caplog.text


def test_provider_model_change_in_review_is_not_local_output_exhaustion(caplog):
    source = source_request()

    class ChangingProvider(ReviewProvider):
        def generate(self, **kwargs):
            result = super().generate(**kwargs)
            if kwargs["response_schema"]["description"].endswith("REVIEW-001"):
                return replace(result, model="different-model")
            return result

    provider = ChangingProvider(source)
    with pytest.raises(AgentError) as caught:
        generate(provider, source)
    assert caught.value.code == "SCHEMA_VIOLATION"
    assert not isinstance(caught.value, StructuredOutputExhaustedError)
    assert len(provider.calls) == 4
    assert caught.value.details["usage"]["inputTokens"] == 44
    assert "VALIDATED_MAP_RETAINED" not in caplog.text


@pytest.mark.parametrize("failed_stage", ["MAP-001", "REDUCE-001"])
def test_exhausted_map_or_reduce_still_fails_the_whole_report(failed_stage, caplog):
    source = source_request()

    def raw(stage, _occurrence, _data, text):
        return "{" if stage == failed_stage else text

    provider = V4Provider(source, raw_hook=raw)
    with pytest.raises(StructuredOutputExhaustedError) as caught:
        generate(provider, source)
    assert caught.value.code == "SCHEMA_VIOLATION"
    assert stages(provider)[-2:] == [failed_stage, failed_stage]
    assert caught.value.details["usage"]["inputTokens"] == 11 * len(provider.calls)
    assert "VALIDATED_MAP_RETAINED" not in caplog.text


def test_exhausted_marker_preserves_existing_public_error_and_accumulated_usage():
    provider = SequenceProvider(response(), response(truncated=True))
    with pytest.raises(StructuredOutputExhaustedError) as caught:
        invoke(provider)
    assert isinstance(caught.value, AgentError)
    assert provider.calls == 2
    assert caught.value.status_code == 502
    assert caught.value.code == "SCHEMA_VIOLATION"
    assert caught.value.message == "invalid output"
    assert caught.value.details["usage"] == {
        "inputTokens": 20,
        "outputTokens": 8,
        "costUsd": 0.04,
        "credits": 2.0,
    }
    assert caught.value.details["truncated"] is True
    assert caught.value.details["executionMetadata"]["usageCompleteness"] == "COMPLETE"
