"""Schema-local guidance survives the SDK without changing evidence contracts.

These checks establish provider format and local score compatibility. They do
not establish whether a model actually follows the category guidance.
"""

from copy import deepcopy

import pytest
from jsonschema import Draft202012Validator
from pydantic_ai.profiles.openai import OpenAIJsonSchemaTransformer
from test_report_insight_assessment import (
    decision_axis_schemas,
    payload,
    request,
    resolve_schema_node,
    response,
)

from app.core.report_importance import score_importance
from app.llm.openai_contract import output_contract
from app.llm.report_insight_assessment import (
    ROLE_WORK,
    draft_schema,
    draft_to_wire,
    source_span_choices,
    validate_draft,
)


def _wire(schema):
    return OpenAIJsonSchemaTransformer(output_contract(schema).schema, strict=True).walk()


def _record(schema, audience="CHIP_MAKER", finding_id=101):
    return schema["properties"]["assessments"]["properties"][audience]["properties"][
        f"finding{finding_id}"
    ]


def _without_descriptions(value, *, unwrap_references=False):
    if isinstance(value, list):
        return [_without_descriptions(item, unwrap_references=unwrap_references) for item in value]
    if not isinstance(value, dict):
        return value
    result = {
        key: _without_descriptions(item, unwrap_references=unwrap_references)
        for key, item in value.items()
        if key != "description"
    }
    # The SDK moves a described $ref into anyOf; remove only this equivalent
    # wrapper, preserving all actual category and source-choice branches.
    if (
        unwrap_references
        and set(result) == {"anyOf"}
        and len(result["anyOf"]) == 1
        and set(result["anyOf"][0]) == {"$ref"}
    ):
        return result["anyOf"][0]
    return result


def test_sdk_keeps_axis_and_proof_guidance_without_changing_validation_keywords():
    source = request(ids=(101, 102), audiences=tuple(ROLE_WORK))
    source.findings[1].claims = []
    source_snapshot = source.model_dump_json(by_alias=True)
    schema = draft_schema(source)
    snapshot = deepcopy(schema)
    wire = _wire(schema)
    undescribed_wire = _wire(_without_descriptions(schema))

    assert _without_descriptions(wire, unwrap_references=True) == _without_descriptions(
        undescribed_wire, unwrap_references=True
    )
    for definition in (
        "ReportConditionalRelation",
        "ReportKnownImpactScope",
        "ReportKnownUrgencyState",
        *(f"ReportWork{audience}" for audience in ROLE_WORK),
    ):
        description = schema["$defs"][definition]["description"]
        assert description.strip()
        assert wire["$defs"][definition]["description"] == description
    for definition, category in (
        ("ReportUnknownConnection", "relation"),
        ("ReportUnknownEffect", "impactScope"),
    ):
        description = schema["$defs"][definition]["properties"][category]["description"]
        assert description.strip()
        assert wire["$defs"][definition]["properties"][category]["description"] == description

    for audience in source.audiences:
        original_record = _record(schema, audience)
        wire_record = _record(wire, audience)
        assert list(wire_record["properties"]) == [
            "findingId",
            "reason",
            "decision",
            "sourceQuotes",
        ]
        original_record = decision_axis_schemas(schema, original_record)
        wire_record = decision_axis_schemas(wire, wire_record)
        for axis, category in (
            ("connection", "relation"),
            ("effect", "impactScope"),
            ("timing", "urgencyState"),
        ):
            original_known = original_record[axis]["anyOf"][0]["properties"]
            wire_known = wire_record[axis]["anyOf"][0]["properties"]
            assert list(wire_known)[0] == category
            assert original_known["basis"]["description"].strip()
            assert wire_known["basis"]["description"] == original_known["basis"]["description"]
            assert (
                resolve_schema_node(wire, wire_known["basis"])
                == wire["$defs"]["Finding101SourceSpan"]
            )
        for index in (0, 2):
            original_relation = resolve_schema_node(
                schema, original_record["connection"]["anyOf"][index]
            )["properties"]["relation"]
            wire_relation = resolve_schema_node(wire, wire_record["connection"]["anyOf"][index])[
                "properties"
            ]["relation"]
            assert original_relation["description"].strip()
            assert wire_relation["description"] == original_relation["description"]

    assert schema == snapshot
    assert source.model_dump_json(by_alias=True) == source_snapshot


@pytest.mark.parametrize(
    "category,expected_axis,expected_score",
    [
        ("CORE_CONSTRAINT", 3, 3.0),
        ("PROJECT_CHANGE", 2, 2.6),
        ("LIMITED_PREPARATION", 1, 2.2),
        ("NO_CHANGE", 0, 1.8),
        ("UNDETERMINED", None, None),
    ],
)
def test_described_effect_choices_keep_cited_zero_and_uncited_unknown_distinct(
    category, expected_axis, expected_score
):
    source = request()
    flat = payload(source)
    assessment = flat["assessments"]["CHIP_MAKER"]["finding101"]
    assessment["impactScope"] = category
    if category == "UNDETERMINED":
        assessment["impactBasis"] = None
    schema = _wire(draft_schema(source))
    assert schema["$defs"]["ReportKnownImpactScope"]["enum"] == [
        "CORE_CONSTRAINT",
        "PROJECT_CHANGE",
        "LIMITED_PREPARATION",
        "NO_CHANGE",
    ]
    native = draft_to_wire(flat, source)
    Draft202012Validator(schema).validate(native)
    public = validate_draft(response(flat, source), source).mapped.insights[0].assessments[0]
    assert public.axes.impact == expected_axis
    score = score_importance(public.axes)
    assert score is None if expected_score is None else score == pytest.approx(expected_score)

    # Zero remains an evidence-backed choice. Unknown remains null; neither
    # can borrow the other branch's proof contract after SDK transformation.
    effect = native["assessments"]["CHIP_MAKER"]["finding101"]["decision"]["effect"]
    effect["basis"] = (
        native["assessments"]["CHIP_MAKER"]["finding101"]["decision"]["connection"]["basis"]
        if category == "UNDETERMINED"
        else None
    )
    assert not Draft202012Validator(schema).is_valid(native)


@pytest.mark.parametrize(
    "change",
    [
        "cross_finding_proof",
        "cross_claim_span",
        "foreign_work",
        "invented_scope",
        "numeric_scope",
        "known_timing_without_proof",
        "unknown_timing_with_proof",
        "direct_without_proof",
        "unknown_connection_with_proof",
        "added_observation",
    ],
)
def test_described_wire_still_rejects_invalid_choices_and_source_bindings(change):
    source = request(ids=(101, 102))
    source.findings[0].claims.append(
        source.findings[0].claims[0].model_copy(update={"id": "101:1"})
    )
    native = draft_to_wire(payload(source), source)
    schema = _wire(draft_schema(source))
    validator = Draft202012Validator(schema)
    validator.validate(native)
    record = native["assessments"]["CHIP_MAKER"]["finding101"]
    if change == "cross_finding_proof":
        record["decision"]["effect"]["basis"] = native["assessments"]["CHIP_MAKER"]["finding102"][
            "decision"
        ]["effect"]["basis"]
    elif change == "cross_claim_span":
        record["decision"]["effect"]["basis"]["sourceSpanId"] = next(
            iter(source_span_choices(source.findings[0])["101:1"])
        )
    elif change == "foreign_work":
        record["decision"]["connection"]["work"] = "SYSTEM_PROCUREMENT"
    elif change == "invented_scope":
        record["decision"]["effect"]["impactScope"] = "POSSIBLE_CHANGE"
    elif change == "numeric_scope":
        record["decision"]["effect"]["impactScope"] = 0
    elif change == "known_timing_without_proof":
        record["decision"]["timing"]["basis"] = None
    elif change == "unknown_timing_with_proof":
        record["decision"]["timing"]["urgencyState"] = "UNDETERMINED"
    elif change == "direct_without_proof":
        record["decision"]["connection"]["basis"] = None
    elif change == "unknown_connection_with_proof":
        record["decision"]["connection"].update(relation="UNDETERMINED", work=None)
    elif change == "added_observation":
        record["decision"]["effect"]["observation"] = "원문에서 실제 변경을 확인했다."
    assert not validator.is_valid(native)
