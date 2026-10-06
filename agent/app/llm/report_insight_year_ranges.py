"""Narrow proof that a two-year range restates one shared source time window."""

import re

from app.core.evidence import _tokens, factual_mismatches

_RANGE = re.compile(r"(?<!\d)(20\d{2})(?:\s*년)?\s*[~∼–-]\s*(20\d{2})\s*년")
_CLAUSE_BREAK = re.compile(r"[.!?;\n]|(?<=며)\s+")
_TOPIC = re.compile(
    r"^(?:에도|에는|에|은|는)?\s*(?:(?:내|동안|사이)(?:에|에는)?\s+)?"
    r"(?P<topic>[가-힣A-Za-z_]{2,})"
)


def supported_year_range_context(value: str, source: str) -> bool:
    """Prove one adjacent range using a shared source clause, then recheck it.

    Merely finding both years anywhere is insufficient. They must be explicitly
    coordinated before one predicate whose target also follows the output range.
    Expansion is comparison-only; all original numeric/entity/polarity/modality
    checks run against that local evidence and against the complete source.
    """
    ranges = list(_RANGE.finditer(value))
    if len(ranges) != 1:
        return False
    match = ranges[0]
    start, end = match.groups()
    if int(end) != int(start) + 1:
        return False  # Two endpoints cannot prove unmentioned intervening years.
    topic_match = _TOPIC.match(value[match.end() :])
    if topic_match is None:
        return False
    target = _tokens(topic_match["topic"])
    if not target:
        return False
    years = re.compile(
        rf"(?<!\d){start}\s*년\s*(?:은\s*물론|과|와|및)\s*"
        rf"{end}\s*년(?:에도|에는|에|은|는)?\s*"
    )
    expanded = value[: match.start()] + f"{start}년과 {end}년" + value[match.end() :]
    for clause in _CLAUSE_BREAK.split(source):
        if re.search(r"각각|순서대로|respectively", clause, re.IGNORECASE):
            continue  # Coordinated years can distribute different actors/targets.
        pair = years.search(clause)
        if pair is None or not target <= _tokens(clause[pair.end() :]):
            continue
        if not factual_mismatches(expanded, clause) and not factual_mismatches(expanded, source):
            return True
    return False
