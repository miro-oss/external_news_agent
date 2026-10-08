"""Relative periods use their own explicit clock and retain original offsets."""

from dataclasses import replace
from datetime import date

import pytest

from app.llm.report_insight_fact_graph import Mention, Relation, Span, analyze_sentence
from app.llm.report_insight_fact_time import resolve_fact_time


def relation(value):
    time = Mention("time", value, Span(0, 2, "내년"))
    return Relation(
        subjects=(),
        predicate="revenue",
        target=None,
        quantity=None,
        time=time,
        state="forecast",
        span=Span(0, 2, "내년"),
        bindings=(time,),
        rule="test_time_only",
        uncertainty=("subject_unresolved",),
    )


@pytest.mark.parametrize(
    ("value", "expected"),
    [
        ("next_year", "2027"),
        ("this_year", "2026"),
        ("last_year", "2025"),
        ("next_year:3분기", "2027-Q3"),
        ("last_year:Q4", "2025-Q4"),
        ("this_year:6월", "2026-06"),
        ("next_year:상반기", "2027-H1"),
        ("last_year:하반기", "2025-H2"),
        ("this_year:H1", "2026-H1"),
        ("this_year:H2", "2026-H2"),
        ("this_month", "2026-10"),
        ("next_month", "2026-11"),
        ("year_end", "2026-year_end"),
    ],
)
def test_explicit_anchor_resolves_supported_period_without_changing_span(value, expected):
    original = relation(value)
    resolved = resolve_fact_time(original, date(2026, 10, 7))
    assert resolved.time.value == expected
    assert resolved.time.span is original.time.span
    assert resolved.bindings == (resolved.time,)
    assert resolved.uncertainty == original.uncertainty
    assert original.time.value == value
    assert original.bindings[0].value == value


def test_next_month_rolls_december_into_following_year():
    assert resolve_fact_time(relation("next_month"), date(2026, 12, 31)).time.value == "2027-01"


def test_article_and_report_clocks_do_not_equate_different_relative_years():
    raw = relation("next_year")
    published = resolve_fact_time(raw, date(2025, 12, 31))
    generated = resolve_fact_time(raw, date(2026, 1, 1))
    assert published.time.value == "2026"
    assert generated.time.value == "2027"
    # The same actual period can use different relative expressions.
    assert (
        published.time.value
        == resolve_fact_time(relation("this_year"), date(2026, 1, 1)).time.value
    )


@pytest.mark.parametrize(
    "value",
    [
        "3분기",
        "상반기",
        "Q1",
        "2026-Q3",
        "next_year:13월",
        "next_year:5분기",
        "next_year:",
        "next_year:상반기이후",
        "next_month:2월",
        "year_end:12월",
    ],
)
def test_unknown_or_already_absolute_periods_are_not_invented(value):
    raw = relation(value)
    assert resolve_fact_time(raw, date(2026, 10, 7)) is raw


def test_absent_anchor_and_absent_time_preserve_identical_relation():
    raw = relation("next_year")
    assert resolve_fact_time(raw, None) is raw
    untimed = replace(raw, time=None, bindings=())
    assert resolve_fact_time(untimed, date(2026, 10, 7)) is untimed


@pytest.mark.parametrize(
    ("value", "anchor"),
    [("last_year", date.min), ("next_year", date.max), ("next_month", date.max)],
)
def test_date_bounds_do_not_produce_invalid_years(value, anchor):
    raw = relation(value)
    assert resolve_fact_time(raw, anchor) is raw


def test_parser_period_and_bindings_retain_literal_original_after_resolution():
    text = "삼성전자의 내년 3분기 매출은 20억원으로 전망된다."
    raw = next(item for item in analyze_sentence(text).relations if item.time is not None)
    assert raw.time.value == "next_year:3분기"
    resolved = resolve_fact_time(raw, date(2026, 10, 7))
    assert resolved.time.value == "2027-Q3"
    assert resolved.time.span.matches(text)
    assert resolved.time.span.text == "내년 3분기"
    assert resolved.time in resolved.bindings
    assert all(item.span.matches(text) for item in resolved.bindings)
