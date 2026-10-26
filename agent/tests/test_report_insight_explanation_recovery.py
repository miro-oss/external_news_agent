"""EXPLAIN can retain only reauthenticated final prose, never bypass grounding."""

import math
from copy import deepcopy
from dataclasses import replace
from decimal import Decimal
from threading import Barrier, Lock

import pytest
from test_report_insight_assessment import request
from test_report_insight_review_time_admission import assert_usage
from test_report_insight_v4_pipeline import V4Provider, stages

from app.core.config import Settings
from app.core.errors import AgentError, StructuredOutputExhaustedError
from app.llm import report_insight_service as service

AUDIENCES = ("CHIP_MAKER", "EQUIPMENT_MAKER", "MARKET_INVESTOR", "IT_INFRA")
ORIGINAL_REASON = "알려진 생산 제약에 맞춰 공정 검증 준비의 영향을 확인한다."
NEW_REASON = "생산라인 가동 중단이 계속돼 준비 일정의 영향을 확인한다."
UNSELECTED = "별도 행사에서는 장비 937대를 전시할 계획이라고 밝혔다."


def source_report(count=1, *, audiences=("CHIP_MAKER",)):
    # Descending IDs expose accidental sorting or losses across the six-row boundary.
    source = request(ids=tuple(range(100 + count, 100, -1)), audiences=audiences)
    for finding in source.findings:
        finding.claims.append(
            finding.claims[0].model_copy(
                update={
                    "id": f"{finding.id}:1",
                    "text": UNSELECTED,
                    "evidence_sentence_ids": [1],
                }
            )
        )
        finding.sentences.append(
            finding.sentences[0].model_copy(update={"index": 1, "text": UNSELECTED})
        )
    return source


def generate(provider, source, **settings):
    return service.ReportInsightService(
        Settings(
            _env_file=None,
            AGENT_MOCK=False,
            AGENT_REPORT_INSIGHT_SOURCE_CACHE_DIR="",
            **settings,
        ),
        provider,
    ).generate(source)


def malformed_explanation(stage, _occurrence, _data, raw):
    return "{" if stage.startswith("EXPLAIN-") else raw


@pytest.mark.parametrize("audience", AUDIENCES)
@pytest.mark.parametrize("count", [1, 6, 7])
def test_valid_original_prose_survives_explanation_exhaustion_across_roles_and_batches(
    audience, count, caplog
):
    source = source_report(count, audiences=(audience,))
    before = source.model_dump_json()
    provider = V4Provider(source, raw_hook=malformed_explanation)

    with caplog.at_level("WARNING"):
        result = generate(provider, source)

    assert provider.stages["REDUCE-001"] == 1
    for index in range(1, math.ceil(count / 6) + 1):
        assert provider.stages[f"EXPLAIN-{index:03d}"] == 2
    assert [row.finding_id for row in result.insights[0].assessments] == [
        finding.id for finding in source.findings
    ]
    for row in result.insights[0].assessments:
        assert ORIGINAL_REASON in row.reason
        assert row.basis_claim_ids == [f"{row.finding_id}:0"]
        assert row.axes.directness == row.axes.impact == row.axes.urgency == 3
    assert "fallback=VALIDATED_ASSESSMENTS_RETAINED" in caplog.text
    recovery_messages = [
        record.getMessage()
        for record in caplog.records
        if "fallback=VALIDATED_ASSESSMENTS_RETAINED" in record.getMessage()
    ]
    assert all(
        text not in message
        for message in recovery_messages
        for text in (ORIGINAL_REASON, UNSELECTED, source.findings[0].sentences[0].text)
    )
    assert source.model_dump_json() == before
    assert_usage(result.meta.model_dump(by_alias=True), len(provider.calls))


@pytest.mark.parametrize("defect", ["prose", "decision", "truncated"])
def test_recovery_rejects_new_defects_without_inheriting_response_truncation(defect):
    source = source_report()

    def wire_hook(stage, _occurrence, _data, value):
        if stage.startswith("EXPLAIN-"):
            record = value["assessments"]["CHIP_MAKER"]["finding101"]
            if defect == "prose":
                record["reason"] = "근거에 없는 937개의 생산 제약을 확인한다."
            elif defect == "decision":
                record["decision"]["connection"]["work"] = "YIELD_CAPACITY"
        return value

    class DefectiveProvider(V4Provider):
        def generate(self, **kwargs):
            result = super().generate(**kwargs)
            if defect == "truncated" and "EXPLAIN-" in kwargs["response_schema"]["description"]:
                return replace(result, truncated=True)
            return result

    provider = DefectiveProvider(
        source,
        wire_hook=wire_hook,
        raw_hook=malformed_explanation if defect == "truncated" else None,
        validate_wire=defect != "decision",
    )
    result = generate(provider, source)

    assert provider.stages["EXPLAIN-001"] == 2
    assert provider.stages["REDUCE-001"] == 1
    assert ORIGINAL_REASON in result.insights[0].assessments[0].reason
    assert result.meta.truncated is False
    assert_usage(result.meta.model_dump(by_alias=True), len(provider.calls))


def test_failed_batch_preserves_successful_siblings_and_other_audiences():
    source = source_report(7, audiences=("CHIP_MAKER", "IT_INFRA"))

    def hook(stage, _occurrence, _data, value):
        if stage.startswith("EXPLAIN-"):
            for records in value["assessments"].values():
                for record in records.values():
                    record["reason"] = NEW_REASON
        return value

    class ConcurrentProvider(V4Provider):
        report_insight_max_concurrency = 2
        report_insight_credit_reservation = Decimal("0.2")

        def __init__(self, *args, **kwargs):
            super().__init__(*args, **kwargs)
            self.explanation_gate = Barrier(2)
            self.lock = Lock()
            self.explanation_active = self.explanation_peak = 0

        def generate(self, **kwargs):
            stage = kwargs["response_schema"]["description"].split(":", 1)[1]
            if not stage.startswith("EXPLAIN-"):
                return super().generate(**kwargs)
            with self.lock:
                self.explanation_active += 1
                self.explanation_peak = max(self.explanation_peak, self.explanation_active)
            try:
                if stage in {"EXPLAIN-001", "EXPLAIN-002"} and not self.stages[stage]:
                    self.explanation_gate.wait(timeout=5)
                return super().generate(**kwargs)
            finally:
                with self.lock:
                    self.explanation_active -= 1

    provider = ConcurrentProvider(
        source,
        hook=hook,
        raw_hook=lambda stage, _occurrence, _data, raw: "{" if stage == "EXPLAIN-001" else raw,
    )
    result = generate(provider, source, AGENT_PROVIDER_CONCURRENCY=4)

    assert provider.explanation_peak == 2
    assert provider.stages["EXPLAIN-001"] == 2
    assert all(provider.stages[f"EXPLAIN-{index:03d}"] == 1 for index in (2, 3, 4))
    assert provider.stages["REDUCE-001"] == 1
    for insight in result.insights:
        assert [row.finding_id for row in insight.assessments] == list(range(107, 100, -1))
        for index, row in enumerate(insight.assessments):
            retained = insight.audience == "CHIP_MAKER" and index < 6
            expected = ORIGINAL_REASON if retained else NEW_REASON
            assert expected in row.reason
            assert row.basis_claim_ids == [f"{row.finding_id}:0"]
    assert_usage(result.meta.model_dump(by_alias=True), len(provider.calls))


def test_final_review_sources_and_decisions_are_retained_instead_of_older_map(monkeypatch):
    source = source_report()
    selected = "제조사는 다른 생산라인의 가동 중단도 현재 계속된다고 밝혔다."
    source.findings[0].claims[1].text = selected
    source.findings[0].sentences[1].text = selected
    review_reason = "같은 생산 제약에 따른 검증 준비 조건을 확인한다."
    monkeypatch.setattr(service, "select_review", lambda *_: (101,))

    def hook(stage, _occurrence, _data, value):
        if stage.startswith("REVIEW-"):
            record = value["assessments"]["CHIP_MAKER"]["finding101"]
            basis = {"claimId": "101:1", "quote": selected}
            record.update(
                relationBasis=deepcopy(basis),
                urgencyBasis=deepcopy(basis),
                impactScope="UNDETERMINED",
                impactBasis=None,
                reason=review_reason,
            )
        return value

    provider = V4Provider(source, hook=hook, raw_hook=malformed_explanation)
    result = generate(provider, source)

    assert provider.stages["REVIEW-001"] == 1
    assert provider.stages["EXPLAIN-001"] == 2
    row = result.insights[0].assessments[0]
    assert row.axes.impact is None
    assert row.basis_claim_ids == ["101:1"]
    assert review_reason in row.reason
    assert ORIGINAL_REASON not in row.reason
    assert_usage(result.meta.model_dump(by_alias=True), len(provider.calls))


@pytest.mark.parametrize(
    "defect", ["unselected_fact", "unsupported_year", "condition", "fingerprint"]
)
def test_invalid_original_proof_cannot_be_retained_when_explanation_also_fails(monkeypatch, defect):
    source = source_report()
    prepare = service.prepare_explanation_batches

    def corrupt_saved_proof(request, validated, **kwargs):
        batches = prepare(request, validated, **kwargs)
        record = validated.draft.assessments["CHIP_MAKER"]["finding101"]
        if defect == "unselected_fact":
            record.reason = "별도 행사의 장비 937대 전시 계획에 따른 준비 조건을 확인한다."
        elif defect == "unsupported_year":
            record.reason = "2037년 생산 중단에 따른 검증 준비를 확인한다."
        elif defect == "condition":
            record.condition = "장비 937대를 전시하는 경우"
        else:
            validated.finding_fingerprints[101] = "changed-source-identity"
        return batches

    monkeypatch.setattr(service, "prepare_explanation_batches", corrupt_saved_proof)
    provider = V4Provider(
        source,
        relation="CONDITIONAL" if defect == "condition" else "DIRECT",
        raw_hook=malformed_explanation,
    )
    with pytest.raises(StructuredOutputExhaustedError):
        generate(provider, source)
    assert provider.stages["EXPLAIN-001"] == 2
    assert provider.stages["REDUCE-001"] == 0


def test_source_change_after_proof_capture_prevents_retention(monkeypatch):
    source = source_report()
    prepare = service.prepare_explanation_batches
    batches = []

    def capture(*args, **kwargs):
        result = prepare(*args, **kwargs)
        batches.extend(result)
        return result

    monkeypatch.setattr(service, "prepare_explanation_batches", capture)

    def raw(stage, occurrence, _data, value):
        if stage.startswith("EXPLAIN-"):
            if occurrence == 1:
                batches[0].request.findings[0].sentences[0].text = "변경된 별도 원문이다."
            return "{"
        return value

    provider = V4Provider(source, raw_hook=raw)
    with pytest.raises(StructuredOutputExhaustedError):
        generate(provider, source)
    assert provider.stages["EXPLAIN-001"] == 2
    assert provider.stages["REDUCE-001"] == 0


def test_required_review_explanation_cannot_reuse_older_valid_map_or_valid_neighbor(monkeypatch):
    source = source_report(2)
    monkeypatch.setattr(service, "select_review", lambda *_: (102, 101))

    def hook(stage, _occurrence, _data, value):
        if stage.startswith("REVIEW-"):
            records = value["assessments"]["CHIP_MAKER"]
            if "finding101" in records:
                records["finding101"]["reason"] = "2037년 생산 중단에 따른 검증 준비를 확인한다."
        return value

    provider = V4Provider(source, hook=hook, raw_hook=malformed_explanation)
    with pytest.raises(StructuredOutputExhaustedError) as caught:
        generate(provider, source)
    assert caught.value.details["validationFailure"]["stage"] == "EXPLAIN-001"
    assert provider.stages["REVIEW-001"] == 2
    assert provider.stages["EXPLAIN-001"] == 2
    assert provider.stages["REDUCE-001"] == 0
    assert_usage(caught.value.details["usage"], len(provider.calls))


@pytest.mark.parametrize(
    "code,details",
    [
        ("SCHEMA_VIOLATION", {}),
        ("PROVIDER_UNAVAILABLE", {"requestDeadlineExceeded": True}),
        ("PROVIDER_UNAVAILABLE", {"rateLimited": True}),
        ("BUDGET_EXCEEDED", {"hardCapCredits": 1}),
        ("PROVIDER_UNAVAILABLE", {"usage": {"inputTokens": 5}}),
    ],
)
def test_provider_failures_never_become_explanation_recovery(code, details, caplog):
    source = source_report()
    original = AgentError(502, code, "PRIVATE_PROVIDER_FAILURE", deepcopy(details))

    class FailingProvider(V4Provider):
        def generate(self, **kwargs):
            if "EXPLAIN-" in kwargs["response_schema"]["description"]:
                self.calls.append(kwargs)
                raise original
            return super().generate(**kwargs)

    provider = FailingProvider(source)
    with caplog.at_level("WARNING"), pytest.raises(AgentError) as caught:
        generate(provider, source)
    assert caught.value is original
    assert not isinstance(caught.value, StructuredOutputExhaustedError)
    assert "REDUCE-001" not in stages(provider)
    assert caught.value.details["usage"]["inputTokens"] == 11 * len(provider.response_texts) + (
        5 if "usage" in details else 0
    )
    assert "VALIDATED_ASSESSMENTS_RETAINED" not in caplog.text
    assert "PRIVATE_PROVIDER_FAILURE" not in caplog.text


def test_explanation_provider_model_change_is_not_output_exhaustion():
    source = source_report()

    class ChangingProvider(V4Provider):
        def generate(self, **kwargs):
            result = super().generate(**kwargs)
            if "EXPLAIN-" in kwargs["response_schema"]["description"]:
                return replace(result, model="different-model")
            return result

    provider = ChangingProvider(source)
    with pytest.raises(AgentError) as caught:
        generate(provider, source)
    assert caught.value.code == "SCHEMA_VIOLATION"
    assert not isinstance(caught.value, StructuredOutputExhaustedError)
    assert provider.stages["EXPLAIN-001"] == 1
    assert provider.stages["REDUCE-001"] == 0
    assert_usage(caught.value.details["usage"], len(provider.calls))


@pytest.mark.parametrize("limit", ["deadline", "credits"])
def test_explanation_observed_usage_obeys_real_pipeline_deadline_and_credit_cap(monkeypatch, limit):
    source = source_report()
    clock = [0.0]
    monkeypatch.setattr("app.llm.report_insight_pipeline.monotonic", lambda: clock[0])
    monkeypatch.setattr(service, "select_review", lambda *_: (101,))

    def hook(stage, _occurrence, _data, value):
        if limit == "deadline" and stage.startswith("EXPLAIN-"):
            clock[0] = 180.001
        return value

    provider = V4Provider(source, hook=hook, raw_hook=malformed_explanation)
    with pytest.raises(AgentError) as caught:
        generate(
            provider,
            source,
            AGENT_REPORT_INSIGHT_TIMEOUT_SECONDS=180,
            AGENT_HARD_CAP_CREDITS_PER_REQUEST=0.5 if limit == "credits" else 5,
        )
    assert not isinstance(caught.value, StructuredOutputExhaustedError)
    assert provider.stages["EXPLAIN-001"] == 1
    assert provider.stages["REDUCE-001"] == 0
    if limit == "deadline":
        assert caught.value.code == "PROVIDER_UNAVAILABLE"
        assert caught.value.details["requestDeadlineExceeded"] is True
    else:
        assert caught.value.code == "BUDGET_EXCEEDED"
        assert caught.value.details["usage"]["credits"] == pytest.approx(0.6)
    assert_usage(caught.value.details["usage"], len(provider.calls))
