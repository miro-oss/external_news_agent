"""Optional REDUCE recovery removes only diagnosed units after its one retry."""

import json
from copy import deepcopy

import pytest
from test_report_insight_assessment import request
from test_report_insight_reduce_partial_repair import public_reduce_projection
from test_report_insight_v4_pipeline import V4Provider, stages

from app.core.config import Settings
from app.core.errors import AgentError, StructuredOutputExhaustedError
from app.llm import report_insight_service as service


def implication():
    return {
        "text": "생산 제약이 지속되면 검증 준비 영향을 확인해야 한다.",
        "mechanism": "생산 제약이 유지되면 검증 준비 조건을 점검한다.",
        "assumption": "같은 생산 제약이 검증 준비에 연결되는 경우",
        "falsifiedBy": "같은 생산라인의 가동이 재개되는 경우",
        "basisClaimIds": ["101:0"],
    }


def scenario(*, required=False, malformed=False):
    source = request()
    originals = []

    def hook(stage, occurrence, _, value):
        if stage != "REDUCE-001":
            return value
        row = value["insights"][0]
        good = implication()
        bad = deepcopy(good)
        bad["text"] = "TSMC의 생산 제약 영향을 확인한다."
        row["implications"] = [good, bad]
        if required:
            row["headline"] = "TSMC의 생산 제약을 확인한다."
        return value

    def wire(stage, occurrence, _, value):
        if stage == "REDUCE-001":
            if occurrence == 1:
                originals.append(deepcopy(value))
            if malformed:
                return {"insights": "untrusted"}
        return value

    provider = V4Provider(source, hook=hook, wire_hook=wire, validate_wire=not malformed)
    engine = service.ReportInsightService(Settings(_env_file=None, AGENT_MOCK=False), provider)
    return source, engine, provider, originals


def test_exhausted_optional_unit_is_omitted_but_all_valid_siblings_and_usage_survive(caplog):
    source, engine, provider, originals = scenario()
    before = source.model_dump_json()
    output = engine.generate(source)
    assert stages(provider) == ["MAP-001", "REVIEW-001", "REDUCE-001", "REDUCE-001"]
    final = output.insights[0].model_dump(by_alias=True)
    original = public_reduce_projection(originals[0]["insights"][0])
    assert final["overview"] == original["overview"]
    assert final["headline"] == original["headline"]
    assert final["implications"] == [original["implications"][0]]
    assert final["watchItems"] == original["watchItems"]
    assert "TSMC" not in output.model_dump_json()
    assert len(final["assessments"]) == 1
    assert output.meta.input_tokens == 44 and output.meta.output_tokens == 28
    assert output.meta.cost_usd == pytest.approx(0.012)
    assert output.meta.credits == pytest.approx(0.8)
    assert not output.meta.truncated
    assert source.model_dump_json() == before
    assert "OPTIONAL_UNITS_OMITTED" in caplog.text
    assert "TSMC" not in caplog.text


@pytest.mark.parametrize("defect", ["required", "malformed"])
def test_required_or_unlocated_failure_remains_a_failure_after_two_calls(defect):
    source, engine, provider, _ = scenario(
        required=defect == "required", malformed=defect == "malformed"
    )
    with pytest.raises(StructuredOutputExhaustedError) as caught:
        engine.generate(source)
    assert caught.value.status_code == 502
    assert stages(provider).count("REDUCE-001") == 2
    assert caught.value.details["usage"]["inputTokens"] == 44
    assert caught.value.details["usage"]["outputTokens"] == 28


def test_provider_unavailability_is_never_treated_as_optional_validation_failure():
    source = request()

    class UnavailableProvider(V4Provider):
        def generate(self, **kwargs):
            if kwargs["response_schema"]["description"].endswith("REDUCE-001"):
                self.calls.append(kwargs)
                raise AgentError(503, "PROVIDER_UNAVAILABLE", "offline provider unavailable")
            return super().generate(**kwargs)

    provider = UnavailableProvider(source)
    engine = service.ReportInsightService(Settings(_env_file=None, AGENT_MOCK=False), provider)
    with pytest.raises(AgentError) as caught:
        engine.generate(source)
    assert caught.value.code == "PROVIDER_UNAVAILABLE"
    assert caught.value.status_code == 503
    assert stages(provider) == ["MAP-001", "REVIEW-001", "REDUCE-001"]


def test_optional_omission_still_runs_full_validator_and_cannot_force_success(monkeypatch):
    source, engine, provider, _ = scenario()
    original_validator = service._validated_v4_reduce_output
    pruned_checks = []

    def validate(response, *args, **kwargs):
        value = json.loads(response.text)
        if "insights" in value and len(value["insights"][0]["implications"]) == 1:
            pruned_checks.append(value)
            raise ValueError("The complete original contract still rejects this candidate.")
        return original_validator(response, *args, **kwargs)

    monkeypatch.setattr(service, "_validated_v4_reduce_output", validate)
    with pytest.raises(StructuredOutputExhaustedError):
        engine.generate(source)
    assert len(pruned_checks) == 1
    assert stages(provider).count("REDUCE-001") == 2
