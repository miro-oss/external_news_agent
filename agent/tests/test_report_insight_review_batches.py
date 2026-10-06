"""Bounded independent review batches retain source scope, dates and one budget."""

from datetime import date

import pytest
from test_report_insight_assessment import framed, item, request
from test_report_insight_v4_pipeline import V4Provider, generate, stages

from app.core.errors import AgentError
from app.llm import report_insight_service as service


def source_request():
    source = request(ids=tuple(range(101, 114)))
    source.report.report_date = None
    source.findings[-1].published_at = date(2026, 9, 30)
    return source


def responses(source, *, late_error=False, permanent=False, clock=None):
    def hook(stage, occurrence, data, value):
        if stage.startswith("MAP"):
            for record in value["assessments"]["CHIP_MAKER"].values():
                record.update(impactScope="UNDETERMINED", impactBasis=None)
        if stage == "REVIEW-001":
            # The first review changes its assessments. That must not reselect
            # candidates or provide its answers to the second independent batch.
            for finding in data["findings"]:
                original = next(f for f in source.findings if f.id == finding["id"])
                record = item(original, relation="UNDETERMINED")
                record["reason"] = "생산 중단 사건의 업무 연결 판단에 필요한 조건이 미확인이다."
                value["assessments"]["CHIP_MAKER"][f"finding{finding['id']}"] = record
        if stage == "REVIEW-002":
            if clock is not None:
                clock[0] = 181.0
            if late_error and (occurrence == 1 or permanent):
                value["assessments"]["CHIP_MAKER"]["finding110"]["reason"] = (
                    "2031년 생산 중단에 따른 검증 준비를 확인한다."
                )
        return value

    return hook


def test_review_selection_is_once_and_late_partial_repair_preserves_full_source_date(monkeypatch):
    source = source_request()
    snapshot = source.model_dump_json(by_alias=True)
    selected, validation_contexts = [], []
    original_select = service.select_review
    original_validate = service._validated_map_output

    def select(full_source, draft):
        selected.append(draft)
        assert [item.axes.impact for item in draft.mapped.insights[0].assessments] == [None] * 13
        return original_select(full_source, draft)

    def validate(response, context, **kwargs):
        validation_contexts.append(
            ([f.id for f in context.findings], context.report.report_end_date)
        )
        return original_validate(response, context, **kwargs)

    monkeypatch.setattr(service, "select_review", select)
    monkeypatch.setattr(service, "_validated_map_output", validate)
    provider = V4Provider(source, hook=responses(source, late_error=True))
    result = generate(provider, source)

    assert stages(provider) == [
        "MAP-001",
        "MAP-002",
        "MAP-003",
        "REVIEW-001",
        "REVIEW-002",
        "REVIEW-002",
        "REDUCE-001",
    ]
    assert len(selected) == 1
    inputs = [framed(call["prompt"]) for call in provider.calls[:-1]]
    assert [[f["id"] for f in data["findings"]] for data in inputs] == [
        list(range(101, 107)),
        list(range(107, 113)),
        [113],
        list(range(101, 107)),
        list(range(107, 113)),
        [110],
    ]
    assert all(data["reportReferenceDate"] == "2026-09-30" for data in inputs)
    for data in inputs:
        assert "previous" not in data and "assessments" not in data
        for finding in data["findings"]:
            original = next(f for f in source.findings if f.id == finding["id"])
            assert finding["articleId"] == original.article_id
            assert finding["claims"] == [c.model_dump(by_alias=True) for c in original.claims]
            assert finding["sentences"] == [s.model_dump(by_alias=True) for s in original.sentences]
    assert validation_contexts.count((list(range(107, 113)), date(2026, 9, 30))) == 3
    final = result.insights[0].assessments
    assert [record.finding_id for record in final] == list(range(101, 114))
    assert all(record.axes.directness is None for record in final[:6])
    assert all(record.axes.impact == 3 for record in final[6:12])
    assert final[-1].axes.directness == 3 and final[-1].axes.impact is None
    assert result.meta.input_tokens == 77 and result.meta.output_tokens == 49
    assert result.meta.cost_usd == 0.021 and result.meta.credits == 1.4
    assert source.model_dump_json(by_alias=True) == snapshot


@pytest.mark.parametrize("failure", ["repair_exhausted", "deadline", "budget"])
def test_later_review_failure_keeps_usage_and_only_validation_can_retain_map(monkeypatch, failure):
    source = source_request()
    clock = [0.0]
    monkeypatch.setattr("app.llm.report_insight_pipeline.monotonic", lambda: clock[0])
    provider = V4Provider(
        source,
        hook=responses(
            source,
            late_error=failure == "repair_exhausted",
            permanent=True,
            clock=clock if failure == "deadline" else None,
        ),
    )
    settings = {"AGENT_REPORT_PROVIDER_TIMEOUT_SECONDS": 180} if failure == "deadline" else {}
    if failure == "budget":
        settings["AGENT_HARD_CAP_CREDITS_PER_REQUEST"] = 0.95
    if failure == "repair_exhausted":
        output = generate(provider, source, **settings)
        assert stages(provider)[-3:] == ["REVIEW-002", "REVIEW-002", "REDUCE-001"]
        assert len(provider.calls) == 7
        final = output.insights[0].assessments
        assert all(item.axes.directness is None for item in final[:6])
        assert all(item.axes.impact is None for item in final[6:])
        assert output.meta.input_tokens == 77 and output.meta.output_tokens == 49
        assert output.meta.cost_usd == 0.021 and output.meta.credits == 1.4
        return
    with pytest.raises(AgentError) as caught:
        generate(provider, source, **settings)
    count = 5
    assert (
        caught.value.code
        == {
            "deadline": "PROVIDER_UNAVAILABLE",
            "budget": "BUDGET_EXCEEDED",
        }[failure]
    )
    assert len(provider.calls) == count
    assert stages(provider)[-1] == "REVIEW-002"
    assert "REDUCE-001" not in stages(provider)
    assert caught.value.details["usage"]["inputTokens"] == 11 * count
    assert caught.value.details["usage"]["credits"] == pytest.approx(0.2 * count)
