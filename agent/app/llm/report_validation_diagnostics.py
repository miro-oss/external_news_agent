"""Bounded server-owned report diagnostics; never provider prose or input values."""

import re
from dataclasses import dataclass
from uuid import uuid4

from pydantic import ValidationError

from app.core.errors import OutputValidationError
from app.core.parser import JsonObjectParseError

_AUDIENCES = frozenset({"CHIP_MAKER", "EQUIPMENT_MAKER", "MARKET_INVESTOR", "IT_INFRA"})
_REDUCE_FIELD = re.compile(
    r"(?:headline|overview|implications|watchItems|"
    r"overview\[[0-2]\]|(?:implications|watchItems)\[[0-4]\]|"
    r"overview\[[0-2]\]\.(?:text|assumption|basisClaimIds)|"
    r"implications\[[0-4]\]\.(?:text|mechanism|assumption|falsifiedBy|basisClaimIds)|"
    r"watchItems\[[0-4]\]\.(?:topic|indicator|trigger|basisClaimIds))"
)
_MAP_FIELD = re.compile(
    r"assessments\[[1-9][0-9]{0,18}\](?:\.(?:reason|basisClaimIds|"
    r"axes\.(?:directness|impact|urgency|novelty)|"
    r"decision\.connection\.(?:condition|relation|work)|"
    r"decision\.effect\.impactScope|decision\.timing\.urgencyState|"
    r"decision\.(?:connection|effect|timing)\.basis(?:\.(?:claimId|sourceSpanId))?))?"
)
_CLAIM_ID = re.compile(r"[1-9][0-9]{0,18}:(?:0|[1-9][0-9]{0,18})")
_LENGTH_KINDS = frozenset({"string_too_long", "string_too_short", "too_long", "too_short"})
_MAX_ISSUES = 8
_MAX_CLAIMS = 8

REPORT_VALIDATION_ERROR_KINDS = frozenset(
    {
        "report_assessment_draft_invalid",
        "report_assessment_invalid",
        "report_assessment_truncated_prefix",
        "report_assumption_unconfirmed",
        "report_fact_mismatch",
        "report_falsification_direction",
        "report_falsification_missing_observation",
        "report_synthesis_empty",
        "report_synthesis_information_gap",
        "report_synthesis_invalid",
        "report_synthesis_metadata_only",
        "report_synthesis_placeholder",
        "report_synthesis_reference_gap",
        "report_synthesis_source_binding",
        "report_synthesis_stage_overreach",
        "report_synthesis_subject_mismatch",
        "report_work_approval_prerequisite_unsupported",
        "report_work_certification_prerequisite_unsupported",
        "report_work_compatibility_procedure_unsupported",
        "report_work_cooling_procedure_unsupported",
        "report_work_inspection_prerequisite_unsupported",
        "report_work_organization_prerequisite_unsupported",
        "report_work_physical_module_unsupported",
        "report_work_specification_procedure_unsupported",
        "report_output_shape",
        "report_output_parse",
        "report_output_unlocated",
    }
)


@dataclass(frozen=True, slots=True)
class ReportValidationIssue:
    # None is an explicit unlocated failure. It is never permission to edit a
    # guessed unit, and makes the repair planner choose whole-output validation.
    audience: str | None
    field: str | None
    error_kind: str
    claim_ids: tuple[str, ...]
    rule_id: str | None = None

    @property
    def rule(self) -> str:
        return self.rule_id if self.rule_id in _RULE_REASONS else self.error_kind

    @property
    def reason(self) -> str:
        """Application-owned explanation, never exception or provider prose."""
        return _RULE_REASONS.get(self.rule, "출력이 해당 검증 규칙을 충족하지 않습니다.")

    @property
    def located(self) -> bool:
        return _safe_issue(self)

    def diagnostic_payload(self) -> dict[str, object]:
        """Internal uniform format; the public API projection stays unchanged."""
        if not _internal_issue(self, None):
            return _unlocated("report_output_unlocated").diagnostic_payload()
        refs = tuple(dict.fromkeys(self.claim_ids))
        return {
            "audience": self.audience,
            "field": self.field,
            "claimIds": list(refs[:_MAX_CLAIMS]),
            "claimIdsTruncated": len(refs) > _MAX_CLAIMS,
            "errorKind": self.error_kind,
            "rule": self.rule,
            "reason": self.reason,
            "repairScope": "located" if self.located else "whole_output",
        }


_RULE_REASONS = {
    "report_fact_mismatch": "생성한 사실값 또는 사건 연결을 선택 원문이 뒷받침하지 않습니다.",
    "report_assessment_draft_invalid": "평가의 판단·업무·근거·조건 사이의 계약이 맞지 않습니다.",
    "report_assessment_invalid": "평가의 필수 항목 또는 점수·근거 계약이 맞지 않습니다.",
    "report_assessment_truncated_prefix": "응답이 잘려 해당 평가를 완성하지 못했습니다.",
    "report_assumption_unconfirmed": "해석에 필요한 미확인 조건을 가정으로 표시하지 않았습니다.",
    "report_falsification_direction": "반증 조건이 해석을 반박하는 방향으로 작성되지 않았습니다.",
    "report_falsification_missing_observation": "반증에 실제로 관측할 수 있는 사건이 없습니다.",
    "report_synthesis_empty": "관련 근거가 있는데 필요한 종합 해석이 비어 있습니다.",
    "report_synthesis_information_gap": "정보 부재를 원문 사건이나 업무 영향으로 바꾸었습니다.",
    "report_synthesis_invalid": "종합 해석의 내용이 관점별 출력 계약과 다릅니다.",
    "report_synthesis_metadata_only": "원문 사건 대신 근거 정보의 유무만 설명하고 있습니다.",
    "report_synthesis_placeholder": "구체적인 해석 대신 미완성 문구가 포함되어 있습니다.",
    "report_synthesis_reference_gap": "종합 항목의 근거가 해당 관점에 허용된 근거와 다릅니다.",
    "report_synthesis_source_binding": "종합 해석의 사건 연결을 인용 원문이 뒷받침하지 않습니다.",
    "report_synthesis_stage_overreach": "종합 해석의 사건 단계가 원문보다 확정적입니다.",
    "report_synthesis_subject_mismatch": "종합 해석의 사건 주체가 인용 원문의 주체와 다릅니다.",
    "report_work_approval_prerequisite_unsupported": "원문에 없는 업무 승인 요건을 추가했습니다.",
    "report_work_certification_prerequisite_unsupported": "원문에 없는 인증 요건을 추가했습니다.",
    "report_work_compatibility_procedure_unsupported": "원문에 없는 호환성 절차를 추가했습니다.",
    "report_work_cooling_procedure_unsupported": "원문에 없는 냉각 업무 절차를 추가했습니다.",
    "report_work_inspection_prerequisite_unsupported": "원문에 없는 검사 요건을 추가했습니다.",
    "report_work_organization_prerequisite_unsupported": "원문에 없는 조직 요건을 추가했습니다.",
    "report_work_physical_module_unsupported": "원문에 없는 물리 장치·모듈을 업무에 추가했습니다.",
    "report_work_specification_procedure_unsupported": "원문에 없는 규격 절차를 추가했습니다.",
    "report_output_parse": "응답을 중복 키가 없는 JSON 객체로 읽을 수 없습니다.",
    "report_output_shape": "응답의 필드·자료형·필수 값이 출력 계약과 다릅니다.",
    "report_output_unlocated": "검증이 실패했지만 실패한 위치를 안전하게 특정할 수 없습니다.",
    "string_too_long": "문자열이 허용된 최대 길이를 넘었습니다.",
    "string_too_short": "문자열이 허용된 최소 길이보다 짧습니다.",
    "too_long": "목록의 항목 수가 허용된 최대 개수를 넘었습니다.",
    "too_short": "목록의 항목 수가 허용된 최소 개수보다 적습니다.",
    "schema_missing": "필수 출력 필드가 빠졌습니다.",
    "schema_extra_forbidden": "계약에 없는 출력 필드가 있습니다.",
    "schema_type": "출력 값의 자료형이 계약과 다릅니다.",
    "schema_value": "출력 값이 허용된 값 또는 제약 조건과 다릅니다.",
    "unsupported_number": "선택 원문에서 해당 숫자를 확인할 수 없습니다.",
    "currency_amount": "통화·금액·배율이 선택 원문과 다릅니다.",
    "numeric_context": "수치가 원문의 주체·대상·시점과 다르게 연결되었습니다.",
    "date": "날짜 또는 기간이 선택 원문과 다릅니다.",
    "company": "선택 원문에서 해당 기업·기관을 확인할 수 없습니다.",
    "polarity": "원문 사건의 긍정·부정 상태가 뒤집혔습니다.",
    "event_state": "계획·전망·실행·완료 상태가 선택 원문과 다릅니다.",
    "source_binding": "주체·사건·대상에 연결된 사실값이 선택 원문과 다릅니다.",
    "internal_reference_in_prose": "내부 참조 ID가 자연어 설명에 포함되었습니다.",
    "report_fact_slot_position": "사실 표식은 필드 맨 앞에 한 개만 사용할 수 있습니다.",
    "report_fact_slot_unknown": "원문 목록에 없는 사실 표식을 선택했습니다.",
    "report_fact_slot_scope": "선택한 원문 표식이 현재 항목의 허용 근거에 속하지 않습니다.",
    "report_fact_interpretation_required": "원문 인용 뒤에 관점 해석이나 확인할 조건이 필요합니다.",
    "report_fact_template_required": (
        "원문 표식 뒤의 자유 문장에서도 회사·기관명, 수치, 내년 같은 시점, 사건·전망 "
        "재서술과 내부 범주 코드를 제거하세요. 표식은 원문 전체로 복원됩니다. "
        "남은 문장에는 어떤 업무 변수를 확인하고 무엇을 판단할지만 짧게 쓰세요."
    ),
    "report_fact_rendered_length": "원문 인용과 해석을 합친 길이가 필드 한도를 넘었습니다.",
}


@dataclass(frozen=True, slots=True)
class ReportValidationContext:
    """One server-created identity shared by parallel stages and their repairs."""

    trace_id: str
    report_id: int
    audiences: tuple[str, ...]

    def __post_init__(self) -> None:
        if (
            type(self.trace_id) is not str
            or re.fullmatch(r"[0-9a-f]{32}", self.trace_id) is None
            or type(self.report_id) is not int
            or self.report_id <= 0
            or type(self.audiences) is not tuple
            or not self.audiences
            or not all(type(item) is str and item in _AUDIENCES for item in self.audiences)
        ):
            raise ValueError("Invalid report diagnostic context")

    @classmethod
    def create(cls, report_id: int, audiences: list[str]) -> "ReportValidationContext":
        return cls(uuid4().hex, report_id, tuple(audiences))

    def log_fields(self) -> tuple[str, int, tuple[str, ...]]:
        return self.trace_id, self.report_id, self.audiences


def _safe_issue(issue: object, stage: str | None = None) -> bool:
    if stage is None:
        fields = (_REDUCE_FIELD, _MAP_FIELD)
    elif stage in {"MAP", "REVIEW"} or stage.startswith(("MAP-", "REVIEW-")):
        fields = (_MAP_FIELD,)
    else:
        fields = (_REDUCE_FIELD,)
    return (
        type(issue) is ReportValidationIssue
        and type(issue.audience) is str
        and issue.audience in _AUDIENCES
        and type(issue.field) is str
        and any(field.fullmatch(issue.field) is not None for field in fields)
        and type(issue.error_kind) is str
        and issue.error_kind in REPORT_VALIDATION_ERROR_KINDS | _LENGTH_KINDS
        and type(issue.claim_ids) is tuple
        and all(type(ref) is str and _CLAIM_ID.fullmatch(ref) for ref in issue.claim_ids)
    )


def report_validation_issue_details(
    error: Exception, schema: dict, *, stage: str | None = None
) -> dict[str, object]:
    """Project typed diagnostics only, including an explicit bounded-output marker."""
    supplied = getattr(error, "validation_issues", None)
    if supplied is None and isinstance(error, ValidationError):
        supplied = pydantic_reduce_issues(error, schema)
    if type(supplied) is not tuple:
        return {}
    safe = tuple(issue for issue in supplied if _safe_issue(issue, stage))
    if not safe:
        return {}
    # Internal rule IDs can distinguish two causes at the same public location.
    # Deduplicate the public projection before its bound, so those causes cannot
    # push a different failed finding out of the response's diagnostic window.
    unique_by_public = {}
    for issue in safe:
        key = (issue.audience, issue.field, issue.error_kind, frozenset(issue.claim_ids))
        unique_by_public.setdefault(key, issue)
    unique = tuple(unique_by_public.values())
    return {
        "issues": [
            {
                "audience": issue.audience,
                "field": issue.field,
                "errorKind": issue.error_kind,
                "claimIds": list(dict.fromkeys(issue.claim_ids))[:_MAX_CLAIMS],
            }
            for issue in unique[:_MAX_ISSUES]
        ],
        "issuesTruncated": len(unique) > _MAX_ISSUES
        or any(len(set(issue.claim_ids)) > _MAX_CLAIMS for issue in unique),
    }


def _resolve(node: object, schema: dict) -> dict:
    seen = set()
    while isinstance(node, dict) and "$ref" in node:
        ref = node["$ref"]
        if not isinstance(ref, str) or not ref.startswith("#/$defs/") or ref in seen:
            return {}
        seen.add(ref)
        node = schema.get("$defs", {}).get(ref.removeprefix("#/$defs/"))
    return node if isinstance(node, dict) else {}


def _audience_at(schema: dict, index: int) -> str | None:
    if schema.get("title") != "ReportInsightReduceOutput":
        return None
    insights = _resolve(schema.get("properties", {}).get("insights"), schema)
    prefix = insights.get("prefixItems")
    if isinstance(prefix, list):
        node = prefix[index] if index < len(prefix) else {}
    else:
        node = insights.get("items")
    node = _resolve(node, schema)
    branches = node.get("anyOf", node.get("oneOf", [node]))
    if not isinstance(branches, list) or not branches:
        return None
    audiences = []
    for branch in branches:
        branch = _resolve(branch, schema)
        value = _resolve(branch.get("properties", {}).get("audience"), schema).get("const")
        if type(value) is not str or value not in _AUDIENCES:
            return None
        audiences.append(value)
    return audiences[0] if len(set(audiences)) == 1 else None


def _closed_field(loc: tuple) -> str | None:
    aliases = {
        "watch_items": "watchItems",
        "falsified_by": "falsifiedBy",
        "basis_claim_ids": "basisClaimIds",
    }
    if len(loc) == 1 and type(loc[0]) is str:
        field = aliases.get(loc[0], loc[0])
    elif len(loc) == 3 and type(loc[0]) is str and type(loc[1]) is int and type(loc[2]) is str:
        field = f"{aliases.get(loc[0], loc[0])}[{loc[1]}].{aliases.get(loc[2], loc[2])}"
    else:
        return None
    return field if _REDUCE_FIELD.fullmatch(field) else None


def pydantic_reduce_issues(
    error: ValidationError, original_schema: dict
) -> tuple[ReportValidationIssue, ...]:
    """Read closed length-error paths; audience comes only from a schema const.

    A multi-audience anyOf does not bind response indices to audiences. Such paths
    are deliberately omitted instead of inspecting rejected provider input.
    """
    issues = []
    for item in error.errors(include_input=False, include_context=False, include_url=False):
        loc = item["loc"]
        if (
            item["type"] not in _LENGTH_KINDS
            or len(loc) < 3
            or loc[0] != "insights"
            or type(loc[1]) is not int
            or not 0 <= loc[1] < 4
        ):
            continue
        audience = _audience_at(original_schema, loc[1])
        field = _closed_field(loc[2:])
        if audience is not None and field is not None:
            issues.append(ReportValidationIssue(audience, field, item["type"], ()))
    return tuple(issues)


@dataclass(frozen=True, slots=True)
class ReportValidationScope:
    """A location supplied by the server's traversal, never parsed from prose.

    The caller must derive these values from the original request or its schema.
    A provider's findingId, audience value or quoted error path is not a scope.
    """

    audience: str
    field: str
    claim_ids: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        if not _safe_issue(
            ReportValidationIssue(self.audience, self.field, "report_output_shape", self.claim_ids)
        ):
            raise ValueError("Invalid server validation scope")


def _unlocated(kind: str, *, rule_id: str | None = None) -> ReportValidationIssue:
    return ReportValidationIssue(None, None, kind, (), rule_id)


def _internal_issue(issue: object, stage: str | None) -> bool:
    if _safe_issue(issue, stage):
        return True
    return (
        type(issue) is ReportValidationIssue
        and issue.audience is None
        and issue.field is None
        and issue.claim_ids == ()
        and issue.error_kind in REPORT_VALIDATION_ERROR_KINDS | _LENGTH_KINDS
        and (issue.rule_id is None or issue.rule_id in _RULE_REASONS)
    )


def _shape_rule(kind: str) -> str:
    if kind in {"missing", "extra_forbidden"}:
        return f"schema_{kind}"
    if kind.endswith(("_type", "_parsing")):
        return "schema_type"
    return "schema_value"


def _map_schema_location(schema: dict, loc: tuple) -> tuple[str, str] | None:
    if (
        schema.get("title") != "ReportAssessmentDraft"
        or len(loc) < 3
        or loc[0] != "assessments"
        or type(loc[1]) is not str
        or type(loc[2]) is not str
    ):
        return None
    assessments = _resolve(schema.get("properties", {}).get("assessments"), schema)
    audience = loc[1]
    audience_node = _resolve(assessments.get("properties", {}).get(audience), schema)
    finding_node = _resolve(audience_node.get("properties", {}).get(loc[2]), schema)
    finding_id = _resolve(finding_node.get("properties", {}).get("findingId"), schema).get("const")
    if (
        audience not in _AUDIENCES
        or type(finding_id) is not int
        or finding_id <= 0
        or loc[2] != f"finding{finding_id}"
    ):
        return None
    base = f"assessments[{finding_id}]"
    # Union labels, unexpected keys and provider-controlled suffixes cannot be
    # interpreted as editable fields. The authenticated finding is still safe.
    suffix = ".".join(loc[3:]) if all(type(part) is str for part in loc[3:]) else ""
    field = f"{base}.{suffix}" if suffix else base
    return audience, field if _MAP_FIELD.fullmatch(field) else base


def _reduce_schema_location(schema: dict, loc: tuple) -> tuple[str, str] | None:
    if len(loc) < 3 or loc[0] != "insights" or type(loc[1]) is not int or not 0 <= loc[1] < 4:
        return None
    audience = _audience_at(schema, loc[1])
    field = _closed_field(loc[2:])
    if field is None and len(loc) >= 4:
        # A malformed list item or one of its reference elements has the same
        # repair unit as its schema-bound list index. Arbitrary suffixes do not
        # grant authority to another unit.
        group = {"watch_items": "watchItems"}.get(loc[2], loc[2])
        index = loc[3]
        maximum = {"overview": 3, "implications": 5, "watchItems": 5}.get(group)
        if maximum is not None and type(index) is int and 0 <= index < maximum:
            field = (
                f"{group}[{index}].basisClaimIds"
                if (len(loc) > 4 and loc[4] in {"basisClaimIds", "basis_claim_ids"})
                else f"{group}[{index}]"
            )
    return (audience, field) if audience is not None and field is not None else None


def collect_report_validation_issues(
    error: Exception,
    schema: dict,
    *,
    stage: str | None = None,
    scope: ReportValidationScope | None = None,
) -> tuple[ReportValidationIssue, ...]:
    """Adapt every guard to one typed format without inventing a location.

    Preserve repeated violations and add explicit unlocated entries when a
    legacy guard supplies fewer locations than failures. This prevents a repair
    from accidentally preserving a field that another guard has rejected.
    """
    if scope is not None and type(scope) is not ReportValidationScope:
        raise TypeError("A server-created ReportValidationScope is required")
    supplied = getattr(error, "validation_issues", ())
    supplied = supplied if type(supplied) is tuple else ()
    issues = [item for item in supplied if _internal_issue(item, stage)]
    if isinstance(error, ValidationError):
        entries = error.errors(include_input=False, include_context=False, include_url=False)
        if len(issues) >= len(entries):
            return tuple(issues)
        # Pydantic locations are keys, not authority: resolve them against the
        # original server schema. Never read item['input'], ctx or msg.
        issues = []
        for entry in entries:
            kind = entry["type"]
            error_kind = kind if kind in _LENGTH_KINDS else "report_output_shape"
            rule_id = None if kind in _LENGTH_KINDS else _shape_rule(kind)
            location = _map_schema_location(schema, entry["loc"]) or _reduce_schema_location(
                schema, entry["loc"]
            )
            if scope is not None:
                issues.append(
                    ReportValidationIssue(
                        scope.audience, scope.field, error_kind, scope.claim_ids, rule_id
                    )
                )
            elif location is not None:
                issues.append(ReportValidationIssue(*location, error_kind, (), rule_id))
            else:
                issues.append(_unlocated(error_kind, rule_id=rule_id))
        return tuple(issues)
    if isinstance(error, OutputValidationError):
        kinds = tuple(
            kind if kind in REPORT_VALIDATION_ERROR_KINDS else "report_output_unlocated"
            for kind in error.error_kinds
        ) or ("report_output_unlocated",)
    elif isinstance(error, JsonObjectParseError):
        kinds = ("report_output_parse",)
    else:
        kinds = ("report_output_unlocated",)
    remaining = list(kinds)
    for issue in issues:
        if issue.error_kind in remaining:
            remaining.remove(issue.error_kind)
    for kind in remaining:
        issues.append(
            ReportValidationIssue(scope.audience, scope.field, kind, scope.claim_ids)
            if scope is not None
            else _unlocated(kind)
        )
    return tuple(issues)


def attach_report_validation_diagnostics(
    error: Exception, schema: dict, *, stage: str | None = None
) -> tuple[ReportValidationIssue, ...]:
    """Call at report validation boundaries before repair selection and logging."""
    issues = collect_report_validation_issues(error, schema, stage=stage)
    error.validation_issues = issues
    return issues
