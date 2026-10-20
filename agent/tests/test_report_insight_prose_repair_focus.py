"""Prompt focus follows authenticated prose scope without narrowing runtime proof."""

import json
from copy import deepcopy
from dataclasses import replace
from datetime import date

import pytest
from test_report_insight_assessment import framed, payload, request, response
from test_report_insight_mixed_record_preservation import (
    GOOD_REASON,
    build_repair,
    corrected_mixed,
    mixed_case,
    mixed_connection_case,
)
from test_report_insight_preserved_connection_repair import corrected_capacity
from test_report_insight_preserved_decision_repair import (
    full_validator,
    repair_payload,
    wire_validator,
)
from test_report_insight_truncated_repair_preservation import (
    candidate,
    truncated,
)
from test_report_insight_truncated_repair_preservation import (
    corrected as corrected_prefix,
)
from test_report_insight_truncated_repair_preservation import (
    mixed_case as prefix_case,
)
from test_report_insight_truncated_repair_preservation import (
    repair_for as prefix_repair,
)
from test_report_insight_v4_pipeline import V4Provider, generate, stages

from app.core.errors import OutputValidationError
from app.llm import report_insight_service as service
from app.llm.report_insight_assessment import (
    draft_prompt,
    draft_schema,
    draft_to_wire,
    review_prompt,
)
from app.llm.report_insight_fact_index import prompt_fact_index
from app.llm.report_insight_fact_rendering import fact_text_slots_payload
from app.llm.report_insight_prose_repair import focus_prose_repair_finding

GOOD_SOURCE_REASON = "삼성전자의 2026년 검증 계획은 공정 검증과 연결된다."
GOOD_CONDITION = "삼성전자의 공정 검증 일정에 필요한 경우"


def add_claim(finding, identifier, index, text):
    finding.claims.append(
        finding.claims[0].model_copy(
            update={"id": identifier, "text": text, "evidence_sentence_ids": [index]}
        )
    )
    finding.sentences.append(finding.sentences[0].model_copy(update={"index": index, "text": text}))


def focus_case(*, fields=("reason",), neighbor=False):
    source = request(
        ids=(101, 202) if neighbor else (101,),
        text="삼성전자는 생산라인 전체의 가동 중단이 현재 계속된다고 밝혔다.",
    )
    finding = source.findings[0]
    for index, text in (
        (1, "생산라인 전체의 가동 중단이 현재 계속된다."),
        (2, "해당 생산라인의 가동 중단은 현재 계속된다."),
    ):
        add_claim(finding, f"101:{index}", index, text)
    # The selected span remains sentence 0, but prose can use every sentence
    # linked to selected claim 101:0, including this separate sentence.
    finding.claims[0].evidence_sentence_ids.append(3)
    finding.sentences.append(
        finding.sentences[0].model_copy(
            update={"index": 3, "text": "삼성전자는 2026년 검증 계획을 발표했다."}
        )
    )
    add_claim(finding, "101:3", 4, "TSMC는 999억원 투자를 발표했다.")
    # Shared source sentences retain only selected claim origins in the index.
    finding.claims.append(
        finding.claims[0].model_copy(
            update={
                "id": "101:4",
                "text": finding.sentences[3].text,
                "evidence_sentence_ids": [3],
            }
        )
    )
    value = payload(source)
    entry = value["assessments"]["CHIP_MAKER"]["finding101"]
    entry["reason"] = (
        "TSMC의 공정 검증 영향을 확인한다." if "reason" in fields else GOOD_SOURCE_REASON
    )
    for field, index in (("impactBasis", 1), ("urgencyBasis", 2)):
        entry[field] = {"claimId": f"101:{index}", "quote": finding.claims[index].text}
    if "decision.connection.condition" in fields:
        entry.update(relation="CONDITIONAL", condition="TSMC의 공정 검증 일정에 필요한 경우")
    return source, value


def corrected_focus(value):
    fixed = deepcopy(value)
    entry = fixed["assessments"]["CHIP_MAKER"]["finding101"]
    entry["reason"] = GOOD_SOURCE_REASON
    if entry["condition"] is not None:
        entry["condition"] = GOOD_CONDITION
    return fixed


def repair_for(source, value, prompt=draft_prompt):
    raw = response(value, source)
    calls = []
    validate = full_validator(source, calls)
    with pytest.raises(service.ReportAssessmentValidationError) as caught:
        validate(raw)
    calls.clear()
    repair = object.__new__(service.ReportInsightService)._repair_call(
        prompt(source), draft_schema(source), raw.text, caught.value, validate
    )
    return repair, caught.value, raw, calls


@pytest.mark.parametrize("prompt", [draft_prompt, review_prompt], ids=["MAP", "REVIEW"])
@pytest.mark.parametrize(
    "summary, rejected_reason, error_kind",
    [
        (
            "애플은 생산라인 가동 중단과 검증 계획을 발표했다.",
            "TSMC의 공정 검증 영향을 확인한다.",
            "report_evidence_insufficient",
        ),
        (
            "삼성전자는 438억원 규모의 공정 검증 계획을 발표했다.",
            "삼성전자의 762억원 규모 검증 계획은 공정 검증과 연결된다.",
            "report_evidence_insufficient",
        ),
        (
            "원문 문장(claim 101:0)은 삼성전자의 공정 검증 계획을 설명한다.",
            "원문 문장(claim 800:0)의 검증 계획은 공정 검증과 연결된다.",
            "report_expression_policy",
        ),
    ],
    ids=["title-alias", "summary-number", "internal-reference"],
)
def test_focused_retry_reads_original_sentences_without_summary_copying(
    prompt, summary, rejected_reason, error_kind
):
    source, value = focus_case()
    finding = source.findings[0]
    # Claims are generated summaries. Their origin metadata remains useful,
    # but wording absent from the linked source must not anchor a prose retry.
    finding.claims[0].text = summary
    finding.article_title = summary
    value["assessments"]["CHIP_MAKER"]["finding101"]["reason"] = rejected_reason
    original = source.model_dump_json(), deepcopy(value)

    repair, error, raw, calls = repair_for(source, value, prompt)
    assert error_kind in error.error_kinds
    focused = framed(repair.prompt)["findings"][0]
    before = framed(prompt(source))["findings"][0]
    assert focused["claims"] == [
        {key: val for key, val in claim.items() if key != "text"}
        for claim in before["claims"][:3]
    ]
    assert focused["sentences"] == before["sentences"][:4]
    assert focused["sourceFactIndex"] == prompt_fact_index(
        source, {"101:0", "101:1", "101:2"}, finding_id=101
    )
    assert summary not in repair.prompt
    assert rejected_reason not in repair.prompt
    # Prompt simplification is not a grounding exemption. The immutable
    # original request and the validators still reject unsupported prose.
    with pytest.raises(service.ReportAssessmentValidationError) as rejected:
        repair.validate(raw)
    assert error_kind in rejected.value.error_kinds
    good = repair_payload(repair, source, corrected_focus(value))
    wire_validator(repair).validate(good)
    accepted = repair.validate(replace(raw, text=json.dumps(good, ensure_ascii=False)))
    actual = draft_to_wire(accepted.draft, source)["assessments"]["CHIP_MAKER"]["finding101"]
    assert actual["decision"] == json.loads(raw.text)["assessments"]["CHIP_MAKER"][
        "finding101"
    ]["decision"]
    assert actual["reason"] == GOOD_SOURCE_REASON
    assert len(calls) == 2
    assert original == (source.model_dump_json(), value)


def test_focused_retry_preserves_summary_span_basis_but_reads_linked_original_sentences():
    source, value = focus_case()
    claim = source.findings[0].claims[0]
    claim.text = "삼성전자는 생산라인의 가동 중단이 현재 계속된다고 밝혔다."
    value["assessments"]["CHIP_MAKER"]["finding101"]["relationBasis"]["quote"] = claim.text
    repair, _, raw, _ = repair_for(source, value)
    focused = framed(repair.prompt)["findings"][0]
    decision = json.loads(raw.text)["assessments"]["CHIP_MAKER"]["finding101"]["decision"]

    assert claim.text not in repair.prompt
    assert focused["proseRepair"]["selectedEvidence"][0] == {
        "axis": "connection",
        **decision["connection"]["basis"],
        "evidenceSentenceIds": [0, 3],
    }
    assert [sentence["text"] for sentence in focused["sentences"]] == [
        sentence.text for sentence in source.findings[0].sentences[:4]
    ]
    accepted = repair.validate(response(corrected_focus(value), source))
    assert accepted.evidence["CHIP_MAKER"][101].relation_basis.quote == claim.text


@pytest.mark.parametrize("selector", ["reason", "condition"])
def test_display_quote_selection_retains_original_source_catalog(selector):
    source, value = focus_case()
    _, error, raw, _ = repair_for(source, value)
    original = framed(draft_prompt(source))
    finding = original["findings"][0]
    snapshot, fields = error.native_prose_repairs[101]
    decision = json.loads(raw.text)["assessments"]["CHIP_MAKER"]["finding101"]["decision"]
    quotes = {"reason": None, "condition": None}
    quotes[selector] = "existing-display-source"
    before = deepcopy(finding)

    focused = focus_prose_repair_finding(
        finding,
        error.native_prose_sources[101],
        snapshot,
        fields,
        {},
        decision,
        audience="CHIP_MAKER",
        reference_date=original["reportReferenceDate"],
        source_quotes=quotes,
    )
    assert focused is finding
    assert focused == before


def frozen_values(record, fields):
    decision = record["decision"]
    expected = {
        "decision.connection.relation": decision["connection"]["relation"],
        "decision.connection.work": decision["connection"]["work"],
        "decision.effect.impactScope": decision["effect"]["impactScope"],
        "decision.timing.urgencyState": decision["timing"]["urgencyState"],
    }
    for axis in ("connection", "effect", "timing"):
        basis = decision[axis]["basis"]
        if basis is None:
            expected[f"decision.{axis}.basis"] = None
        else:
            for field in ("claimId", "sourceSpanId"):
                expected[f"decision.{axis}.basis.{field}"] = basis[field]
    for path, selector, value in (
        ("reason", "reason", record["reason"]),
        ("decision.connection.condition", "condition", decision["connection"]["condition"]),
    ):
        if path not in fields:
            expected[path] = value
            expected[f"sourceQuotes.{selector}"] = record["sourceQuotes"][selector]
    return expected


@pytest.mark.parametrize("prompt", [draft_prompt, review_prompt], ids=["MAP", "REVIEW"])
@pytest.mark.parametrize(
    "fields",
    [("reason",), ("decision.connection.condition",), ("reason", "decision.connection.condition")],
)
def test_prose_focus_keeps_axis_union_and_all_selected_claim_sentences(prompt, fields):
    source, value = focus_case(fields=fields)
    original = source.model_dump_json(), deepcopy(value)
    repair, error, raw, calls = repair_for(source, value, prompt)
    assert error.native_prose_repairs[101][1] == fields
    focused = framed(repair.prompt)["findings"][0]
    before = framed(prompt(source))["findings"][0]
    selected = {"101:0", "101:1", "101:2"}
    assert focused["claims"] == [
        {key: val for key, val in claim.items() if key != "text"}
        for claim in before["claims"]
        if claim["id"] in selected
    ]
    assert focused["sentences"] == before["sentences"][:4]
    assert "sourceQuoteChoices" not in focused
    assert focused["sourceFactIndex"] == prompt_fact_index(source, selected, finding_id=101)
    # Neither an unselected claim nor its claim alias on a shared sentence leaks.
    for omitted in ("101:3", "101:4", "TSMC", "999"):
        assert omitted not in json.dumps(focused, ensure_ascii=False)
    record = json.loads(raw.text)["assessments"]["CHIP_MAKER"]["finding101"]
    assert focused["proseRepair"] == {
        "editableFields": list(fields),
        "preservedValues": frozen_values(record, fields),
        "selectedEvidence": [
            {
                "axis": axis,
                **record["decision"][axis]["basis"],
                "evidenceSentenceIds": next(
                    claim.evidence_sentence_ids
                    for claim in source.findings[0].claims
                    if claim.id == record["decision"][axis]["basis"]["claimId"]
                ),
            }
            for axis in ("connection", "effect", "timing")
        ],
    }
    wire = repair_payload(repair, source, corrected_focus(value))
    wire_validator(repair).validate(wire)
    accepted = repair.validate(replace(raw, text=json.dumps(wire, ensure_ascii=False)))
    actual = draft_to_wire(accepted.draft, source)["assessments"]["CHIP_MAKER"]["finding101"]
    assert actual["decision"] == wire["assessments"]["CHIP_MAKER"]["finding101"]["decision"]
    assert actual["reason"] == GOOD_SOURCE_REASON
    assert len(calls) == 1
    # Even though the full request still contains TSMC, it is not cited and
    # cannot support the original reason/condition in final runtime validation.
    with pytest.raises(service.ReportAssessmentValidationError):
        repair.validate(raw)
    assert len(calls) == 2
    drift = deepcopy(wire)
    drift["assessments"]["CHIP_MAKER"]["finding101"]["decision"]["effect"]["basis"] = None
    assert not wire_validator(repair).is_valid(drift)
    with pytest.raises(OutputValidationError, match="변경할 수 없습니다"):
        repair.validate(replace(raw, text=json.dumps(drift, ensure_ascii=False)))
    assert len(calls) == 2
    assert original == (source.model_dump_json(), value)


def test_no_basis_focus_keeps_all_eligible_sources_without_promoting_scoring_proof():
    source, _ = focus_case()
    value = payload(source, relation="UNDETERMINED")
    entry = value["assessments"]["CHIP_MAKER"]["finding101"]
    entry["reason"] = "애플의 투자와 공정 업무의 관련성은 미확인이다."
    repair, _, raw, _ = repair_for(source, value)
    before = framed(draft_prompt(source))["findings"][0]
    focused = framed(repair.prompt)["findings"][0]
    expected = deepcopy(before)
    for claim in expected["claims"]:
        claim.pop("text")
    expected.pop("sourceQuoteChoices")
    assert {key: val for key, val in focused.items() if key != "proseRepair"} == expected
    assert focused["proseRepair"]["selectedEvidence"] == []
    fixed = deepcopy(value)
    fixed["assessments"]["CHIP_MAKER"]["finding101"]["reason"] = (
        "TSMC의 999억원 투자와 공정 업무의 관련성은 미확인이다."
    )
    accepted = repair.validate(response(fixed, source))
    assessment = accepted.mapped.insights[0].assessments[0]
    assert assessment.basis_claim_ids == []
    assert set(assessment.axes.model_dump().values()) == {None}
    assert (
        json.loads(raw.text)["assessments"]["CHIP_MAKER"]["finding101"]["decision"]
        == (
            draft_to_wire(accepted.draft, source)["assessments"]["CHIP_MAKER"]["finding101"][
                "decision"
            ]
        )
    )


def test_partial_focus_preserves_neighbor_and_validates_against_complete_original_source():
    source, value = focus_case(neighbor=True)
    before = source.model_dump_json()
    repair, _, raw, calls = repair_for(source, value)
    assert [finding["id"] for finding in framed(repair.prompt)["findings"]] == [101]
    assert len(framed(repair.prompt)["findings"][0]["claims"]) == 3
    fixed = repair_payload(repair, source, corrected_focus(value))
    accepted = repair.validate(replace(raw, text=json.dumps(fixed, ensure_ascii=False)))
    assert set(accepted.evidence["CHIP_MAKER"]) == {101, 202}
    merged = calls[0]["assessments"]["CHIP_MAKER"]
    original = json.loads(raw.text)["assessments"]["CHIP_MAKER"]
    assert merged["finding202"] == original["finding202"]
    assert len(source.findings[0].claims) == 5
    assert source.model_dump_json() == before


@pytest.mark.parametrize("scope", ["connection", "full"])
def test_mixed_record_focus_changes_only_the_prose_record(scope):
    source, value = (
        mixed_connection_case() if scope == "connection" else mixed_case(same_finding=True)
    )
    finding = source.findings[1]
    add_claim(finding, "102:1", 1, "TSMC는 999억원 투자를 발표했다.")
    before = {finding["id"]: finding for finding in framed(draft_prompt(source))["findings"]}
    repair, error, raw, calls = build_repair(source, value, draft_prompt)
    assert set(error.native_prose_repairs) == {102}
    focused = {finding["id"]: finding for finding in framed(repair.prompt)["findings"]}
    assert focused[101] == before[101]
    assert "proseRepair" not in focused[101]
    assert [claim["id"] for claim in focused[102]["claims"]] == ["102:0"]
    assert focused[102]["proseRepair"]["editableFields"] == ["reason"]
    fixed = corrected_capacity(value) if scope == "connection" else corrected_mixed(value)
    fixed["assessments"]["CHIP_MAKER"]["finding102"]["reason"] = GOOD_REASON
    wire = repair_payload(repair, source, fixed)
    repair.validate(replace(raw, text=json.dumps(wire, ensure_ascii=False)))
    assert (
        calls[0]["assessments"]["CHIP_MAKER"]["finding103"]
        == (json.loads(raw.text)["assessments"]["CHIP_MAKER"]["finding103"])
    )


def test_truncated_focus_keeps_full_scope_for_unfinished_and_mixed_records():
    source, value = prefix_case()
    add_claim(source.findings[1], "102:1", 1, "TSMC는 999억원 투자를 발표했다.")
    original = source.model_dump_json()
    raw = truncated(source, value, 3)
    repair, error, calls = prefix_repair(source, raw)
    assert set(error.native_prose_repairs) == {102}
    focused = {finding["id"]: finding for finding in framed(repair.prompt)["findings"]}
    before = {finding["id"]: finding for finding in framed(draft_prompt(source))["findings"]}
    assert set(focused) == {101, 102, 104}
    assert focused[101] == before[101] and focused[104] == before[104]
    assert [claim["id"] for claim in focused[102]["claims"]] == ["102:0"]
    assert focused[102]["proseRepair"]["editableFields"] == ["reason"]
    _, fixed_raw = candidate(repair, source, raw, corrected_prefix(value))
    repair.validate(fixed_raw)
    merged = calls[0]["assessments"]["CHIP_MAKER"]
    assert set(merged) == {"finding101", "finding102", "finding103", "finding104"}
    assert merged["finding103"] == error.preserved_wire["assessments"]["CHIP_MAKER"]["finding103"]
    assert source.model_dump_json() == original and raw.truncated


@pytest.mark.parametrize(
    "defect", ["unselected_source", "selected_other_sentence", "snapshot", "fields"]
)
def test_unauthenticated_prompt_or_snapshot_never_enables_focus(defect):
    source, value = focus_case(fields=("decision.connection.condition",))
    _, error, raw, _ = repair_for(source, value)
    changed = source.model_copy(deep=True)
    if defect == "unselected_source":
        changed.findings[0].sentences[4].text += " 추가 원문"
    elif defect == "selected_other_sentence":
        changed.findings[0].sentences[3].text += " 추가 원문"
    else:
        snapshot, fields = error.native_prose_repairs[101]
        error.native_prose_repairs[101] = (
            snapshot.model_copy(update={"reason": "바뀐 설명"})
            if defect == "snapshot"
            else snapshot,
            ("reason",) if defect == "fields" else fields,
        )
    repair = object.__new__(service.ReportInsightService)._repair_call(
        draft_prompt(changed), draft_schema(changed), raw.text, error, lambda candidate: candidate
    )
    assert framed(repair.prompt)["findings"][0] == framed(draft_prompt(changed))["findings"][0]


def test_legacy_display_catalog_retains_ordinary_source_projection():
    source, value = focus_case()
    _, error, raw, calls = repair_for(source, value)
    prompt = draft_prompt(source)
    original = framed(prompt)
    original["findings"][0]["factTextSlots"] = fact_text_slots_payload(source, finding_id=101)
    instructions = prompt.split("<report-insight-input>", 1)[0]
    legacy = (
        instructions + "<report-insight-input>" + json.dumps(original) + "</report-insight-input>"
    )
    repair = object.__new__(service.ReportInsightService)._repair_call(
        legacy, draft_schema(source), raw.text, error, full_validator(source, calls)
    )
    assert framed(repair.prompt)["findings"] == original["findings"]
    repair.validate(response(corrected_focus(value), source))


def test_map_and_review_focus_keep_full_report_date_across_multiple_batches(monkeypatch):
    source, value = focus_case()
    other = request(
        ids=tuple(range(102, 108)),
        text="삼성전자는 생산라인 전체의 가동 중단이 현재 계속된다고 밝혔다.",
    )
    source.findings.extend(other.findings)
    value["assessments"]["CHIP_MAKER"].update(payload(other)["assessments"]["CHIP_MAKER"])
    source.report.report_date = None
    source.report.report_end_date = None
    source.findings[-1].published_at = date(2026, 9, 30)
    before = source.model_dump_json()
    public_sources = []
    original_validate = service._validated_map_output

    def validate_public(raw, validation_request, **kwargs):
        for finding in validation_request.findings:
            if finding.id == 101:
                public_sources.append(
                    (
                        [claim.id for claim in finding.claims],
                        validation_request.report.report_end_date,
                    )
                )
        return original_validate(raw, validation_request, **kwargs)

    monkeypatch.setattr(service, "_validated_map_output", validate_public)

    def hook(stage, occurrence, _, generated):
        if stage.startswith("REDUCE"):
            return generated
        proposed = value if occurrence == 1 else corrected_focus(value)
        for key in generated["assessments"]["CHIP_MAKER"]:
            generated["assessments"]["CHIP_MAKER"][key] = deepcopy(
                proposed["assessments"]["CHIP_MAKER"][key]
            )
        return generated

    provider = V4Provider(source, hook=hook)
    result = generate(provider, source)
    assert stages(provider) == [
        "MAP-001",
        "MAP-001",
        "MAP-002",
        "REVIEW-001",
        "REVIEW-001",
        "EXPLAIN-001",
        "EXPLAIN-001",
        "REDUCE-001",
    ]
    assert all(provider.schema_validity)
    for first_index, repair_index in ((0, 1), (3, 4)):
        first = framed(provider.calls[first_index]["prompt"])
        repair = framed(provider.calls[repair_index]["prompt"])
        # Neither the first MAP nor REVIEW contains the latest dated finding.
        assert 107 not in [finding["id"] for finding in first["findings"]]
        assert all(finding["publishedAt"] == "2026-09-25" for finding in first["findings"])
        assert first["reportReferenceDate"] == repair["reportReferenceDate"] == "2026-09-30"
        assert first["report"]["reportEndDate"] is repair["report"]["reportEndDate"] is None
        assert [finding["id"] for finding in repair["findings"]] == [101]
        focused = repair["findings"][0]
        assert focused["proseRepair"]["editableFields"] == ["reason"]
        assert [claim["id"] for claim in focused["claims"]] == ["101:0", "101:1", "101:2"]
        assert "TSMC" not in json.dumps(focused, ensure_ascii=False)
        original_record = provider.wire_payloads[first_index]["assessments"]["CHIP_MAKER"][
            "finding101"
        ]
        repaired_record = provider.wire_payloads[repair_index]["assessments"]["CHIP_MAKER"][
            "finding101"
        ]
        assert repaired_record["decision"] == original_record["decision"]
        assert repaired_record["reason"] == GOOD_SOURCE_REASON
    # Only model input is focused; native/public acceptance still sees all
    # original eligible claims and the full report's time anchor on every call.
    assert public_sources
    assert all(
        claim_ids == ["101:0", "101:1", "101:2", "101:3", "101:4"]
        and reference == date(2026, 9, 30)
        for claim_ids, reference in public_sources
    )
    assert [assessment.finding_id for assessment in result.insights[0].assessments] == list(
        range(101, 108)
    )
    assert result.insights[0].assessments[0].reason == GOOD_SOURCE_REASON
    assert result.meta.input_tokens == 88 and result.meta.output_tokens == 56
    assert result.meta.cost_usd == 0.024 and result.meta.credits == 1.6
    assert source.model_dump_json() == before
