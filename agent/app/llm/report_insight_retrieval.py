"""Dependency-free RAG confined to the supplied, grounded report snapshot.

A map assessment determines eligibility, not source identity: all identifiers and
claim/sentence text are reconstructed from the validated request. BM25 combines
role vocabulary with map reasons to select reduce evidence. It is lexical
retrieval, not a claim verifier, semantic search, confidence score, or external
search. Korean character n-grams provide conservative compound-word matching.
"""

import re
import unicodedata
from collections import Counter, defaultdict
from collections.abc import Sequence
from dataclasses import dataclass
from math import log
from typing import Any

from app.schemas.analyze import Audience, ClaimType
from app.schemas.report_insight import (
    ReportInsightAssessment,
    ReportInsightClaim,
    ReportInsightFinding,
    ReportInsightRequest,
)

ALGORITHM_VERSION = "scoped-bm25.v1"
MAX_RETRIEVAL_CLAIMS = 24
# Vocabulary describes work questions; it is never factual evidence or instructions.
_ROLE_QUERIES: dict[Audience, str] = {
    "CHIP_MAKER": "반도체 제조 양산 생산능력 생산공정 공정 인증 수율 웨이퍼 파운드리 메모리 HBM "
    "패키징 고객인증 고객요구 공급계약 장기계약 원재료 소재확보 생산일정 "
    "fab capacity wafer yield foundry memory packaging "
    "manufacturing production qualification customer certification supply contract materials",
    "EQUIPMENT_MAKER": "장비 설비 소재 발주 공정인증 장비수주 납품 설치 증설 투자집행 "
    "식각 증착 노광 검사장비 패키징 equipment materials machinery orders capex "
    "installation delivery etching deposition lithography procurement",
    "MARKET_INVESTOR": "매출 실적 수익성 영업이익 순이익 시장점유율 수요 공급계약 "
    "재무 현금흐름 투자수익 earnings revenue margins profitability valuation "
    "demand market cashflow financial profit guidance",
    "IT_INFRA": "데이터센터 서버 GPU 가속기 메모리 저장장치 "
    "대역폭 냉각 전력 조달 도입 구축 "
    "시스템 운영 인프라 납기 datacenter server accelerator bandwidth cooling "
    "power procurement deployment infrastructure latency availability data-center "
    "memory storage dram ddr hbm nand ssd",
}
_WORDS = re.compile(r"[가-힣]+|[a-z0-9]+(?:[._-][a-z0-9]+)*")
_KOREAN = re.compile(r"^[가-힣]+$")
# Remove only grammatical tails, preserving at least two characters.
_TAILS = (
    "에서는",
    "으로는",
    "까지는",
    "에서도",
    "에게",
    "에서",
    "으로",
    "까지",
    "부터",
    "하며",
    "하고",
    "했다",
    "한다",
    "이다",
    "된다",
    "됐다",
    "되는",
    "하는",
    "에는",
    "와",
    "과",
    "를",
    "을",
    "의",
    "은",
    "는",
    "이",
    "가",
    "에",
)
_STOP_WORDS = frozenset(
    {
        "the",
        "and",
        "for",
        "with",
        "from",
        "that",
        "this",
        "was",
        "were",
        "has",
        "have",
        "will",
        "are",
        "its",
        "into",
        "than",
        "their",
        "a",
        "an",
        "of",
        "to",
        "in",
        "on",
        "is",
        "it",
        "as",
        "by",
        "or",
        "at",
        "be",
        "및",
        "또는",
        "대한",
        "관련",
        "위한",
        "통해",
        "따른",
        "따라",
        "있는",
        "있다",
        "있으며",
        "경우",
        "때문",
        "것으로",
        "이번",
        "보고서",
        "근거",
        "확인",
        "필요",
        "발표",
        "검토",
        "계획",
        "진행",
        "가능",
        "해당",
        "중요",
        "대상",
    }
)


@dataclass(frozen=True)
class RetrievedReportInsightSentence:
    index: int
    text: str


@dataclass(frozen=True)
class RetrievedReportInsightClaim:
    claim_id: str
    finding_id: int
    article_id: int
    article_title: str
    canonical_url: str
    published_at: str | None
    topic_name: str
    text: str
    claim_type: ClaimType
    attributed_to: str | None
    evidence_sentence_ids: tuple[int, ...]
    sentences: tuple[RetrievedReportInsightSentence, ...]
    score: float

    def to_payload(self) -> dict[str, Any]:
        return {
            "claimId": self.claim_id,
            "findingId": self.finding_id,
            "articleId": self.article_id,
            "articleTitle": self.article_title,
            "canonicalUrl": self.canonical_url,
            "publishedAt": self.published_at,
            "topicName": self.topic_name,
            "text": self.text,
            "claimType": self.claim_type,
            "attributedTo": self.attributed_to,
            "evidenceSentenceIds": list(self.evidence_sentence_ids),
            "sentences": [{"index": item.index, "text": item.text} for item in self.sentences],
            "score": self.score,
        }


@dataclass(frozen=True)
class ReportInsightRetrievalResult:
    report_id: int
    audience: Audience
    evidence: tuple[RetrievedReportInsightClaim, ...]

    @property
    def claim_ids(self) -> tuple[str, ...]:
        return tuple(claim.claim_id for claim in self.evidence)

    def to_payload(self) -> dict[str, Any]:
        return {
            "reportId": self.report_id,
            "audience": self.audience,
            "algorithm": ALGORITHM_VERSION,
            "evidence": [claim.to_payload() for claim in self.evidence],
        }


@dataclass(frozen=True)
class _Document:
    finding: ReportInsightFinding
    claim: ReportInsightClaim
    sentences: tuple[RetrievedReportInsightSentence, ...]
    terms: Counter[str]
    length: float

    @property
    def source_order(self) -> tuple[int, int]:
        return self.finding.id, int(self.claim.id.partition(":")[2])


def tokenize_report_evidence(text: str) -> Counter[str]:
    """Return exact words and lower-weight Korean compound n-grams.

    Unicode normalization is used only for lookup; original payloads are intact.
    Numeric identifiers and procedural strings have no special parsing behavior.
    Long uninterrupted Korean tokens generate n-grams up to length four, keeping
    complexity linear in the input length rather than all-substring matching.
    """
    terms: Counter[str] = Counter()
    for word in _WORDS.findall(unicodedata.normalize("NFKC", text).casefold()):
        if _KOREAN.fullmatch(word):
            for _ in range(2):
                tail = next(
                    (tail for tail in _TAILS if word.endswith(tail) and len(word) - len(tail) >= 2),
                    None,
                )
                if tail is None:
                    break
                word = word[: -len(tail)]
        if word in _STOP_WORDS or len(word) < 2 or word.isdecimal():
            continue
        terms[f"word:{word}"] += 1
        if _KOREAN.fullmatch(word) and len(word) >= 3:
            # A set prevents a repeated fragment inside one compound amplifying relevance.
            grams = {
                word[start : start + size]
                for size in range(2, min(4, len(word)) + 1)
                for start in range(len(word) - size + 1)
            }
            for gram in sorted(grams):
                if gram not in _STOP_WORDS:
                    terms[f"gram:{gram}"] += 0.18
    return terms


def _index(request: ReportInsightRequest) -> list[_Document]:
    documents: list[_Document] = []
    for finding in sorted(request.findings, key=lambda finding: finding.id):
        sentences = {sentence.index: sentence.text for sentence in finding.sentences}
        for claim in sorted(finding.claims, key=lambda claim: int(claim.id.partition(":")[2])):
            # Defense against unvalidated model_construct inputs: never repair invented IDs.
            prefix, separator, point = claim.id.partition(":")
            if (
                prefix != str(finding.id)
                or separator != ":"
                or not point.isascii()
                or not point.isdecimal()
                or str(int(point)) != point
            ):
                continue
            if (
                not claim.evidence_sentence_ids
                or not set(claim.evidence_sentence_ids) <= sentences.keys()
            ):
                continue
            evidence = tuple(
                RetrievedReportInsightSentence(index, sentences[index])
                for index in sorted(set(claim.evidence_sentence_ids))
            )
            terms = tokenize_report_evidence(claim.text)
            terms = Counter({term: count * 2.5 for term, count in terms.items()})
            for sentence in evidence:
                terms.update(tokenize_report_evidence(sentence.text))
            for text, weight in ((finding.article_title, 0.35), (finding.topic_name, 0.25)):
                terms.update(
                    {term: count * weight for term, count in tokenize_report_evidence(text).items()}
                )
            documents.append(_Document(finding, claim, evidence, terms, sum(terms.values())))
    return documents


def _valid_assessments(
    documents: Sequence[_Document], assessments: Sequence[ReportInsightAssessment]
) -> dict[int, ReportInsightAssessment]:
    known: dict[int, set[str]] = defaultdict(set)
    for document in documents:
        known[document.finding.id].add(document.claim.id)
    by_finding: dict[int, list[ReportInsightAssessment]] = defaultdict(list)
    for assessment in assessments:
        by_finding[assessment.finding_id].append(assessment)
    valid: dict[int, ReportInsightAssessment] = {}
    for finding_id, candidates in by_finding.items():
        # Duplicate assessments and mixed/cross-finding IDs fail closed.
        if len(candidates) != 1 or finding_id not in known:
            continue
        assessment = candidates[0]
        if assessment.axes.directness is None or assessment.axes.directness == 0:
            continue
        if (
            not assessment.basis_claim_ids
            or not set(assessment.basis_claim_ids) <= known[finding_id]
        ):
            continue
        valid[finding_id] = assessment
    return valid


def _priority(assessment: ReportInsightAssessment) -> float:
    axes = assessment.axes
    values = [(axes.directness, 0.4), (axes.impact, 0.4), (axes.urgency, 0.2)]
    available = [(score, weight) for score, weight in values if score is not None]
    return sum(score * weight for score, weight in available) / sum(
        weight for _, weight in available
    )


def _bm25(
    document: _Document, query: Counter[str], idf: dict[str, float], average_length: float
) -> float:
    k1, b = 1.2, 0.65
    normalization = k1 * (1 - b + b * document.length / max(average_length, 1))
    return sum(
        weight
        * idf[term]
        * document.terms[term]
        * (k1 + 1)
        / (document.terms[term] + normalization)
        for term, weight in query.items()
        if term in document.terms
    )


def _has_role_match(document: _Document, role_query: Counter[str]) -> bool:
    source_words = [
        term.removeprefix("word:") for term in document.terms if term.startswith("word:")
    ]
    for term in role_query:
        if not term.startswith("word:"):
            continue
        role_word = term.removeprefix("word:")
        if term in document.terms:
            return True
        if _KOREAN.fullmatch(role_word) and any(role_word in word for word in source_words):
            return True
    return False


def _dedupe_key(document: _Document) -> tuple[Any, ...]:
    # Retain corroboration from distinct articles; remove duplicate claims from one article.
    def normalized(text: str) -> str:
        return " ".join(unicodedata.normalize("NFKC", text).split()).casefold()

    return (
        document.finding.article_id,
        normalized(document.claim.text),
        document.claim.claim_type,
        document.claim.attributed_to,
        tuple((sentence.index, normalized(sentence.text)) for sentence in document.sentences),
    )


def retrieve_report_insight_evidence(
    request: ReportInsightRequest,
    audience: Audience,
    assessments: Sequence[ReportInsightAssessment],
    *,
    limit: int = MAX_RETRIEVAL_CLAIMS,
    preserve_assessment_bases: bool = False,
) -> ReportInsightRetrievalResult:
    """Rank only original snapshot evidence for this audience's reduce stage.

    All findings still require map assessments upstream. This function neither
    edits them nor fetches any additional material. Unsupported IDs cannot mint
    sources. Map text and source text are lexical data, never executable commands.
    Empty output is legitimate for an unrelated or undecidable perspective.
    """
    if audience not in _ROLE_QUERIES or audience not in request.audiences:
        raise ValueError("audience must be present in the validated report request")
    if type(limit) is not int or not 0 <= limit <= MAX_RETRIEVAL_CLAIMS:
        raise ValueError(f"limit must be an integer from 0 to {MAX_RETRIEVAL_CLAIMS}")
    empty = ReportInsightRetrievalResult(request.report.id, audience, ())
    if limit == 0:
        return empty
    documents = _index(request)
    valid = _valid_assessments(documents, assessments)
    if not documents or not valid:
        return empty
    document_frequency: Counter[str] = Counter()
    for document in documents:
        document_frequency.update(document.terms.keys())
    idf = {
        term: log(1 + (len(documents) - count + 0.5) / (count + 0.5))
        for term, count in document_frequency.items()
    }
    average_length = sum(document.length for document in documents) / len(documents)
    role_query = tokenize_report_evidence(_ROLE_QUERIES[audience])
    snapshot_order = {finding.id: index for index, finding in enumerate(request.findings)}
    # Reasons of the most relevant assessed findings expand the retrieval query.
    priorities = sorted(
        valid.values(),
        key=lambda assessment: (
            assessment.axes.impact is None,
            -_priority(assessment),
            snapshot_order[assessment.finding_id],
        ),
    )
    reason_query: Counter[str] = Counter()
    for rank, assessment in enumerate(priorities[:5]):
        reason_query.update(
            {
                term: min(count, 1) * 0.45 / (rank + 1)
                for term, count in tokenize_report_evidence(assessment.reason).items()
            }
        )
    scored: dict[str, tuple[float, _Document]] = {}
    ranked: list[tuple[float, _Document]] = []
    for document in documents:
        assessment = valid.get(document.finding.id)
        if assessment is None:
            continue
        role_score = _bm25(document, role_query, idf, average_length)
        reason_score = _bm25(document, reason_query, idf, average_length)
        score = role_score * 1.5 + reason_score + _priority(assessment) * 0.15
        if document.claim.id in assessment.basis_claim_ids:
            score += 0.25
        scored[document.claim.id] = (round(score, 8), document)
        # Lexical matching governs additional retrieval, but must not override
        # validated map relevance for the report's most important original bases.
        if role_score > 0 and _has_role_match(document, role_query):
            ranked.append(scored[document.claim.id])
    ranked.sort(key=lambda item: (-item[0], item[1].source_order))
    primary: list[list[tuple[float, _Document]]] = []
    for assessment in priorities[:5]:
        # The public projection of the evidence-first draft keeps relation,
        # impact, then timing proof order. Lexical score must not replace the
        # actual work-connection proof with an incidental axis quote.
        basis = [scored[claim_id] for claim_id in assessment.basis_claim_ids]
        if not preserve_assessment_bases:
            # Preserve the legacy-v3 replay's lexical primary-basis selection.
            basis.sort(key=lambda item: (-item[0], item[1].source_order))
        primary.append(basis)
    # Keep public importance order, including the snapshot's equal-score ties.
    # Give each top finding its first proof before taking additional axis
    # proofs. Up to three unique axes per top-five draft fit the 24-claim cap.
    # Short caller limits still preserve fair top-finding coverage.
    seeded = [basis[0] for basis in primary]
    if preserve_assessment_bases:
        seeded.extend(item for basis in primary for item in basis[1:])
    seen: set[tuple[Any, ...]] = set()
    selected: list[RetrievedReportInsightClaim] = []
    for score, document in [*seeded, *ranked]:
        key = _dedupe_key(document)
        if key in seen:
            continue
        seen.add(key)
        finding, claim = document.finding, document.claim
        selected.append(
            RetrievedReportInsightClaim(
                claim_id=claim.id,
                finding_id=finding.id,
                article_id=finding.article_id,
                article_title=finding.article_title,
                canonical_url=finding.canonical_url,
                published_at=finding.published_at.isoformat() if finding.published_at else None,
                topic_name=finding.topic_name,
                text=claim.text,
                claim_type=claim.claim_type,
                attributed_to=claim.attributed_to,
                evidence_sentence_ids=tuple(claim.evidence_sentence_ids),
                sentences=document.sentences,
                score=score,
            )
        )
        if len(selected) == limit:
            break
    return ReportInsightRetrievalResult(request.report.id, audience, tuple(selected))
