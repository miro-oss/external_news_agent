"""Distinguish source-backed calendar comparisons from report-baseline claims."""

import re

# A calendar period is not a rolling duration: '일주일 전' alone cannot prove
# a previous-calendar-week comparison. These aliases describe the same reference,
# not an inferred amount, subject, direction, or event; other fact guards still run.
# Keep the existing guard's weekly scope; other time/amount checks remain with
# the fact validators. Do not add new period rejection policies without labels.
_WEEKS = {
    offset: re.compile(
        r"(?<![가-힣A-Za-z0-9])" + pattern
        + r"\s*(?:대비|보다|에\s*비해|와\s*비교|과\s*비교)"
    )
    for offset, pattern in {
        1: r"(?:전주|지난\s*주|직전\s*주)",
        2: r"(?:전전\s*주|지지난\s*주)",
    }.items()
}
_REPORT = re.compile(r"(?:지난|이전|직전)\s*보고서")
_NEWNESS = {
    "first": re.compile(r"처음으로"),
    "change": re.compile(r"신규\s*변화"),
    "entry": re.compile(r"신규\s*진입"),
    "identified": re.compile(r"새롭게\s*확인"),
    "highlighted": re.compile(r"새롭게\s*부각"),
}
_OPPOSITES = {"증가": "감소", "감소": "증가", "상승": "하락", "하락": "상승"}
_DIRECTION = re.compile("|".join(_OPPOSITES))


def comparison_direction_conflict(value: str, raw_source: str) -> bool:
    """Reject opposite directions only with otherwise identical bound context.

    A different subject/quantity/qualifier is not an exact contradiction here.
    This deliberately does not infer direction from arbitrary wording or combine
    directions from separate source sentences.
    """
    def statements(text):
        result = []
        for part in re.split(r"(?<=[.!?])\s+|\n+", text):
            if not any(pattern.search(part) for pattern in _WEEKS.values()):
                continue
            for offset, pattern in _WEEKS.items():
                part = pattern.sub(f"<previous_week_{offset}>", part)
            result.append(re.sub(r"\s+", "", part).strip(" .!?"))
        return result

    originals = set(statements(raw_source))
    for candidate in statements(value):
        if candidate in originals:
            continue
        matches = list(_DIRECTION.finditer(candidate))
        if len(matches) == 1:
            match = matches[0]
            opposite = candidate[:match.start()] + _OPPOSITES[match[0]] + candidate[match.end():]
            if opposite in originals:
                return True
    return False


def unsupported_comparison(value: str, raw_source: str, *, research_first: bool = False) -> bool:
    """Require each comparison's own reference in raw evidence, never a summary.

    This checks references only. It does not declare the entire statement supported.
    In particular, one report/newness token cannot license an unrelated period.
    """
    if any(pattern.search(value) and not pattern.search(raw_source) for pattern in _WEEKS.values()):
        return True
    if _REPORT.search(value) and not _REPORT.search(raw_source):
        return True
    return any(
        pattern.search(value)
        and not pattern.search(raw_source)
        and not (kind == "first" and research_first)
        for kind, pattern in _NEWNESS.items()
    )
