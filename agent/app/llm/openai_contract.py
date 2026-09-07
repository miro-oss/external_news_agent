"""OpenAI wire constraints; public validators still decide whether output is usable."""

import json
from copy import deepcopy
from dataclasses import dataclass
from typing import Any, get_args

from app.schemas.analyze import Audience

AUDIENCE_ORDER = get_args(Audience)
_AXES = ("customerMove", "dealSignal", "competitorThreat", "industryShift")
ANALYZE_WIRE_VERSION = "analyze.ko.v7+perspective.ko.v1+sensitivity.ko.v2"
EXPLORE_WIRE_VERSION = "explore.ko.v2"
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
""".strip()


@dataclass(frozen=True)
class OpenAIOutputContract:
    schema: dict[str, Any]
    wrapped: bool
    analysis: bool

    def instructions(self, original: str) -> str:
        if self.analysis:
            original += "\n\n" + _ANALYZE_INSTRUCTION
        if self.wrapped:
            original += (
                "\n\n출력은 result 키 하나를 가진 객체로 감싸세요. 제안은 result 안에 넣으세요."
            )
        return original

    def public_text(self, raw: str) -> str:
        if not (self.wrapped or self.analysis):
            return raw
        try:
            value = json.loads(raw)
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
        return json.dumps(value, ensure_ascii=False)


def output_contract(response_schema: dict[str, Any]) -> OpenAIOutputContract:
    schema = deepcopy(response_schema)
    analysis = schema.get("title") == "AnalyzeOutput"
    if analysis:
        _constrain_analysis(schema)
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
    return OpenAIOutputContract(schema=schema, wrapped=wrapped, analysis=analysis)


def _object(properties: dict[str, Any]) -> dict[str, Any]:
    return {
        "type": "object",
        "properties": properties,
        "required": list(properties),
        "additionalProperties": False,
    }


def _constrain_analysis(schema: dict[str, Any]) -> None:
    definitions = schema["$defs"]
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
