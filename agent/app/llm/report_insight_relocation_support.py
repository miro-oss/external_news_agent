"""Reject a narrow physical-relocation premise presented as direct IT work."""

import re
from dataclasses import dataclass

from app.schemas.analyze import Audience
from app.schemas.report_insight import ReportInsightFinding
from app.schemas.report_insight_assessment import ReportFindingAssessmentDraft

_WORK = {"NETWORK", "DEPLOYMENT_OPERATIONS", "SYSTEM_PROCUREMENT"}
_PHYSICAL_SUBJECT = re.compile(
    r"임직원|직원|인력|사원|본사|본점|사옥|사무실|사무공간|헤드쿼터|\bHQ\b", re.I
)
_MOVE = re.compile(r"(?:이동|이전|입주)(?:하|했|한|할|합)|옮기|옮겨|옮겼")
_IT_WORD = (
    r"(?:서버|네트워크|전산|정보\s*시스템|IT\s*시스템|데이터\s*센터|통신망|"
    r"통신\s*장비|회선|클라우드|라우터|스위치|(?<![A-Za-z])IT(?![A-Za-z]))"
)
_IT_CONTEXT = re.compile(_IT_WORD, re.I)
# An IT employee/company is not itself an IT installation or migration. Only
# these explicit nominal modifiers are removed for diagnostic matching.
_IT_PEOPLE_OR_COMPANY = re.compile(
    _IT_WORD + r"\s*(?:(?:개발|운영|관리|지원|담당)\s*)?(?:임직원|직원|인력|사원|기업|회사)",
    re.I,
)


@dataclass(frozen=True)
class RelocationSupportProblem:
    native_field: str
    problem: str
    claim_ids: tuple[str, ...]


def relocation_support_problems(
    item: ReportFindingAssessmentDraft, finding: ReportInsightFinding, audience: Audience
) -> tuple[RelocationSupportProblem, ...]:
    if (
        audience != "IT_INFRA"
        or item.relation != "DIRECT"
        or item.work not in _WORK
        or item.relation_basis is None
        or item.finding_id != finding.id
    ):
        return ()
    claim = next((c for c in finding.claims if c.id == item.relation_basis.claim_id), None)
    if claim is None:
        return ()  # Citation/schema validation owns invalid bases.
    sentences = {s.index: s.text for s in finding.sentences}
    sources = [sentences[i] for i in claim.evidence_sentence_ids if i in sentences]
    # Do not turn an overstated compressed claim into original-source support.
    # Conversely, missing original text cannot prove a physical-only event.
    if not sources:
        return ()
    physical_move = any(
        _PHYSICAL_SUBJECT.search(clause) and _MOVE.search(clause)
        for source in sources
        for clause in re.split(r"[.!?。！？\n]", source)
    )
    if not physical_move:
        return ()
    source = "\n".join(sources)
    if _IT_CONTEXT.search(_IT_PEOPLE_OR_COMPANY.sub("", source)):
        # An explicit technical object can make this a real IT event. This
        # narrow rule abstains; it does not certify that object's interpretation.
        return ()
    return (
        RelocationSupportProblem(
            native_field="decision.connection.relation",
            problem="physical_relocation_only_it_direct",
            claim_ids=(claim.id,),
        ),
    )
