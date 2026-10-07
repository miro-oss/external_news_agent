"""Contract number and local grammatical scope cannot reverse source events."""

from types import SimpleNamespace

import pytest

from app.llm.report_insight_guard import factual_states
from app.llm.report_insight_service import _validate_prose


def validate(source: str) -> None:
    _validate_prose(
        ["양사는 협약을 체결했다."],
        ["301:0"],
        {"301:0": source},
        {"301:0": SimpleNamespace(text=source)},
    )


@pytest.mark.parametrize(
    "object_",
    ["an agreement", "agreements", "a contract", "contracts", "a lease", "leases"],
)
def test_singular_and_plural_completed_contracts_support_the_same_state(object_):
    source = f"The companies signed {object_}."
    assert factual_states(source).get("contract") == {True}
    validate(source)


@pytest.mark.parametrize(
    "source",
    [
        "The companies did not change prices and signed an agreement.",
        "The companies did not change prices and formally signed agreements.",
        "The companies did not change prices and signed leases.",
        "The companies didn't change prices and signed contracts.",
        "After discussing whether to expand, the companies signed an agreement.",
        "Before discussing whether to expand, the companies signed contracts.",
        "Despite uncertainty over whether to expand, the companies signed leases.",
        "Although the companies did not change prices, they signed agreements.",
        "The companies have not changed prices but signed an agreement.",
    ],
)
def test_closed_or_independent_clause_does_not_negate_or_condition_signing(source):
    assert factual_states(source).get("contract") == {True}
    validate(source)


@pytest.mark.parametrize(
    "source",
    [
        "The companies have not signed agreements.",
        "The companies have never signed contracts.",
        "The companies have not yet formally signed an agreement.",
        "The companies haven't yet signed a contract.",
        "The companies have not reviewed and signed agreements.",
        "The companies had never reviewed and signed contracts.",
        "The companies did not confirm they had signed an agreement.",
        "The companies did not confirm they had reviewed and signed an agreement.",
        "The companies did not find evidence that they reviewed and signed agreements.",
        "The companies did not reveal they had reviewed and signed agreements.",
        "The companies did not disclose they had reviewed and signed contracts.",
        "The companies did not explain they had reviewed and signed agreements.",
        "The companies did not reveal the partners reviewed and signed agreements.",
        "The companies did not disclose the partners wrote and signed contracts.",
        "The companies did not change the terms under which "
        "the partners reviewed and signed contracts.",
        "The companies did not change how they reviewed and signed agreements.",
        "If the companies did not change prices and signed contracts, talks would end.",
        "If prices had changed, the companies would have signed contracts.",
        "The companies discussed whether they signed agreements.",
        "The companies discussed whether they had reviewed and signed agreements.",
        "The companies denied that they signed agreements.",
        "The companies will have signed contracts by next year.",
        "After discussing whether to expand, the companies had not signed agreements.",
        "After discussing whether to expand, if the companies signed agreements, talks would end.",
        "Although prices did not change, the companies have not signed contracts.",
        "If prices did not change but the companies signed agreements, talks would end.",
        "The companies signed agreement drafts.",
    ],
)
def test_negation_shared_auxiliaries_and_signing_conditions_still_block_completion(source):
    assert True not in factual_states(source).get("contract", set())
    with pytest.raises(ValueError):
        validate(source)


@pytest.mark.parametrize("object_", ["agreements", "contracts", "leases"])
def test_negated_signing_does_not_become_positive_with_plural_objects(object_):
    source = f"The companies have not yet formally signed {object_}."
    assert factual_states(source).get("contract") == {False}
    with pytest.raises(ValueError):
        validate(source)


@pytest.mark.parametrize("object_", ["agreements", "contracts", "leases"])
def test_plural_noun_does_not_admit_a_proposal_as_a_completed_contract(object_):
    source = f"The companies signed {object_} proposal."
    assert True not in factual_states(source).get("contract", set())
    with pytest.raises(ValueError):
        validate(source)
