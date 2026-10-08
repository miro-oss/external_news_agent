"""Private, source-bound repair evidence; never a public error or log payload.

These details explain an existing rejection. They do not decide acceptance, infer
replacement facts, or turn an unparsed relation into a contradiction.
"""

import re
from dataclasses import dataclass, field
from datetime import date
from typing import Literal

from app.llm.report_insight_fact_index import FactIndex
from app.llm.report_insight_fact_verification import compare_indexed_facts

DiagnosticCategory = Literal[
    "FORMAT", "REFERENCE", "CONTRADICTION", "INSUFFICIENT_EVIDENCE", "EXPRESSION_POLICY"
]
_MAX_SPAN = 240
_MAX_DETAILS = 4
_MAX_EVIDENCE = 4
_CLAIM_ID = re.compile(r"[1-9][0-9]{0,18}:(?:0|[1-9][0-9]{0,18})")
_LITERAL_PREFIXES = {
    "unsupported_number": ("근거에서 확인되지 않는 숫자: ", "근거에서 확인되지 않는 숫자 배수: "),
    "currency_amount": ("근거와 일치하지 않는 통화·금액 숫자: ",),
    "date": ("근거와 일치하지 않는 날짜 표현: ",),
    "company": ("근거에서 확인되지 않는 기업명: ",),
    "source_binding": (
        "근거에서 확인되지 않는 식별자: ",
        "연결 원문에 없는 수치 지표 단정: ",
    ),
    "event_state": ("연결 원문에 없는 사건 단정: ",),
}


def diagnostic_category(rule: str) -> DiagnosticCategory:
    """Classify owned rules conservatively; a missing fact is not its negation."""
    if rule.startswith("schema_") or rule in {
        "report_output_shape",
        "report_output_parse",
        "report_output_unlocated",
        "report_assessment_truncated_prefix",
        "string_too_long",
        "string_too_short",
        "too_long",
        "too_short",
        "report_fact_rendered_length",
        "report_fact_source_quotes_required",
    }:
        return "FORMAT"
    if rule in {
        "report_evidence_reference_invalid",
        "report_synthesis_reference_gap",
        "report_fact_slot_unknown",
        "report_fact_slot_scope",
    }:
        return "REFERENCE"
    if rule in {
        "internal_reference_in_prose",
        "report_fact_slot_position",
        "report_fact_interpretation_required",
        "report_fact_slot_without_prose",
        "report_synthesis_empty",
        "report_synthesis_metadata_only",
        "report_synthesis_placeholder",
        "report_synthesis_information_gap",
        "report_synthesis_invalid",
        "report_expression_policy",
        "report_falsification_missing_observation",
        "report_falsification_direction",
    }:
        return "EXPRESSION_POLICY"
    if rule == "report_fact_contradiction":
        return "CONTRADICTION"
    # Numeric/state/binding labels can also mean "not found". Only a matching
    # explicit FactCheck below promotes those cases to CONTRADICTION.
    return "INSUFFICIENT_EVIDENCE"


def source_quote_error_kind(rule: str) -> str:
    """Classify an existing source-selection/layout rejection, without facts inference."""
    return {
        "REFERENCE": "report_evidence_reference_invalid",
        "EXPRESSION_POLICY": "report_expression_policy",
    }.get(diagnostic_category(rule), "report_output_shape")


@dataclass(frozen=True, slots=True)
class RepairTextSpan:
    start: int
    end: int
    text: str = field(repr=False)

    @classmethod
    def checked(cls, value: str, start: int, end: int):
        if type(value) is not str or not 0 <= start < end <= len(value) or end - start > _MAX_SPAN:
            return None
        return cls(start, end, value[start:end])

    def payload(self):
        return {"start": self.start, "end": self.end, "text": self.text}


@dataclass(frozen=True, slots=True)
class RepairEvidence:
    claim_ids: tuple[str, ...]
    evidence_id: str | None
    sentence_index: int | None
    coordinate_space: Literal["source_sentence", "linked_evidence_text"]
    span: RepairTextSpan | None = field(repr=False)
    expected_value: str | None = field(default=None, repr=False)
    relation: Literal["contradicts_generated", "selected_context"] = "selected_context"

    def payload(self):
        return {
            "claimIds": list(self.claim_ids[:8]),
            "evidenceId": self.evidence_id,
            "sentenceIndex": self.sentence_index,
            "coordinateSpace": self.coordinate_space,
            "sourceSpan": self.span.payload() if self.span else None,
            "expectedValue": self.expected_value,
            "relation": self.relation,
        }


@dataclass(frozen=True, slots=True)
class RepairDetail:
    category: DiagnosticCategory
    generated_span: RepairTextSpan | None = field(repr=False)
    expected_evidence: tuple[RepairEvidence, ...] = field(repr=False)

    def payload(self):
        return {
            "category": self.category,
            "generatedCoordinateSpace": "validated_field",
            "generatedSpan": self.generated_span.payload() if self.generated_span else None,
            "expectedEvidence": [row.payload() for row in self.expected_evidence[:_MAX_EVIDENCE]],
            "expectedEvidenceTruncated": len(self.expected_evidence) > _MAX_EVIDENCE,
        }


def fact_error_kind(rule: str, details: tuple[RepairDetail, ...] = ()) -> str:
    """Promote only wholly established contradictions; mixed cases stay unknown.

    A caller with several failures for one rule may group details by category
    first so a contradiction and an unsupported fact keep distinct error kinds.
    """
    if details and all(
        type(item) is RepairDetail and item.category == "CONTRADICTION" for item in details
    ):
        return "report_fact_contradiction"
    if diagnostic_category(rule) == "EXPRESSION_POLICY":
        return "report_expression_policy"
    return "report_evidence_insufficient"


def _context(refs, evidence, index):
    """Source context is a search scope, never an asserted replacement value."""
    selected = set(refs)
    if index is not None:
        return tuple(
            RepairEvidence(
                tuple(origin.claim_id for origin in row.claims if origin.claim_id in selected),
                row.evidence_id,
                row.sentence_index,
                "source_sentence",
                RepairTextSpan.checked(row.text, 0, min(len(row.text), _MAX_SPAN)),
            )
            for row in index.evidence
            if any(origin.claim_id in selected for origin in row.claims)
        )[: _MAX_EVIDENCE + 1]
    return tuple(
        RepairEvidence(
            (ref,),
            None,
            None,
            "linked_evidence_text",
            RepairTextSpan.checked(evidence[ref], 0, min(len(evidence[ref]), _MAX_SPAN)),
        )
        for ref in refs
        if ref in evidence and type(evidence[ref]) is str
    )[: _MAX_EVIDENCE + 1]


def _conflicts(value, rules, refs, index, reference_date):
    by_rule = {rule: [] for rule in rules}
    if index is None:
        return by_rule
    selected = set(refs)
    # The supplied index may be broader than this particular field. Reject
    # foreign provenance instead of borrowing a sibling's source.
    if any(any(origin.claim_id not in selected for origin in row.claims) for row in index.evidence):
        return by_rule
    sources = {row.evidence_id: row for row in index.evidence}
    for check in compare_indexed_facts(value, index, reference_date=reference_date):
        if check.outcome != "contradicted" or check.candidate is None:
            continue
        quantity = check.reason == "quantity_conflict"
        if not quantity and check.reason != "event_state_conflict":
            continue
        relevant = (
            {"unsupported_number", "currency_amount", "numeric_context", "source_binding"}
            if quantity
            else {"event_state", "source_binding", "polarity"}
        ) & set(rules)
        candidate_span = (
            check.candidate.quantity.span
            if quantity and check.candidate.quantity is not None
            else check.candidate.span
        )
        generated = RepairTextSpan.checked(value, candidate_span.start, candidate_span.end)
        if generated is None or generated.text != candidate_span.text:
            continue
        expected = []
        for fact in check.evidence:
            row = sources.get(fact.evidence_id)
            if row is None or not set(fact.claim_ids) <= selected:
                continue
            expected_span = (
                fact.relation.quantity.span
                if quantity and fact.relation.quantity is not None
                else fact.relation.span
            )
            span = RepairTextSpan.checked(row.text, expected_span.start, expected_span.end)
            if span is None or span.text != expected_span.text:
                continue
            expected.append(
                RepairEvidence(
                    fact.claim_ids,
                    row.evidence_id,
                    row.sentence_index,
                    "source_sentence",
                    span,
                    span.text if quantity else fact.relation.state,
                    "contradicts_generated",
                )
            )
        if expected:
            detail = RepairDetail("CONTRADICTION", generated, tuple(expected[: _MAX_EVIDENCE + 1]))
            for rule in relevant:
                by_rule[rule].append(detail)
    return by_rule


def prose_repair_details(
    value: str,
    rules: tuple[str, ...],
    *,
    refs,
    evidence,
    mismatches=(),
    fact_index: FactIndex | None = None,
    reference_date: date | None = None,
) -> dict[str, tuple[RepairDetail, ...]]:
    """Attach bounded explanations to existing failures, without new acceptance rules.

    Literal locations come only from the guard's known message format and an
    actual slice of this field. Unknown/normalized literals keep a null range.
    Explicit indexed conflicts supply the exact opposite source relation/value.
    """
    if (
        type(value) is not str
        or type(rules) is not tuple
        or not all(type(rule) is str for rule in rules)
    ):
        return {}
    refs = tuple(
        dict.fromkeys(ref for ref in refs if type(ref) is str and _CLAIM_ID.fullmatch(ref))
    )
    details = _conflicts(value, rules, refs, fact_index, reference_date)
    context = _context(refs, evidence, fact_index)
    for rule in rules:
        for mismatch in mismatches:
            if type(mismatch) is not str:
                continue
            prefix = next(
                (
                    prefix
                    for prefix in _LITERAL_PREFIXES.get(rule, ())
                    if mismatch.startswith(prefix)
                ),
                None,
            )
            if prefix is None:
                continue
            for literal in mismatch[len(prefix) :].split(", "):
                if not literal or len(literal) > _MAX_SPAN:
                    continue
                for match in re.finditer(re.escape(literal), value, re.IGNORECASE):
                    # A number reported by the guard must not locate a substring
                    # of another number. Unlocatable normalization stays unknown.
                    if literal[0].isdigit() and (
                        (match.start() and value[match.start() - 1].isdigit())
                        or (match.end() < len(value) and value[match.end()].isdigit())
                    ):
                        continue
                    span = RepairTextSpan.checked(value, *match.span())
                    if any(
                        item.generated_span
                        and item.generated_span.start <= span.start
                        and span.end <= item.generated_span.end
                        for item in details[rule]
                    ):
                        continue
                    details[rule].append(RepairDetail(diagnostic_category(rule), span, context))
        if not details[rule]:
            details[rule].append(RepairDetail(diagnostic_category(rule), None, context))
    return {rule: tuple(items[: _MAX_DETAILS + 1]) for rule, items in details.items()}
