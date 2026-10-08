"""Repairing a native decision cannot add a new optional display-source choice."""

from copy import deepcopy

import pytest
from jsonschema import Draft202012Validator
from test_report_insight_source_quote_repair_scope import repair_schema, source_with_two_claims
from test_report_insight_v4_pipeline import V4Provider, generate, stages

from app.core.errors import StructuredOutputExhaustedError
from app.llm import report_insight_service as service
from app.llm.report_insight_assessment import draft_schema
from app.llm.report_insight_fact_rendering import build_fact_text_catalog
from app.llm.report_validation_diagnostics import ReportValidationIssue


def native_repair(*, quote=None, bad_basis=False, malformed_quotes=None):
    source = source_with_two_claims()
    slots = {slot.claim_ids[0]: slot.slot_id for slot in build_fact_text_catalog(source).slots}

    def hook(stage, occurrence, _, value):
        if stage == "MAP-001":
            record = value["assessments"]["CHIP_MAKER"].get("finding101")
            if record is not None and occurrence == 1:
                record["reason"] = "새 냉각 모듈의 준비가 공정 검증 업무에 영향을 준다."
            elif record is not None:
                basis = {"claimId": "101:1", "quote": source.findings[0].claims[1].text}
                for field in ("relationBasis", "impactBasis", "urgencyBasis"):
                    record[field] = deepcopy(basis)
        return value

    def wire(stage, occurrence, _, value):
        if stage == "MAP-001":
            records = value["assessments"]["CHIP_MAKER"]
            if "finding102" in records:
                records["finding102"]["sourceQuotes"]["reason"] = slots["102:0"]
            if "finding101" in records:
                record = records["finding101"]
                record["sourceQuotes"]["reason"] = (
                    slots["101:0"] if occurrence == 1 else slots[quote] if quote else None
                )
                if occurrence == 2 and bad_basis:
                    record["decision"]["connection"]["basis"] = {
                        "claimId": "102:0",
                        "sourceSpanId": "s102_0_0",
                    }
                if occurrence == 2 and malformed_quotes == "null":
                    record["sourceQuotes"] = None
                elif occurrence == 2 and malformed_quotes == "missing":
                    del record["sourceQuotes"]
        return value

    return (
        source,
        slots,
        V4Provider(
            source,
            hook=hook,
            wire_hook=wire,
            validate_wire=not (quote or bad_basis or malformed_quotes),
        ),
    )


def test_editable_native_basis_retry_omits_only_failed_record_optional_quotes(monkeypatch):
    source, slots, provider = native_repair()
    snapshots = []
    original = service.validate_draft

    def capture(response, request):
        if len(request.findings) == 2:
            snapshots.append(service.parse_wire_draft(response.text).model_dump(by_alias=True))
        return original(response, request)

    monkeypatch.setattr(service, "validate_draft", capture)
    generate(provider, source)
    assert stages(provider)[:2] == ["MAP-001", "MAP-001"]
    assert provider.schema_validity[:2] == [True, True]
    first, repaired = provider.wire_payloads[:2]
    assert set(repaired["assessments"]["CHIP_MAKER"]) == {"finding101"}
    repaired_record = repaired["assessments"]["CHIP_MAKER"]["finding101"]
    assert repaired_record["sourceQuotes"] == {"reason": None, "condition": None}
    assert repaired_record["decision"]["connection"]["basis"]["claimId"] == "101:1"
    entries = repair_schema(provider)["properties"]["assessments"]["properties"]["CHIP_MAKER"][
        "properties"
    ]
    assert entries["finding101"]["properties"]["sourceQuotes"]["properties"] == {
        "reason": {"type": "null"},
        "condition": {"type": "null"},
    }
    assert (
        snapshots[0]["assessments"]["CHIP_MAKER"]["finding102"]
        == snapshots[1]["assessments"]["CHIP_MAKER"]["finding102"]
    )
    assert (
        first["assessments"]["CHIP_MAKER"]["finding102"]["sourceQuotes"]["reason"] == slots["102:0"]
    )
    assert "진단·수리 과정" in provider.calls[1]["prompt"]


@pytest.mark.parametrize("quote", ["101:1", "102:0"])
def test_a_provider_ignoring_null_only_retry_is_rejected_without_normalizing_the_quote(quote):
    source, slots, provider = native_repair(quote=quote)
    with pytest.raises(StructuredOutputExhaustedError) as caught:
        generate(provider, source)
    assert stages(provider) == ["MAP-001", "MAP-001"]
    assert provider.schema_validity == [True, False]
    assert not Draft202012Validator(repair_schema(provider)).is_valid(provider.wire_payloads[1])
    assert (
        provider.wire_payloads[1]["assessments"]["CHIP_MAKER"]["finding101"]["sourceQuotes"][
            "reason"
        ]
        == slots[quote]
    )
    assert caught.value.details["validationFailure"]["errorKinds"] == ["report_assessment_invalid"]
    assert caught.value.details["usage"]["inputTokens"] == 22


def test_omitting_optional_quotes_does_not_waive_mandatory_same_finding_basis_validation():
    source, _, provider = native_repair(bad_basis=True)
    with pytest.raises(StructuredOutputExhaustedError):
        generate(provider, source)
    assert stages(provider) == ["MAP-001", "MAP-001"]
    assert provider.wire_payloads[1]["assessments"]["CHIP_MAKER"]["finding101"]["sourceQuotes"] == {
        "reason": None,
        "condition": None,
    }


@pytest.mark.parametrize("malformed", ["null", "missing"])
def test_malformed_selector_objects_fail_validation_without_an_attribute_error(malformed):
    source, _, provider = native_repair(malformed_quotes=malformed)
    with pytest.raises(StructuredOutputExhaustedError) as caught:
        generate(provider, source)
    assert stages(provider) == ["MAP-001", "MAP-001"]
    assert caught.value.details["validationFailure"]["errorKinds"] == ["report_assessment_invalid"]


def test_unlocated_diagnostic_keeps_the_ordinary_repair_without_guessing_a_record():
    source = source_with_two_claims()
    repair = service.StructuredCallRepair(
        prompt="", response_schema=draft_schema(source), validate=lambda response: response
    )
    error = service.ReportAssessmentValidationError(
        "unlocated",
        error_kinds=("report_assessment_draft_invalid",),
        failed_finding_ids=(101,),
        validation_issues=(
            ReportValidationIssue("CHIP_MAKER", None, "report_assessment_draft_invalid", ()),
        ),
    )
    assert service._omit_editable_repair_quotes(repair, error) is repair
