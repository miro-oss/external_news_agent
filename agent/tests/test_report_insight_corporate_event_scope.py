"""Corporate events must come from selected raw sentences, including on retry."""

from copy import deepcopy

import pytest
from jsonschema import Draft202012Validator
from test_report_insight_assessment import framed, request
from test_report_insight_investment_quote_scope import (
    AUDIENCE,
    REDUCE_FIELDS,
    full_synthesis,
    response,
)
from test_report_insight_reduce_partial_repair import public_reduce_projection, repair_jobs
from test_report_insight_v4_pipeline import V4Provider, generate, stages

from app.llm import report_insight_service as service
from app.llm.report_insight_assessment import validate_draft

RAW = "삼성전자는 생산라인의 가동 중단이 현재 계속된다고 밝혔다."
EVENT = "삼성전자는 IPO를 통해 자금 조달을 추진한다고 밝혔다."
GOOD_REASON = "삼성전자의 생산 제약은 수급과 투자 판단에 연결된다."
EVENT_REASONS = (
    "삼성전자의 IPO는 생산 제약의 대응과 투자 판단에 연결된다.",
    "삼성전자의 자금 조달은 생산 제약의 대응과 투자 판단에 연결된다.",
)
SCOPES = ("unselected_claim", "other_finding", "title", "claim_summary")


def scoped_source(scope):
    source = request(ids=(101, 102), text=RAW, audiences=(AUDIENCE,))
    finding = source.findings[0]
    if scope == "unselected_claim":
        finding.claims.append(
            finding.claims[0].model_copy(
                update={"id": "101:1", "text": EVENT, "evidence_sentence_ids": [1]}
            )
        )
        finding.sentences.append(
            finding.sentences[0].model_copy(update={"index": 1, "text": EVENT})
        )
    elif scope == "other_finding":
        source.findings[1].claims[0].text += " " + EVENT
        source.findings[1].sentences[0].text += " " + EVENT
    elif scope == "title":
        finding.article_title = EVENT
    elif scope == "claim_summary":
        # A claim summary can carry event vocabulary without the raw sentence
        # establishing that transaction. Keep its original factual clause valid.
        finding.claims[0].text += " IPO 자금 조달"
    elif scope == "selected":
        finding.claims[0].text += " " + EVENT
        finding.sentences[0].text += " " + EVENT
    else:
        raise AssertionError(scope)
    return source


def flat_hook(source, mutate=None):
    """Keep wire bases on raw text even when a claim summary is contaminated."""
    sources = {finding.id: finding for finding in source.findings}

    def hook(stage, occurrence, data, value):
        if stage.startswith(("MAP", "REVIEW")):
            for record in value["assessments"][AUDIENCE].values():
                raw = sources[record["findingId"]].sentences[0].text
                for field in ("relationBasis", "impactBasis", "urgencyBasis"):
                    if record[field] is not None:
                        record[field]["quote"] = raw
                record["reason"] = GOOD_REASON
        return mutate(stage, occurrence, data, value) if mutate else value

    return hook


@pytest.mark.parametrize("scope", SCOPES)
@pytest.mark.parametrize("stage", ["MAP-001", "REVIEW-001"])
@pytest.mark.parametrize("reason", EVENT_REASONS)
def test_native_repair_rejects_event_outside_selected_raw_and_preserves_valid_decisions(
    monkeypatch, scope, stage, reason
):
    source = scoped_source(scope)
    before = source.model_dump_json()
    failures = []
    original_repair = service.ReportInsightService._repair_call

    def capture(self, prompt, schema, raw, error, validate):
        failures.append(error)
        return original_repair(self, prompt, schema, raw, error, validate)

    monkeypatch.setattr(service.ReportInsightService, "_repair_call", capture)

    def mutate(current, occurrence, data, value):
        if current == stage and occurrence == 1:
            value["assessments"][AUDIENCE]["finding101"]["reason"] = reason
        return value

    provider = V4Provider(source, hook=flat_hook(source, mutate))
    result = generate(provider, source)
    expected = ["MAP-001", "REVIEW-001", "REDUCE-001"]
    expected.insert(expected.index(stage), stage)
    assert stages(provider) == expected
    assert len(failures) == 1
    assert "report_evidence_insufficient" in failures[0].error_kinds
    assert "source_binding" in failures[0].fact_repair_kinds
    assert any(
        issue.field == "assessments[101].reason"
        and issue.claim_ids == ("101:0",)
        and issue.error_kind == "report_evidence_insufficient"
        for issue in failures[0].validation_issues
    )
    index = expected.index(stage)
    original = provider.wire_payloads[index]["assessments"][AUDIENCE]
    repaired = provider.wire_payloads[index + 1]["assessments"][AUDIENCE]
    assert set(repaired) == {"finding101"}
    assert repaired["finding101"]["decision"] == original["finding101"]["decision"]
    assert repaired["finding101"]["reason"] == GOOD_REASON
    changed_decision = deepcopy(provider.wire_payloads[index + 1])
    changed_decision["assessments"][AUDIENCE]["finding101"]["decision"]["timing"][
        "urgencyState"
    ] = "MONITOR"
    assert not Draft202012Validator(provider.calls[index + 1]["response_schema"]).is_valid(
        changed_decision
    )
    focused = framed(provider.calls[index + 1]["prompt"])["findings"]
    assert [finding["id"] for finding in focused] == [101]
    assert [claim["id"] for claim in focused[0]["claims"]] == ["101:0"]
    assert focused[0]["sentences"] == [{"index": 0, "text": RAW}]
    assert focused[0]["proseRepair"]["editableFields"] == ["reason"]
    assert GOOD_REASON in result.insights[0].assessments[1].reason
    assert result.meta.input_tokens == 44 and result.meta.output_tokens == 28
    assert source.model_dump_json() == before


@pytest.mark.parametrize("scope", SCOPES)
@pytest.mark.parametrize("reason", EVENT_REASONS)
def test_reduce_item_cannot_borrow_corporate_event_from_other_claims_or_metadata(scope, reason):
    source = scoped_source(scope)

    def mutate(stage, occurrence, data, value):
        value = full_synthesis(stage, occurrence, data, value)
        if stage == "REDUCE-001":
            overview = value["insights"][0]["overview"][0]
            overview["basisClaimIds"] = ["101:0"]
            overview["text"] = reason if occurrence == 1 else GOOD_REASON
        return value

    provider = V4Provider(source, hook=flat_hook(source, mutate))
    result = generate(provider, source)
    assert stages(provider) == ["MAP-001", "REVIEW-001", "REDUCE-001", "REDUCE-001"]
    assert result.insights[0].overview[0].text == GOOD_REASON
    assert result.insights[0].overview[0].basis_claim_ids == ["101:0"]
    initial = provider.wire_payloads[-2]["insights"][0]
    assert result.insights[0].headline == initial["headline"]
    assert result.meta.input_tokens == 44 and result.meta.output_tokens == 28


@pytest.mark.parametrize("group,field", REDUCE_FIELDS)
def test_every_reduce_prose_field_repairs_unselected_event_without_replacing_healthy_units(
    group, field
):
    source = scoped_source("claim_summary")
    before = source.model_dump_json()

    def mutate(stage, occurrence, data, value):
        value = full_synthesis(stage, occurrence, data, value)
        if stage == "REDUCE-001" and occurrence == 1:
            insight = value["insights"][0]
            record = insight if group is None else insight[group][0]
            record[field] = EVENT_REASONS[0]
        return value

    provider = V4Provider(source, hook=flat_hook(source, mutate))
    result = generate(provider, source)
    assert stages(provider) == ["MAP-001", "REVIEW-001", "REDUCE-001", "REDUCE-001"]
    jobs = repair_jobs(provider.calls[-1]["prompt"])
    assert len(jobs) == 1
    assert jobs[0]["group"] == (group or "headline")
    assert jobs[0]["index"] == (None if group is None else 0)
    expected_field = field if group is None else f"{group}[0].{field}"
    assert any(
        diagnostic["field"] == expected_field
        and diagnostic["errorKind"] == "report_evidence_insufficient"
        and any(rule["rule"] == "source_binding" for rule in diagnostic["rules"])
        for diagnostic in jobs[0]["diagnostics"]
    )
    assert set(provider.wire_payloads[-1]["repairs"]) == {jobs[0]["key"]}
    original = provider.wire_payloads[-2]["insights"][0]
    final = result.insights[0].model_dump(by_alias=True, mode="json")
    for healthy in ("headline", "overview", "implications", "watchItems"):
        if healthy != (group or "headline"):
            assert final[healthy] == public_reduce_projection(original[healthy])
    if group == "overview":
        assert final["overview"][1] == public_reduce_projection(original["overview"][1])
    assert all(GOOD_REASON in assessment.reason for assessment in result.insights[0].assessments)
    assert result.meta.input_tokens == 44 and result.meta.output_tokens == 28
    assert source.model_dump_json() == before


@pytest.mark.parametrize("position", ["before", "after"])
def test_modified_public_quote_labels_cannot_hide_an_unsupported_corporate_event(position):
    source = scoped_source("claim_summary")
    provider = V4Provider(source, hook=flat_hook(source))
    generate(provider, source)
    draft = validate_draft(response(provider.wire_payloads[0]), source)
    assessment = draft.mapped.insights[0].assessments[0]
    quote = f"원문: 「{RAW}」 해석: {GOOD_REASON}"
    altered = assessment.model_copy(
        update={
            "reason": EVENT_REASONS[0] + " " + quote
            if position == "before"
            else quote + " " + EVENT_REASONS[0]
        }
    )
    claims, evidence = service._source_context(source)
    for native in (None, draft.evidence[AUDIENCE][101]):
        errors = service._assessment_prose_errors(
            altered, native, assessment.basis_claim_ids, evidence, claims, source, audience=AUDIENCE
        )
        assert any("source_binding" in error.fact_repair_kinds for _, error in errors)
        assert all(field == "reason" for field, _ in errors)


@pytest.mark.parametrize("reason", EVENT_REASONS)
def test_selected_corporate_event_passes_map_review_and_final_assessment(reason):
    source = scoped_source("selected")

    def mutate(stage, occurrence, data, value):
        if stage.startswith(("MAP", "REVIEW")):
            value["assessments"][AUDIENCE]["finding101"]["reason"] = reason
        return value

    provider = V4Provider(source, hook=flat_hook(source, mutate))
    result = generate(provider, source)
    assert stages(provider) == ["MAP-001", "REVIEW-001", "REDUCE-001"]
    assert reason in result.insights[0].assessments[0].reason
    assert result.meta.input_tokens == 33 and result.meta.output_tokens == 21


@pytest.mark.parametrize("stage", ["MAP-001", "REVIEW-001"])
@pytest.mark.parametrize(
    "reason",
    [
        "삼성전자의 IPO가 완료됐다. 생산 제약은 투자 판단에 연결된다.",
        "삼성전자는 IPO를 통해 자금 조달을 완료했다. 생산 제약은 투자 판단에 연결된다.",
    ],
)
def test_selected_corporate_event_plan_does_not_support_completed_transaction(
    monkeypatch, stage, reason
):
    source = scoped_source("selected")
    failures = []
    original_repair = service.ReportInsightService._repair_call

    def capture(self, prompt, schema, raw, error, validate):
        failures.append(error)
        return original_repair(self, prompt, schema, raw, error, validate)

    monkeypatch.setattr(service.ReportInsightService, "_repair_call", capture)

    def mutate(current, occurrence, data, value):
        if current == stage and occurrence == 1:
            value["assessments"][AUDIENCE]["finding101"]["reason"] = reason
        return value

    provider = V4Provider(source, hook=flat_hook(source, mutate))
    result = generate(provider, source)
    expected = ["MAP-001", "REVIEW-001", "REDUCE-001"]
    expected.insert(expected.index(stage), stage)
    assert stages(provider) == expected
    assert len(failures) == 1
    assert "event_state" in failures[0].fact_repair_kinds
    assert "report_evidence_insufficient" in failures[0].error_kinds
    assert all(GOOD_REASON in assessment.reason for assessment in result.insights[0].assessments)


@pytest.mark.parametrize(
    "reason",
    [
        "삼성전자의 상장 추진과 조달 기대는 자금 조달 전망과 투자 판단에 연결된다.",
        "대표 주관사 선정 및 내년 IPO로 약 100억달러 조달 전망이 제기되었다. "
        "자금 조달이 성사되는 경우에 한해 투자 판단에 영향이 있어 "
        "자금조달 성사 전제를 필요로 한다.",
        "삼성전자의 상장 추진과 예정 조달 규모는 자금조달을 통한 투자 가능성에 연결된다.",
    ],
)
def test_currency_funding_forecast_passes_map_review_reduce_without_fabricated_actor(reason):
    source = request(
        ids=(101,),
        audiences=(AUDIENCE,),
        text=RAW + " 삼성전자는 미국 IPO 대표 주관사를 선정했고, 내년 상장을 통해 "
        "약 100억달러를 조달할 것으로 예상된다.",
    )
    before = source.model_dump_json()

    def mutate(stage, occurrence, data, value):
        if stage.startswith(("MAP", "REVIEW")):
            value["assessments"][AUDIENCE]["finding101"]["reason"] = reason
        elif stage == "REDUCE-001":
            value["insights"][0]["overview"][0]["text"] = reason
        return value

    provider = V4Provider(source, hook=flat_hook(source, mutate))
    result = generate(provider, source)
    assert stages(provider) == ["MAP-001", "REVIEW-001", "REDUCE-001"]
    assert reason in result.insights[0].assessments[0].reason
    assert result.insights[0].overview[0].text == reason
    assert result.meta.input_tokens == 33 and result.meta.output_tokens == 21
    assert source.model_dump_json() == before


@pytest.mark.parametrize("group,field", REDUCE_FIELDS)
def test_selected_corporate_event_passes_every_reduce_prose_field(group, field):
    source = scoped_source("selected")
    reason = EVENT_REASONS[0]
    before = deepcopy(source.model_dump())

    def mutate(stage, occurrence, data, value):
        value = full_synthesis(stage, occurrence, data, value)
        if stage == "REDUCE-001":
            insight = value["insights"][0]
            record = insight if group is None else insight[group][0]
            record[field] = reason
            if group is not None:
                record["basisClaimIds"] = ["101:0"]
        return value

    provider = V4Provider(source, hook=flat_hook(source, mutate))
    result = generate(provider, source)
    assert stages(provider) == ["MAP-001", "REVIEW-001", "REDUCE-001"]
    public = result.insights[0].model_dump(by_alias=True)
    assert (public if group is None else public[group][0])[field] == reason
    assert source.model_dump() == before
