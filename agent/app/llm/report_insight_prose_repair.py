"""Source-bound prompt projections for already authenticated prose repairs."""

from copy import deepcopy
from dataclasses import dataclass

from app.llm.report_insight_assessment import finding_prompt_payload
from app.llm.report_insight_guard import report_reference_date
from app.schemas.report_insight_assessment import ReportFindingAssessmentDraft


@dataclass(frozen=True)
class ProseRepairSources:
    snapshot: ReportFindingAssessmentDraft
    fields: tuple[str, ...]
    original: dict
    focused: dict
    audiences: tuple[str, ...]
    reference_date: str | None

    def matches(self, snapshot, fields, source, audience, reference_date):
        # Compare the entire source projection, including claim type, speaker,
        # linked sentences and fact proposals, not only the selected span.
        return (
            self.snapshot == snapshot
            and self.fields == fields
            and self.original == source
            and self.audiences == (audience,)
            and self.reference_date == reference_date
        )


def prose_repair_sources(request, contexts):
    """Mint only alongside server-owned native prose preservation proofs."""
    findings = {finding.id: finding for finding in request.findings}
    reference = report_reference_date(request)
    result = {}
    for identifier, (snapshot, fields) in contexts.items():
        selected = {
            basis.claim_id
            for basis in (snapshot.relation_basis, snapshot.impact_basis, snapshot.urgency_basis)
            if basis is not None
        }
        finding = findings[identifier]
        original = finding_prompt_payload(request, finding)
        # An undecidable relation has no basis. Its prose is checked against
        # all eligible local claims; an empty selection must not erase them.
        focused = (
            finding_prompt_payload(request, finding, claim_ids=selected) if selected else original
        )
        result[identifier] = ProseRepairSources(
            deepcopy(snapshot),
            tuple(fields),
            original,
            deepcopy(focused),
            tuple(request.audiences),
            reference.isoformat() if reference else None,
        )
    return result


def focus_prose_repair_finding(
    source, context, snapshot, fields, fixed, decision, *, audience, reference_date, source_quotes
):
    """Return a model-input copy; no request, output or validator is changed."""
    if (
        not isinstance(context, ProseRepairSources)
        or not context.matches(snapshot, fields, source, audience, reference_date)
        or "factTextSlots" in source
        or any(value is not None for value in (source_quotes or {}).values())
    ):
        return source
    focused = deepcopy(context.focused)
    focused["proseRepair"] = {
        "editableFields": [field for field in fields if field not in fixed],
        "preservedValues": deepcopy(fixed),
        "selectedEvidence": [
            {
                "axis": axis,
                **basis,
                "text": source["sourceQuoteChoices"][basis["claimId"]][basis["sourceSpanId"]],
            }
            for axis, value in decision.items()
            if (basis := value["basis"]) is not None
        ],
    }
    return focused
