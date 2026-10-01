"""Uniform native records preserve source proofs and strict local correlations."""

import json
from copy import deepcopy

import pytest
from jsonschema import Draft202012Validator
from jsonschema import ValidationError as JsonSchemaValidationError
from test_report_insight_assessment import payload, request, response

from app.llm.base import ProviderResponse, ProviderUsage
from app.llm.report_insight_assessment import (
    IMPACT_SCORES,
    RELATION_SCORES,
    ROLE_WORK,
    URGENCY_SCORES,
    ReportAssessmentDraftValidationError,
    draft_prompt,
    draft_schema,
    draft_to_wire,
    review_prompt,
    validate_draft,
)
from app.llm.report_insight_instructions import report_stage_instruction
from app.schemas.report_insight import CLAIMLESS_ASSESSMENT_REASON

ROLES = tuple(ROLE_WORK)


def _native_response(value):
    return ProviderResponse(
        json.dumps(value, ensure_ascii=False), "openai", "offline", ProviderUsage()
    )


def _record(schema, audience, finding_id):
    return schema["properties"]["assessments"]["properties"][audience]["properties"][
        f"finding{finding_id}"
    ]


@pytest.mark.parametrize("audience", ROLES)
def test_claimful_record_has_uniform_objects_and_shared_category_enums(audience):
    source = request(ids=(4261,), audiences=(audience,))
    schema = draft_schema(source)
    record = _record(schema, audience, 4261)
    assert record["type"] == "object"
    assert "anyOf" not in record
    assert record["additionalProperties"] is False
    assert set(record["required"]) == {
        "findingId",
        "connection",
        "effect",
        "timing",
        "reason",
    }
    properties = record["properties"]
    for field in ("connection", "effect", "timing"):
        assert properties[field]["type"] == "object"
        assert "anyOf" not in properties[field]
        assert properties[field]["additionalProperties"] is False
        assert set(properties[field]["required"]) == set(properties[field]["properties"])
    for field, category, definition, values in (
        ("connection", "relation", "ReportRelation", RELATION_SCORES),
        ("effect", "impactScope", "ReportImpactScope", IMPACT_SCORES),
        ("timing", "urgencyState", "ReportUrgencyState", URGENCY_SCORES),
    ):
        assert properties[field]["properties"][category] == {"$ref": f"#/$defs/{definition}"}
        assert schema["$defs"][definition] == {"type": "string", "enum": list(values)}
        assert "UNDETERMINED" in values
    assert schema["$defs"][f"ReportWork{audience}"]["enum"] == list(ROLE_WORK[audience])
    assert list(properties["connection"]["properties"]) == [
        "basis",
        "work",
        "condition",
        "relation",
    ]
    Draft202012Validator.check_schema(schema)


@pytest.mark.parametrize("audience", ROLES)
@pytest.mark.parametrize("relation", tuple(RELATION_SCORES))
def test_same_native_record_roundtrips_every_consistent_relation_without_source_changes(
    audience, relation
):
    source = request(ids=(4261,), audiences=(audience,))
    original_source = source.model_dump_json(by_alias=True)
    flat = payload(source, relation=relation)
    value = flat["assessments"][audience]["finding4261"]
    if relation == "DIRECT":
        value.update(
            impactScope="UNDETERMINED",
            impactBasis=None,
            urgencyState="UNDETERMINED",
            urgencyBasis=None,
        )
    if relation == "UNDETERMINED":
        value["reason"] = "원문은 있지만 해당 관점 업무의 대상과 범위를 판단할 정보가 부족하다."
    native = draft_to_wire(flat, source)
    snapshot = deepcopy(native)
    Draft202012Validator(draft_schema(source)).validate(native)
    validated = validate_draft(_native_response(native), source)
    assert validated.draft.model_dump(by_alias=True) == flat
    assert draft_to_wire(validated) == native == snapshot
    assert source.model_dump_json(by_alias=True) == original_source
    public = validated.mapped.insights[0].assessments[0]
    assert public.axes.directness == RELATION_SCORES[relation]
    if relation in {"DIRECT", "UNRELATED", "UNDETERMINED"}:
        assert public.axes.impact is None
        assert public.axes.urgency is None
    if relation == "DIRECT":
        assert native["assessments"][audience]["finding4261"]["connection"]["basis"] is not None
        assert public.basis_claim_ids == ["4261:0"]
    elif relation == "UNDETERMINED":
        assert public.basis_claim_ids == []


@pytest.mark.parametrize(
    "relation,change,message",
    [
        ("DIRECT", "connection_null_basis", "connection.basis"),
        ("DIRECT", "connection_null_work", "허용된 구체 업무"),
        ("DIRECT", "connection_nonnull_condition", "condition=null"),
        ("UNRELATED", "effect_known", "업무 영향·시급성"),
        ("UNRELATED", "timing_known", "업무 영향·시급성"),
        ("UNRELATED", "connection_known_work", "work=null"),
        ("UNRELATED", "connection_null_basis", "connection.basis"),
        ("UNDETERMINED", "connection_known_basis", "connection.basis"),
        ("UNDETERMINED", "effect_known", "업무 영향·시급성"),
        ("UNDETERMINED", "connection_known_work", "work=null"),
        ("CONDITIONAL", "connection_null_condition", "미확인 중간 조건"),
        ("BACKGROUND", "connection_null_condition", "미확인 중간 조건"),
        ("BACKGROUND", "connection_null_work", "허용된 구체 업무"),
        ("DIRECT", "effect_known_null_basis", "effect.basis"),
        ("DIRECT", "effect_unknown_known_basis", "effect.basis"),
        ("DIRECT", "timing_known_null_basis", "timing.basis"),
        ("DIRECT", "timing_unknown_known_basis", "timing.basis"),
    ],
)
def test_uniform_native_schema_does_not_weaken_category_proof_work_condition_validation(
    relation, change, message
):
    source = request(ids=(4261,))
    original_source = source.model_dump_json(by_alias=True)
    flat = payload(source, relation=relation)
    if relation == "UNDETERMINED":
        flat["assessments"]["CHIP_MAKER"]["finding4261"]["reason"] = (
            "원문은 있지만 관점 업무의 연결 조건과 범위를 판단할 정보가 부족하다."
        )
    native = draft_to_wire(flat, source)
    item = native["assessments"]["CHIP_MAKER"]["finding4261"]
    known = draft_to_wire(payload(source), source)["assessments"]["CHIP_MAKER"]["finding4261"]
    changes = {
        "connection_null_basis": ("connection", "basis", None),
        "connection_known_basis": ("connection", "basis", known["connection"]["basis"]),
        "connection_null_work": ("connection", "work", None),
        "connection_known_work": ("connection", "work", ROLE_WORK["CHIP_MAKER"][0]),
        "connection_nonnull_condition": ("connection", "condition", "같은 준비가 필요한 경우"),
        "connection_null_condition": ("connection", "condition", None),
        "effect_known_null_basis": ("effect", "basis", None),
        "effect_unknown_known_basis": ("effect", "impactScope", "UNDETERMINED"),
        "timing_known_null_basis": ("timing", "basis", None),
        "timing_unknown_known_basis": ("timing", "urgencyState", "UNDETERMINED"),
    }
    if change == "effect_known":
        item["effect"] = deepcopy(known["effect"])
    elif change == "timing_known":
        item["timing"] = deepcopy(known["timing"])
    else:
        field, category, value = changes[change]
        item[field][category] = deepcopy(value)
    snapshot = deepcopy(native)
    # Native nullable fields are deliberately independent; the existing local
    # validator must reject contradictions rather than normalize their axes.
    Draft202012Validator(draft_schema(source)).validate(native)
    with pytest.raises(ReportAssessmentDraftValidationError, match=message):
        validate_draft(_native_response(native), source)
    assert native == snapshot
    assert source.model_dump_json(by_alias=True) == original_source


@pytest.mark.parametrize(
    "change", ["foreign_claim", "foreign_span", "unknown_span", "foreign_work"]
)
def test_uniform_records_still_close_role_work_and_claim_bound_literal_proof_choices(change):
    source = request(ids=(4261, 4262))
    native = draft_to_wire(payload(source), source)
    item = native["assessments"]["CHIP_MAKER"]["finding4261"]
    if change == "foreign_claim":
        item["connection"]["basis"]["claimId"] = "4262:0"
    elif change == "foreign_span":
        item["connection"]["basis"]["sourceSpanId"] = "s4262_0_0"
    elif change == "unknown_span":
        item["connection"]["basis"]["sourceSpanId"] = "s4261_0_9999"
    else:
        item["connection"]["work"] = "SYSTEM_PROCUREMENT"
    with pytest.raises(JsonSchemaValidationError):
        Draft202012Validator(draft_schema(source)).validate(native)


@pytest.mark.parametrize("audiences", [(role,) for role in ROLES] + [ROLES])
@pytest.mark.parametrize("stage", ["MAP", "REVIEW", "REDUCE"])
def test_active_nano_stage_instructions_do_not_offer_the_claimless_full_reason_as_an_anchor(
    audiences, stage
):
    instruction = report_stage_instruction(list(audiences), stage)
    assert CLAIMLESS_ASSESSMENT_REASON not in instruction
    assert "검증을 통과한 claim 근거가 없어" not in instruction
    if stage != "REDUCE":
        assert "Schema const" in instruction


def test_native_map_and_review_prompt_text_do_not_repeat_the_claimless_full_reason():
    source = request(ids=(4261,))
    for prompt in (draft_prompt(source), review_prompt(source)):
        assert CLAIMLESS_ASSESSMENT_REASON not in prompt


def test_claimless_record_keeps_fixed_schema_constants_and_canonical_roundtrip():
    source = request(ids=(4261,))
    flat = payload(source, relation="UNDETERMINED")
    flat["assessments"]["CHIP_MAKER"]["finding4261"]["reason"] = CLAIMLESS_ASSESSMENT_REASON
    source = source.model_copy(
        update={"findings": [source.findings[0].model_copy(update={"claims": []})]}
    )
    before = source.model_dump_json(by_alias=True)
    schema = draft_schema(source)
    record = _record(schema, "CHIP_MAKER", 4261)
    assert record["properties"]["reason"] == {
        "type": "string",
        "const": CLAIMLESS_ASSESSMENT_REASON,
    }
    for field, category in (
        ("connection", "relation"),
        ("effect", "impactScope"),
        ("timing", "urgencyState"),
    ):
        props = record["properties"][field]["properties"]
        assert props[category] == {"type": "string", "const": "UNDETERMINED"}
        assert props["basis"] == {"type": "null"}
    native = draft_to_wire(flat, source)
    snapshot = deepcopy(native)
    Draft202012Validator(schema).validate(native)
    validated = validate_draft(response(flat, source), source)
    assert validated.draft.model_dump(by_alias=True) == flat
    assert draft_to_wire(validated) == native == snapshot
    assert source.model_dump_json(by_alias=True) == before
    public = validated.mapped.insights[0].assessments[0]
    assert public.reason == CLAIMLESS_ASSESSMENT_REASON
    assert public.basis_claim_ids == []
    assert all(value is None for value in public.axes.model_dump().values())
    altered = deepcopy(native)
    altered["assessments"]["CHIP_MAKER"]["finding4261"]["reason"] = "업무 관계 판단을 보류한다."
    with pytest.raises(JsonSchemaValidationError):
        Draft202012Validator(schema).validate(altered)
    with pytest.raises(ReportAssessmentDraftValidationError, match="고정 보류 이유"):
        validate_draft(_native_response(altered), source)
