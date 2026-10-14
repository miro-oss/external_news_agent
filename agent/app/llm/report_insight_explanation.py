"""Prepare prose-only calls from final decisions and their selected sources."""

from copy import deepcopy
from dataclasses import dataclass
from datetime import date
from typing import Any

from app.core.errors import OutputValidationError
from app.llm.base import ProviderResponse
from app.llm.openai_contract import _object
from app.llm.prompt_data import prompt_json
from app.llm.report_insight_assessment import (
    ValidatedAssessmentDraft,
    draft_schema,
    draft_to_wire,
    finding_prompt_payload,
    merge_drafts,
    parse_wire_draft,
)
from app.llm.report_insight_guard import report_reference_date
from app.llm.report_insight_instructions import ASSESSMENT_SELECTED_BASIS_RULE
from app.schemas.report_insight import ReportInsightRequest
from app.schemas.report_insight_assessment import ReportFindingAssessmentDraft


@dataclass(frozen=True)
class ExplanationBatch:
    # Validation retains the original finding. Only the model input is focused.
    request: ReportInsightRequest
    prompt: str
    response_schema: dict[str, Any]
    fixed_records: dict[str, dict[str, dict[str, Any]]]
    finding_ids: tuple[int, ...]


def _fixed_schema(value):
    if isinstance(value, dict):
        return _object({key: _fixed_schema(item) for key, item in value.items()})
    if value is None:
        return {"type": "null"}
    if type(value) is int:
        return {"type": "integer", "const": value}
    if isinstance(value, str):
        return {"type": "string", "const": value}
    raise TypeError("An assessment decision contains an unsupported value.")


def prepare_explanation_batches(
    request: ReportInsightRequest,
    validated: ValidatedAssessmentDraft,
    *,
    batch_size: int = 6,
    reference_date: date | None = None,
) -> tuple[ExplanationBatch, ...]:
    """Select only rows that had access to evidence outside their final bases."""
    if type(batch_size) is not int or batch_size < 1:
        raise ValueError("Explanation batch size must be a positive integer.")
    authenticated = merge_drafts(request, validated)
    wire = draft_to_wire(authenticated, request)
    reference = reference_date if reference_date is not None else report_reference_date(request)
    condition_schema = next(
        branch
        for branch in ReportFindingAssessmentDraft.model_json_schema(by_alias=True)["properties"][
            "condition"
        ]["anyOf"]
        if branch.get("type") == "string"
    )
    result = []
    for audience in request.audiences:
        targets = []
        for finding in request.findings:
            record = wire["assessments"][audience][f"finding{finding.id}"]
            decision = record["decision"]
            selected = {
                axis["basis"]["claimId"] for axis in decision.values() if axis["basis"] is not None
            }
            if (
                decision["connection"]["relation"] == "UNDETERMINED"
                or not selected
                or not selected < {claim.id for claim in finding.claims}
            ):
                continue
            targets.append((finding, record, selected))
        for offset in range(0, len(targets), batch_size):
            group = targets[offset : offset + batch_size]
            subset = request.model_copy(
                deep=True,
                update={"audiences": (audience,), "findings": [item[0] for item in group]},
            )
            projected_findings, inputs, fixed_records = [], [], {}
            for finding, record, selected in group:
                claims = [claim for claim in finding.claims if claim.id in selected]
                sentence_ids = {index for claim in claims for index in claim.evidence_sentence_ids}
                projected_findings.append(
                    finding.model_copy(
                        deep=True,
                        update={
                            "claims": claims,
                            "sentences": [
                                item for item in finding.sentences if item.index in sentence_ids
                            ],
                        },
                    )
                )
                payload = finding_prompt_payload(request, finding, claim_ids=selected)
                decision = deepcopy(record["decision"])
                # Do not anchor new prose to either previously generated field.
                condition_required = decision["connection"].pop("condition") is not None
                payload.update(
                    decision=decision,
                    conditionRequired=condition_required,
                    selectedEvidence=[
                        {
                            "axis": axis,
                            **value["basis"],
                            "text": payload["sourceQuoteChoices"][value["basis"]["claimId"]][
                                value["basis"]["sourceSpanId"]
                            ],
                        }
                        for axis, value in decision.items()
                        if value["basis"] is not None
                    ],
                )
                inputs.append(payload)
                fixed_records[f"finding{finding.id}"] = deepcopy(record)
            projected = subset.model_copy(update={"findings": projected_findings})
            schema = draft_schema(projected)
            entries = schema["properties"]["assessments"]["properties"][audience]["properties"]
            for key, record in fixed_records.items():
                decision_schema = _fixed_schema(record["decision"])
                if record["decision"]["connection"]["condition"] is not None:
                    decision_schema["properties"]["connection"]["properties"]["condition"] = (
                        deepcopy(condition_schema)
                    )
                entries[key]["properties"]["decision"] = decision_schema
                entries[key]["properties"]["sourceQuotes"] = _fixed_schema(
                    {"reason": None, "condition": None}
                )
            payload = {
                "reportReferenceDate": reference.isoformat() if reference else None,
                "audiences": [audience],
                "findings": inputs,
            }
            result.append(
                ExplanationBatch(
                    request=subset,
                    prompt=(
                        "최종 검증된 판단과 선택 원문으로 설명을 새로 작성하세요. 각 항목은 "
                        "findingId, decision, reason, sourceQuotes 순서입니다. Schema const의 "
                        "관계·업무·영향·시점·basis를 유지하고 reason과 conditionRequired=true인 "
                        "condition만 작성하세요. conditionRequired=false이면 condition=null을 "
                        "유지합니다. sourceQuotes는 모두 null입니다. "
                        + ASSESSMENT_SELECTED_BASIS_RULE
                        + " 각 finding의 claims와 sentences는 최종 선택 claim과 연결 문장 "
                        "전체입니다. selectedEvidence는 고정된 축별 인용입니다. 선택되지 않은 "
                        "원문을 추측하거나 원문이 없다는 면책 문구를 쓰지 마세요.\n\n"
                        + "<report-insight-input>\n"
                        + prompt_json(payload)
                        + "\n</report-insight-input>"
                    ),
                    response_schema=schema,
                    fixed_records={audience: fixed_records},
                    finding_ids=tuple(item[0].id for item in group),
                )
            )
    return tuple(result)


def validate_explanation_fields(response: ProviderResponse, batch: ExplanationBatch) -> dict:
    """Check exact structure and frozen values before the existing full validators."""
    actual = parse_wire_draft(response.text).model_dump(by_alias=True, mode="json")
    records = actual["assessments"]
    if set(records) != set(batch.fixed_records) or any(
        set(records[audience]) != set(expected)
        for audience, expected in batch.fixed_records.items()
    ):
        raise OutputValidationError(
            "설명 작성은 요청한 audience와 finding만 정확히 반환해야 합니다.",
            error_kinds=("report_assessment_invalid",),
        )
    for audience, expected in batch.fixed_records.items():
        for key, original in expected.items():
            candidate, frozen = deepcopy(records[audience][key]), deepcopy(original)
            candidate.pop("reason")
            frozen.pop("reason")
            if original["decision"]["connection"]["condition"] is not None:
                if candidate["decision"]["connection"]["condition"] is None:
                    raise OutputValidationError(
                        "설명 작성은 검증된 조건부 관계의 condition을 제거할 수 없습니다.",
                        error_kinds=("report_assessment_invalid",),
                    )
                candidate["decision"]["connection"].pop("condition")
                frozen["decision"]["connection"].pop("condition")
            if candidate != frozen:
                raise OutputValidationError(
                    "설명 작성은 검증된 판단·원문 근거·null 조건·sourceQuotes를 "
                    "변경할 수 없습니다.",
                    error_kinds=("report_assessment_invalid",),
                )
    return actual
