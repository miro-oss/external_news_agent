"""Diagnostic-only REDUCE projection for collecting mixed shape/semantic failures.

The returned candidate must never become an accepted response or repair base.
Only the original wire object is repaired. Invalid units are absent from the
candidate; their intact scalar fields remain separate diagnostic inputs. Any
cross-unit diagnosis that depends on removal is unlocalized and deliberately
requires whole-output repair.
"""

import re
from copy import deepcopy
from dataclasses import dataclass, replace

from pydantic import ValidationError

from app.llm.report_validation_diagnostics import ReportValidationIssue
from app.schemas.report_insight import (
    ReportInsightImplication,
    ReportInsightOverview,
    ReportInsightReduceOutput,
    ReportInsightWatchItem,
)
from app.schemas.report_insight_source_quotes import (
    SOURCE_QUOTE_ID_PATTERN,
    ReportInsightStructuredReduceOutput,
)

_GROUPS = frozenset({"overview", "implications", "watchItems"})
_RECORD_FIELDS = _GROUPS | {"audience", "headline"}
_FIELD = re.compile(
    r"(?P<group>overview|implications|watchItems)(?:\[(?P<index>[0-4])\]"
    r"(?P<suffix>\.(?:text|assumption|mechanism|falsifiedBy|topic|indicator|trigger|basisClaimIds))?)?"
)
_SHAPE_KINDS = frozenset(
    {"report_output_shape", "string_too_long", "string_too_short", "too_long", "too_short"}
)
_HEADLINE_PLACEHOLDER = "업무 연결 조건을 확인한다."
_ALIASES = {"watch_items": "watchItems"}
_UNIT_MODELS = {
    "overview": ReportInsightOverview,
    "implications": ReportInsightImplication,
    "watchItems": ReportInsightWatchItem,
}


@dataclass(frozen=True, slots=True)
class ShapeOwnedUnit:
    audience: str
    group: str
    index: int | None

    def covers(self, other: "ShapeOwnedUnit") -> bool:
        return (
            self.audience == other.audience
            and self.group == other.group
            and (self.index is None or self.index == other.index)
        )


@dataclass(frozen=True, slots=True)
class IncompleteReduceUnit:
    """Original scalar fields from an authenticated shape-invalid unit.

    These values are diagnostic inputs, never a substitute accepted object.
    Missing/malformed references remain unknown instead of borrowing evidence.
    """

    audience: str
    group: str
    index: int
    prose: tuple[tuple[str, str, int], ...]
    claim_ids: tuple[str, ...] | None
    source_quotes: tuple[tuple[str, str | None], ...] | None = None


def _incomplete_unit(audience, group, index, raw, *, structured=False):
    if type(raw) is not dict:
        return None
    prose = []
    for name, field in _UNIT_MODELS[group].model_fields.items():
        if field.annotation is not str:
            continue
        keys = {name, field.alias} & raw.keys()
        if len(keys) != 1:
            continue
        value = raw[next(iter(keys))]
        limit = next(item.max_length for item in field.metadata if hasattr(item, "max_length"))
        if type(value) is str and value.strip() and len(value.strip()) <= limit:
            prose.append((field.alias, value.strip(), limit))
    reference_keys = {"basisClaimIds", "basis_claim_ids"} & raw.keys()
    refs = raw[next(iter(reference_keys))] if len(reference_keys) == 1 else None
    claim_ids = (
        tuple(dict.fromkeys(refs))
        if type(refs) is list and refs and all(type(ref) is str and ref for ref in refs)
        else None
    )
    selections = None
    selection_keys = {"sourceQuotes", "source_quotes"} & raw.keys()
    if structured and len(selection_keys) == 1:
        supplied = raw[next(iter(selection_keys))]
        if type(supplied) is dict:
            selections = tuple(
                (name, selected)
                for name, selected in supplied.items()
                if name
                in {
                    field.alias
                    for field in _UNIT_MODELS[group].model_fields.values()
                    if field.annotation is str
                }
                and (
                    selected is None
                    or (type(selected) is str and re.fullmatch(SOURCE_QUOTE_ID_PATTERN, selected))
                )
            )
    return IncompleteReduceUnit(audience, group, index, tuple(prose), claim_ids, selections)


@dataclass(frozen=True, slots=True)
class ReduceShapeScan:
    candidate: ReportInsightReduceOutput | ReportInsightStructuredReduceOutput
    original_indexes: tuple[tuple[str, str, int, int], ...]
    shape_owned: tuple[ShapeOwnedUnit, ...]
    incomplete_units: tuple[IncompleteReduceUnit, ...] = ()

    def remap(
        self, issues: tuple[ReportValidationIssue, ...]
    ) -> tuple[ReportValidationIssue, ...] | None:
        """Map untouched units back to original indexes, or reject unsafe scope.

        A group-level diagnosis after a member was removed is not attributable
        to an original intact group. Returning None makes that uncertainty force
        full repair, rather than authorizing arbitrary edits of sibling units.
        """
        if type(issues) is not tuple:
            return None
        indexes = {
            (audience, group, candidate): original
            for audience, group, candidate, original in self.original_indexes
        }
        audiences = {item.audience for item in self.candidate.insights}
        remapped = []
        for issue in issues:
            if (
                type(issue) is not ReportValidationIssue
                or not issue.located
                or issue.audience not in audiences
            ):
                return None
            unit = _issue_unit(issue)
            if unit is None:
                return None
            if unit.index is None:
                if any(
                    owned.audience == unit.audience and owned.group == unit.group
                    for owned in self.shape_owned
                ):
                    return None
                remapped.append(issue)
                continue
            original = indexes.get((unit.audience, unit.group, unit.index))
            if original is None:
                return None
            match = _FIELD.fullmatch(issue.field)
            remapped.append(
                replace(issue, field=f"{unit.group}[{original}]{match.group('suffix') or ''}")
            )
        return tuple(remapped)


def _issue_unit(issue: ReportValidationIssue) -> ShapeOwnedUnit | None:
    if issue.field == "headline":
        return ShapeOwnedUnit(issue.audience, "headline", None)
    match = _FIELD.fullmatch(issue.field or "")
    if match is None:
        return None
    group = match.group("group")
    index = int(match.group("index")) if match.group("index") is not None else None
    if group == "overview" and index is not None and index >= 3:
        return None
    return ShapeOwnedUnit(issue.audience, group, index)


def _error_unit(loc: tuple, audience_order: list[str]) -> ShapeOwnedUnit | None:
    if (
        len(loc) < 3
        or loc[0] != "insights"
        or type(loc[1]) is not int
        or not 0 <= loc[1] < len(audience_order)
    ):
        return None
    audience = audience_order[loc[1]]
    group = _ALIASES.get(loc[2], loc[2])
    if group == "sourceQuotes" and (len(loc) == 3 or (len(loc) == 4 and loc[3] == "headline")):
        return ShapeOwnedUnit(audience, "headline", None)
    if group == "headline" and len(loc) == 3:
        return ShapeOwnedUnit(audience, group, None)
    if group not in _GROUPS:
        return None
    if len(loc) == 3:
        return ShapeOwnedUnit(audience, group, None)
    if type(loc[3]) is int and 0 <= loc[3] < (3 if group == "overview" else 5):
        return ShapeOwnedUnit(audience, group, loc[3])
    return None


def build_reduce_shape_scan(
    raw: dict,
    shape_issues: tuple[ReportValidationIssue, ...],
    audiences,
    *,
    structured: bool = False,
) -> ReduceShapeScan | None:
    """Remove only units authenticated both by schema issues and actual errors.

    Root shape, audience membership/uniqueness, unlocated failures and stale
    diagnostics are not repairable through this projection. The caller must run
    all semantic/template guards on candidate, remap their issues, repair the
    untouched original wire and validate the entire resulting output again.
    """
    if (
        type(raw) is not dict
        or set(raw) != {"insights"}
        or type(raw["insights"]) is not list
        or type(shape_issues) is not tuple
        or not shape_issues
    ):
        return None
    order = []
    for insight in raw["insights"]:
        if (
            type(insight) is not dict
            or not set(insight)
            <= (_RECORD_FIELDS | {"sourceQuotes"} if structured else _RECORD_FIELDS)
            or type(insight.get("audience")) is not str
        ):
            return None
        order.append(insight["audience"])
    if len(order) != len(set(order)) or set(order) != set(audiences):
        return None
    owned = []
    for issue in shape_issues:
        if (
            type(issue) is not ReportValidationIssue
            or not issue.located
            or issue.audience not in order
            or issue.error_kind not in _SHAPE_KINDS
        ):
            return None
        unit = _issue_unit(issue)
        if unit is None:
            return None
        owned.append(unit)
    owned = list(dict.fromkeys(owned))
    model = ReportInsightStructuredReduceOutput if structured else ReportInsightReduceOutput
    try:
        model.model_validate(raw)
    except ValidationError as error:
        actual = [
            _error_unit(entry["loc"], order)
            for entry in error.errors(include_input=False, include_context=False, include_url=False)
        ]
    else:
        return None
    if (
        not actual
        or any(unit is None for unit in actual)
        or any(not any(mark.covers(unit) for mark in owned) for unit in actual)
        or any(not any(mark.covers(unit) for unit in actual) for mark in owned)
    ):
        return None
    candidate = deepcopy(raw)
    indexes = []
    incomplete = []
    for insight in candidate["insights"]:
        audience = insight["audience"]
        if ShapeOwnedUnit(audience, "headline", None) in owned:
            insight["headline"] = _HEADLINE_PLACEHOLDER
            if structured:
                insight["sourceQuotes"] = {"headline": None}
        for group in _GROUPS:
            if ShapeOwnedUnit(audience, group, None) in owned:
                insight[group] = []
                continue
            entries = insight.get(group)
            if type(entries) is not list:
                return None
            kept = []
            for index, entry in enumerate(entries):
                if ShapeOwnedUnit(audience, group, index) in owned:
                    unit = _incomplete_unit(audience, group, index, entry, structured=structured)
                    if unit is not None:
                        incomplete.append(unit)
                    continue
                indexes.append((audience, group, len(kept), index))
                kept.append(entry)
            insight[group] = kept
    try:
        parsed = model.model_validate(candidate)
    except ValidationError:
        return None
    return ReduceShapeScan(parsed, tuple(indexes), tuple(owned), tuple(incomplete))
