"""A partial repair sends only definitions used by its unchanged contract."""

import json
from copy import deepcopy

import pytest
from jsonschema import Draft202012Validator
from test_report_insight_assessment import payload, request

from app.llm.openai_contract import _retain_referenced_definitions, output_contract
from app.llm.report_insight_assessment import draft_schema, draft_to_wire


@pytest.mark.parametrize(
    "relation", ["DIRECT", "CONDITIONAL", "BACKGROUND", "UNRELATED", "UNDETERMINED"]
)
def test_partial_repair_keeps_exact_source_choices_and_axis_contract(relation):
    source = request(ids=tuple(range(101, 107)))
    original = draft_schema(source)
    entries = original["properties"]["assessments"]["properties"]["CHIP_MAKER"]
    entries["properties"] = {"finding101": entries["properties"]["finding101"]}
    entries["required"] = ["finding101"]
    snapshot = deepcopy(original)

    compact = output_contract(original).schema

    assert original == snapshot
    assert len(json.dumps(compact)) < len(json.dumps(original)) * 0.6
    assert "Finding101SourceSpan" in compact["$defs"]
    assert all(
        f"Finding{finding_id}SourceSpan" not in compact["$defs"] for finding_id in range(102, 107)
    )
    for name, definition in compact["$defs"].items():
        assert definition == original["$defs"][name]
    before, after = Draft202012Validator(original), Draft202012Validator(compact)
    native = draft_to_wire(payload(source, relation=relation), source)
    native["assessments"]["CHIP_MAKER"] = {
        "finding101": native["assessments"]["CHIP_MAKER"]["finding101"]
    }
    assert before.is_valid(native) and after.is_valid(native)
    for changed in ("cross_finding_basis", "extra_record", "missing_record", "inconsistent_axes"):
        invalid = deepcopy(native)
        decision = invalid["assessments"]["CHIP_MAKER"]["finding101"]["decision"]
        if changed == "cross_finding_basis":
            decision["connection"]["basis"] = {"claimId": "102:0", "sourceSpanId": "s102_0_0"}
        elif changed == "extra_record":
            invalid["assessments"]["CHIP_MAKER"]["finding102"] = deepcopy(
                invalid["assessments"]["CHIP_MAKER"]["finding101"]
            )
        elif changed == "missing_record":
            invalid["assessments"]["CHIP_MAKER"].clear()
        else:
            decision["effect"] = {"impactScope": "CORE_CONSTRAINT", "basis": None}
        assert not before.is_valid(invalid) and not after.is_valid(invalid)


def test_transitive_and_recursive_definitions_are_retained_in_original_order():
    schema = {
        "$ref": "#/$defs/A",
        "$defs": {
            "Unused": {"type": "string"},
            "B": {"anyOf": [{"type": "null"}, {"$ref": "#/$defs/A"}]},
            "A": {"type": "object", "properties": {"next": {"$ref": "#/$defs/B"}}},
        },
    }
    _retain_referenced_definitions(schema)
    assert list(schema["$defs"]) == ["B", "A"]


@pytest.mark.parametrize(
    "reference", ["external.json", "#anchor", "#/$defs/Missing", "#/$defs/A/properties/x"]
)
def test_unknown_reference_scopes_leave_the_schema_unchanged(reference):
    schema = {"$ref": reference, "$defs": {"A": {"type": "string"}}}
    original = deepcopy(schema)
    _retain_referenced_definitions(schema)
    assert schema == original
