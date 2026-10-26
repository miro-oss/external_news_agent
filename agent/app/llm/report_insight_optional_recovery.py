"""Omit only diagnosed optional units, then rerun the complete output contract."""

import re
from copy import deepcopy
from dataclasses import replace

from app.core.errors import StructuredOutputExhaustedError
from app.llm.prompt_data import prompt_json
from app.llm.report_insight_reduce_repair import ReduceRepairContext
from app.llm.report_validation_diagnostics import ReportValidationIssue

_OPTIONAL_UNIT = re.compile(
    r"(implications|watchItems)\[([0-4])\](?:\.(?:text|mechanism|assumption|falsifiedBy|"
    r"topic|indicator|trigger|basisClaimIds))?"
)


def recover_optional_reduce(error, *, prompt, schema, response, validate):
    """Return (validated output, removed locations) or None; never issue a call.

    Only structured_call's exhausted validation can enter this path. A provider,
    deadline, budget, unknown shape, required-field, or unlocated failure cannot
    be converted to success. The context contains the final fully merged wire,
    including validated siblings, rather than the last partial repair JSON.
    """
    if type(error) is not StructuredOutputExhaustedError or response is None:
        return None
    failure = error.__cause__
    context = getattr(failure, "repair_context", None)
    if (
        not isinstance(context, ReduceRepairContext)
        or context.prompt != prompt
        or context.schema != schema
        or context.issues != getattr(failure, "validation_issues", ())
        or not context.issues
        or not getattr(failure, "partial_repair_eligible", False)
        or response.truncated
    ):
        return None
    candidate = deepcopy(context.original)
    removed = {}
    try:
        rows = {row["audience"]: row for row in candidate["insights"]}
        if len(rows) != len(candidate["insights"]):
            return None
        for issue in context.issues:
            if type(issue) is not ReportValidationIssue or not issue.located:
                return None
            match = _OPTIONAL_UNIT.fullmatch(issue.field)
            if match is None:
                return None
            group, index = match[1], int(match[2])
            items = rows[issue.audience][group]
            if type(items) is not list or index >= len(items):
                return None
            removed.setdefault((issue.audience, group), set()).add(index)
        for (audience, group), indexes in removed.items():
            rows[audience][group] = [
                item for index, item in enumerate(rows[audience][group]) if index not in indexes
            ]
        output = validate(replace(response, text=prompt_json(candidate)))
    except (ValueError, KeyError, TypeError, IndexError):
        return None
    locations = tuple(
        (audience, f"{group}[{index}]")
        for (audience, group), indexes in removed.items()
        for index in sorted(indexes)
    )
    return output, locations
