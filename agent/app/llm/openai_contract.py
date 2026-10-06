"""OpenAI wire constraints; public validators still decide whether output is usable."""

import json
import re
from copy import deepcopy
from dataclasses import dataclass
from typing import Any, get_args

from app.schemas.analyze import Audience

AUDIENCE_ORDER = get_args(Audience)
_AXES = ("customerMove", "dealSignal", "competitorThreat", "industryShift")
ANALYZE_WIRE_VERSION = "analyze.ko.v11+perspective.ko.v1+sensitivity.ko.v2"
EXPLORE_WIRE_VERSION = "explore.ko.v2"
REPORT_WIRE_VERSION = "report.ko.v1.5"
EVIDENCE_WIRE_VERSION = "evidence.ko.v3"
SELF_CRITIQUE_WIRE_VERSION = "self-critique.ko.v3"
_ANALYZE_INSTRUCTION = """
OpenAI 출력 형식 보충:
- perspectiveTags는 배열이 아니라 CHIP_MAKER, EQUIPMENT_MAKER, MARKET_INVESTOR,
  IT_INFRA 네 키를 각각 한 번 갖는 객체다. 각 값에는 relevance, hook,
  evidenceSentenceIds만 넣는다. audience 필드와 배열 변환은 서버가 담당한다.
- 관련 근거가 없으면 relevance=none, hook=null, evidenceSentenceIds=[]다.
  관련성이 있으면 실제 근거 번호를 하나 이상 연결하고 hook을 작성한다.
- 민감도 0점도 판정이며 실제 근거 문장이 필요하다. 판정 불가는 null과 빈 배열이다.
  적어도 한 축은 원문에 근거해 판정해야 한다. 계약을 채우기 위한 근거·신호는 만들지 않는다.
- bullet text에는 목록 번호(1., 2., 3.)나 근거 번호([1])를 붙이지 않는다.
  근거 번호는 evidenceSentenceIds에만 기록한다. 기사에 나오는 실제 숫자는 그대로 보존한다.
- OPINION은 원문 발언자를 attributedTo에 명시하고, FACT/FORECAST는 null을 사용한다.
""".strip()


@dataclass(frozen=True)
class OpenAIOutputContract:
    schema: dict[str, Any]
    wrapped: bool
    analysis: bool
    evidence_keys: tuple[str, ...] = ()
    promotion_conflict: bool = False
    report_change_keys: tuple[str, ...] = ()
    report_insight_keys: tuple[tuple[str, int], ...] = ()

    def instructions(self, original: str) -> str:
        if self.analysis:
            original += "\n\n" + _ANALYZE_INSTRUCTION
        if self.promotion_conflict:
            original += (
                "\n\nOpenAI 승격 제안은 promoteCandidates 배열 대신 promotionConflict를 사용한다. "
                "실제 충돌로 승격할 필요가 없으면 null이다. 필요하면 사전 허용된 articleId 하나, "
                "그 기사와 실제로 충돌하는 otherArticleIds, 구체적인 충돌 text를 함께 쓴다. "
                "otherArticleIds에 선택한 articleId 자신을 넣지 않는다. 서버가 이 관측을 "
                "crossSource.conflicts와 promoteCandidates로 변환한다. "
                "동일 충돌을 중복 작성하지 않는다. "
                "승격을 위해 존재하지 않는 충돌이나 근거를 만들지 않는다."
            )
        if self.evidence_keys:
            original += (
                "\n\nOpenAI results는 배열 대신 JSON Schema의 항목 키를 가진 객체다. "
                "각 항목의 고정 claimId와 그 주장에 제공된 문장 번호만 사용한다. "
                "근거가 없으면 ungrounded와 빈 acceptedSentenceIds로 판정한다."
            )
        if self.report_change_keys:
            original += (
                "\n\nOpenAI REPORT_CHANGES items는 배열 대신 JSON Schema의 candidate 키를 "
                "가진 객체다. 고정 candidateId를 각각 정확히 한 번 사용한다. "
                "previousClaimIds와 currentClaimIds에는 각 방향에 허용된 ID만 넣는다."
            )
        if self.report_insight_keys:
            original += (
                "\n\nOpenAI REPORT_INSIGHT MAP의 insights[].assessments는 배열 대신 "
                "JSON Schema가 정한 finding<ID> 키를 모두 갖는 객체다. 각 키의 고정 "
                "findingId와 그 finding에 허용된 basisClaimIds만 사용한다. 모든 키를 "
                "정확히 한 번 반환하고 reason과 axes를 작성한다. 근거가 없는 finding은 "
                "고정 판단 보류 이유, 빈 basisClaimIds와 모든 축 null을 유지한다. "
                "서버가 입력 finding 순서대로 assessments 배열로 변환한다."
            )
        if self.wrapped:
            original += (
                "\n\n출력은 result 키 하나를 가진 객체로 감싸세요. 제안은 result 안에 넣으세요."
            )
        return original

    def public_text(self, raw: str) -> str:
        if not (
            self.wrapped
            or self.analysis
            or self.evidence_keys
            or self.report_change_keys
            or self.report_insight_keys
        ):
            return raw
        try:
            value = json.loads(
                raw,
                object_pairs_hook=_unique_json_object if self.report_insight_keys else None,
            )
        except (ValueError, TypeError):
            # Keep malformed/truncated output for the existing bounded repair flow.
            return raw
        if self.wrapped:
            if not isinstance(value, dict) or set(value) != {"result"}:
                return raw
            value = value["result"]
        if self.analysis and isinstance(value, dict):
            tags = value.get("perspectiveTags")
            if (
                isinstance(tags, dict)
                and set(tags) == set(AUDIENCE_ORDER)
                and all(isinstance(tag, dict) and "audience" not in tag for tag in tags.values())
            ):
                value["perspectiveTags"] = [
                    {"audience": audience, **tags[audience]} for audience in AUDIENCE_ORDER
                ]
            if self.promotion_conflict:
                _public_promotion(value)
        if self.evidence_keys and isinstance(value, dict):
            results = value.get("results")
            if isinstance(results, dict) and set(results) == set(self.evidence_keys):
                value["results"] = [results[key] for key in self.evidence_keys]
        if self.report_change_keys and isinstance(value, dict):
            items = value.get("items")
            if isinstance(items, dict) and set(items) == set(self.report_change_keys):
                value["items"] = [items[key] for key in self.report_change_keys]
        if self.report_insight_keys and not _public_report_insight_map(
            value, self.report_insight_keys
        ):
            return raw
        return json.dumps(value, ensure_ascii=False)


def output_contract(response_schema: dict[str, Any]) -> OpenAIOutputContract:
    schema = deepcopy(response_schema)
    analysis = schema.get("title") == "AnalyzeOutput"
    promotion_conflict = analysis and "promotionConflict" in schema.get("properties", {})
    if analysis:
        _constrain_analysis(schema)
        _preserve_string_lengths(schema)
    if schema.get("title") in {"ReportInsightReduceOutput", "ReportInsightReduceRepair"}:
        # Strict SDK conversion otherwise drops the public nonempty prose
        # bounds, allowing empty assumptions even during a bounded repair.
        _preserve_string_lengths(schema)
        _drop_redundant_fixed_string_constraints(schema)
    if schema.get("title") == "ReportAssessmentDraft":
        # Partial repair keeps the original validation closure, but its wire
        # schema only needs definitions reachable from the failed records.
        # Unreachable records otherwise dominate a one-finding repair request.
        _retain_referenced_definitions(schema)
        # Preserve bounds on generated prose through the SDK, including nested
        # decision conditions. Exact source choices and category enums stay intact.
        pending = [schema]
        while pending:
            node = pending.pop()
            if isinstance(node, list):
                pending.extend(node)
            elif isinstance(node, dict):
                for field in ("reason", "condition"):
                    _preserve_string_lengths(node.get("properties", {}).get(field, {}))
                pending.extend(node.values())
        _drop_redundant_fixed_string_constraints(schema)
    results = schema.get("properties", {}).get("results", {})
    evidence_keys = (
        tuple(results["properties"])
        if schema.get("title") == "EvidenceBatchOutput" and results.get("type") == "object"
        else ()
    )
    changes = schema.get("properties", {}).get("items", {})
    report_change_keys = (
        tuple(changes["properties"])
        if schema.get("title") == "ReportChangesOutput" and changes.get("type") == "object"
        else ()
    )
    report_insight_keys = (
        _constrain_report_insight_map(schema)
        if schema.get("title") == "ReportInsightMapOutput"
        else ()
    )
    wrapped = schema.get("type") != "object"
    if wrapped:
        # Keep #/$defs references at the document root.
        definitions = schema.pop("$defs", {})
        schema = {
            "title": response_schema.get("title", "output"),
            "type": "object",
            "properties": {"result": schema},
            "required": ["result"],
            "additionalProperties": False,
            "$defs": definitions,
        }
    return OpenAIOutputContract(
        schema=schema,
        wrapped=wrapped,
        analysis=analysis,
        evidence_keys=evidence_keys,
        promotion_conflict=promotion_conflict,
        report_change_keys=report_change_keys,
        report_insight_keys=report_insight_keys,
    )


def _drop_redundant_fixed_string_constraints(schema: dict[str, Any]) -> None:
    """Keep exact repair values while avoiding redundant decoder intersections."""
    pending: list[Any] = [schema]
    while pending:
        node = pending.pop()
        if isinstance(node, list):
            pending.extend(node)
        elif isinstance(node, dict):
            value = node.get("const")
            minimum, maximum = node.get("minLength", 0), node.get("maxLength")
            if (
                node.get("type") == "string"
                and isinstance(value, str)
                and type(minimum) is int
                and minimum >= 0
                and len(value) >= minimum
                and (maximum is None or type(maximum) is int and len(value) <= maximum)
            ):
                choices = node.get("enum")
                if (
                    isinstance(choices, list)
                    and all(isinstance(choice, str) for choice in choices)
                    and value in choices
                ):
                    node.pop("enum")
                if _fixed_string_matches_trimmed_pattern(value, node.get("pattern")):
                    node.pop("pattern")
            # These values are JSON data, not nested schemas.
            pending.extend(
                child
                for key, child in node.items()
                if key not in {"const", "enum", "default", "examples"}
            )


def _fixed_string_matches_trimmed_pattern(value: str, pattern: Any) -> bool:
    # Only recognize our own length/trim patterns. Preserve other regexes and
    # Unicode cases where Python and provider regex character rules can differ.
    if (
        not isinstance(pattern, str)
        or not value
        or value[0].isspace()
        or value[-1].isspace()
        or value[0] == "\ufeff"
        or value[-1] == "\ufeff"
        or any(ord(char) > 0xFFFF or 0xD800 <= ord(char) <= 0xDFFF for char in value)
    ):
        return False
    if pattern == r"^\S$":
        return len(value) == 1
    optional = re.fullmatch(
        re.escape(r"^\S(?:[\s\S]{0,") + r"([0-9]*)" + re.escape(r"}\S)?$"), pattern
    )
    if optional is not None:
        upper = optional.group(1)
        return not upper or len(value) <= int(upper) + 2
    required = re.fullmatch(
        re.escape(r"^\S[\s\S]{") + r"([0-9]+),([0-9]*)" + re.escape(r"}\S$"), pattern
    )
    if required is None:
        return False
    lower, upper = required.groups()
    return len(value) >= int(lower) + 2 and (not upper or len(value) <= int(upper) + 2)


def _retain_referenced_definitions(schema: dict[str, Any]) -> None:
    """Drop unreachable root definitions without altering any reachable schema.

    Report drafts use root-local references only. Preserve the original schema
    if a future contract introduces other reference scopes or unresolved refs.
    """
    definitions = schema.get("$defs")
    if not isinstance(definitions, dict):
        return
    pending = [{key: value for key, value in schema.items() if key != "$defs"}]
    used: set[str] = set()
    while pending:
        node = pending.pop()
        if isinstance(node, list):
            pending.extend(node)
        elif isinstance(node, dict):
            if {"$id", "$anchor", "$dynamicRef", "$recursiveRef"} & node.keys():
                return
            if "$ref" in node:
                reference = node["$ref"]
                if not isinstance(reference, str) or not reference.startswith("#/$defs/"):
                    return
                name = reference.removeprefix("#/$defs/")
                if "/" in name:
                    return
                name = name.replace("~1", "/").replace("~0", "~")
                if name not in definitions:
                    return
                if name not in used:
                    used.add(name)
                    pending.append(definitions[name])
            pending.extend(value for key, value in node.items() if key != "$defs")
    schema["$defs"] = {name: value for name, value in definitions.items() if name in used}


def _constrain_report_insight_map(schema: dict[str, Any]) -> tuple[tuple[str, int], ...]:
    definitions = schema.get("$defs", {})
    branches = definitions.get("ReportInsightAssessment", {}).get("anyOf", [])
    entries = {}
    keys = []
    for branch in branches:
        finding_id = branch.get("properties", {}).get("findingId", {}).get("const")
        if type(finding_id) is not int or finding_id <= 0:
            return ()
        key = f"finding{finding_id}"
        if key in entries:
            return ()
        entries[key] = deepcopy(branch)
        keys.append((key, finding_id))
    if not entries:
        # Generic, unbound model schemas retain their public array contract.
        return ()
    definitions["ReportInsightMapAudience"]["properties"]["assessments"] = _object(entries)
    del definitions["ReportInsightAssessment"]
    return tuple(keys)


def _unique_json_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError("Duplicate JSON object key")
        value[key] = item
    return value


def _public_report_insight_map(value: Any, keys: tuple[tuple[str, int], ...]) -> bool:
    insights = value.get("insights") if isinstance(value, dict) else None
    if not isinstance(insights, list) or not insights:
        return False
    expected = {key for key, _ in keys}
    for insight in insights:
        assessments = insight.get("assessments") if isinstance(insight, dict) else None
        if not isinstance(assessments, dict) or set(assessments) != expected:
            return False
        for key, finding_id in keys:
            assessment = assessments[key]
            if (
                not isinstance(assessment, dict)
                or type(assessment.get("findingId")) is not int
                or assessment["findingId"] != finding_id
            ):
                return False
    # Convert only after every audience passed: malformed output is never repaired here.
    for insight in insights:
        insight["assessments"] = [insight["assessments"][key] for key, _ in keys]
    return True


def _public_promotion(value: dict[str, Any]) -> None:
    if "promotionConflict" not in value or "promoteCandidates" in value:
        return
    promotion = value["promotionConflict"]
    cross_source = value.get("crossSource")
    if not isinstance(cross_source, dict) or not isinstance(cross_source.get("conflicts"), list):
        return
    if promotion is None:
        value.pop("promotionConflict")
        value["promoteCandidates"] = []
        return
    if (
        not isinstance(promotion, dict)
        or set(promotion) != {"articleId", "otherArticleIds", "text"}
        or type(promotion["articleId"]) is not int
        or not isinstance(promotion["otherArticleIds"], list)
        or not promotion["otherArticleIds"]
        or any(type(item) is not int for item in promotion["otherArticleIds"])
        or not isinstance(promotion["text"], str)
    ):
        # Leave malformed data intact for the bounded repair and semantic validators.
        return
    value.pop("promotionConflict")
    value["promoteCandidates"] = [promotion["articleId"]]
    cross_source["conflicts"].append(
        {
            "articleIds": [promotion["articleId"], *promotion["otherArticleIds"]],
            "text": promotion["text"],
        }
    )


def _object(properties: dict[str, Any]) -> dict[str, Any]:
    return {
        "type": "object",
        "properties": properties,
        "required": list(properties),
        "additionalProperties": False,
    }


def _preserve_string_lengths(node: Any) -> None:
    # The SDK strips minLength/maxLength in strict mode. A supported pattern
    # preserves those bounds and requests trimmed strings, matching AgentModel.
    # Avoid lookarounds: the SDK also removes patterns that contain them.
    if isinstance(node, list):
        for child in node:
            _preserve_string_lengths(child)
    elif isinstance(node, dict):
        minimum = node.get("minLength", 0)
        maximum = node.get("maxLength")
        if node.get("type") == "string" and minimum >= 1 and "pattern" not in node:
            upper = "" if maximum is None else str(maximum - 2)
            if maximum == 1:
                pattern = r"^\S$"
            elif minimum == 1:
                pattern = rf"^\S(?:[\s\S]{{0,{upper}}}\S)?$"
            else:
                pattern = rf"^\S[\s\S]{{{minimum - 2},{upper}}}\S$"
            node["pattern"] = pattern
        for child in node.values():
            _preserve_string_lengths(child)


def _constrain_analysis(schema: dict[str, Any]) -> None:
    definitions = schema["$defs"]
    bullet = definitions["EvidenceBullet"]["properties"]
    attribution = next(item for item in bullet["attributedTo"]["anyOf"] if item["type"] == "string")
    definitions["EvidenceBullet"] = {
        "anyOf": [
            _object(
                {
                    **deepcopy(bullet),
                    "claimType": {"type": "string", "enum": kinds},
                    "attributedTo": deepcopy(speaker),
                }
            )
            for kinds, speaker in (
                (["FACT", "FORECAST"], {"type": "null"}),
                (["OPINION"], attribution),
            )
        ]
    }
    axis = definitions["SensitivityAxis"]["properties"]
    score = next(item for item in axis["score"]["anyOf"] if item["type"] == "integer")
    available = _object(
        {
            "score": deepcopy(score),
            "evidenceSentenceIds": {**deepcopy(axis["evidenceSentenceIds"]), "minItems": 1},
        }
    )
    unavailable = _object(
        {
            "score": {"type": "null"},
            "evidenceSentenceIds": {**deepcopy(axis["evidenceSentenceIds"]), "maxItems": 0},
        }
    )
    definitions["AvailableSensitivityAxis"] = available
    definitions["UnavailableSensitivityAxis"] = unavailable
    definitions["SensitivityAxis"] = {
        "anyOf": [
            {"$ref": "#/$defs/AvailableSensitivityAxis"},
            {"$ref": "#/$defs/UnavailableSensitivityAxis"},
        ]
    }
    # Four disjoint cases: the first available axis determines the branch.
    # This expresses 'at least one' without unsupported if/then/allOf/not.
    definitions["Sensitivity"] = {
        "anyOf": [
            _object(
                {
                    name: {
                        "$ref": "#/$defs/"
                        + (
                            "UnavailableSensitivityAxis"
                            if index < first
                            else "AvailableSensitivityAxis"
                            if index == first
                            else "SensitivityAxis"
                        )
                    }
                    for index, name in enumerate(_AXES)
                }
            )
            for first in range(len(_AXES))
        ]
    }
    tag = definitions["PerspectiveTag"]["properties"]
    definitions["OpenAIPerspectiveTag"] = {
        "anyOf": [
            _object(
                {
                    "relevance": {"type": "string", "enum": ["none"]},
                    "hook": {"type": "null"},
                    "evidenceSentenceIds": {**deepcopy(tag["evidenceSentenceIds"]), "maxItems": 0},
                }
            ),
            _object(
                {
                    "relevance": {"type": "string", "enum": ["low", "medium", "high"]},
                    "hook": {"type": "string", "minLength": 1},
                    "evidenceSentenceIds": {**deepcopy(tag["evidenceSentenceIds"]), "minItems": 1},
                }
            ),
        ]
    }
    schema["properties"]["perspectiveTags"] = _object(
        {audience: {"$ref": "#/$defs/OpenAIPerspectiveTag"} for audience in AUDIENCE_ORDER}
    )
    del definitions["PerspectiveTag"]
