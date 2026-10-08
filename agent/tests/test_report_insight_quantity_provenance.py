"""Unit-price arithmetic preserves the provenance required for literal facts."""

from dataclasses import replace

import pytest

from app.llm.report_insight_fact_graph import Mention, Span, analyze_sentence
from app.llm.report_insight_fact_verification import EvidenceFact, _compare, _mismatch_messages
from app.llm.report_insight_source_facts import build_source_facts, source_fact_mismatches


def test_english_thousand_is_one_quantity_with_original_source_offsets():
    source = "Samsung Electronics' production volume is 1 thousand units."
    parsed = build_source_facts(source)
    assert not parsed.unresolved
    assert len(parsed.facts) == 1
    fact = parsed.facts[0]
    assert (fact.subject, fact.quantity, fact.unit) == ("삼성전자", "1000", "count")
    assert fact.matches(source)
    assert source_fact_mismatches("삼성전자의 생산량은 2000개다.", source)
    assert not source_fact_mismatches("삼성전자의 생산량은 1000개다.", source)


def test_english_and_still_separates_respective_thousand_quantities():
    source = (
        "Samsung Electronics and SK hynix's production volumes are "
        "1 thousand units and 2 thousand units, respectively."
    )
    parsed = build_source_facts(source)
    assert not parsed.unresolved
    assert [(fact.subject, fact.quantity) for fact in parsed.facts] == [
        ("삼성전자", "1000"),
        ("sk하이닉스", "2000"),
    ]
    assert all(fact.matches(source) for fact in parsed.facts)
    assert source_fact_mismatches("삼성전자의 생산량은 2000개다.", source)
    assert source_fact_mismatches("SK하이닉스의 생산량은 1000개다.", source)


def _speaker(name):
    return Mention("actor", name, Span(0, len(name), name)) if name else None


def _price_pair(
    *,
    price,
    state="forecast",
    source_speaker="한빛증권",
    candidate_speaker="한빛증권",
    claim_types=("FORECAST",),
    attributed_to=("한빛증권",),
):
    source = analyze_sentence("별빛장비는 검사 장비 3대를 총 9억 원에 공급했다.")
    candidate = analyze_sentence(f"별빛장비가 공급한 검사 장비의 대당 가격은 {price}억 원이다.")
    original = next(relation for relation in source.relations if relation.quantity)
    value = next(relation for relation in candidate.relations if relation.quantity)
    assert original.quantity.role == "aggregate" and value.quantity.role == "per_unit"
    assert not original.uncertainty and not value.uncertainty
    # Vary provenance independently of the already tested literal quantity parser.
    evidence = EvidenceFact(
        source.source_sha256,
        replace(original, state=state, attributed_to=_speaker(source_speaker)),
        claim_types=claim_types,
        attributed_to=attributed_to,
    )
    return replace(value, state=state, attributed_to=_speaker(candidate_speaker)), evidence


@pytest.mark.parametrize("price", [3, 9])
@pytest.mark.parametrize("candidate_speaker", [None, "다른증권"])
def test_unit_price_neither_proves_nor_rejects_another_speakers_forecast(price, candidate_speaker):
    candidate, evidence = _price_pair(price=price, candidate_speaker=candidate_speaker)
    check = _compare(candidate, (evidence,))
    assert (check.outcome, check.reason) == ("unknown", "attribution_unresolved")
    assert not _mismatch_messages((check,))


@pytest.mark.parametrize("price", [3, 9])
@pytest.mark.parametrize(
    "speaker,attributed_to",
    [(None, ()), (None, ("한빛증권",)), ("한빛증권", ()), ("한빛증권", ("다른증권",))],
)
def test_unit_price_cannot_convert_unattributed_or_differently_attributed_opinion_to_fact(
    price, speaker, attributed_to
):
    candidate, evidence = _price_pair(
        price=price,
        source_speaker=speaker,
        candidate_speaker=speaker,
        claim_types=("OPINION",),
        attributed_to=attributed_to,
    )
    check = _compare(candidate, (evidence,))
    assert (check.outcome, check.reason) == ("unknown", "source_opinion_attribution_unresolved")
    assert not _mismatch_messages((check,))


@pytest.mark.parametrize("price", [3, 9])
@pytest.mark.parametrize("state", ["asserted", "completed"])
def test_unit_price_cannot_strengthen_forecast_claim_metadata_even_when_text_state_matches(
    price, state
):
    candidate, evidence = _price_pair(price=price, state=state)
    check = _compare(candidate, (evidence,))
    assert (check.outcome, check.reason) == ("unknown", "source_forecast_modality_unresolved")
    assert not _mismatch_messages((check,))


@pytest.mark.parametrize("price,outcome", [(3, "supported"), (9, "contradicted")])
@pytest.mark.parametrize(
    "claim_type,state",
    [
        ("FORECAST", "forecast"),
        ("FORECAST", "planned"),
        ("OPINION", "forecast"),
        ("FACT", "completed"),
    ],
)
def test_unit_price_preserves_valid_same_speaker_provenance(price, outcome, claim_type, state):
    candidate, evidence = _price_pair(price=price, claim_types=(claim_type,), state=state)
    check = _compare(candidate, (evidence,))
    assert check.outcome == outcome
    assert check.reason == (
        "explicit_unit_price_derived" if outcome == "supported" else "quantity_conflict"
    )
    assert bool(_mismatch_messages((check,))) == (outcome == "contradicted")


@pytest.mark.parametrize("price", [3, 9])
def test_conflicting_speaker_provenance_is_not_discarded_in_favor_of_eligible_arithmetic(price):
    candidate, evidence = _price_pair(price=price)
    other = replace(
        evidence, relation=replace(evidence.relation, attributed_to=_speaker("다른증권"))
    )
    check = _compare(candidate, (evidence, other))
    assert (check.outcome, check.reason) == ("unknown", "conflicting_source_relations")
    assert not _mismatch_messages((check,))
