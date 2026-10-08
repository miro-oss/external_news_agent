"""Schema comparisons shared by offline repair tests, without provider imports."""

from copy import deepcopy


def assert_only_display_quotes_require_null(modified, original):
    expected = deepcopy(original)
    for audience in expected["properties"]["assessments"]["properties"].values():
        for entry in audience["properties"].values():
            selectors = entry["properties"]["sourceQuotes"]["properties"]
            for field in ("reason", "condition"):
                selectors[field] = {"type": "null"}
    # This compares the complete schema: axes, mandatory bases, definitions,
    # record identities and all non-display constraints must remain identical.
    assert modified == expected
