"""One original-sentence provenance index for MAP, REVIEW and REDUCE.

Stored claim summaries identify permissible sources; they never become another
source sentence. Extracted relations retain their exact original offsets and
uncertainty. A fact ID identifies a parse, not an independently verified truth.
"""

from __future__ import annotations

import hashlib
import json
from collections import Counter
from collections.abc import Collection
from dataclasses import dataclass
from datetime import date

from app.llm.report_insight_fact_graph import Analysis, Mention, Relation, Span, analyze_sentence
from app.schemas.analyze import ClaimType
from app.schemas.report_insight import ReportInsightRequest

MAX_PROMPT_FACTS_PER_FINDING = 24
MAX_PROMPT_UNCERTAINTIES_PER_FINDING = 2


@dataclass(frozen=True)
class ClaimOrigin:
    claim_id: str
    claim_type: ClaimType
    attributed_to: str | None


@dataclass(frozen=True)
class SourceEvidence:
    evidence_id: str
    finding_id: int
    article_id: int
    sentence_index: int
    source_sha256: str
    published_at: date | None
    text: str
    claims: tuple[ClaimOrigin, ...]
    analysis: Analysis


@dataclass(frozen=True)
class IndexedFact:
    fact_id: str
    evidence_id: str
    relation: Relation


@dataclass(frozen=True)
class FactIndex:
    evidence: tuple[SourceEvidence, ...]
    facts: tuple[IndexedFact, ...]


def _stable_id(prefix: str, value: object) -> str:
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return f"{prefix}-{hashlib.sha256(encoded.encode()).hexdigest()[:24]}"


def _binding_uncertainty(relation: Relation) -> tuple[str, ...]:
    reasons = list(relation.uncertainty)
    if not relation.subjects and "subject_unresolved" not in reasons:
        reasons.append("subject_unresolved")
    if relation.target is None and "target_unresolved" not in reasons:
        reasons.append("target_unresolved")
    return tuple(reasons)


def build_fact_index(
    request: ReportInsightRequest, claim_ids: Collection[str] | None = None
) -> FactIndex:
    """Index only original sentences linked to the selected stored claims.

    Selection changes the visible claim origins, never sentence/fact identity.
    No neighboring sentence, title, summary or publication date fills a missing
    relation argument. Publication dates remain metadata, not inferred time.
    """
    selected = None if claim_ids is None else frozenset(claim_ids)
    rows: list[SourceEvidence] = []
    facts: list[IndexedFact] = []
    for finding in sorted(request.findings, key=lambda item: item.id):
        origins: dict[int, list[ClaimOrigin]] = {}
        for claim in sorted(finding.claims, key=lambda item: item.id):
            if selected is not None and claim.id not in selected:
                continue
            origin = ClaimOrigin(claim.id, claim.claim_type, claim.attributed_to)
            for sentence_id in claim.evidence_sentence_ids:
                origins.setdefault(sentence_id, []).append(origin)
        for sentence in sorted(finding.sentences, key=lambda item: item.index):
            if sentence.index not in origins:
                continue
            analysis = analyze_sentence(sentence.text)
            evidence_id = _stable_id(
                "source",
                (finding.id, finding.article_id, sentence.index, analysis.source_sha256),
            )
            rows.append(
                SourceEvidence(
                    evidence_id=evidence_id,
                    finding_id=finding.id,
                    article_id=finding.article_id,
                    sentence_index=sentence.index,
                    source_sha256=analysis.source_sha256,
                    published_at=finding.published_at,
                    text=sentence.text,
                    claims=tuple(origins[sentence.index]),
                    analysis=analysis,
                )
            )
            for relation in analysis.relations:
                if _binding_uncertainty(relation):
                    continue
                facts.append(
                    IndexedFact(
                        fact_id=_stable_id("fact", (evidence_id, _relation_payload(relation))),
                        evidence_id=evidence_id,
                        relation=relation,
                    )
                )
    return FactIndex(tuple(rows), tuple(facts))


def _span_payload(span: Span) -> dict:
    return {"start": span.start, "end": span.end, "text": span.text}


def _mention_payload(mention: Mention | None) -> dict | None:
    if mention is None:
        return None
    return {
        "kind": mention.kind,
        "value": mention.value,
        "span": _span_payload(mention.span),
        "unit": mention.unit,
        "qualifier": mention.qualifier,
        "upper": mention.upper,
        "role": mention.role,
    }


def _relation_payload(relation: Relation) -> dict:
    return {
        "subjects": [_mention_payload(subject) for subject in relation.subjects],
        "subjectMode": relation.subject_mode,
        "predicate": relation.predicate,
        "target": _mention_payload(relation.target),
        "quantity": _mention_payload(relation.quantity),
        "time": _mention_payload(relation.time),
        "state": relation.state,
        "attributedTo": _mention_payload(relation.attributed_to),
        "span": _span_payload(relation.span),
        "bindings": [_mention_payload(binding) for binding in relation.bindings],
        "rule": relation.rule,
    }


def _offsets(span: Span) -> dict:
    return {"start": span.start, "end": span.end}


def _prompt_mention(mention: Mention | None) -> dict | None:
    if mention is None:
        return None
    payload = {
        "kind": mention.kind,
        "value": mention.value,
        "span": _offsets(mention.span),
    }
    if mention.kind == "quantity":
        payload.update(unit=mention.unit, qualifier=mention.qualifier, role=mention.role)
        if mention.upper is not None:
            payload["upper"] = mention.upper
    return payload


def _prompt_relation(relation: Relation) -> dict:
    # Raw source sentences are already present in all three stage prompts.
    # Repeat neither complete clauses nor bindings already named in the slots.
    represented = (
        *relation.subjects,
        relation.target,
        relation.quantity,
        relation.time,
        relation.attributed_to,
    )
    return {
        "subjects": [_prompt_mention(subject) for subject in relation.subjects],
        "subjectMode": relation.subject_mode,
        "predicate": relation.predicate,
        "target": _prompt_mention(relation.target),
        "quantity": _prompt_mention(relation.quantity),
        "time": _prompt_mention(relation.time),
        "state": relation.state,
        "attributedTo": _prompt_mention(relation.attributed_to),
        "span": _offsets(relation.span),
        "bindings": [
            _prompt_mention(binding)
            for binding in dict.fromkeys(relation.bindings)
            if binding not in represented
        ],
        "rule": relation.rule,
    }


def _evidence_payload(row: SourceEvidence) -> dict:
    return {
        "evidenceId": row.evidence_id,
        "findingId": row.finding_id,
        "articleId": row.article_id,
        "sentenceIndex": row.sentence_index,
        "sourceSha256": row.source_sha256,
        "sourceLength": len(row.text),
        "publishedAt": row.published_at.isoformat() if row.published_at else None,
        "claims": [
            {
                "claimId": claim.claim_id,
                "claimType": claim.claim_type,
                "attributedTo": claim.attributed_to,
            }
            for claim in row.claims
        ],
    }


def prompt_fact_index(
    request: ReportInsightRequest,
    claim_ids: Collection[str] | None = None,
    *,
    finding_id: int | None = None,
) -> dict:
    """Render the same index with explicit extraction and payload limits.

    Limits are per finding, so selecting another audience or MAP batch cannot
    silently change fact identity. The full immutable index is not truncated;
    only this prompt projection is. Raw linked sentences stay in stage inputs.
    """
    selected = None if claim_ids is None else frozenset(claim_ids)
    if finding_id is not None:
        selected = {
            claim.id
            for finding in request.findings
            if finding.id == finding_id
            for claim in finding.claims
            if selected is None or claim.id in selected
        }
    index = build_fact_index(request, selected)
    rows_by_id = {row.evidence_id: row for row in index.evidence}
    emitted_ids: set[str] = set()
    fact_counts: dict[int, int] = {}
    uncertainty_counts: dict[int, int] = {}
    uncertainty_signatures: dict[int, set[tuple[str, tuple[str, ...]]]] = {}
    reason_counts: Counter[str] = Counter()
    facts: list[dict] = []
    uncertainty: list[dict] = []
    uncertainty_total = 0
    for fact in index.facts:
        row = rows_by_id[fact.evidence_id]
        count = fact_counts.get(row.finding_id, 0)
        if count >= MAX_PROMPT_FACTS_PER_FINDING:
            continue
        fact_counts[row.finding_id] = count + 1
        facts.append(
            {
                "factId": fact.fact_id,
                "evidenceId": fact.evidence_id,
                **_prompt_relation(fact.relation),
            }
        )
        emitted_ids.add(fact.evidence_id)
    for row in index.evidence:
        unresolved = [
            {
                "evidenceId": row.evidence_id,
                "kind": "unbound_relation",
                "span": _offsets(relation.span),
                "rule": relation.rule,
                "reasons": list(_binding_uncertainty(relation)),
            }
            for relation in row.analysis.relations
            if _binding_uncertainty(relation)
        ] + [
            {
                "evidenceId": row.evidence_id,
                "kind": "unresolved_source",
                "span": _offsets(item.span),
                "rule": None,
                "reasons": [item.reason],
            }
            for item in row.analysis.unresolved
        ]
        uncertainty_total += len(unresolved)
        for item in unresolved:
            reason_counts.update(item["reasons"])
        available = max(
            0,
            MAX_PROMPT_UNCERTAINTIES_PER_FINDING - uncertainty_counts.get(row.finding_id, 0),
        )
        emitted = []
        signatures = uncertainty_signatures.setdefault(row.finding_id, set())
        for item in unresolved:
            if len(emitted) >= available:
                break
            signature = (item["kind"], tuple(item["reasons"]))
            if signature in signatures:
                continue
            signatures.add(signature)
            emitted.append(item)
        if emitted:
            uncertainty.extend(emitted)
            uncertainty_counts[row.finding_id] = uncertainty_counts.get(row.finding_id, 0) + len(
                emitted
            )
            emitted_ids.add(row.evidence_id)
    return {
        "schemaVersion": "source-fact-index.v1",
        "sourceScope": "linked_original_sentences",
        "extractionScope": "partial_explicit_relations",
        "offsetUnit": "unicode_code_points",
        "evidence": [
            _evidence_payload(row) for row in index.evidence if row.evidence_id in emitted_ids
        ],
        "facts": facts,
        "uncertainty": uncertainty,
        "uncertaintyReasonCounts": dict(sorted(reason_counts.items())),
        "limitsPerFinding": {
            "facts": MAX_PROMPT_FACTS_PER_FINDING,
            "uncertaintyExamples": MAX_PROMPT_UNCERTAINTIES_PER_FINDING,
        },
        "counts": {
            "sourceSentences": len(index.evidence),
            "mentions": sum(len(row.analysis.mentions) for row in index.evidence),
            "relations": sum(len(row.analysis.relations) for row in index.evidence),
            "boundFacts": len(index.facts),
            "uncertainties": uncertainty_total,
            "emittedFacts": len(facts),
            "emittedUncertainties": len(uncertainty),
        },
        "truncated": len(facts) < len(index.facts) or len(uncertainty) < uncertainty_total,
    }
