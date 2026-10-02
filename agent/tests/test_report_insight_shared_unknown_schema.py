"""Share identical unknown branches without widening the provider contract."""

import json
from copy import deepcopy

import pytest
from jsonschema import Draft202012Validator
from jsonschema import ValidationError as JsonSchemaValidationError
from pydantic_ai.profiles.openai import OpenAIJsonSchemaTransformer
from test_report_insight_assessment import payload, request

from app.llm.openai_contract import output_contract
from app.llm.report_insight_assessment import ROLE_WORK, draft_schema, draft_to_wire
from app.schemas.report_insight import CLAIMLESS_ASSESSMENT_REASON

UNKNOWN = {
    "connection": ("ReportUnknownConnection", "relation", ("work", "condition", "basis")),
    "effect": ("ReportUnknownEffect", "impactScope", ("basis",)),
    "timing": ("ReportUnknownTiming", "urgencyState", ("basis",)),
}


def _wire(schema):
    return OpenAIJsonSchemaTransformer(output_contract(schema).schema, strict=True).walk()


def _inline_unknowns(schema):
    """Restore the previous inline form, leaving all other references intact."""
    names = {name for name, _, _ in UNKNOWN.values()}

    def expand(value):
        if isinstance(value, list):
            return [expand(item) for item in value]
        if not isinstance(value, dict):
            return value
        reference = value.get("$ref", "").removeprefix("#/$defs/")
        if reference in names:
            assert set(value) == {"$ref"}
            return deepcopy(schema["$defs"][reference])
        return {key: expand(item) for key, item in value.items()}

    result = expand(schema)
    for name in names:
        del result["$defs"][name]
    return result


def _encoded(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def test_sdk_preserves_shared_unknowns_and_the_previous_contract_order():
    source = request(ids=tuple(range(101, 113)), audiences=tuple(ROLE_WORK))
    source.findings[-1] = source.findings[-1].model_copy(update={"claims": [], "sentences": []})
    logical = draft_schema(source)
    snapshot = deepcopy(logical)
    wire = _wire(logical)
    assert logical == snapshot
    # Expanding only the three references restores the complete prior native
    # contract byte-for-byte, including field order and known-first branches.
    assert _encoded(_inline_unknowns(wire)) == _encoded(_wire(_inline_unknowns(logical)))
    for field, (name, category, nulls) in UNKNOWN.items():
        expected_properties = {
            category: {"type": "string", "const": "UNDETERMINED"},
            **{key: {"type": "null"} for key in nulls},
        }
        definition = deepcopy(wire["$defs"][name])
        # Guidance is an annotation; the shared branch must retain every
        # validation keyword and its original category-first property order.
        definition["properties"][category].pop("description", None)
        assert definition == {
            "type": "object",
            "properties": expected_properties,
            "required": list(expected_properties),
            "additionalProperties": False,
        }
        for audience in source.audiences:
            records = wire["properties"]["assessments"]["properties"][audience]["properties"]
            for finding in source.findings:
                record = records[f"finding{finding.id}"]["properties"]
                assert list(record) == ["findingId", "connection", "effect", "timing", "reason"]
                assert record["findingId"] == {"type": "integer", "const": finding.id}
                if finding.claims:
                    assert record[field]["anyOf"][-1] == {"$ref": f"#/$defs/{name}"}
                else:
                    assert record[field] == {"$ref": f"#/$defs/{name}"}
                    assert record["reason"] == {
                        "type": "string",
                        "const": CLAIMLESS_ASSESSMENT_REASON,
                    }
    Draft202012Validator.check_schema(wire)


@pytest.mark.parametrize("field", UNKNOWN)
@pytest.mark.parametrize("change", ["extra", "missing_basis", "nonnull_basis"])
def test_shared_unknowns_retain_closed_null_only_contract(field, change):
    source = request()
    native = draft_to_wire(payload(source, relation="UNDETERMINED"), source)
    schema = _wire(draft_schema(source))
    validators = [Draft202012Validator(schema), Draft202012Validator(_inline_unknowns(schema))]
    for validator in validators:
        validator.validate(native)
    axis = native["assessments"]["CHIP_MAKER"]["finding101"][field]
    if change == "extra":
        axis["unexpected"] = None
    elif change == "missing_basis":
        del axis["basis"]
    else:
        axis["basis"] = {"claimId": "101:0", "sourceSpanId": "s101_0_0"}
    for validator in validators:
        with pytest.raises(JsonSchemaValidationError):
            validator.validate(native)


@pytest.mark.parametrize("audiences", [("CHIP_MAKER",), tuple(ROLE_WORK)])
def test_shared_unknowns_reduce_twelve_finding_native_schema_size(audiences):
    source = request(ids=tuple(range(101, 113)), audiences=audiences)
    logical = draft_schema(source)
    previous = _wire(_inline_unknowns(logical))
    compact = _wire(logical)
    assert len(_encoded(compact)) < len(_encoded(previous)) * 0.90
    # Known output and IDs are unaffected by factoring unrelated branches.
    native = draft_to_wire(payload(source), source)
    Draft202012Validator(previous).validate(native)
    Draft202012Validator(compact).validate(native)
