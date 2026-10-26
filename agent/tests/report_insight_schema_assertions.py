"""Schema comparisons shared by offline repair tests, without provider imports."""

from copy import deepcopy


def assert_same_native_constraints(modified, original):
    def canonical(schema):
        result = deepcopy(schema)
        for audience in result["properties"]["assessments"]["properties"].values():
            for entry in audience["properties"].values():
                if set(entry.get("properties", {})) == {
                    "findingId",
                    "decision",
                    "reason",
                    "sourceQuotes",
                }:
                    # Only record serialization order changed for native retries.
                    # Sorting preserves missing/extra/duplicate required fields;
                    # all nested required lists and branches stay untouched.
                    entry["required"] = sorted(entry["required"])
        return result

    assert canonical(modified) == canonical(original)


def assert_only_display_quotes_require_null(modified, original):
    expected = deepcopy(original)
    for audience in expected["properties"]["assessments"]["properties"].values():
        for entry in audience["properties"].values():
            selectors = entry["properties"]["sourceQuotes"]["properties"]
            for field in ("reason", "condition"):
                selectors[field] = {"type": "null"}
    # This compares the complete schema: axes, mandatory bases, definitions,
    # record identities and all non-display constraints must remain identical.
    assert_same_native_constraints(modified, expected)
