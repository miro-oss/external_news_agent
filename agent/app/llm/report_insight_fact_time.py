"""Resolve explicit relative fact times without changing their source spans.

The caller supplies a publication date for evidence and the report reference
date for generated prose. No anchor is inferred from a title, neighboring
sentence, the current clock, or an unqualified quarter.
"""

import re
from dataclasses import replace
from datetime import date

from app.llm.report_insight_fact_graph import Relation

_YEAR_OFFSETS = {"last_year": -1, "this_year": 0, "next_year": 1}
_QUARTER = re.compile(r"(?:([1-4])분기|[Qq]([1-4]))")
_MONTH = re.compile(r"([0-9]{1,2})월")
_HALVES = {"상반기": "H1", "하반기": "H2", "H1": "H1", "H2": "H2"}


def _resolved_value(value: str, anchor: date) -> str | None:
    relative, separator, suffix = value.partition(":")
    if relative in _YEAR_OFFSETS:
        year = anchor.year + _YEAR_OFFSETS[relative]
        if not 1 <= year <= 9999:
            return None
        if not separator:
            return f"{year:04d}"
        # Consume the entire suffix. Unknown modifiers must not disappear and
        # make a partially understood expression look like an exact year.
        if quarter := _QUARTER.fullmatch(suffix):
            return f"{year:04d}-Q{quarter[1] or quarter[2]}"
        if month := _MONTH.fullmatch(suffix):
            number = int(month[1])
            return f"{year:04d}-{number:02d}" if 1 <= number <= 12 else None
        if suffix in _HALVES:
            return f"{year:04d}-{_HALVES[suffix]}"
        return None
    if separator:
        return None
    if relative == "year_end":
        # "Year end" is an imprecise interval, not a claim about December 31.
        return f"{anchor.year:04d}-year_end"
    if relative in {"this_month", "next_month"}:
        month_index = anchor.year * 12 + anchor.month - 1 + (relative == "next_month")
        year, month = divmod(month_index, 12)
        if 1 <= year <= 9999:
            return f"{year:04d}-{month + 1:02d}"
    return None


def resolve_fact_time(relation: Relation, anchor: date | None) -> Relation:
    """Return an anchored copy, or the identical relation when unresolved.

    Only the normalized time value changes. Offsets, literal text, extraction
    rule and uncertainty remain intact; the original relation stays immutable.
    """
    if anchor is None or relation.time is None:
        return relation
    resolved = _resolved_value(relation.time.value, anchor)
    if resolved is None:
        return relation
    time = replace(relation.time, value=resolved)
    return replace(
        relation,
        time=time,
        bindings=tuple(
            time if binding == relation.time else binding for binding in relation.bindings
        ),
    )
