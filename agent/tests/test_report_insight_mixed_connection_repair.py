"""Mixed axis and reason repairs retain only an authenticated valid connection."""

import json
from copy import deepcopy
from dataclasses import replace

import pytest
from report_insight_schema_assertions import assert_only_display_quotes_require_null
from test_report_insight_assessment import framed, payload, request, response
from test_report_insight_axis_support_repair import case
from test_report_insight_preserved_connection_repair import repair_for
from test_report_insight_preserved_decision_repair import wire_validator
from test_report_insight_v4_pipeline import V4Provider, generate, stages
from test_report_insight_work_repair_actions import full_validate

from app.core.errors import AgentError, OutputValidationError
from app.llm import report_insight_service as service
from app.llm.report_insight_assessment import (
    ReportAssessmentDraftValidationError,
    draft_prompt,
    draft_schema,
    draft_to_wire,
    project_public_assessment,
)


def mixed_case(relation="DIRECT", *, neighbor=False):
    source, value = case("forecast")
    finding = source.findings[0]
    # A different, equally valid source choice must still stay frozen.
    finding.claims.append(finding.claims[0].model_copy(update={"id": "101:1"}))
    entry = value["assessments"]["IT_INFRA"]["finding101"]
    entry.update(
        relation=relation,
        condition=(
            "메모리 가격 전망이 시스템 조달에 적용되는 경우" if relation == "CONDITIONAL" else None
        ),
        reason="9999년의 가격 전망은 시스템 조달과 연결된다.",
    )
    if neighbor:
        other = request(
            ids=(202,),
            audiences=("IT_INFRA",),
            text="회사는 서버 도입 프로젝트의 자원 배분과 준비 일정을 변경했다.",
        )
        other_value = payload(other)
        other_value["assessments"]["IT_INFRA"]["finding202"].update(
            impactScope="PROJECT_CHANGE",
            urgencyState="SCHEDULED_PREPARATION",
            reason="서버 도입 자원과 준비 일정 변경은 시스템 조달 업무에 직접 영향을 준다.",
        )
        source.findings.extend(other.findings)
        value["assessments"]["IT_INFRA"].update(other_value["assessments"]["IT_INFRA"])
    return source, value


def corrected(value):
    result = deepcopy(value)
    result["assessments"]["IT_INFRA"]["finding101"].update(
        impactScope="UNDETERMINED",
        impactBasis=None,
        urgencyState="UNDETERMINED",
        urgencyBasis=None,
        reason="시장 가격 전망은 시스템 조달과 연결된다.",
    )
    return result


def local_errors(source, value):
    """Collect real errors before aggregation, without inventing their authority."""
    raw = response(value, source)
    with pytest.raises(ReportAssessmentDraftValidationError) as native:
        service.validate_legacy_draft(raw, source)
    snapshot = native.value.native_connection_repairs[101].snapshot
    mapped = service.ReportInsightMapOutput(
        insights=[
            service.ReportInsightMapAudience(
                audience="IT_INFRA", assessments=[project_public_assessment(snapshot)]
            )
        ]
    )
    with pytest.raises(service.ReportAssessmentValidationError) as public:
        service._validated_map_output(
            replace(raw, text=mapped.model_dump_json(by_alias=True)),
            source,
            native_assessments={"IT_INFRA": {101: snapshot}},
        )
    assert public.value.native_prose_repairs[101] == (snapshot, ("reason",))
    return native.value, public.value


@pytest.mark.parametrize("relation", ["DIRECT", "CONDITIONAL"])
@pytest.mark.parametrize("template_wire", [False, True])
def test_mixed_reason_and_axis_failures_freeze_connection_but_allow_every_invalid_field(
    relation, template_wire
):
    source, value = mixed_case(relation)
    raw = response(value, source)
    before = source.model_dump_json(), raw.text
    error = service._native_assessment_repair_errors(raw, source, template_wire=template_wire)
    assert set(error.native_connection_repairs) == {101}
    assert not error.native_prose_repairs  # Failed axes must remain editable.
    assert {issue.field for issue in error.validation_issues} == {
        "assessments[101].decision.effect.impactScope",
        "assessments[101].decision.timing.urgencyState",
        "assessments[101].reason",
    }
    schema = draft_schema(source)
    repair = object.__new__(service.ReportInsightService)._repair_call(
        draft_prompt(source), schema, raw.text, error, lambda result: full_validate(result, source)
    )
    fixed = draft_to_wire(corrected(value), source)
    wire_validator(repair).validate(fixed)
    accepted = repair.validate(replace(raw, text=json.dumps(fixed, ensure_ascii=False)))
    restored = draft_to_wire(accepted.draft, source)["assessments"]["IT_INFRA"]["finding101"]
    original = json.loads(raw.text)["assessments"]["IT_INFRA"]["finding101"]
    assert restored["decision"]["connection"] == original["decision"]["connection"]
    assert restored["reason"] == corrected(value)["assessments"]["IT_INFRA"]["finding101"]["reason"]
    assert restored["decision"]["effect"] == {"impactScope": "UNDETERMINED", "basis": None}
    assert restored["decision"]["timing"] == {"urgencyState": "UNDETERMINED", "basis": None}
    assert restored["sourceQuotes"] == {"reason": None, "condition": None}
    assert before == (source.model_dump_json(), raw.text)


@pytest.mark.parametrize("relation", ["DIRECT", "CONDITIONAL"])
@pytest.mark.parametrize("drift", ["downgrade", "work", "condition", "basis"])
def test_mixed_repair_rejects_connection_drift_in_sdk_schema_and_server(relation, drift):
    source, value = mixed_case(relation)
    repair, _, raw = repair_for(source, value)
    candidate = corrected(value)
    entry = candidate["assessments"]["IT_INFRA"]["finding101"]
    if drift == "downgrade":
        entry.update(
            relation="UNDETERMINED",
            work=None,
            relationBasis=None,
            condition=None,
            reason="관점 업무의 연결 여부를 판단할 근거가 부족하다.",
        )
    elif drift == "work":
        entry["work"] = "DEPLOYMENT_OPERATIONS"
    elif drift == "condition":
        entry.update(
            relation="CONDITIONAL",
            condition="메모리 가격 전망이 실제 시스템 조달에 적용되는 경우",
        )
    else:
        entry["relationBasis"]["claimId"] = "101:1"
    wire = draft_to_wire(candidate, source)
    # The critical loss and same-finding source drift are otherwise valid output.
    if drift in {"downgrade", "basis"}:
        full_validate(replace(raw, text=json.dumps(wire, ensure_ascii=False)), source)
    assert not wire_validator(repair).is_valid(wire)
    with pytest.raises(OutputValidationError, match="connection을 변경할 수 없습니다"):
        repair.validate(replace(raw, text=json.dumps(wire, ensure_ascii=False)))


@pytest.mark.parametrize("retained", ["reason", "effect", "timing"])
def test_mixed_repair_still_validates_all_repaired_fields(retained):
    source, value = mixed_case()
    repair, _, raw = repair_for(source, value)
    fixed = corrected(value)
    entry = fixed["assessments"]["IT_INFRA"]["finding101"]
    original = value["assessments"]["IT_INFRA"]["finding101"]
    fields = {
        "reason": ("reason",),
        "effect": ("impactScope", "impactBasis"),
        "timing": ("urgencyState", "urgencyBasis"),
    }[retained]
    entry.update({field: original[field] for field in fields})
    wire = draft_to_wire(fixed, source)
    wire_validator(repair).validate(wire)
    with pytest.raises(ValueError):
        repair.validate(replace(raw, text=json.dumps(wire, ensure_ascii=False)))


@pytest.mark.parametrize("rule", ["company", "source_binding"])
def test_mixed_company_or_product_reference_repair_preserves_connection_and_grounding(rule):
    source, value = mixed_case()
    entry = value["assessments"]["IT_INFRA"]["finding101"]
    if rule == "company":
        entry["reason"] = "삼성전자의 가격 전망은 시스템 조달과 연결된다."
        fixed_reason = "메모리 가격 전망은 시스템 조달과 연결된다."
    else:
        text = "HBM4 메모리 가격은 내년에 상승할 것으로 전망된다."
        for claim in source.findings[0].claims:
            claim.text = text
        source.findings[0].sentences[0].text = text
        for field in ("relationBasis", "impactBasis", "urgencyBasis"):
            entry[field]["quote"] = text
        entry["reason"] = "HBM5의 가격 전망은 시스템 조달과 연결된다."
        fixed_reason = "HBM4의 가격 전망은 시스템 조달과 연결된다."
    repair, error, raw = repair_for(source, value)
    assert set(error.native_connection_repairs) == {101}
    assert not error.native_prose_repairs
    assert any(
        issue.field == "assessments[101].reason" and issue.rule == rule
        for issue in error.validation_issues
    )
    fixed = corrected(value)
    fixed["assessments"]["IT_INFRA"]["finding101"]["reason"] = fixed_reason
    accepted = repair.validate(response(fixed, source))
    expected = json.loads(raw.text)["assessments"]["IT_INFRA"]["finding101"]
    actual = draft_to_wire(accepted.draft, source)["assessments"]["IT_INFRA"]["finding101"]
    assert actual["decision"]["connection"] == expected["decision"]["connection"]
    assert actual["reason"] == fixed_reason
    fixed["assessments"]["IT_INFRA"]["finding101"]["reason"] = entry["reason"]
    with pytest.raises(service.ReportAssessmentValidationError) as caught:
        repair.validate(response(fixed, source))
    assert rule in caught.value.fact_repair_kinds


@pytest.mark.parametrize("defect", ["condition", "coherence", "time"])
def test_mixed_preservation_does_not_freeze_an_invalid_premise_or_unknown_cause(defect):
    source, value = mixed_case("CONDITIONAL")
    entry = value["assessments"]["IT_INFRA"]["finding101"]
    if defect == "condition":
        entry["condition"] = "삼성전자의 가격 전망이 시스템 조달에 적용되는 경우"
    elif defect == "coherence":
        entry["reason"] = "영향 범위는 미확인이다. " + entry["reason"]
    else:
        finding = source.findings[0]
        text = "계약 신청 기한은 2024년 10월이었다."
        finding.claims.append(
            finding.claims[0].model_copy(
                update={"id": "101:2", "text": text, "evidence_sentence_ids": [1]}
            )
        )
        finding.sentences.append(finding.sentences[0].model_copy(update={"index": 1, "text": text}))
        entry.update(
            reason="2024년 10월 계약 기한이 임박했다. " + entry["reason"],
            urgencyState="MONITOR",
            urgencyBasis={"claimId": "101:2", "quote": text},
        )
    repair, error, _ = repair_for(source, value)
    assert not error.native_connection_repairs and not error.native_prose_repairs
    assert_only_display_quotes_require_null(repair.response_schema, draft_schema(source))
    if defect == "condition":
        assert any(
            issue.field == "assessments[101].decision.connection.condition"
            and issue.rule == "company"
            for issue in error.validation_issues
        )
    elif defect == "coherence":
        assert any(
            issue.rule == "report_assessment_impact_reason_conflict"
            for issue in error.validation_issues
        )
    else:
        assert "report_assessment_invalid" in error.error_kinds
        assert "이미 지난 근거 기한" in str(error)


@pytest.mark.parametrize(
    "defect",
    [
        "unknown",
        "duplicate_unknown",
        "empty_kinds",
        "unknown_with_context",
        "condition",
        "snapshot",
        "source",
        "wrong_type",
    ],
)
def test_composer_requires_each_local_cause_to_authorize_the_same_connection(defect):
    source, value = mixed_case()
    native, public = local_errors(source, value)
    errors = [native, public]
    if defect in {"unknown", "duplicate_unknown"}:
        unknown = service.ReportAssessmentValidationError(
            "decision.effect.impactScope reason nativeFields=reason",
            error_kinds=("future_unknown_rule",),
            failed_finding_ids=(101,),
            validation_issues=public.validation_issues,
        )
        errors.extend([unknown] * (2 if defect == "duplicate_unknown" else 1))
    elif defect in {"empty_kinds", "unknown_with_context"}:
        public.error_kinds = () if defect == "empty_kinds" else ("future_unknown_rule",)
    elif defect == "condition":
        snapshot, _ = public.native_prose_repairs[101]
        public.native_prose_repairs[101] = (snapshot, ("reason", "decision.connection.condition"))
    elif defect == "snapshot":
        snapshot, fields = public.native_prose_repairs[101]
        public.native_prose_repairs[101] = (
            snapshot.model_copy(update={"reason": "다른 응답"}),
            fields,
        )
    elif defect == "source":
        other, _ = local_errors(source, value)
        other.native_connection_repairs[101] = replace(
            native.native_connection_repairs[101], source_fingerprint="unrelated-source"
        )
        errors.append(other)
    else:
        errors[0] = service.ReportAssessmentValidationError(
            str(native),
            error_kinds=native.error_kinds,
            failed_finding_ids=(101,),
            native_connection_repairs=native.native_connection_repairs,
        )
    assert service._composed_connection_repair_context(errors, 101) is None


def test_identical_owned_diagnostics_do_not_expand_or_erase_mixed_scope():
    source, value = mixed_case()
    native, public = local_errors(source, value)
    expected = native.native_connection_repairs[101]
    assert (
        service._composed_connection_repair_context([native, public, native, public], 101)
        == expected
    )
    assert service._composed_connection_repair_context([public, public], 101) is None


@pytest.mark.parametrize("defect", ["raw", "source"])
def test_mixed_context_is_reauthenticated_against_original_wire_and_source(defect):
    source, value = mixed_case()
    _, error, raw = repair_for(source, value)
    assert error.native_connection_repairs
    if defect == "raw":
        changed = json.loads(raw.text)
        changed["assessments"]["IT_INFRA"]["finding101"]["reason"] += " 추가"
        raw = replace(raw, text=json.dumps(changed, ensure_ascii=False))
    else:
        source.findings[0].sentences[0].text += " 추가 원문"
    schema = draft_schema(source)
    repair = object.__new__(service.ReportInsightService)._repair_call(
        draft_prompt(source), schema, raw.text, error, lambda result: full_validate(result, source)
    )
    assert repair.response_schema == schema


@pytest.mark.parametrize("retained", [None, "reason", "effect", "downgrade"])
def test_partial_mixed_repair_preserves_healthy_neighbor_and_has_one_retry(retained, monkeypatch):
    source, value = mixed_case(neighbor=True)
    original_validate = service.validate_draft
    validated_inputs = []

    def capture(raw, request):
        if len(request.findings) == 2:
            validated_inputs.append(json.loads(raw.text))
        return original_validate(raw, request)

    monkeypatch.setattr(service, "validate_draft", capture)

    def hook(stage, occurrence, data, result):
        if stage.startswith("REDUCE"):
            for insight in result["insights"]:
                insight.update(
                    headline="시스템 조달의 변경과 준비 일정을 살펴본다.",
                    overview=[
                        {
                            "text": (
                                "서버 도입 자원과 준비 일정 변경은 시스템 조달 업무에 영향을 준다."
                            ),
                            "basisClaimIds": ["202:0"],
                            "assumption": "서버 도입 프로젝트의 자원 배분이 변경되는 경우",
                        }
                    ],
                )
            return result
        proposed = deepcopy(value if stage == "MAP-001" and occurrence == 1 else corrected(value))
        entry = proposed["assessments"]["IT_INFRA"]["finding101"]
        if occurrence > 1 and retained == "reason":
            entry["reason"] = value["assessments"]["IT_INFRA"]["finding101"]["reason"]
        elif occurrence > 1 and retained == "effect":
            entry.update(impactScope="PROJECT_CHANGE", impactBasis=deepcopy(entry["relationBasis"]))
        elif occurrence > 1 and retained == "downgrade":
            entry.update(
                relation="UNDETERMINED",
                work=None,
                relationBasis=None,
                condition=None,
                reason="관점 업무의 연결 여부를 판단할 근거가 부족하다.",
            )
        wanted = {f"finding{finding['id']}" for finding in data["findings"]}
        proposed["assessments"]["IT_INFRA"] = {
            key: entry
            for key, entry in proposed["assessments"]["IT_INFRA"].items()
            if key in wanted
        }
        return proposed

    provider = V4Provider(source, hook=hook, validate_wire=retained != "downgrade")
    if retained is None:
        result = generate(provider, source)
        assert stages(provider) == ["MAP-001", "MAP-001", "REVIEW-001", "REDUCE-001"]
        assert result.meta.input_tokens == 44 and result.meta.output_tokens == 28
        assert result.insights[0].assessments[0].axes.directness == 3
        assert result.insights[0].assessments[0].axes.impact is None
        merged = validated_inputs[1]["assessments"]["IT_INFRA"]
        original = provider.wire_payloads[0]["assessments"]["IT_INFRA"]
        assert merged["finding202"] == original["finding202"]
        assert (
            merged["finding101"]["decision"]["connection"]
            == original["finding101"]["decision"]["connection"]
        )
        assert (
            merged["finding101"]["reason"]
            == corrected(value)["assessments"]["IT_INFRA"]["finding101"]["reason"]
        )
    else:
        with pytest.raises(AgentError) as caught:
            generate(provider, source)
        assert caught.value.code == "SCHEMA_VIOLATION"
        assert caught.value.details["usage"]["inputTokens"] == 22
        assert stages(provider) == ["MAP-001", "MAP-001"]
    assert [finding["id"] for finding in framed(provider.calls[1]["prompt"])["findings"]] == [101]
    assert set(provider.wire_payloads[1]["assessments"]["IT_INFRA"]) == {"finding101"}
