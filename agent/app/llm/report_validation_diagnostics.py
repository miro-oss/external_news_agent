"""Bounded server-owned REDUCE diagnostics; never provider prose or input values."""

import re
from dataclasses import dataclass

from pydantic import ValidationError

_AUDIENCES = frozenset({"CHIP_MAKER", "EQUIPMENT_MAKER", "MARKET_INVESTOR", "IT_INFRA"})
_FIELD = re.compile(
    r"(?:headline|overview|implications|watchItems|"
    r"overview\[[0-2]\]\.(?:text|assumption|basisClaimIds)|"
    r"implications\[[0-4]\]\.(?:text|mechanism|assumption|falsifiedBy|basisClaimIds)|"
    r"watchItems\[[0-4]\]\.(?:topic|indicator|trigger|basisClaimIds))"
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
    }
)


@dataclass(frozen=True, slots=True)
class ReportValidationIssue:
    audience: str
    field: str
    error_kind: str
    claim_ids: tuple[str, ...]


def _safe_issue(issue: object) -> bool:
    return (
        type(issue) is ReportValidationIssue
        and type(issue.audience) is str
        and issue.audience in _AUDIENCES
        and type(issue.field) is str
        and _FIELD.fullmatch(issue.field) is not None
        and type(issue.error_kind) is str
        and issue.error_kind in REPORT_VALIDATION_ERROR_KINDS | _LENGTH_KINDS
        and type(issue.claim_ids) is tuple
        and all(type(ref) is str and _CLAIM_ID.fullmatch(ref) for ref in issue.claim_ids)
    )


def report_validation_issue_details(error: Exception, schema: dict) -> dict[str, object]:
    """Project typed diagnostics only, including an explicit bounded-output marker."""
    supplied = getattr(error, "validation_issues", None)
    if supplied is None and isinstance(error, ValidationError):
        supplied = pydantic_reduce_issues(error, schema)
    if type(supplied) is not tuple:
        return {}
    safe = tuple(issue for issue in supplied if _safe_issue(issue))
    if not safe:
        return {}
    unique = tuple(dict.fromkeys(safe))
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
    return field if _FIELD.fullmatch(field) else None


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
