"""MAP quotes come from validated bases; REDUCE retains separate source selections."""

from copy import deepcopy

import pytest
from test_report_insight_assessment import request
from test_report_insight_v4_pipeline import V4Provider, generate, stages

from app.llm.report_insight_fact_rendering import fact_text_slots_payload
from app.llm.report_insight_service import _semantic_synthesis_view
from app.schemas.report_insight import ReportInsightReduceOutput


def select_source(record, field, slot):
    fields = (
        "reason",
        "condition",
        "headline",
        "text",
        "mechanism",
        "assumption",
        "falsifiedBy",
        "topic",
        "indicator",
        "trigger",
    )
    record.setdefault("sourceQuotes", {name: None for name in fields if name in record})[field] = (
        slot["slotId"]
    )


def test_production_basis_and_reduce_quotes_preserve_source_without_public_internal_handles():
    source = request(text="삼성전자는 생산라인 전체의 가동 중단이 현재 계속된다고 밝혔다.")
    snapshot = source.model_dump_json(by_alias=True)
    original = source.findings[0].sentences[0].text

    def hook(stage, occurrence, data, value):
        if stage.startswith(("MAP", "REVIEW")):
            finding = data["findings"][0]
            assert "factTextSlots" not in finding
            assert finding["sentences"][0]["text"] == original
        else:
            slot = data["factTextSlots"]["CHIP_MAKER"][0]
            overview = value["insights"][0]["overview"][0]
            select_source(overview, "text", slot)
            select_source(overview, "assumption", slot)
            overview["assumption"] = "같은 생산 제약이 검증 준비에 연결되는 경우"
        return value

    provider = V4Provider(source, hook=hook)
    output = generate(provider, source)
    assert stages(provider) == ["MAP-001", "REVIEW-001", "REDUCE-001"]
    for wire in provider.wire_payloads[:2]:
        assert wire["assessments"]["CHIP_MAKER"]["finding101"]["sourceQuotes"] == {
            "reason": None,
            "condition": None,
        }
    insight = output.insights[0]
    assert insight.assessments[0].reason.startswith(f"원문: 「{original}」 해석: ")
    assert insight.overview[0].text.startswith(f"원문: 「{original}」 해석: ")
    assert insight.overview[0].assumption.startswith(f"원문: 「{original}」 미확인 가정: ")
    assert "{{fact:" not in output.model_dump_json()
    assert "source-" not in output.model_dump_json()
    assert source.model_dump_json(by_alias=True) == snapshot


def test_production_omits_only_long_display_quotes_and_preserves_facts_citations_and_other_text():
    source = request(
        text="삼성전자는 생산라인 전체의 가동 중단이 현재 계속된다고 밝혔다."
        + " 관련 조건은 추가 확인이 필요하다는 설명이다." * 6
    )
    snapshot = source.model_dump_json(by_alias=True)
    expected = {}

    def hook(stage, occurrence, data, value):
        if stage.startswith(("MAP", "REVIEW")):
            assert "factTextSlots" not in data["findings"][0]
            record = value["assessments"]["CHIP_MAKER"]["finding101"]
            expected["reason"] = record["reason"]
        else:
            slot = data["factTextSlots"]["CHIP_MAKER"][0]
            insight = value["insights"][0]
            expected["headline"] = insight["headline"]
            select_source(insight, "headline", slot)
            overview = insight["overview"][0]
            expected["overview"] = deepcopy(overview)
            select_source(overview, "text", slot)
        return value

    provider = V4Provider(source, hook=hook)
    output = generate(provider, source)
    assert stages(provider) == ["MAP-001", "REVIEW-001", "REDUCE-001"]
    insight = output.insights[0]
    assert insight.assessments[0].reason == expected["reason"]
    assert insight.assessments[0].basis_claim_ids == ["101:0"]
    assert insight.headline == expected["headline"]
    # The overview has room for the complete source, so its quote stays exact.
    assert insight.overview[0].text == (
        f"원문: 「{source.findings[0].sentences[0].text}」 해석: {expected['overview']['text']}"
    )
    assert insight.overview[0].basis_claim_ids == expected["overview"]["basisClaimIds"]
    assert insight.overview[0].assumption == expected["overview"]["assumption"]
    assert source.model_dump_json(by_alias=True) == snapshot
    assert "fact_quote_omitted_for_length" not in output.model_dump_json()


def test_production_template_failure_repairs_only_failed_reduce_unit_and_keeps_good_template():
    source = request(text="삼성전자는 생산라인 전체의 가동 중단이 현재 계속된다고 밝혔다.")
    initial = []

    def hook(stage, occurrence, data, value):
        if stage != "REDUCE-001":
            return value
        slot = data["factTextSlots"]["CHIP_MAKER"][0]
        overview = value["insights"][0]["overview"]
        select_source(overview[0], "text", slot)
        second = deepcopy(overview[0])
        second["text"] = "같은 생산 제약의 지속 여부에 따라 검증 순서를 조정해야 한다."
        overview.append(second)
        if occurrence == 1:
            overview[1]["text"] = "삼성전자의 생산량은 999개다."
            initial.append(deepcopy(overview[0]))
        return value

    provider = V4Provider(source, hook=hook)
    output = generate(provider, source)
    assert stages(provider)[-2:] == ["REDUCE-001", "REDUCE-001"]
    repair_schema = provider.calls[-1]["response_schema"]
    assert repair_schema["title"] == "ReportInsightReduceRepair"
    assert len(repair_schema["properties"]["repairs"]["properties"]) == 1
    assert output.insights[0].overview[0].text.startswith("원문: 「삼성전자는")
    assert provider.wire_payloads[-2]["insights"][0]["overview"][0] == initial[0]
    assert "999" not in output.model_dump_json()


@pytest.mark.parametrize("mutation", ["source", "interpretation", "refs"])
def test_semantic_view_never_strips_an_unauthenticated_or_mutated_public_projection(mutation):
    source = request()
    slot = fact_text_slots_payload(source, include_source_text=True)[0]
    rendered = f"원문: 「{slot['sourceText']}」 해석: 생산 계획의 영향을 확인한다."
    native = ReportInsightReduceOutput.model_validate(
        {
            "insights": [
                {
                    "audience": "CHIP_MAKER",
                    "headline": "공정 준비 조건 점검",
                    "overview": [
                        {
                            "text": rendered,
                            "assumption": "공정에 적용되는 경우",
                            "basisClaimIds": ["101:0"],
                        }
                    ],
                    "implications": [],
                    "watchItems": [],
                }
            ]
        }
    )
    changed = native.model_copy(deep=True)
    if mutation == "source":
        changed.insights[0].overview[0].text = rendered.replace("생산라인", "가공시설")
    elif mutation == "interpretation":
        changed.insights[0].overview[0].text = rendered + " 공급량은 999개다."
    else:
        changed.insights[0].overview[0].basis_claim_ids = ["102:0"]
    result = _semantic_synthesis_view(changed, source, {"CHIP_MAKER": ["101:0"]}, native)
    assert result == changed
    assert result.insights[0].overview[0].text.startswith("원문:")


@pytest.mark.parametrize("mutate_good_reason", [False, True])
@pytest.mark.parametrize("finding_ids", [(101,), (101, 102)])
def test_condition_repair_freezes_raw_good_reason_and_decisions_before_automatic_display(
    mutate_good_reason, finding_ids
):
    from app.core.errors import StructuredOutputExhaustedError

    source = request(ids=finding_ids)
    original_reason = []

    def hook(stage, occurrence, data, value):
        if not stage.startswith(("MAP", "REVIEW")):
            return value
        record = value["assessments"]["CHIP_MAKER"]["finding101"]
        assert "factTextSlots" not in data["findings"][0]
        if stage == "MAP-001" and occurrence == 1:
            original_reason.append(record["reason"])
            record["condition"] = "삼성전자의 준비가 공정 검증 일정에 필요한 경우"
        else:
            record["condition"] = "해당 준비가 공정 검증 일정에 필요한 경우"
            if mutate_good_reason:
                record["reason"] = "다른 검증 준비의 영향을 확인한다."
        return value

    provider = V4Provider(source, relation="CONDITIONAL", hook=hook, validate_wire=False)
    if mutate_good_reason:
        with pytest.raises(StructuredOutputExhaustedError):
            generate(provider, source)
        assert provider.schema_validity == [True, False]
    else:
        output = generate(provider, source)
        assert output.insights[0].assessments[0].reason.startswith("원문: 「제조사는")
        assert "삼성전자" not in output.model_dump_json()
    repair = provider.calls[1]
    record = repair["response_schema"]["properties"]["assessments"]["properties"]["CHIP_MAKER"][
        "properties"
    ]["finding101"]
    assert record["properties"]["reason"]["const"] == original_reason[0]
    first, retry = (
        wire["assessments"]["CHIP_MAKER"]["finding101"] for wire in provider.wire_payloads[:2]
    )
    assert first["sourceQuotes"] == retry["sourceQuotes"] == {"reason": None, "condition": None}
    assert not first["reason"].startswith("원문:")
    if not mutate_good_reason:
        assert retry["reason"] == first["reason"]
    assert retry["decision"]["effect"] == first["decision"]["effect"]
    assert retry["decision"]["timing"] == first["decision"]["timing"]
    assert {
        key: item for key, item in retry["decision"]["connection"].items() if key != "condition"
    } == {key: item for key, item in first["decision"]["connection"].items() if key != "condition"}
    assert "이번 수리는 진단된 reason/condition" in repair["prompt"]
