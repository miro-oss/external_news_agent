"""Explicit source bindings catch factual swaps without inventing unknown facts."""

import json
from dataclasses import FrozenInstanceError, replace

import pytest
from test_report_insight_assessment import request

from app.llm.report_insight_service import _eligible_report_request
from app.llm.report_insight_source_facts import (
    build_source_facts,
    source_fact_hints,
    source_fact_mismatches,
    source_fact_payload,
)

COMPANIES = "삼성전자와 SK하이닉스의 생산량은 각각 20개와 10개다."
YEARS = "2028년과 2030년의 생산량은 각각 20개와 10개다."
CONSTRUCTION = "삼성전자는 공장을 건설할 계획이다. SK하이닉스는 별도 공장을 완공했다."


@pytest.mark.parametrize(
    ("source", "value", "prefix"),
    [
        (COMPANIES, "삼성전자의 생산량은 10개다.", "근거와 연결이 다른 숫자:"),
        (COMPANIES, "SK하이닉스의 생산량은 20개다.", "근거와 연결이 다른 숫자:"),
        (YEARS, "2028년 생산량은 10개다.", "근거와 연결이 다른 숫자:"),
        (YEARS, "2030년 생산량은 20개다.", "근거와 연결이 다른 숫자:"),
        (CONSTRUCTION, "삼성전자는 공장을 완공했다.", "근거의 주체·사건 연결과 다릅니다:"),
    ],
)
def test_known_cross_owner_year_and_event_swaps_are_rejected(source, value, prefix):
    errors = source_fact_mismatches(value, source)
    assert len(errors) == 1
    assert errors[0].startswith(prefix)


@pytest.mark.parametrize(
    ("source", "value"),
    [
        (COMPANIES, "삼성전자의 생산량은 20개다."),
        (COMPANIES, "SK하이닉스와 삼성전자의 생산량은 각각 10개와 20개다."),
        (YEARS, "2030년과 2028년의 생산량은 각각 10개와 20개다."),
        (CONSTRUCTION, "SK하이닉스는 별도 공장을 완공했다."),
        (CONSTRUCTION, "삼성전자는 공장을 완공할 계획이다."),
        (CONSTRUCTION, "삼성전자는 공장을 완공할 경우 공급 조건을 확인한다."),
        (CONSTRUCTION, "삼성전자 공장의 완공 여부를 확인해야 한다."),
        (CONSTRUCTION, "삼성전자는 서울 공장을 완공했다."),
        (COMPANIES, "삼성전자의 공급량은 10개다."),
    ],
)
def test_supported_reordering_conditions_and_different_targets_are_not_false_conflicts(
    source, value
):
    assert source_fact_mismatches(value, source) == []


@pytest.mark.parametrize(
    ("source", "value", "number", "unit"),
    [
        (
            "Samsung Electronics' production volume is 1,000 units.",
            "삼성전자의 생산량은 1천개다.",
            "1000",
            "count",
        ),
        (
            "삼성전자의 생산량은 20개다.",
            "Samsung Electronics' production volume is 20 pieces.",
            "20",
            "count",
        ),
        (
            "삼성전자의 매출은 2 million USD다.",
            "Samsung Electronics' revenue is 200만 달러.",
            "2000000",
            "usd",
        ),
        (
            "삼성전자의 공급량은 20톤이다.",
            "Samsung Electronics' supply volume is 20 tonnes.",
            "20",
            "tonne",
        ),
    ],
)
def test_explicit_aliases_and_exact_unit_scaling_preserve_supported_facts(
    source, value, number, unit
):
    for text in (source, value):
        parsed = build_source_facts(text)
        assert len(parsed.facts) == 1 and parsed.unresolved == ()
        assert (parsed.facts[0].quantity, parsed.facts[0].unit) == (number, unit)
        # A bare assertion does not prove observation, realization, or source claim type.
        assert parsed.facts[0].modality == "asserted"
    assert source_fact_mismatches(value, source) == []


def test_foreign_language_binding_does_not_lose_respective_order():
    source = (
        "Samsung Electronics and SK hynix's production volumes are "
        "20 units and 10 units, respectively."
    )
    assert len(build_source_facts(source).facts) == 2
    assert source_fact_mismatches("삼성전자의 생산량은 10개다.", source)
    assert not source_fact_mismatches("SK하이닉스의 생산량은 10개다.", source)
    assert source_fact_mismatches("In 2028 production volume is 10 units.", YEARS)


def test_foreign_language_construction_stage_is_bound_to_its_own_subject():
    source = "Samsung Electronics plans to build a factory. SK hynix completed another factory."
    assert len(build_source_facts(source).facts) == 2
    assert source_fact_mismatches("삼성전자는 공장을 완공했다.", source)
    assert not source_fact_mismatches("Samsung Electronics will build a factory.", source)


@pytest.mark.parametrize(
    "source",
    [
        "삼성전자와 SK하이닉스의 생산량은 20개와 10개다.",
        "삼성전자와 SK하이닉스의 생산량은 각각 20개다.",
        "삼성전자의 생산량은 약 20개다.",
        "삼성전자는 공장을 완공할 경우 공급 조건을 확인한다.",
        "만약 삼성전자는 공장을 완공했다.",
        "기사는 삼성전자가 공장을 완공했다고 보도했다.",
        "그 회사는 공장을 완공했다.",
    ],
)
def test_unresolved_text_is_preserved_and_is_not_negative_evidence(source):
    parsed = build_source_facts(source)
    assert parsed.facts == ()
    assert parsed.unresolved and parsed.unresolved[0].span.matches(source)
    assert source_fact_mismatches("삼성전자의 생산량은 10개다.", source) == []
    assert source_fact_mismatches("삼성전자는 공장을 완공했다.", source) == []


@pytest.mark.parametrize(
    "source",
    [
        "지난해 삼성전자의 생산량은 20개다.",
        "삼성전자의 예상 생산량은 20개다.",
        "Last year Samsung Electronics' production volume is 20 units.",
        "Samsung Electronics' expected production volume is 20 units.",
        "삼성전자는 공장을 건설할 계획이며 SK하이닉스는 공장을 완공했다.",
        "Samsung Electronics plans to build a factory and SK hynix completed a factory.",
        "삼성전자는 A사가 공장을 건설할 계획이라고 설명한 공장을 완공했다.",
        "Samsung Electronics completed a factory that SK hynix will build near another factory.",
        "삼성전자는 내년에 가동할 공장을 완공했다.",
        "정체불명의 생산량은 20개다.",
        "Forecast's production volume is 20 units.",
    ],
)
def test_qualifiers_unrecognized_actors_and_multiple_clauses_are_not_fabricated_slots(source):
    parsed = build_source_facts(source)
    assert parsed.facts == ()
    assert parsed.unresolved and parsed.unresolved[0].span.matches(source)
    assert source_fact_hints({"source": source}) == {}


@pytest.mark.parametrize(
    ("source", "value"),
    [
        ("삼성전자의 생산량은 20개다. 삼성전자의 생산량은 30개다.", "삼성전자의 생산량은 10개다."),
        (
            "삼성전자는 공장을 건설할 계획이다. 삼성전자는 공장을 완공했다.",
            "삼성전자는 공장을 완공했다.",
        ),
        (
            "삼성전자의 생산량은 20개다. 이후 같은 생산량이 10개로 바뀌었다.",
            "삼성전자의 생산량은 10개다.",
        ),
        (
            "삼성전자는 공장을 건설할 계획이다. 이후 그 공장을 완공했다.",
            "삼성전자는 공장을 완공했다.",
        ),
        ("Samsung Electronics' supply volume is 20 tons.", "삼성전자의 공급량은 10톤이다."),
    ],
)
def test_multiple_values_discourse_and_ambiguous_units_do_not_create_hard_conflicts(source, value):
    assert source_fact_mismatches(value, source) == []


@pytest.mark.parametrize(
    ("source", "value"),
    [
        (
            "삼성전자는 공장을 건설할 계획이다. 이후 이 공장의 건설을 마쳤다.",
            "삼성전자는 공장을 완공했다.",
        ),
        (
            "삼성전자의 생산량은 20개다. 이후 정정된 수치가 발표됐다.",
            "삼성전자의 생산량은 10개다.",
        ),
        (
            "삼성전자는 공장을 건설할 계획이다.",
            "다음은 가정이다. 삼성전자는 공장을 완공했다.",
        ),
        (
            "삼성전자의 생산량은 20개다.",
            "다음은 인용된 예상 수치다. 삼성전자의 생산량은 10개다.",
        ),
    ],
)
def test_unresolved_context_on_either_side_defers_new_hard_conflicts(source, value):
    assert build_source_facts(source).unresolved or build_source_facts(value).unresolved
    assert source_fact_mismatches(value, source) == []


def test_valid_completion_claim_is_not_excluded_by_partially_parsed_source():
    source = request(text="삼성전자는 공장을 건설할 계획이다. 이후 이 공장의 건설을 마쳤다.")
    source.findings[0].claims[0].text = "삼성전자는 공장을 완공했다."
    original = source.model_dump_json(by_alias=True)
    eligible = _eligible_report_request(source)
    assert [claim.text for claim in eligible.findings[0].claims] == ["삼성전자는 공장을 완공했다."]
    assert source.model_dump_json(by_alias=True) == original


def test_fact_identity_and_every_slot_offset_remain_bound_to_exact_original_source():
    source = "  " + COMPANIES + "\n" + CONSTRUCTION + "\n해석은 아직 불명확하다."
    parsed = build_source_facts(source)
    assert len(parsed.facts) == 4
    for fact in parsed.facts:
        assert fact.source_sha256 == parsed.source_sha256
        assert fact.matches(source)
        assert not fact.matches(source.replace("20개", "21개"))
        for binding in fact.bindings:
            assert fact.span.start <= binding.span.start < binding.span.end <= fact.span.end
            assert binding.span.matches(source)
    altered = replace(parsed.facts[0], span=replace(parsed.facts[0].span, start=1))
    assert not altered.matches(source)
    with pytest.raises(FrozenInstanceError):
        parsed.facts[0].quantity = "999"


def test_cache_is_bounded_and_source_content_is_part_of_its_identity():
    first = build_source_facts(COMPANIES)
    assert build_source_facts(COMPANIES) is first
    changed = build_source_facts(COMPANIES.replace("20개", "30개"))
    assert first is not changed
    assert first.source_sha256 != changed.source_sha256
    assert build_source_facts.cache_info().maxsize == 256


def test_payload_is_compact_bounded_and_explicitly_non_authoritative():
    source = COMPANIES + "\n이후 변경 여부는 알 수 없다."
    payload = source_fact_payload(source, max_facts=1)
    assert payload["hintOnly"] is True
    assert payload["experimental"] is True
    assert payload["extractionScope"] == "explicit_closed_grammar"
    assert len(payload["facts"]) == 1
    assert payload["truncated"] is True
    assert payload["unresolved"][0]["reason"] == "unparsed_clause"
    serialized = json.dumps(payload, ensure_ascii=False)
    assert "text" not in serialized
    assert COMPANIES not in serialized
    assert "unresolved does not mean unsupported" in payload["interpretation"]
    assert source_fact_payload("", max_facts=0)["facts"] == []
    with pytest.raises(ValueError):
        source_fact_payload(source, max_facts=-1)


def test_source_fact_hints_omit_empty_and_unparsed_sources_without_changing_known_payload():
    assert source_fact_hints({"blank": " ", "unknown": "완료 여부는 아직 알 수 없다."}) == {}
    sources = {"0": COMPANIES, "1": "", "2": "향후 영향은 추가 검토한다."}
    assert source_fact_hints(sources) == {"0": source_fact_payload(COMPANIES)}
