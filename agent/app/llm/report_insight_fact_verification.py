"""Compare explicit relations, never a bag of words or a full entailment verdict.

Each source is analyzed separately: a subject in one sentence cannot acquire a
number, time, or completed event from another. Unknown is deliberately distinct
from supported. Existing grounding checks still run for unparsed prose.
"""

import re
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import date
from decimal import Decimal
from typing import Literal

from app.llm.report_insight_fact_graph import Analysis, Mention, Relation, analyze_sentence
from app.llm.report_insight_fact_index import FactIndex
from app.llm.report_insight_fact_time import resolve_fact_time

_REVISION = re.compile(
    r"정정|수정됐|수정했|오보|오류|철회|번복|바로잡|잘못|"
    r"\b(?:corrected|correction|revised|retracted|erratum|erroneous)\b",
    re.I,
)


@dataclass(frozen=True, slots=True)
class EvidenceFact:
    source_sha256: str
    relation: Relation
    evidence_id: str | None = None
    fact_id: str | None = None
    claim_ids: tuple[str, ...] = ()
    claim_types: tuple[str, ...] = ()
    attributed_to: tuple[str, ...] = ()


@dataclass(frozen=True, slots=True)
class FactCheck:
    outcome: Literal["supported", "contradicted", "unknown"]
    reason: str
    candidate: Relation | None
    evidence: tuple[EvidenceFact, ...] = ()


def _value(mention: Mention | None) -> str | None:
    return mention.value if mention is not None else None


def _identity(relation: Relation) -> tuple:
    """Omitted/extra targets or times are not interchangeable identities."""
    return (
        tuple(sorted(subject.value for subject in relation.subjects)),
        "single" if len(relation.subjects) == 1 else relation.subject_mode,
        relation.predicate,
        _value(relation.target),
        _value(relation.time),
        relation.quantity.unit if relation.quantity else None,
        relation.quantity.role if relation.quantity else None,
    )


def _interval(quantity: Mention) -> tuple[Decimal, Decimal] | None:
    number = Decimal(quantity.value)
    if quantity.qualifier == "exact":
        return number, number
    if quantity.qualifier == "min":
        return number, Decimal("Infinity")
    if quantity.qualifier == "max":
        return Decimal("-Infinity"), number
    if quantity.qualifier == "range" and quantity.upper is not None:
        return number, Decimal(quantity.upper)
    # "About" has no universally valid percentage tolerance.
    return None


def _quantity_check(candidate: Mention | None, source: Mention | None) -> tuple[str, str]:
    if candidate is None and source is None:
        return "supported", "same_quantity"
    if candidate is None or source is None:
        return "unknown", "quantity_missing"
    if (candidate.value, candidate.qualifier, candidate.upper) == (
        source.value,
        source.qualifier,
        source.upper,
    ):
        return "supported", "same_quantity"
    left, right = _interval(candidate), _interval(source)
    if left is None or right is None:
        return "unknown", "quantity_precision_unresolved"
    if right[1] < left[0] or left[1] < right[0]:
        return "contradicted", "quantity_conflict"
    if left[0] <= right[0] and right[1] <= left[1]:
        return "supported", "quantity_entailed"
    return "unknown", "quantity_precision_unresolved"


def _state_check(candidate: Relation, source: Relation) -> tuple[str, str]:
    if candidate.state == source.state:
        return "supported", "same_state"
    if candidate.state == "completed" and source.state in {"planned", "forecast", "negated"}:
        return "contradicted", "event_state_conflict"
    if candidate.state == "ongoing" and source.state in {"planned", "forecast", "negated"}:
        return "contradicted", "event_state_conflict"
    if candidate.state == "negated" and source.state in {"completed", "ongoing"}:
        return "contradicted", "event_state_conflict"
    if candidate.state == "asserted" and source.state in {"forecast", "planned"}:
        return "unknown", "event_state_strengthening"
    return "unknown", "event_state_unresolved"


def _pair_check(candidate: Relation, evidence: EvidenceFact) -> tuple[str, str]:
    source = evidence.relation
    if _value(candidate.attributed_to) != _value(source.attributed_to):
        return "unknown", "attribution_unresolved"
    if (
        candidate.quantity is not None
        and source.quantity is not None
        and "negated" in {candidate.state, source.state}
    ):
        # "Not 20" does not assert a measured value of 20, nor contradict 30.
        if (candidate.quantity.value, candidate.quantity.qualifier, candidate.quantity.upper) != (
            source.quantity.value,
            source.quantity.qualifier,
            source.quantity.upper,
        ):
            return "unknown", "negated_quantity_unresolved"
        if {candidate.state, source.state} == {"asserted", "negated"}:
            return "contradicted", "event_state_conflict"
    quantity = _quantity_check(candidate.quantity, source.quantity)
    state = _state_check(candidate, source)
    for outcome in ("contradicted", "unknown"):
        for result in (quantity, state):
            if result[0] == outcome:
                return result
    if evidence.claim_types and "FACT" not in evidence.claim_types:
        if "OPINION" in evidence.claim_types:
            if candidate.attributed_to is None:
                return "unknown", "source_opinion_attribution_unresolved"
            if candidate.attributed_to.value.casefold() not in {
                name.casefold() for name in evidence.attributed_to
            }:
                return "unknown", "source_opinion_attribution_unresolved"
        elif "FORECAST" in evidence.claim_types and candidate.state not in {"forecast", "planned"}:
            return "unknown", "source_forecast_modality_unresolved"
    return "supported", "explicit_relation_supported"


def _possible_alternative(candidate: Relation, other: Relation) -> bool:
    """An unresolved reference can revise this event but cannot prove a new one."""
    if not other.uncertainty or other.predicate != candidate.predicate:
        return False
    candidate_owners = {actor.value for actor in candidate.subjects}
    other_owners = {actor.value for actor in other.subjects}
    if (
        other_owners
        and not candidate_owners.intersection(other_owners)
        and "unresolved_coreference" not in other.uncertainty
    ):
        return False
    if (
        other.target is not None
        and candidate.target is not None
        and other.target.value != candidate.target.value
        and "unresolved_coreference" not in other.uncertainty
    ):
        return False
    if (
        other.time is not None
        and candidate.time is not None
        and other.time.value != candidate.time.value
    ):
        return False
    if (
        other.quantity is not None
        and candidate.quantity is not None
        and (other.quantity.unit, other.quantity.role)
        != (candidate.quantity.unit, candidate.quantity.role)
    ):
        return False
    return True


def _unresolved_revision(candidate: Relation, analysis: Analysis) -> bool:
    """A correction with an omitted subject is uncertainty, never a new fact.

    This includes raw source records which contain no extracted relation at all.
    Ordinary unparsed context does not invalidate otherwise explicit bindings.
    """
    for scope in analysis.unresolved:
        if not _REVISION.search(scope.span.text):
            continue
        mentions = [
            item
            for item in analysis.mentions
            if scope.span.start <= item.span.start and item.span.end <= scope.span.end
        ]
        actors = {item.value for item in mentions if item.kind == "actor"}
        owners = {item.value for item in candidate.subjects}
        if actors and not actors.intersection(owners):
            continue
        quantities = [item for item in mentions if item.kind == "quantity"]
        if candidate.quantity is not None and quantities:
            if any(item.unit == candidate.quantity.unit for item in quantities):
                return True
        elif any(
            item.value == candidate.predicate
            for item in mentions
            if item.kind in {"metric", "event"}
        ):
            return True
        elif re.search(
            r"그|해당|이\s*(?:수치|내용|발표|계획|사건)|\b(?:it|this|that)\b", scope.span.text
        ):
            return True
    return False


def _compare(candidate: Relation, evidence: tuple[EvidenceFact, ...]) -> FactCheck:
    if candidate.uncertainty:
        return FactCheck("unknown", "candidate_binding_uncertain", candidate)
    if not candidate.subjects:
        return FactCheck("unknown", "candidate_subject_unresolved", candidate)
    if candidate.time is not None and not candidate.time.value[:4].isdigit():
        # "Next year" belongs to the original publication's clock. The index
        # preserves publishedAt, but this pure string comparator has no report
        # reference date and must not equate two unrelated relative dates.
        return FactCheck("unknown", "relative_time_unresolved", candidate)
    if candidate.state in {"conditional", "unknown", "reported"}:
        return FactCheck("unknown", "candidate_state_unresolved", candidate)
    identity = _identity(candidate)
    matches = tuple(item for item in evidence if _identity(item.relation) == identity)
    if not matches:
        return FactCheck("unknown", "no_matching_source_relation", candidate)
    alternatives = tuple(
        item for item in evidence if _possible_alternative(candidate, item.relation)
    )
    if alternatives:
        return FactCheck("unknown", "source_binding_uncertain", candidate, matches + alternatives)
    # Uncertain or mutually conflicting descriptions of this same event cannot
    # be settled by selecting the convenient sentence or the last occurrence.
    if any(item.relation.uncertainty for item in matches):
        return FactCheck("unknown", "source_binding_uncertain", candidate, matches)
    for index, left in enumerate(matches):
        for right in matches[index + 1 :]:
            if (
                _quantity_check(left.relation.quantity, right.relation.quantity)[0]
                == ("contradicted")
                or _state_check(left.relation, right.relation)[0] == "contradicted"
            ):
                return FactCheck("unknown", "conflicting_source_relations", candidate, matches)
    checks = [_pair_check(candidate, item) for item in matches]
    if all(outcome == "supported" for outcome, _ in checks):
        return FactCheck("supported", "explicit_relation_supported", candidate, matches)
    if all(outcome == "contradicted" for outcome, _ in checks):
        reasons = tuple(dict.fromkeys(reason for _, reason in checks))
        return FactCheck("contradicted", reasons[0], candidate, matches)
    if len(set(checks)) == 1:
        return FactCheck("unknown", checks[0][1], candidate, matches)
    return FactCheck("unknown", "conflicting_source_relations", candidate, matches)


def compare_source_facts(value: str, sources: Sequence[str]) -> tuple[FactCheck, ...]:
    """Return per-relation outcomes, including an explicit unparsed remainder.

    Source strings are original sentence records, not concatenated summaries.
    One supported relation is not a supported verdict for an entire sentence.
    """
    if isinstance(sources, str):
        raise TypeError("sources must be a sequence of separate original sentences")
    analyses = tuple(analyze_sentence(source) for source in dict.fromkeys(sources))
    evidence = tuple(
        EvidenceFact(analysis.source_sha256, relation)
        for analysis in analyses
        for relation in analysis.relations
    )
    generated = analyze_sentence(value)
    return _checks(generated, analyses, evidence)


def _checks(
    generated: Analysis,
    analyses: tuple[Analysis, ...],
    evidence: tuple[EvidenceFact, ...],
    *,
    reference_date: date | None = None,
) -> tuple[FactCheck, ...]:
    checks = tuple(
        FactCheck("unknown", "unresolved_revision_context", candidate)
        if any(_unresolved_revision(candidate, analysis) for analysis in (*analyses, generated))
        else _compare(candidate, evidence)
        for relation in generated.relations
        for candidate in (resolve_fact_time(relation, reference_date),)
    )
    if generated.unresolved or not checks:
        checks += (FactCheck("unknown", "unparsed_candidate_text", None),)
    return checks


def compare_indexed_facts(
    value: str, index: FactIndex, *, reference_date: date | None
) -> tuple[FactCheck, ...]:
    """Use the exact prompt index, including source clocks and claim provenance."""
    fact_ids = {(fact.evidence_id, fact.relation): fact.fact_id for fact in index.facts}
    evidence = tuple(
        EvidenceFact(
            row.source_sha256,
            resolve_fact_time(relation, row.published_at),
            row.evidence_id,
            fact_ids.get((row.evidence_id, relation)),
            tuple(origin.claim_id for origin in row.claims),
            tuple(dict.fromkeys(origin.claim_type for origin in row.claims)),
            tuple(
                dict.fromkeys(origin.attributed_to for origin in row.claims if origin.attributed_to)
            ),
        )
        for row in index.evidence
        for relation in row.analysis.relations
    )
    return _checks(
        analyze_sentence(value),
        tuple(row.analysis for row in index.evidence),
        evidence,
        reference_date=reference_date,
    )


def _mismatch_messages(checks: tuple[FactCheck, ...]) -> list[str]:
    messages = {
        "quantity_conflict": "근거와 연결이 다른 숫자: 동일 주체·사건·대상·시점의 수치 충돌",
        "event_state_conflict": (
            "근거의 주체·사건 연결과 다릅니다: 같은 사건의 긍정·부정 또는 계획·완료 상태 충돌"
        ),
    }
    return list(
        dict.fromkeys(
            messages[check.reason]
            for check in checks
            if check.outcome == "contradicted" and check.reason in messages
        )
    )


def fact_graph_mismatches(value: str, sources: Sequence[str]) -> list[str]:
    """Only proven binding conflicts add hard errors; unknown never grants a bypass."""
    return _mismatch_messages(compare_source_facts(value, sources))


def fact_index_mismatches(
    value: str, index: FactIndex, *, reference_date: date | None
) -> list[str]:
    return _mismatch_messages(compare_indexed_facts(value, index, reference_date=reference_date))
