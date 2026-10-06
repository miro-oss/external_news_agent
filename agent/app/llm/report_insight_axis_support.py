"""Reject forecasts used as current constraints or confirmed work changes/schedules.

This is a narrow source-stage check, not a general entailment or scoring model.
Ambiguous mixed events remain with the model and the other validators. Evidence,
category decisions, and public output are never rewritten here.
"""

import re
from dataclasses import dataclass

from app.schemas.report_insight import ReportInsightFinding
from app.schemas.report_insight_assessment import (
    ReportAssessmentSourceQuote,
    ReportFindingAssessmentDraft,
)


@dataclass(frozen=True)
class AxisSupportProblem:
    native_field: str
    problem: str
    claim_ids: tuple[str, ...]


_MARKET = re.compile(
    r"수급|공급\s*(?:부족|과잉|불균형|난)|수요.{0,20}공급|공급.{0,20}수요|"
    r"가격|단가|출하량"
)
_FORECAST = re.compile(
    r"(?:전망|예상|예측)(?!치)|"
    r"(?:이어질|심화될|증가할|감소할|상승할|하락할|오를|내릴|늘어날|줄어들|초과할)\s*것"
)
_FORECAST_DENIAL = re.compile(
    r"(?:전망|예상|예측)(?:이|은|는|가)?\s*(?:아니|없|틀렸)|"
    r"(?:전망|예상|예측)(?:과|와)\s*달리|"
    r"(?:전망|예상|예측).{0,12}(?:부인|철회|취소|일축|배제)|"
    r"(?:전망|예상|예측)(?:되|하)(?:지|지는)\s*않"
)
# A different event or an unresolved action clause is not a forecast-only case.
# Do not infer whether these mixed contexts establish a particular impact here.
_OTHER_EVENT = re.compile(
    r"프로젝트|발주|납품|착공|준공|설치|배포|도입|개발|공사|"
    r"예산|인력|준비|구매\s*계획|조달\s*계획|계약|협약|검증|승인"
)
_FIXED_PRICE = re.compile(
    r"(?:가격|단가|요금|견적)[^.!?。;；\n]{0,45}"
    r"(?:(?:인상|인하|변경|조정|확정|책정)(?:했|됐|되었)|"
    r"(?:올랐|내렸|올렸|하락했|상승했|정했)|(?:인상|인하)하고\s*있)"
)
_NOT_ASSERTED_SUFFIX = re.compile(
    r"^(?:다)?(?:면|거나)|^(?:다)?(?:고|는|다는)\s*(?:가정|소문)|"
    r"^.{0,16}(?:부인|사실이\s*아니)"
)
# CORE needs a stricter forecast-only boundary: current/past states, capacity,
# causes, or subordinate clauses may carry a constraint alongside the forecast.
# These markers only abstain; they never establish that a constraint is real.
# Even hypothetical capacity is deliberately left to the model/other validators.
_CORE_MIXED_CONTEXT = re.compile(
    r"현재|이미|지금|과거|지난|종전|기존|발생|현실화|"
    r"생산\s*(?:능력|용량)|캐파|능력\s*부족|수요\s*미충족|"
    r"못\s*미쳐|못해|못하여|"
    r"(?:부족|제약|차질|미달)(?:해|하여|돼|되어|로|이어서|이라)|"
    r"때문|탓|여파|원인|인해|따라|조건|경우|면|지만|는데|으므로"
)
# Only strong discourse boundaries, never numeric commas or decimal points.
_BOUNDARY = re.compile(
    r"[.!?。](?=\s|$)|[\n;；]|"
    r"(?:반면|한편|그러나|하지만|이와\s*별개로|이에\s*비해)\s*"
)


def _sections(text: str) -> tuple[tuple[int, int], ...]:
    result, start = [], 0
    for boundary in _BOUNDARY.finditer(text):
        if text[start : boundary.start()].strip():
            result.append((start, boundary.start()))
        start = boundary.end()
    if text[start:].strip():
        result.append((start, len(text)))
    return tuple(result)


def _selected_contexts(
    basis: ReportAssessmentSourceQuote, finding: ReportInsightFinding
) -> tuple[str, ...]:
    claim = next((claim for claim in finding.claims if claim.id == basis.claim_id), None)
    if claim is None or not basis.quote.strip():
        return ()  # Existing literal/reference guards own malformed evidence.
    sentences = {sentence.index: sentence.text for sentence in finding.sentences}
    linked = [sentences[index] for index in claim.evidence_sentence_ids if index in sentences]
    anchored = []
    for text in linked:
        offset = text.find(basis.quote)
        if offset < 0:
            continue
        end = offset + len(basis.quote)
        anchored.extend(
            text[start:stop] for start, stop in _sections(text) if start < end and stop > offset
        )
    if anchored:
        return tuple(anchored)
    if basis.quote not in claim.text or not linked:
        return ()
    # A compressed claim can strengthen a forecast to FACT. Use its linked
    # original sentences; never borrow another claim or the article title.
    return tuple(text[start:stop] for text in linked for start, stop in _sections(text))


def _market_forecast_only(text: str) -> bool:
    if not (_MARKET.search(text) and _FORECAST.search(text)):
        return False
    if _FORECAST_DENIAL.search(text) or _OTHER_EVENT.search(text):
        return False
    if any(
        not _NOT_ASSERTED_SUFFIX.search(text[match.end() :])
        for match in _FIXED_PRICE.finditer(text)
    ):
        return False
    return True


def assessment_axis_support_problems(
    item: ReportFindingAssessmentDraft, finding: ReportInsightFinding
) -> tuple[AxisSupportProblem, ...]:
    problems = []
    for field, category, rejected, basis, problem in (
        (
            "decision.effect.impactScope",
            item.impact_scope,
            "CORE_CONSTRAINT",
            item.impact_basis,
            "market_forecast_only_core_constraint",
        ),
        (
            "decision.effect.impactScope",
            item.impact_scope,
            "PROJECT_CHANGE",
            item.impact_basis,
            "market_forecast_only_project_change",
        ),
        (
            "decision.timing.urgencyState",
            item.urgency_state,
            "SCHEDULED_PREPARATION",
            item.urgency_basis,
            "market_forecast_only_scheduled_preparation",
        ),
    ):
        if category != rejected or basis is None:
            continue
        contexts = _selected_contexts(basis, finding)
        if contexts and all(
            _market_forecast_only(context)
            and not (rejected == "CORE_CONSTRAINT" and _CORE_MIXED_CONTEXT.search(context))
            for context in contexts
        ):
            problems.append(AxisSupportProblem(field, problem, (basis.claim_id,)))
    return tuple(problems)
