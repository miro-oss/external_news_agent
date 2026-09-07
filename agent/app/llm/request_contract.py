"""Request-bound OpenAI schemas. Public semantic validators remain authoritative."""

from copy import deepcopy
from typing import Any

from app.llm.openai_contract import _object
from app.schemas.analyze import (
    AnalyzeOutput,
    AnalyzeRequest,
    PreviousFindingBullet,
    SelfCritiqueOutput,
)
from app.schemas.evidence import EvidenceBatchOutput, EvidenceClaim
from app.schemas.report import ReportOutput, ReportRequest


def _integer_choices(ids: list[int]) -> dict[str, Any]:
    # Exact contiguous ranges avoid the provider's 1,000-enum-value budget even
    # for 50 evidence claims with 50 distinct, non-contiguous sentence IDs each.
    ranges: list[dict[str, Any]] = []
    for value in sorted(set(ids)):
        if ranges and ranges[-1]["maximum"] + 1 == value:
            ranges[-1]["maximum"] = value
        else:
            ranges.append({"type": "integer", "minimum": value, "maximum": value})
    if not ranges:
        raise ValueError("An ID choice must contain at least one value")
    return ranges[0] if len(ranges) == 1 else {"anyOf": ranges}


def _array_choices(array: dict[str, Any], ids: list[int]) -> None:
    if ids:
        array["items"] = _integer_choices(ids)
    else:
        array["maxItems"] = 0


def analysis_schema(
    request: AnalyzeRequest,
    sentence_count: int,
    promotion_eligible_ids: set[int],
) -> dict[str, Any]:
    schema = AnalyzeOutput.model_json_schema(by_alias=True)
    if request.plan != "FREE":
        return schema
    definitions = schema["$defs"]
    for name in ("EvidenceBullet", "SensitivityAxis", "PerspectiveTag"):
        definitions[name]["properties"]["evidenceSentenceIds"]["items"]["maximum"] = sentence_count
    known_ids = [request.article.id, *(member.id for member in request.issue_members)]
    definitions["SoleSourceObservation"]["properties"]["articleId"] = _integer_choices(known_ids)
    _array_choices(definitions["ConflictObservation"]["properties"]["articleIds"], known_ids)
    _array_choices(schema["properties"]["promoteCandidates"], sorted(promotion_eligible_ids))
    if not request.issue_members:
        for prop in definitions["CrossSource"]["properties"].values():
            prop["maxItems"] = 0
    return schema


def report_schema(request: ReportRequest) -> dict[str, Any]:
    schema = ReportOutput.model_json_schema(by_alias=True)
    if request.plan == "FREE" and request.findings:
        definitions = schema["$defs"]
        definitions["AllowedFindingId"] = _integer_choices(
            [finding.id for finding in request.findings]
        )
        for name in ("ImportantEvent", "WatchItem"):
            definitions[name]["properties"]["sourceFindingIds"]["items"] = {
                "$ref": "#/$defs/AllowedFindingId"
            }
    return schema


def critique_schema(
    claim_id: str,
    original: PreviousFindingBullet,
    *,
    openai: bool,
) -> dict[str, Any]:
    schema = SelfCritiqueOutput.model_json_schema(by_alias=True)
    if not openai:
        return schema
    props = schema["$defs"]["SelfCritiqueRevision"]["properties"]
    props["claimId"] = {"type": "string", "const": claim_id}
    _array_choices(props["evidenceSentenceIds"], original.evidence_sentence_ids)
    schema["$defs"]["SelfCritiqueRevision"] = {
        "anyOf": [
            _object(
                {
                    **deepcopy(props),
                    "action": {"type": "string", "enum": ["KEEP", "REVISE"]},
                    "groundedness": {"type": "string", "enum": ["grounded", "weak"]},
                    "evidenceSentenceIds": {
                        **deepcopy(props["evidenceSentenceIds"]),
                        "minItems": 1,
                    },
                }
            ),
            _object(
                {
                    **deepcopy(props),
                    "action": {"type": "string", "const": "REJECT"},
                    "groundedness": {"type": "string", "const": "ungrounded"},
                    "confidence": {"type": "number", "const": 0},
                    "evidenceSentenceIds": {
                        **deepcopy(props["evidenceSentenceIds"]),
                        "maxItems": 0,
                    },
                }
            ),
        ]
    }
    return schema


def evidence_schema(claims: list[EvidenceClaim], *, openai: bool) -> dict[str, Any]:
    schema = EvidenceBatchOutput.model_json_schema(by_alias=True)
    if not openai:
        return schema
    props = schema["$defs"].pop("EvidenceResult")["properties"]
    entries = {}
    for index, claim in enumerate(claims):
        ids_name = f"EvidenceIds{index}"
        schema["$defs"][ids_name] = _integer_choices([sentence.id for sentence in claim.sentences])
        common = {**deepcopy(props), "claimId": {"type": "string", "const": claim.claim_id}}
        refs = {"type": "array", "items": {"$ref": f"#/$defs/{ids_name}"}}
        entries[f"claim{index}"] = {
            "anyOf": [
                _object(
                    {
                        **common,
                        "status": {"type": "string", "enum": ["grounded", "weak"]},
                        "acceptedSentenceIds": {**refs, "minItems": 1},
                    }
                ),
                _object(
                    {
                        **common,
                        "status": {"type": "string", "const": "ungrounded"},
                        "acceptedSentenceIds": {**refs, "maxItems": 0},
                    }
                ),
            ]
        }
    schema["properties"]["results"] = _object(entries)
    return schema
