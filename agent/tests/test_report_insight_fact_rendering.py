"""Factual values come from source clauses, never model-owned substitutions."""

import json
from copy import deepcopy

import pytest
from test_report_insight_assessment import payload, request

from app.llm.base import ProviderResponse, ProviderUsage
from app.llm.report_insight_assessment import (
    ReportAssessmentDraftValidationError,
    draft_prompt,
    draft_to_wire,
    merge_drafts,
    review_prompt,
    validate_template_draft,
)
from app.llm.report_insight_fact_rendering import (
    FactTemplateError,
    build_fact_text_catalog,
    fact_text_slots_payload,
    render_fact_template,
    render_reduce_templates,
    split_rendered_prose,
)


def response(value):
    return ProviderResponse(
        text=json.dumps(value, ensure_ascii=False),
        provider="mock",
        model="mock",
        usage=ProviderUsage(),
    )


def context(text="삼성전자의 생산량은 20개다.", *, ids=(101,)):
    source = request(ids=ids, text=text)
    catalog = build_fact_text_catalog(source)
    return source, catalog, catalog.slots[0]


def template(slot, prose="생산 계획에 미칠 영향을 확인할 필요가 있다."):
    return f"{{{{fact:{slot.slot_id}}}}} {prose}"


def test_complete_clause_preserves_subject_quantity_unit_and_factual_state():
    text = "삼성전자는 2027년 생산량을 20개로 확대할 계획이라고 밝혔다."
    _, catalog, slot = context(text)
    rendered = render_fact_template(template(slot), catalog, ["101:0"], max_length=180)
    assert rendered.text == f"원문: 「{text}」 해석: 생산 계획에 미칠 영향을 확인할 필요가 있다."
    assert rendered.interpretation == "생산 계획에 미칠 영향을 확인할 필요가 있다."
    assert "source-" not in rendered.text
    assert "{{" not in rendered.text
    assert rendered.slot.source_text == text
    assert not rendered.fact_quote_omitted_for_length


@pytest.mark.parametrize(
    "value",
    [
        "삼성전자의 생산량은 10개다.",
        "생산량은 20개다.",
        "매출은 100억원으로 증가했다.",
        "내년 생산 계획의 영향을 확인한다.",
        "HBM4 공급 계획을 확인한다.",
        "삼성전자의 영향은 조건부다.",
        "Samsung 공급 계획을 확인한다.",
        "Acme 공급 계획을 확인한다.",
        "공장을 완공했다.",
        "장기 공급 계약을 체결했다.",
        "투자했다.",
        "수주가 확정이다.",
        "출하를 시작했다.",
        "투자할 계획이다.",
        "공급할 예정이다.",
        "양산이 예정되어 있다.",
        "공장 건설을 계획했다.",
        "상반기 생산 일정을 점검한다.",
        "공급량이 두 배로 늘었다.",
    ],
)
def test_freehand_hard_facts_are_rejected_even_if_some_values_exist_in_source(value):
    _, catalog, _ = context()
    with pytest.raises(FactTemplateError, match="report_fact_template_required"):
        render_fact_template(value, catalog, ["101:0"], max_length=700)


@pytest.mark.parametrize(
    "value",
    [
        "공급 계약의 이행 여부를 확인할 필요가 있다.",
        "양산 전환 여부에 따라 공정 검증 일정의 조정이 필요할 수 있다.",
        "확정 수주로 연결되는 경우 장비 인도 일정을 재검토한다.",
        "전력 확보가 필요한 경우 운영 일정을 조정한다.",
        "투자 집행 여부와 공급 여력의 변화를 관찰한다.",
        "공급했다면 운영을 조정한다.",
        "공급했으면 운영을 조정한다.",
    ],
)
def test_ordinary_work_vocabulary_remains_free_form(value):
    _, catalog, _ = context()
    rendered = render_fact_template(value, catalog, ["101:0"], max_length=700)
    assert rendered.text == value
    assert rendered.slot is None


@pytest.mark.parametrize(
    "builder",
    [
        lambda slot: "그것은 사실이 아니며 " + template(slot),
        lambda slot: "공급량은 " + template(slot),
        lambda slot: template(slot) + " " + template(slot),
        lambda slot: "{{fact:invented}} 업무 영향을 확인한다.",
        lambda slot: f"{{{{fact:{slot.slot_id}}}}}는 사실이다.",
    ],
)
def test_markers_cannot_be_negated_spliced_duplicated_or_invented(builder):
    _, catalog, slot = context()
    with pytest.raises(FactTemplateError, match="report_fact_slot_position"):
        render_fact_template(builder(slot), catalog, ["101:0"], max_length=700)


def test_valid_handle_from_another_finding_is_not_authorized_by_request_membership():
    _, catalog, slot = context(ids=(101, 102))
    with pytest.raises(FactTemplateError, match="report_fact_slot_scope"):
        render_fact_template(template(slot), catalog, ["102:0"], max_length=700)


def test_source_templates_never_promote_claim_summary_or_title_to_original_evidence():
    source, _, _ = context()
    source.findings[0].claims[0].text = "요약의 다른 기업은 99개를 공급했다."
    source.findings[0].article_title = "제목에만 있는 기업은 555개를 공급했다."
    slots = fact_text_slots_payload(source, include_source_text=True)
    assert len(slots) == 1
    assert slots[0]["sourceText"] == "삼성전자의 생산량은 20개다."
    assert "99개" not in json.dumps(slots, ensure_ascii=False)
    assert "555개" not in json.dumps(slots, ensure_ascii=False)
    assert slots[0]["sourceStart"] == 0
    assert slots[0]["sourceEnd"] == len(slots[0]["sourceText"])


def test_forecast_attribution_is_retained_and_unparsed_quotes_are_not_called_verified_facts():
    source, _, _ = context("관계자는 공급 물량이 증가할 수 있다는 의견을 제시했다.")
    source.findings[0].claims[0].claim_type = "OPINION"
    source.findings[0].claims[0].attributed_to = "관계자"
    slot = fact_text_slots_payload(source, include_source_text=True)[0]
    assert slot["claimTypes"] == ["OPINION"]
    assert slot["attributedTo"] == ["관계자"]
    assert slot["factIds"] == []
    assert slot["sourceText"].startswith("관계자는")


def test_long_optional_quote_is_omitted_without_truncating_or_rewriting_original_source():
    source, catalog, slot = context(
        "삼성전자의 생산량은 20개다. " + "관련 조건은 추가 확인이 필요하다. " * 8
    )
    before = source.model_dump_json()
    interpretation = "생산 계획에 미칠 영향을 확인할 필요가 있다."
    result = render_fact_template(
        template(slot, interpretation), catalog, ["101:0"], max_length=180
    )
    assert result.text == result.interpretation == interpretation
    assert result.slot is slot
    assert result.fact_quote_omitted_for_length
    assert result.slot.source_text == source.findings[0].sentences[0].text
    assert source.model_dump_json() == before


@pytest.mark.parametrize("with_slot", [False, True])
def test_layout_fallback_cannot_omit_or_truncate_an_overlong_interpretation(with_slot):
    _, catalog, slot = context()
    interpretation = "생산 계획에 미칠 영향을 확인할 필요가 있다. " * 8
    value = template(slot, interpretation) if with_slot else interpretation
    with pytest.raises(FactTemplateError, match="report_fact_rendered_length"):
        render_fact_template(value, catalog, ["101:0"], max_length=180)


@pytest.mark.parametrize(
    ("mutation", "expected"),
    [
        ("foreign", "report_fact_slot_scope"),
        ("unknown", "report_fact_slot_unknown"),
        ("company", "report_fact_template_required"),
        ("number", "report_fact_template_required"),
        ("state", "report_fact_template_required"),
    ],
)
def test_long_quote_layout_fallback_never_bypasses_source_or_prose_validation(mutation, expected):
    _, catalog, slot = context(
        "삼성전자의 생산량은 20개다. " + "관련 조건은 추가 확인이 필요하다. " * 8
    )
    prose = {
        "company": "삼성전자의 생산 일정을 확인한다.",
        "number": "생산량은 999개다.",
        "state": "공장 가동이 진행 중이다.",
    }.get(mutation, "생산 조건을 확인한다.")
    value = template(slot, prose)
    refs = ["102:0"] if mutation == "foreign" else ["101:0"]
    if mutation == "unknown":
        value = value.replace(slot.slot_id, "source-" + "0" * 24)
    with pytest.raises(FactTemplateError, match=expected):
        render_fact_template(value, catalog, refs, max_length=180)


def test_exact_source_identity_and_claim_scope_are_required_to_split_rendered_prose():
    _, catalog, slot = context()
    result = render_fact_template(template(slot), catalog, ["101:0"], max_length=180)
    assert split_rendered_prose(result.text, catalog, ["101:0"]).slot == slot
    assert split_rendered_prose(result.text, catalog, ["102:0"]).slot is None
    forged = result.text.replace("20개", "10개")
    assert split_rendered_prose(forged, catalog, ["101:0"]).slot is None


def test_new_native_map_and_review_use_rendering_then_preserve_public_projection_on_merge():
    source = request(text="삼성전자는 생산라인 전체의 가동 중단이 현재 계속된다고 밝혔다.")
    catalog = build_fact_text_catalog(source)
    wire = draft_to_wire(payload(source), source)
    original_reason = wire["assessments"]["CHIP_MAKER"]["finding101"]["reason"]
    wire["assessments"]["CHIP_MAKER"]["finding101"]["reason"] = template(
        catalog.slots[0], original_reason
    )
    validated = validate_template_draft(response(wire), source)
    reason = validated.mapped.insights[0].assessments[0].reason
    assert reason.startswith("원문: 「삼성전자는")
    assert reason.endswith(original_reason)
    assert merge_drafts(source, validated).mapped == validated.mapped
    assert "factTextSlots" in draft_prompt(source)
    assert "factTextSlots" in review_prompt(source)


def test_native_template_failure_is_located_and_does_not_change_neighboring_records():
    source = request(ids=(101, 102))
    wire = draft_to_wire(payload(source), source)
    unchanged = deepcopy(wire["assessments"]["CHIP_MAKER"]["finding102"])
    wire["assessments"]["CHIP_MAKER"]["finding101"]["reason"] = "삼성전자의 생산량은 99개다."
    with pytest.raises(ReportAssessmentDraftValidationError) as captured:
        validate_template_draft(response(wire), source)
    error = captured.value
    assert error.failed_finding_ids == (101,)
    assert error.validation_issues[0].field == "assessments[101].reason"
    assert error.validation_issues[0].claim_ids == ("101:0",)
    assert error.validation_issues[0].rule_id == "report_fact_template_required"
    assert wire["assessments"]["CHIP_MAKER"]["finding102"] == unchanged


def reduce_wire():
    return {
        "insights": [
            {
                "audience": "CHIP_MAKER",
                "headline": "공정 준비 조건 점검",
                "overview": [
                    {
                        "text": "업무 영향을 확인한다.",
                        "assumption": "공정에 적용되는 경우",
                        "basisClaimIds": ["101:0"],
                    }
                ],
                "implications": [
                    {
                        "text": "공정 준비를 점검한다.",
                        "mechanism": "공정 조건을 확인한다.",
                        "assumption": "공정에 적용되는 경우",
                        "falsifiedBy": "적용이 철회되는 경우",
                        "basisClaimIds": ["101:0"],
                    }
                ],
                "watchItems": [
                    {
                        "topic": "공정 준비",
                        "indicator": "공정 적용 여부",
                        "trigger": "적용 철회",
                        "basisClaimIds": ["101:0"],
                    }
                ],
            }
        ]
    }


@pytest.mark.parametrize(
    "group,field",
    [
        (None, "headline"),
        ("overview", "text"),
        ("overview", "assumption"),
        ("implications", "text"),
        ("implications", "mechanism"),
        ("implications", "assumption"),
        ("implications", "falsifiedBy"),
        ("watchItems", "topic"),
        ("watchItems", "indicator"),
        ("watchItems", "trigger"),
    ],
)
def test_every_reduce_human_visible_field_enforces_source_owned_facts(group, field):
    source, _, slot = context()
    value = reduce_wire()
    record = value["insights"][0] if group is None else value["insights"][0][group][0]
    record[field] = "삼성전자의 생산량은 10개다."
    _, issues = render_reduce_templates(value, source, {"CHIP_MAKER": ["101:0"]})
    assert len(issues) == 1
    assert issues[0].field == (field if group is None else f"{group}[0].{field}")
    record[field] = template(slot)
    rendered, issues = render_reduce_templates(value, source, {"CHIP_MAKER": ["101:0"]})
    assert issues == ()
    result = rendered["insights"][0] if group is None else rendered["insights"][0][group][0]
    assert "20개" in result[field]
    assert "source-" not in result[field]
    assert record[field].startswith("{{fact:")


def test_reduce_unit_cannot_borrow_another_claim_or_another_audiences_sources():
    source, _, slot = context(ids=(101, 102))
    value = reduce_wire()
    value["insights"][0]["overview"][0]["text"] = template(slot)
    value["insights"][0]["overview"][0]["basisClaimIds"] = ["102:0"]
    _, issues = render_reduce_templates(value, source, {"CHIP_MAKER": ["101:0", "102:0"]})
    assert issues[0].rule_id == "report_fact_slot_scope"
    value["insights"][0]["overview"][0]["basisClaimIds"] = ["101:0"]
    _, issues = render_reduce_templates(value, source, {"CHIP_MAKER": ["102:0"]})
    assert issues[0].rule_id == "report_fact_slot_scope"
