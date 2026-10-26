"""Narrow bilingual research assertions, proved within a cited source clause."""

import re

from app.core.evidence import _companies
from app.llm.report_insight_fact_graph import _clauses, _state

_PUBLISHED_PAPER = re.compile(
    r"\bpublished\s+(?:(?:a|an|the|their|our|its)\s+)?"
    r"(?:(?:technical|research|scientific)\s+)?papers?\b|"
    r"\bpapers?\s+(?:was|were|has\s+been|have\s+been)\s+published\b",
    re.I,
)
_PAPER_OBJECT = re.compile(
    r"^(?:(?:연구진|연구팀|저자들?|원문)(?:은|는|이|가)\s+)?논문(?:을|를)\s*$"
)
_WITHDRAWN = re.compile(r"\b(?:retracted|withdrawn|unpublished)\b|철회|게재\s*취소", re.I)
# The event alone is insufficient: the explored technical object must also
# match in that same clause. Unknown translations do not establish support.
_TARGETS = tuple(
    re.compile(pattern, re.I)
    for pattern in (
        r"백사이드|후면|\bbackside\b",
        r"클록|클럭|\bclocks?\b",
        r"메쉬|메시|\bmesh(?:es)?\b",
        r"메모리|\bmemor(?:y|ies)\b",
        r"전력|\bpower\b",
        r"회로|\bcircuits?\b",
        r"네트워크|\bnetworks?\b",
        r"트랜지스터|\btransistors?\b",
        r"냉각|\bcooling\b",
    )
)
_TARGET_WORD = "(?:" + "|".join(pattern.pattern for pattern in _TARGETS) + ")"
_TARGET_PHRASE = rf"(?P<target>(?:{_TARGET_WORD}\s*){{2,}})"
_DESIGN_EXPLORATION = re.compile(
    _TARGET_PHRASE + r"의?\s*설계\s*공간(?:을|에\s*대해)?\s*처음으로\s*탐색", re.I
)
_FIRST_EXPLORATION = re.compile(
    r"\bfirst\s+(?:design[ -]space\s+exploration|"
    r"exploration\s+of\s+(?:the\s+)?design\s+space)\s+of\s+(?:the\s+)?"
    + _TARGET_PHRASE
    + r'(?=\s*(?:$|[,.!?。;；”"]))',
    re.I,
)


def _asserted_source(clause: str) -> bool:
    return _state(clause)[0] in {"asserted", "reported", "completed"} and not _WITHDRAWN.search(
        clause
    )


def _same_named_owner(value: str, source: str) -> bool:
    owners = _companies(value)
    return not owners or owners == _companies(source)


def supported_paper_publication(value: str, position: int, source: str) -> bool:
    """Recognize positive published-paper evidence for this disclosure event.

    Only a generic paper object is supported. Named or technically qualified
    paper objects need a richer binding than this alias proves and stay unknown.
    This only supplies an event alias to the absence check. All original
    company, quantity, source-binding and state validators still run.
    """
    scope = next((part for part in _clauses(value) if part.start <= position < part.end), None)
    if scope is None or not _PAPER_OBJECT.search(scope.text[: position - scope.start]):
        return False
    clause = scope.text
    return any(
        _PUBLISHED_PAPER.search(part.text)
        and _asserted_source(part.text)
        and _same_named_owner(clause, part.text)
        for part in _clauses(source)
    )


def supported_research_novelty(value: str, source: str) -> bool:
    """Prove every '처음으로' claim as the same cited design-space exploration.

    A 'first' elsewhere in an article, another event, or an unparsed target
    cannot substitute for this local event/object pair.
    """
    generated = [part.text for part in _clauses(value) if "처음으로" in part.text]
    if not generated:
        return False
    for clause in generated:
        match = _DESIGN_EXPLORATION.search(clause)
        if match is None or clause.count("처음으로") != 1:
            return False
        target = {
            index for index, pattern in enumerate(_TARGETS) if pattern.search(match["target"])
        }
        if not any(
            (original := _FIRST_EXPLORATION.search(part.text))
            and _asserted_source(part.text)
            and _same_named_owner(clause, part.text)
            and target
            == {
                index
                for index, pattern in enumerate(_TARGETS)
                if pattern.search(original["target"])
            }
            for part in _clauses(source)
        ):
            return False
    return True
