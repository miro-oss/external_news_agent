"""Bounded recovery uses only complete, independently validated wire records."""

import json
from copy import deepcopy
from dataclasses import replace
from datetime import date

import pytest
from test_report_insight_assessment import framed
from test_report_insight_v4_pipeline import V4Provider, generate, partial_repair_fixture, stages

from app.core.errors import AgentError
from app.llm import report_insight_service as service
from app.llm.report_insight_prefix import closed_assessment_prefix


def prefix(records):
    # Remove only the three enclosing objects; the last record stays closed.
    return json.dumps({"assessments": {"CHIP_MAKER": records}}, ensure_ascii=False)[:-3]


RECORD = {"findingId": 101, "reason": 'string braces } and ", finding102: { stay data'}
PREFIX = prefix({"finding101": RECORD})


def test_parser_reads_complete_values_without_interpreting_embedded_json_or_instructions():
    assert closed_assessment_prefix(PREFIX + " \t\r\n", "CHIP_MAKER", [101, 102]) == {
        "finding101": RECORD
    }


@pytest.mark.parametrize(
    "raw",
    [
        "",
        '{"assessments":{"CHIP_MAKER":{',
        PREFIX + ",",
        PREFIX + ',"finding102',
        PREFIX + ',"finding102":{"findingId":102',
        PREFIX + "}}}",
        PREFIX + "}}}, arbitrary instructions",
        PREFIX + "\u00a0",
        "```json\n" + PREFIX,
        PREFIX.replace('"assessments"', '"instructions"', 1),
        PREFIX.replace('"CHIP_MAKER"', '"IT_INFRA"', 1),
        PREFIX.replace('"finding101"', '"finding102"', 1),
        PREFIX.replace('"findingId": 101', '"findingId": 102'),
        PREFIX.replace('"findingId": 101', '"findingId": true'),
        PREFIX.replace('"findingId": 101', '"findingId": 101.0'),
        PREFIX.replace('"findingId": 101', '"findingId": NaN'),
        PREFIX.replace('"findingId": 101', '"findingId": Infinity'),
        PREFIX.replace('"findingId": 101', '"findingId": 1e999'),
        PREFIX.replace('"findingId": 101', '"findingId": 101, "extra": 1e999'),
        PREFIX.replace('"findingId": 101', '"findingId": 101, "findingId": 101'),
        PREFIX.replace('"findingId": 101', '"findingId": 101, "finding\\u0049d": 101'),
        PREFIX + ',"finding101":{"findingId":101}',
        PREFIX + ',"finding999":{"findingId":999}',
        prefix({"finding101": RECORD, "finding102": {"findingId": 102}}),
        PREFIX + '}},"IT_INFRA":{}',
        PREFIX + " " * 128_000,
    ],
)
def test_parser_rejects_non_boundary_or_ambiguous_json_instead_of_fixing_it(raw):
    assert closed_assessment_prefix(raw, "CHIP_MAKER", [101, 102]) is None


@pytest.mark.parametrize("ids", [[101], [101, 101], [True, 102], [101.0, 102]])
def test_parser_requires_a_unique_strict_request_with_a_missing_record(ids):
    assert closed_assessment_prefix(PREFIX, "CHIP_MAKER", ids) is None


class PrefixProvider(V4Provider):
    def __init__(self, source, reasons, *, defects=(), repair_defect=None, flag=True):
        def hook(stage, occurrence, _, value):
            for entries in value["assessments"].values():
                for record in entries.values():
                    record["reason"] = reasons[record["findingId"]]
            if stage == "MAP-001" and occurrence == 1:
                entries = value["assessments"]["CHIP_MAKER"]
                for identifier in defects:
                    entries[f"finding{identifier}"]["reason"] = (
                        "2031년에는 원문에서 확인되지 않은 조건이 적용된다."
                    )
            return value

        super().__init__(source, relation="UNRELATED", hook=hook)
        self.flag, self.repair_defect = flag, repair_defect

    def generate(self, **kwargs):
        response = super().generate(**kwargs)
        if len(self.calls) == 1:
            wire = json.loads(response.text)["assessments"]["CHIP_MAKER"]
            raw = prefix(dict(list(wire.items())[:5])) + " " * 300
            return replace(response, text=raw, truncated=self.flag)
        if len(self.calls) == 2 and self.repair_defect:
            wire = json.loads(response.text)
            entries = wire["assessments"]["CHIP_MAKER"]
            if self.repair_defect == "missing":
                entries.pop(next(iter(entries)))
            elif self.repair_defect == "extra":
                entries["finding102"] = deepcopy(
                    self.wire_payloads[0]["assessments"]["CHIP_MAKER"]["finding102"]
                )
            elif self.repair_defect == "public":
                next(iter(entries.values()))["reason"] = "2031년 조건이 실제로 적용됐다."
            elif self.repair_defect == "native":
                next(iter(entries.values()))["decision"]["effect"] = {
                    "impactScope": "NO_CHANGE",
                    "basis": None,
                }
            return replace(
                response,
                text=json.dumps(wire, ensure_ascii=False),
                truncated=self.repair_defect == "truncated",
            )
        return response


@pytest.mark.parametrize("invalid", [(), (103,)])
def test_recovers_only_missing_and_invalid_items_and_revalidates_full_batch(monkeypatch, invalid):
    source, reasons, _ = partial_repair_fixture()
    before = source.model_dump_json(by_alias=True)
    validations, recovery_errors = [], []
    native, public = service.validate_draft, service._validated_map_output
    repair_factory = service._partial_assessment_repair

    def validate_native(response, request):
        validations.append((json.loads(response.text), [f.id for f in request.findings]))
        return native(response, request)

    def validate_public(response, request, **kwargs):
        assert request.report.report_end_date == date(2026, 9, 30) or len(request.findings) == 8
        return public(response, request, **kwargs)

    def repair(prompt, schema, raw, error, validate, fallback):
        recovery_errors.append(error)
        return repair_factory(prompt, schema, raw, error, validate, fallback)

    monkeypatch.setattr(service, "validate_draft", validate_native)
    monkeypatch.setattr(service, "_validated_map_output", validate_public)
    monkeypatch.setattr(service, "_partial_assessment_repair", repair)
    provider = PrefixProvider(source, reasons, defects=invalid)
    result = generate(provider, source)
    expected_repair = [*invalid, 106]
    assert [f["id"] for f in framed(provider.calls[1]["prompt"])["findings"]] == expected_repair
    assert framed(provider.calls[1]["prompt"])["reportReferenceDate"] == "2026-09-30"
    assert stages(provider) == ["MAP-001", "MAP-001", "MAP-002"]
    error = recovery_errors[0]
    assert error.failed_finding_ids == tuple(expected_repair)
    assert "findingId=106: 잘린 출력에 완성된 항목이 없습니다." in str(error)
    if invalid:
        assert "findingId=103" in str(error) and "2031" in str(error)
    original = provider.wire_payloads[0]["assessments"]["CHIP_MAKER"]
    merged = next(w for w, ids in validations if ids == list(range(101, 107)))
    for key, value in error.preserved_wire["assessments"]["CHIP_MAKER"].items():
        assert value == original[key] == merged["assessments"]["CHIP_MAKER"][key]
    assert [a.finding_id for a in result.insights[0].assessments] == list(range(101, 109))
    assert [a.reason for a in result.insights[0].assessments] == list(reasons.values())
    assert result.meta.input_tokens == 33 and result.meta.output_tokens == 21
    assert result.meta.cost_usd == 0.009 and result.meta.credits == 0.6
    assert source.model_dump_json(by_alias=True) == before


@pytest.mark.parametrize("mode", ["not_truncated", "all_invalid", "multiple_audiences"])
def test_ambiguous_or_unusable_prefix_keeps_the_existing_full_repair(mode):
    source, reasons, _ = partial_repair_fixture()
    if mode == "multiple_audiences":
        source = source.model_copy(update={"audiences": ["CHIP_MAKER", "EQUIPMENT_MAKER"]})
    provider = PrefixProvider(
        source,
        reasons,
        defects=tuple(range(101, 106)) if mode == "all_invalid" else (),
        flag=mode != "not_truncated",
    )
    result = generate(provider, source)
    assert [f["id"] for f in framed(provider.calls[1]["prompt"])["findings"]] == list(
        range(101, 107)
    )
    assert len(result.insights) == len(source.audiences)


@pytest.mark.parametrize("defect", ["missing", "extra", "native", "public", "truncated"])
def test_failed_repair_has_no_third_call_and_keeps_original_truncation_usage(defect):
    source, reasons, _ = partial_repair_fixture()
    provider = PrefixProvider(source, reasons, repair_defect=defect)
    with pytest.raises(AgentError) as caught:
        generate(provider, source)
    assert stages(provider) == ["MAP-001", "MAP-001"]
    assert caught.value.code == "SCHEMA_VIOLATION"
    assert caught.value.details["truncated"] is True
    assert caught.value.details["usage"]["inputTokens"] == 22
    assert caught.value.details["usage"]["outputTokens"] == 14
    assert caught.value.details["usage"]["costUsd"] == 0.006
