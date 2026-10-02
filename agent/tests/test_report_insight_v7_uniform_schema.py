"""Fixed native records enforce compact axis branches and preserve source proofs."""

import json
from copy import deepcopy

import pytest
from jsonschema import Draft202012Validator
from jsonschema import ValidationError as JsonSchemaValidationError
from pydantic_ai.profiles.openai import OpenAIJsonSchemaTransformer
from test_report_insight_assessment import payload, request, resolve_schema_node, response

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
def test_claimful_record_has_fixed_keys_and_category_first_axis_branches(audience):
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
        for branch in properties[field]["anyOf"]:
            branch = resolve_schema_node(schema, branch)
            assert branch["type"] == "object"
            assert branch["additionalProperties"] is False
            assert set(branch["required"]) == set(branch["properties"])
    for field, category, definition, values in (
        ("effect", "impactScope", "ReportKnownImpactScope", IMPACT_SCORES),
        ("timing", "urgencyState", "ReportKnownUrgencyState", URGENCY_SCORES),
    ):
        known, unknown = [
            resolve_schema_node(schema, branch) for branch in properties[field]["anyOf"]
        ]
        assert list(known["properties"])[0] == category
        assert known["properties"][category] == {"$ref": f"#/$defs/{definition}"}
        assert schema["$defs"][definition] == {
            "type": "string",
            "enum": [value for value in values if value != "UNDETERMINED"],
        }
        assert unknown["properties"][category] == {"type": "string", "const": "UNDETERMINED"}
    direct, conditional, unrelated, unknown = [
        resolve_schema_node(schema, branch) for branch in properties["connection"]["anyOf"]
    ]
    assert direct["properties"]["relation"]["const"] == "DIRECT"
    assert conditional["properties"]["relation"] == {"$ref": "#/$defs/ReportConditionalRelation"}
    assert unrelated["properties"]["relation"]["const"] == "UNRELATED"
    assert unknown["properties"]["relation"]["const"] == "UNDETERMINED"
    assert schema["$defs"]["ReportConditionalRelation"]["enum"] == ["CONDITIONAL", "BACKGROUND"]
    assert schema["$defs"][f"ReportWork{audience}"]["enum"] == list(ROLE_WORK[audience])
    for branch in properties["connection"]["anyOf"]:
        branch = resolve_schema_node(schema, branch)
        assert list(branch["properties"]) == ["relation", "work", "condition", "basis"]
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
        ("UNDETERMINED", "connection_nonnull_condition", "condition=null"),
        ("UNDETERMINED", "connection_claimless_condition", "condition=null"),
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
def test_native_axis_branches_and_local_validator_reject_category_contradictions(
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
        "connection_claimless_condition": ("connection", "condition", "claims=[]"),
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
    wire = OpenAIJsonSchemaTransformer(draft_schema(source), strict=True).walk()
    validator = Draft202012Validator(wire)
    # Relation-to-effect/timing consistency is still checked across axes locally.
    if change in {"effect_known", "timing_known"}:
        validator.validate(native)
    else:
        with pytest.raises(JsonSchemaValidationError):
            validator.validate(native)
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


@pytest.mark.parametrize(
    "failure",
    ["connection_citation", "all_axis_citations", "metadata_condition", "claimless_condition"],
)
def test_captured_v7_unknown_field_combinations_fail_native_schema(failure):
    source = request(ids=(4261,))
    native = draft_to_wire(payload(source, relation="UNDETERMINED"), source)
    item = native["assessments"]["CHIP_MAKER"]["finding4261"]
    item["reason"] = "원문에 구체적 업무 연결 조건이 명확히 제시되지 않아 관계 판단이 불확실함."
    basis = {"claimId": "4261:0", "sourceSpanId": "s4261_0_0"}
    if failure == "connection_citation":
        item["connection"]["basis"] = basis
    elif failure == "all_axis_citations":
        for field in ("connection", "effect", "timing"):
            item[field]["basis"] = deepcopy(basis)
    else:
        item["connection"]["condition"] = (
            "원문에 구체적인 업무 연결 조건이 명확히 제시되지 않음."
            if failure == "metadata_condition"
            else "claims=[]"
        )
    wire = OpenAIJsonSchemaTransformer(draft_schema(source), strict=True).walk()
    with pytest.raises(JsonSchemaValidationError):
        Draft202012Validator(wire).validate(native)


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
        props = resolve_schema_node(schema, record["properties"][field])["properties"]
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
