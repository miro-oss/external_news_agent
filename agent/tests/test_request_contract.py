import json
from copy import deepcopy

import httpx2
import pytest
from jsonschema import Draft202012Validator, ValidationError
from pydantic_ai.profiles.openai import OpenAIJsonSchemaTransformer
from test_analyze_service import request as analyze_request
from test_evidence_service import provider_request as evidence_request
from test_openai_contract import wire_analysis
from test_openai_provider import client_for, response_body
from test_report_service import request as report_request
from test_report_service import valid_output as report_output
from test_self_critique_service import critique_output
from test_self_critique_service import request as critique_request

from app.core.config import Settings
from app.llm.evidence_service import EvidenceVerifierService
from app.llm.openai_contract import output_contract
from app.llm.openai_provider import OpenAIAnalyzeProvider
from app.llm.report_service import ReportWriterService
from app.llm.request_contract import (
    analysis_schema,
    critique_schema,
    evidence_schema,
    report_schema,
)
from app.llm.self_critique_service import ArticleSelfCritiqueService
from app.schemas.analyze import AnalyzeOutput, SelfCritiqueOutput
from app.schemas.evidence import EvidenceBatchOutput, EvidenceClaim
from app.schemas.report import ReportOutput


@pytest.mark.parametrize("claim_type,attribution", [("OPINION", None), ("FACT", "A사")])
def test_wire_requires_attribution_exactly_for_opinions(claim_type, attribution):
    contract = output_contract(AnalyzeOutput.model_json_schema(by_alias=True))
    validator = Draft202012Validator(
        OpenAIJsonSchemaTransformer(contract.schema, strict=True).walk()
    )
    wire = wire_analysis()
    wire["sections"][0]["bullets"][0].update(claimType=claim_type, attributedTo=attribution)
    with pytest.raises(ValidationError):
        validator.validate(wire)


def test_analysis_sentence_bounds_survive_wire_conversion():
    schema = analysis_schema(analyze_request(), 2, set())
    before = deepcopy(schema)
    contract = output_contract(schema)
    validator = Draft202012Validator(
        OpenAIJsonSchemaTransformer(contract.schema, strict=True).walk()
    )
    wire = wire_analysis()
    validator.validate(wire)
    for target in (
        wire["sections"][0]["bullets"][0],
        wire["perspectiveTags"]["CHIP_MAKER"],
        wire["classification"]["sensitivity"]["customerMove"],
    ):
        original = target["evidenceSentenceIds"]
        target["evidenceSentenceIds"] = [3]
        with pytest.raises(ValidationError):
            validator.validate(wire)
        target["evidenceSentenceIds"] = original
    assert schema == before


def validator_for(schema):
    return Draft202012Validator(
        OpenAIJsonSchemaTransformer(output_contract(schema).schema, strict=True).walk()
    )


def test_report_allows_actual_noncontiguous_finding_ids_only():
    request = report_request()
    request.findings.append(request.findings[0].model_copy(update={"id": 503}))
    validator = validator_for(report_schema(request))
    for finding_id in (501, 503):
        validator.validate(json.loads(report_output([finding_id])))
    for finding_id in (1, 502, 999):
        with pytest.raises(ValidationError):
            validator.validate(json.loads(report_output([finding_id])))
    request.plan = "PAID"
    assert report_schema(request) == ReportOutput.model_json_schema(by_alias=True)


def claims():
    return [
        EvidenceClaim.model_validate(
            {
                "claimId": f"claim-{index}",
                "claim": "설비 투자 계획이 확대됐다.",
                "claimType": "FACT",
                "attributedTo": None,
                "sentences": [
                    {"id": sid, "text": "생산 능력을 높이기 위해 팹 지출을 늘린다."} for sid in ids
                ],
            }
        )
        for index, ids in enumerate(([2, 8], [11, 13]))
    ]


def evidence_wire():
    return {
        "results": {
            f"claim{i}": {
                "claimId": claim.claim_id,
                "status": "weak",
                "reason": "관련 근거가 일부 있습니다.",
                "acceptedSentenceIds": [claim.sentences[0].id],
            }
            for i, claim in enumerate(claims())
        }
    }


@pytest.mark.parametrize(
    "case",
    ["missing", "extra", "duplicate-claim", "cross-claim", "gap", "empty", "ungrounded-refs"],
)
def test_evidence_rejects_missing_results_and_wrong_claim_references(case):
    schema = evidence_schema(claims(), openai=True)
    validator = validator_for(schema)
    wire = evidence_wire()
    validator.validate(wire)
    result = wire["results"]["claim0"]
    if case == "missing":
        del wire["results"]["claim1"]
    elif case == "extra":
        wire["results"]["claim2"] = result
    elif case == "duplicate-claim":
        result["claimId"] = "claim-1"
    elif case in ("cross-claim", "gap", "empty"):
        result["acceptedSentenceIds"] = {"cross-claim": [11], "gap": [3], "empty": []}[case]
    else:
        result["status"] = "ungrounded"
    with pytest.raises(ValidationError):
        validator.validate(wire)
    assert evidence_schema(claims(), openai=False) == EvidenceBatchOutput.model_json_schema(
        by_alias=True
    )


def test_evidence_conversion_preserves_ids_order_and_rejects_partial_objects():
    contract = output_contract(evidence_schema(claims(), openai=True))
    wire = evidence_wire()
    wire["results"] = dict(reversed(list(wire["results"].items())))
    output = EvidenceBatchOutput.model_validate_json(contract.public_text(json.dumps(wire)))
    assert [r.claim_id for r in output.results] == ["claim-0", "claim-1"]
    assert [r.accepted_sentence_ids for r in output.results] == [[2], [11]]
    del wire["results"]["claim1"]
    assert isinstance(json.loads(contract.public_text(json.dumps(wire)))["results"], dict)


@pytest.mark.parametrize(
    "changes",
    [
        {"claimId": "0:1"},
        {"action": "REVISE", "evidenceSentenceIds": [2]},
        {"action": "REJECT"},
        {
            "action": "REJECT",
            "groundedness": "ungrounded",
            "evidenceSentenceIds": [],
            "confidence": 0.5,
        },
    ],
)
def test_critique_disallows_wrong_targets_new_evidence_and_inconsistent_rejection(changes):
    request = critique_request(claim="A사는 투자를 승인했다.", evidence="A사는 투자를 발표했다.")
    bullet = request.previous_finding.sections[0].bullets[0]
    validator = validator_for(critique_schema("0:0", bullet, openai=True))
    validator.validate(critique_output())
    validator.validate(
        critique_output(
            action="REJECT", groundedness="ungrounded", evidenceSentenceIds=[], confidence=0
        )
    )
    with pytest.raises(ValidationError):
        validator.validate(critique_output(**changes))
    assert critique_schema("0:0", bullet, openai=False) == SelfCritiqueOutput.model_json_schema(
        by_alias=True
    )


@pytest.mark.parametrize("task", ["report", "evidence", "critique"])
def test_services_send_request_bounds_to_responses_and_preserve_usage(task):
    if task == "report":
        request = report_request()
        wire = json.loads(report_output())
    elif task == "evidence":
        request = evidence_request()
        request.claims = claims()
        wire = evidence_wire()
    else:
        request = critique_request(
            claim="A사는 투자를 승인했다.", evidence="A사는 투자를 발표했다."
        )
        wire = critique_output()
    sent = []

    def handler(http_request):
        payload = json.loads(http_request.content)
        schema = payload["text"]["format"]["schema"]
        Draft202012Validator(schema).validate(wire)
        invalid = deepcopy(wire)
        if task == "report":
            invalid["importantEvents"][0]["sourceFindingIds"] = [999]
        elif task == "evidence":
            invalid["results"]["claim0"]["acceptedSentenceIds"] = [999]
        else:
            invalid["revision"]["claimId"] = "0:9"
        with pytest.raises(ValidationError):
            Draft202012Validator(schema).validate(invalid)
        sent.append(payload)
        return httpx2.Response(200, json=response_body(json.dumps(wire)))

    with client_for(handler) as client:
        settings = Settings(AGENT_MOCK=False)
        provider = OpenAIAnalyzeProvider(settings, client)
        if task == "report":
            response = ReportWriterService(settings, provider).write(request)
        elif task == "evidence":
            response = EvidenceVerifierService(settings, provider).verify(request)
        else:
            response = ArticleSelfCritiqueService(settings, provider).critique(request)
    assert len(sent) == 1
    assert response.meta.input_tokens == 1000
    assert response.meta.cost_usd == 0.000125
    assert (
        response.meta.prompt_version
        == {
            "report": "report.ko.v1.5",
            "evidence": "evidence.ko.v3",
            "critique": "self-critique.ko.v3",
        }[task]
    )


def test_maximum_evidence_batch_stays_within_strict_schema_enum_budget():
    batch = [
        claims()[0].model_copy(
            update={
                "claim_id": f"claim-{i}",
                "sentences": [
                    claims()[0].sentences[0].model_copy(update={"id": 1 + 2 * j + 100 * i})
                    for j in range(50)
                ],
            }
        )
        for i in range(50)
    ]
    schema = OpenAIJsonSchemaTransformer(evidence_schema(batch, openai=True), strict=True).walk()
    Draft202012Validator.check_schema(schema)
    wire = {
        "results": {
            f"claim{i}": {
                "claimId": c.claim_id,
                "status": "weak",
                "reason": "부분 근거",
                "acceptedSentenceIds": [c.sentences[-1].id],
            }
            for i, c in enumerate(batch)
        }
    }
    Draft202012Validator(schema).validate(wire)

    def enum_count(value):
        if isinstance(value, dict):
            return len(value.get("enum", [])) + sum(enum_count(v) for v in value.values())
        if isinstance(value, list):
            return sum(enum_count(v) for v in value)
        return 0

    assert enum_count(schema) <= 1000
