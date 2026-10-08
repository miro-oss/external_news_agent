"""Reject explicit new event kinds, without treating every identifier as a fact.

This is a narrow absence check, not an entailment decision. When the cited source
mentions the same event or metric, the existing relation and state guards retain
responsibility for its subject, object and status. Questions and conditions do
not assert that an event occurred.
"""

import re

from app.llm.report_insight_fact_graph import analyze_sentence
from app.llm.report_insight_guard import _UNASSERTED_STATE_SUFFIX, factual_states

_CLAUSE_END = re.compile(r"[.!?。;；\n]")
_EVENT_PREDICATE = re.compile(
    r"(?:을|를|이|가|은|는)?\s*"
    r"(?:(?:체결|완료|확정|성사|시작|개시|추진|진행|계획|예정|검토|재개|중단|착수)\s*)?"
    r"(?:"
    r"(?:했|됐|되었|하였)(?:다|고|으며|음|다는|다고|던|으므로|으니)|"
    r"(?:돼|되어|되고|하고)\s*있(?:다|습니다|으며|고)|"
    r"중(?:이다|입니다|이며|이고)|"
    r"(?:한다|합니다|된다|됩니다|이다|이었다)|"
    r"(?:할|될|을)\s*(?:계획|예정|목표)(?:이다|이었다|이라고|로)"
    r")(?![가-힣])"
)
_METRIC_PREDICATE = re.compile(
    r"(?:늘었|줄었|증가했|감소했|상승했|하락했|기록했|달성했|돌파했|확대됐|축소됐)"
    r"(?:다|고|으며|다는|다고)(?![가-힣])"
)
_NON_ASSERTION = re.compile(r"\s*(?:가정|전제|경우|때)(?:[에서라면하]|\s|$)")
_UNSUPPORTED_REPORTED_CLAIM = re.compile(r"\s*주장(?:에\s*대한|의)\s*근거(?:가|는)?\s*없")
_CONDITION_PREFIX = re.compile(r"(?:다면|으면|라면|경우|때)(?:에는|에|엔)?(?=\s|[,，]|$)")
_WRITTEN_MULTIPLE = re.compile(
    r"(?<![가-힣])(?P<number>한|두|세|네|다섯|여섯|일곱|여덟|아홉|열)\s*배"
    r"(?=$|[^가-힣]|[은는이가을를로의])"
)
_MULTIPLES = dict(
    zip(
        ("한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉", "열"),
        range(1, 11),
        strict=True,
    )
)


def _multiples(value: str) -> set[str]:
    normalized = _WRITTEN_MULTIPLE.sub(lambda m: f"{_MULTIPLES[m['number']]}배", value)
    return {
        mention.value
        for mention in analyze_sentence(normalized).mentions
        if mention.kind == "quantity" and mention.unit == "multiple"
    }


def _event_or_metric_mentions(value: str):
    mentions = analyze_sentence(value).mentions
    metrics = [mention for mention in mentions if mention.kind == "metric"]
    return [
        mention
        for mention in mentions
        if mention.kind == "metric"
        or (
            mention.kind == "event"
            # 생산라인 is a facility noun, not a claim of ongoing production.
            and not value[mention.span.end :].startswith("라인")
            and not any(
                metric.span.start <= mention.span.start < metric.span.end for metric in metrics
            )
        )
    ]


def unsupported_fact_assertions(value: str, source: str) -> list[str]:
    """Find asserted event/metric kinds absent from this field's cited raw source."""
    source_kinds = {(mention.kind, mention.value) for mention in _event_or_metric_mentions(source)}
    # Reuse existing aliases such as English plural agreements and leases;
    # lexical extraction alone must not contradict a recognized source state.
    if "contract" in factual_states(source):
        source_kinds.add(("event", "contract"))
    errors = []
    mentions = _event_or_metric_mentions(value)
    for index, mention in enumerate(mentions):
        if (mention.kind, mention.value) in source_kinds:
            continue
        prefix = _CLAUSE_END.split(value[: mention.span.start])[-1]
        if _CONDITION_PREFIX.search(prefix):
            continue
        end = mentions[index + 1].span.start if index + 1 < len(mentions) else len(value)
        tail = _CLAUSE_END.split(value[mention.span.end : end], maxsplit=1)[0]
        predicate = (
            _EVENT_PREDICATE.match(tail)
            if mention.kind == "event"
            else _METRIC_PREDICATE.search(tail)
        )
        if predicate is None or _NON_ASSERTION.match(tail[predicate.end() :]):
            continue
        # Preserve the ending consumed by the predicate matcher so the shared
        # state guard can recognize an immediately attached denial/abstention.
        suffix = tail[predicate.end() :]
        if predicate[0].endswith(("다는", "다고")):
            if _UNASSERTED_STATE_SUFFIX.match(predicate[0][-1] + suffix) or (
                _UNSUPPORTED_REPORTED_CLAIM.match(suffix)
            ):
                continue
        label = "사건 단정" if mention.kind == "event" else "수치 지표 단정"
        errors.append(f"연결 원문에 없는 {label}: {mention.span.text}")
    if _WRITTEN_MULTIPLE.search(value):
        missing = _multiples(value) - _multiples(source)
        if missing:
            errors.append("근거에서 확인되지 않는 숫자 배수: " + ", ".join(sorted(missing)))
    return list(dict.fromkeys(errors))
