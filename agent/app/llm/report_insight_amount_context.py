"""Prove a labeled monetary annotation and its same-context companion separately."""

import re

from app.core.evidence import (
    _canonical_numeric_text,
    _clauses,
    _companies,
    _contains_alias,
    _contextual_number_mismatches,
    _currency_amounts,
    _date_number_anchors,
    _date_terms,
    _normalize,
    _quantity_values,
)
from app.llm.report_insight_fact_graph import analyze_sentence

_ANNOTATION = re.compile(r"(?P<label>[A-Za-z가-힣][A-Za-z0-9가-힣_-]{1,24})\s*\((?P<body>[^()]*)\)")
# Segment/accounting-scope parsing is outside this small exception. An explicit
# sub-business or standalone/whole-group qualification keeps the original guard
# rather than being merged under the broad metric name 'operating profit'.
_EXPLICIT_BUSINESS_SCOPE = re.compile(
    r"부문|사업|전사|별도|\b(?:segment|division|standalone)\b", re.I
)
_QUOTED_MEASURE = re.compile(r'"[^"\n]+"|\'[^\'\n]+\'|“[^”\n]+”|‘[^’\n]+’')


def _metrics(value):
    return {
        mention.value for mention in analyze_sentence(value).mentions if mention.kind == "metric"
    }


def _plain_metric_relation(value, metrics):
    relations = [
        relation
        for relation in analyze_sentence(value).relations
        if relation.predicate in metrics and relation.quantity is not None
    ]
    if len(relations) != 1:
        return None
    relation = relations[0]
    # Keep known product/site/segment qualifiers, rather than treating equal
    # metric names as equal objects. Only the plain company-level measure and
    # its ordinary consolidated form are handled by this small exception.
    if relation.target is None or relation.target.value not in {
        relation.predicate,
        relation.predicate + ":연결",
    }:
        return None
    if "unresolved_target_qualification" in relation.uncertainty:
        # A reporting preamble may precede a literally quoted bare measure.
        # Only that observed boundary can disambiguate an unparsed qualifier;
        # an unknown product/site modifier never becomes the generic metric.
        target, quantity = relation.target.span, relation.quantity.span
        plain = re.compile(
            rf"\s*(?:(?:분기|연간|연결)\s*)?{re.escape(target.text)}"
            rf"\s*{re.escape(quantity.text)}\s*"
        )
        if not any(
            quote.start() < target.start < target.end <= quantity.start < quantity.end < quote.end()
            and plain.fullmatch(quote[0][1:-1])
            for quote in _QUOTED_MEASURE.finditer(value)
        ):
            return None
    return relation


def _year_context(value):
    # An omitted year and 'this year' share the report's default clock. Explicit
    # previous/future years cannot be borrowed across otherwise equal quarters.
    return frozenset(
        {term for term in _date_number_anchors(value) if term.endswith("년")}
        | (_date_terms(value) & {"내년", "지난해"})
    )


def supported_labeled_amount_context(value: str, source: str) -> str | None:
    """Support two amounts only through complete, compatible source clauses.

    A parenthetical label identifies one amount (e.g. a consensus figure), while
    a metric names the second. Each needs its own one-amount source clause, with
    the same sole company, metric and explicit time. This is not a value union:
    swapped labels, companies, periods and metrics do not acquire support from
    another clause. Return only the exact legacy context diagnostic this proves;
    unrelated numeric/relational errors must survive. Ambiguous forms keep failing.
    """
    value = _canonical_numeric_text(_normalize(value))
    source = _canonical_numeric_text(_normalize(source))
    amounts = _currency_amounts(value)
    metrics = _metrics(value)
    dates = _date_number_anchors(value) | _date_terms(value)
    owners = _companies(value)
    if (
        len(_clauses(value)) != 1
        or len(amounts) != 2
        or len({amount for _, _, amount in amounts}) != 2
        or _quantity_values(value) != {amount for _, _, amount in amounts}
        or len(metrics) != 1
        or not dates
        or len(owners) > 1
        or _EXPLICIT_BUSINESS_SCOPE.search(value)
    ):
        return None
    annotations = []
    for match in _ANNOTATION.finditer(value):
        local = _currency_amounts(match["body"])
        if len(local) != 1:
            continue
        start, end, amount = local[0]
        rest = match["body"][:start] + match["body"][end:]
        if re.fullmatch(r"\s*(?:약|대략)?\s*", rest):
            annotations.append((match, amount))
    if len(annotations) != 1:
        return None
    annotation, annotated_amount = annotations[0]
    other = next(item for item in amounts if item[2] != annotated_amount)
    # The companion must follow its explicit metric, not an inferred omitted
    # predicate or a second unexplained parenthetical number.
    if not any(
        mention.kind == "metric" and annotation.end() <= mention.span.start < other[0]
        for mention in analyze_sentence(value).mentions
    ):
        return None
    unannotated = value[: annotation.start()] + annotation["label"] + value[annotation.end() :]
    companion = _plain_metric_relation(unannotated, metrics)
    if companion is None:
        return None
    candidates = {amount: set() for _, _, amount in amounts}
    for clause in _clauses(source):
        local = {amount for _, _, amount in _currency_amounts(clause)}
        local_owners = _companies(clause)
        if (
            len(local) != 1
            or not local <= candidates.keys()
            or _metrics(clause) != metrics
            or not dates <= (_date_number_anchors(clause) | _date_terms(clause))
            or len(local_owners) != 1
            or not owners <= local_owners
            or _EXPLICIT_BUSINESS_SCOPE.search(clause)
        ):
            continue
        amount = next(iter(local))
        relation = _plain_metric_relation(clause, metrics)
        if (
            relation is None
            or len(relation.subjects) != 1
            or _companies(relation.subjects[0].value) != local_owners
            or set(relation.uncertainty) - {"unresolved_target_qualification"}
            or relation.quantity.qualifier not in {"exact", "approx"}
            or relation.quantity.upper is not None
        ):
            continue
        # The monetary annotation is still a source-labeled forecast. The
        # companion cannot promote that forecast into an achieved actual value.
        tentative = {"conditional", "forecast", "planned"}
        if amount != annotated_amount and not (
            companion.state == relation.state
            or companion.state in tentative
            and relation.state in tentative
        ):
            continue
        if amount == annotated_amount and (
            relation.state not in {"asserted", "forecast", "reported"}
            or relation.quantity.qualifier == "approx"
            and not re.match(r"\s*(?:약|대략)", annotation["body"])
        ):
            continue
        if (
            amount != annotated_amount
            and relation.quantity.qualifier == "approx"
            and companion.quantity.qualifier != "approx"
        ):
            continue
        if amount == annotated_amount and not _contains_alias(clause, annotation["label"]):
            continue
        candidates[amount].add((next(iter(local_owners)), _year_context(clause)))
    left, right = candidates.values()
    if len(left & right) != 1:
        return None
    mismatches = _contextual_number_mismatches(value, source)
    return mismatches[0] if len(mismatches) == 1 else None
