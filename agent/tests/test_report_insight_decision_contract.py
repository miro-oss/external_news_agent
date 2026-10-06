"""Private decision branches reject impossible tuples before local validation.

These format tests do not establish source sufficiency or model accuracy.
"""

from copy import deepcopy
from itertools import product

import pytest
from jsonschema import Draft202012Validator
from pydantic import ValidationError
from pydantic_ai.profiles.openai import OpenAIJsonSchemaTransformer
from test_report_insight_assessment import (
    payload,
    request,
    resolved_object_depth,
    schema_nodes,
    schema_size_metrics,
    source_finding,
)

from app.llm.openai_contract import output_contract
from app.llm.report_insight_assessment import (
    IMPACT_SCORES,
    RELATION_SCORES,
    ROLE_WORK,
    URGENCY_SCORES,
    draft_schema,
    draft_to_wire,
    parse_wire_draft,
    source_span_choices,
)
from app.schemas.report_insight import CLAIMLESS_ASSESSMENT_REASON


def wire_schema(source):
    logical = draft_schema(source)
    snapshot = deepcopy(logical)
    wire = OpenAIJsonSchemaTransformer(output_contract(logical).schema, strict=True).walk()
    assert logical == snapshot
    return wire


@pytest.mark.parametrize(
    "relation,impact,urgency", product(RELATION_SCORES, IMPACT_SCORES, URGENCY_SCORES)
)
def test_actual_sdk_schema_accepts_exactly_the_consistent_axis_tuples(relation, impact, urgency):
    source = request()
    native = draft_to_wire(payload(source, relation=relation), source)
    decision = native["assessments"]["CHIP_MAKER"]["finding101"]["decision"]
    basis = {"claimId": "101:0", "sourceSpanId": "s101_0_0"}
    decision["effect"] = {
        "impactScope": impact,
        "basis": None if impact == "UNDETERMINED" else deepcopy(basis),
    }
    decision["timing"] = {
        "urgencyState": urgency,
        "basis": None if urgency == "UNDETERMINED" else deepcopy(basis),
    }
    expected = relation not in {"UNRELATED", "UNDETERMINED"} or (
        impact == urgency == "UNDETERMINED"
    )
    assert Draft202012Validator(wire_schema(source)).is_valid(native) is expected


@pytest.mark.parametrize("size", [6, 12, 50])
def test_four_audience_sdk_schema_shares_proofs_and_stays_within_all_limits(size):
    source = request(ids=tuple(range(101, 101 + size)), audiences=tuple(ROLE_WORK))
    for finding in source.findings:
        finding.claims = [
            finding.claims[0].model_copy(update={"id": f"{finding.id}:{index}"})
            for index in range(3)
        ]
    original = source.model_dump_json(by_alias=True)
    schema = wire_schema(source)
    metrics = schema_size_metrics(schema)
    assert metrics["enum_values"] <= 1000
    assert metrics["properties"] <= 5000
    assert metrics["string_characters"] <= 120_000
    assert resolved_object_depth(schema) <= 10
    assert "anyOf" not in schema
    for node in schema_nodes(schema):
        assert not {"allOf", "not", "if", "then", "else", "dependentSchemas"} & node.keys()
        if node.get("type") == "object":
            assert node["additionalProperties"] is False
            assert set(node["required"]) == set(node["properties"])
    assert len([key for key in schema["$defs"] if key.endswith("SourceSpan")]) == size
    assert (
        metrics["enum_values"]
        == sum(
            len(spans)
            for finding in source.findings
            for spans in source_span_choices(finding).values()
        )
        + 30
    )
    for audience in source.audiences:
        records = schema["properties"]["assessments"]["properties"][audience]["properties"]
        for finding in source.findings:
            record = records[f"finding{finding.id}"]
            assert "anyOf" not in record
            assert list(record["properties"]) == ["findingId", "reason", "decision"]
            related, unrelated = record["properties"]["decision"]["anyOf"]
            for branch in (related, unrelated):
                assert list(branch["properties"]) == ["connection", "effect", "timing"]
            assert related["properties"]["effect"] == {"$ref": f"#/$defs/Finding{finding.id}Effect"}
            assert related["properties"]["timing"] == {"$ref": f"#/$defs/Finding{finding.id}Timing"}
            assert unrelated["properties"]["effect"] == {"$ref": "#/$defs/ReportUnknownEffect"}
            assert unrelated["properties"]["timing"] == {"$ref": "#/$defs/ReportUnknownTiming"}
    Draft202012Validator(schema).validate(draft_to_wire(payload(source), source))
    assert source.model_dump_json(by_alias=True) == original


@pytest.mark.parametrize(
    "condition,valid",
    [
        ("", False),
        (" ", False),
        ("가" * 121, False),
        ("가", True),
        ("가" * 120, True),
        (None, False),
    ],
)
def test_sdk_preserves_concrete_condition_string_bounds(condition, valid):
    source = request()
    native = draft_to_wire(payload(source, relation="CONDITIONAL"), source)
    native["assessments"]["CHIP_MAKER"]["finding101"]["decision"]["connection"]["condition"] = (
        condition
    )
    assert Draft202012Validator(wire_schema(source)).is_valid(native) is valid


def test_claimless_uses_same_outer_and_decision_shape_with_exact_unknowns():
    source = request()
    source.findings[0].claims = []
    source.findings[0].sentences = []
    schema = wire_schema(source)
    native = {
        "assessments": {
            "CHIP_MAKER": {
                "finding101": {
                    "findingId": 101,
                    "decision": {
                        "connection": {
                            "relation": "UNDETERMINED",
                            "work": None,
                            "condition": None,
                            "basis": None,
                        },
                        "effect": {"impactScope": "UNDETERMINED", "basis": None},
                        "timing": {"urgencyState": "UNDETERMINED", "basis": None},
                    },
                    "reason": CLAIMLESS_ASSESSMENT_REASON,
                }
            }
        }
    }
    Draft202012Validator(schema).validate(native)
    native["assessments"]["CHIP_MAKER"]["finding101"]["decision"]["effect"]["impactScope"] = (
        "NO_CHANGE"
    )
    assert not Draft202012Validator(schema).is_valid(native)


def test_dense_original_span_choices_survive_shared_decision_definitions():
    source = source_finding(
        text="공정 검증을 준비한다.",
        sentence=" ".join(f"검증 구절 {index}." for index in range(1100)),
        second_claim="별도 공급 계약의 조건을 검토한다.",
    )
    snapshot = source.model_dump_json(by_alias=True)
    schema = wire_schema(source)
    assert schema_size_metrics(schema)["enum_values"] <= 1000
    choices = source_span_choices(source.findings[0])
    assert sum(map(len, choices.values())) > 1000
    assert len([key for key in schema["$defs"] if key.endswith("SourceSpan")]) == 1
    native = draft_to_wire(payload(source), source)
    Draft202012Validator(schema).validate(native)
    assert source.model_dump_json(by_alias=True) == snapshot


def test_old_ungrouped_native_record_is_rejected_without_fallback():
    import json

    source = request()
    native = draft_to_wire(payload(source), source)
    record = native["assessments"]["CHIP_MAKER"]["finding101"]
    record.update(record.pop("decision"))
    assert not Draft202012Validator(wire_schema(source)).is_valid(native)
    with pytest.raises(ValidationError):
        parse_wire_draft(json.dumps(native))
