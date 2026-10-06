"""Default v4 batches, review, synthesis and one shared request ledger."""

import json
from collections import Counter
from copy import deepcopy
from datetime import date
from decimal import Decimal

import pytest
from fastapi.testclient import TestClient
from jsonschema import Draft202012Validator
from test_report_insight_assessment import framed, item, request

from app.core.config import Settings, get_settings
from app.core.errors import AgentError
from app.core.parser import JsonObjectParseError
from app.llm import report_insight_service as insight_service
from app.llm.base import ProviderResponse, ProviderUsage
from app.llm.report_insight_assessment import draft_to_wire, validate_draft
from app.llm.report_insight_service import (
    PROMPT_VERSION,
    RUBRIC_VERSION,
    ReportInsightService,
    importance_grade,
    importance_score,
)
from app.main import create_app


class V4Provider:
    """Use original evidence to return draft or REDUCE JSON; no SDK exists here."""

    def __init__(
        self,
        source,
        *,
        relation="DIRECT",
        hook=None,
        wire_hook=None,
        raw_hook=None,
        validate_wire=True,
        credits="0.2",
    ):
        self.source = source
        self.relation = relation
        self.hook = hook
        self.wire_hook = wire_hook
        self.raw_hook = raw_hook
        self.validate_wire = validate_wire
        self.credits = Decimal(credits)
        self.calls = []
        self.stages = Counter()
        self.wire_payloads = []
        self.schema_validity = []
        self.response_texts = []

    def generate(self, **kwargs):
        self.calls.append(kwargs)
        schema = kwargs["response_schema"]
        stage = schema["description"].split(":", 1)[1]
        self.stages[stage] += 1
        data = framed(kwargs["prompt"])
        if schema["title"] == "ReportAssessmentDraft":
            source = {finding.id: finding for finding in self.source.findings}
            value = {
                "assessments": {
                    audience: {
                        f"finding{finding['id']}": item(
                            source[finding["id"]], audience=audience, relation=self.relation
                        )
                        for finding in data["findings"]
                    }
                    for audience in data["audiences"]
                }
            }
        else:
            value = {"insights": []}
            for group in data["retrievedEvidence"]:
                refs = [evidence["claimId"] for evidence in group["evidence"]]
                value["insights"].append(
                    {
                        "audience": group["audience"],
                        "headline": "생산 준비의 제약과 확인 조건을 점검한다.",
                        "overview": [
                            {
                                "text": "생산 제약의 지속 여부와 검증 준비 조건을 확인해야 한다.",
                                "basisClaimIds": refs[:1],
                                "assumption": "같은 생산 제약이 검증 준비에 연결되는 경우",
                            }
                        ]
                        if refs
                        else [],
                        "implications": [],
                        "watchItems": [],
                    }
                )
        if self.hook is not None:
            value = self.hook(stage, self.stages[stage], data, value)
        # Keep existing fixture hooks on the private flat draft, then model the
        # actual provider contract before the service receives its response.
        if value is not None and schema["title"] == "ReportAssessmentDraft":
            value = draft_to_wire(value, self.source)
        if self.wire_hook is not None:
            value = self.wire_hook(stage, self.stages[stage], data, value)
        self.wire_payloads.append(value)
        validator = Draft202012Validator(schema)
        self.schema_validity.append(validator.is_valid(value))
        # Only explicitly malformed wire fixtures bypass this provider check.
        # REDUCE semantic failures must remain valid native Structured Outputs.
        if value is not None:
            if self.validate_wire:
                validator.validate(value)
        response_text = json.dumps(value, ensure_ascii=False)
        if self.raw_hook is not None:
            response_text = self.raw_hook(stage, self.stages[stage], data, response_text)
        self.response_texts.append(response_text)
        return ProviderResponse(
            text=response_text,
            provider="openai",
            model="gpt-4.1-nano",
            usage=ProviderUsage(
                input_tokens=11, output_tokens=7, cost_usd=Decimal("0.003"), credits=self.credits
            ),
        )


def generate(provider, source, **settings):
    return ReportInsightService(Settings(AGENT_MOCK=False, **settings), provider).generate(source)


def stages(provider):
    return [call["response_schema"]["description"].split(":", 1)[1] for call in provider.calls]


def partial_repair_fixture():
    source = request(
        ids=tuple(range(101, 109)),
        text="도서관은 독서 모임의 참가 신청이 현재 계속된다고 밝혔다.",
    )
    source = source.model_copy(
        update={
            "report": source.report.model_copy(update={"report_date": None}),
            "findings": [
                finding.model_copy(
                    update={
                        "published_at": date(2026, 9, 30)
                        if finding.id == 108
                        else date(2026, 9, 25)
                    }
                )
                for finding in source.findings
            ],
        }
    )
    reasons = dict(
        zip(
            range(101, 109),
            (
                "관점 업무에 연결될 조건을 확인해야 한다.",
                "직접적인 업무 영향의 근거를 확인해야 한다.",
                "검증 준비와 연결할 중간 조건을 검토해야 한다.",
                "공정 업무와의 연결 조건을 검토해야 한다.",
                "예상 업무 변화의 적용 범위를 확인해야 한다.",
                "상황이 업무 판단에 미칠 연결 조건을 살펴봐야 한다.",
                "업무 영향의 판단에 필요한 원문 범위를 확인해야 한다.",
                "관점 관련성을 판단할 업무 조건을 먼저 확인해야 한다.",
            ),
            strict=True,
        )
    )

    def hook(stage, occurrence, _, value):
        for record in value["assessments"]["CHIP_MAKER"].values():
            record["reason"] = reasons[record["findingId"]]
        if stage == "MAP-001" and occurrence == 1:
            value["assessments"]["CHIP_MAKER"]["finding104"]["reason"] = (
                "2026년에는 공정 업무와의 연결 조건을 검토해야 한다."
            )
        return value

    return source, reasons, hook


def test_partial_native_prose_repair_preserves_other_five_records_and_full_date(monkeypatch):
    source, reasons, hook = partial_repair_fixture()
    snapshot = source.model_dump_json(by_alias=True)
    native_inputs, public_contexts = [], []
    original_draft_validator = insight_service.validate_draft
    original_map_validator = insight_service._validated_map_output

    def capture_draft(response, validation_request):
        native_inputs.append(json.loads(response.text))
        return original_draft_validator(response, validation_request)

    def capture_map(response, validation_request, **kwargs):
        public_contexts.append(
            (
                [finding.id for finding in validation_request.findings],
                validation_request.report.report_end_date,
            )
        )
        return original_map_validator(response, validation_request, **kwargs)

    monkeypatch.setattr(insight_service, "validate_draft", capture_draft)
    monkeypatch.setattr(insight_service, "_validated_map_output", capture_map)
    provider = V4Provider(source, relation="UNRELATED", hook=hook)
    result = generate(provider, source)
    assert stages(provider) == ["MAP-001", "MAP-001", "MAP-002"]
    first_input, repair_input = [framed(call["prompt"]) for call in provider.calls[:2]]
    assert [finding["id"] for finding in first_input["findings"]] == list(range(101, 107))
    assert [finding["id"] for finding in repair_input["findings"]] == [104]
    assert first_input["reportReferenceDate"] == repair_input["reportReferenceDate"] == "2026-09-30"
    repair_schema = provider.calls[1]["response_schema"]
    entries = repair_schema["properties"]["assessments"]["properties"]["CHIP_MAKER"]
    assert set(entries["properties"]) == {"finding104"} and entries["required"] == ["finding104"]
    assert repair_schema["description"] == provider.calls[0]["response_schema"]["description"]
    assert provider.schema_validity == [True, True, True]
    assert len(native_inputs) == 3
    original, merged = [value["assessments"]["CHIP_MAKER"] for value in native_inputs[:2]]
    assert list(merged) == [f"finding{finding_id}" for finding_id in range(101, 107)]
    for key, record in original.items():
        if key != "finding104":
            assert merged[key] == record
    assert merged["finding104"]["reason"] == reasons[104]
    assert public_contexts[:2] == [(list(range(101, 107)), date(2026, 9, 30))] * 2
    assert [record.finding_id for record in result.insights[0].assessments] == list(range(101, 109))
    assert [record.reason for record in result.insights[0].assessments] == list(reasons.values())
    assert result.meta.input_tokens == 33 and result.meta.output_tokens == 21
    assert result.meta.cost_usd == 0.009 and result.meta.credits == 0.6
    assert source.model_dump_json(by_alias=True) == snapshot


def test_partial_native_repair_rejects_an_extra_non_target_record_without_third_call():
    source, _, hook = partial_repair_fixture()

    def wire_hook(stage, occurrence, _, value):
        if stage == "MAP-001" and occurrence == 2:
            original = provider.wire_payloads[0]["assessments"]["CHIP_MAKER"]["finding101"]
            extra = deepcopy(original)
            extra["reason"] = "추가 업무 조건을 확인해야 한다."
            value["assessments"]["CHIP_MAKER"]["finding101"] = extra
        return value

    provider = V4Provider(
        source, relation="UNRELATED", hook=hook, wire_hook=wire_hook, validate_wire=False
    )
    with pytest.raises(AgentError) as caught:
        generate(provider, source)
    assert caught.value.code == "SCHEMA_VIOLATION"
    assert stages(provider) == ["MAP-001", "MAP-001"]
    assert [finding["id"] for finding in framed(provider.calls[1]["prompt"])["findings"]] == [104]
    assert provider.schema_validity == [True, False]
    assert caught.value.details["usage"] == {
        "inputTokens": 22,
        "outputTokens": 14,
        "costUsd": 0.006,
        "credits": 0.4,
    }


@pytest.mark.parametrize(
    "defect",
    ["duplicate_finding", "duplicate_condition", "duplicate_span", "NaN", "Infinity", "-Infinity"],
)
def test_partial_native_repair_strictly_rejects_duplicate_or_nonfinite_raw_json(defect):
    source, _, hook = partial_repair_fixture()

    def raw_hook(stage, occurrence, _, raw):
        if stage != "MAP-001" or occurrence != 2:
            return raw
        if defect == "duplicate_finding":
            record = json.loads(raw)["assessments"]["CHIP_MAKER"]["finding104"]
            encoded = json.dumps(record, ensure_ascii=False)
            marker = f'"finding104": {encoded}'
            assert marker in raw
            return raw.replace(marker, f"{marker}, {marker}", 1)
        if defect == "duplicate_condition":
            assert '"condition": null' in raw
            return raw.replace(
                '"condition": null',
                '"condition": "추가 업무 조건이 확인되는 경우", "condition": null',
                1,
            )
        if defect == "duplicate_span":
            assert '"sourceSpanId": "' in raw
            return raw.replace(
                '"sourceSpanId": "',
                '"sourceSpanId": "s104_0_99999", "sourceSpanId": "',
                1,
            )
        assert '"findingId": 104' in raw
        return raw.replace('"findingId": 104', f'"findingId": {defect}', 1)

    provider = V4Provider(source, relation="UNRELATED", hook=hook, raw_hook=raw_hook)
    with pytest.raises(AgentError) as caught:
        generate(provider, source)
    assert caught.value.code == "SCHEMA_VIOLATION"
    assert isinstance(caught.value.__cause__, JsonObjectParseError)
    assert stages(provider) == ["MAP-001", "MAP-001"]
    assert [finding["id"] for finding in framed(provider.calls[1]["prompt"])["findings"]] == [104]
    assert caught.value.details["usage"] == {
        "inputTokens": 22,
        "outputTokens": 14,
        "costUsd": 0.006,
        "credits": 0.4,
    }
    assert caught.value.details["executionMetadata"]["usageCompleteness"] == "COMPLETE"


def test_partial_native_repair_keeps_existing_fenced_json_support():
    source, reasons, hook = partial_repair_fixture()

    def raw_hook(stage, _, data, raw):
        return f"```json\n{raw}\n```" if stage == "MAP-001" else raw

    provider = V4Provider(source, relation="UNRELATED", hook=hook, raw_hook=raw_hook)
    result = generate(provider, source)
    assert stages(provider) == ["MAP-001", "MAP-001", "MAP-002"]
    assert all(raw.startswith("```json\n") for raw in provider.response_texts[:2])
    assert [finding["id"] for finding in framed(provider.calls[1]["prompt"])["findings"]] == [104]
    assert [record.finding_id for record in result.insights[0].assessments] == list(range(101, 109))
    assert [record.reason for record in result.insights[0].assessments] == list(reasons.values())
    assert result.meta.input_tokens == 33 and result.meta.output_tokens == 21
    assert result.meta.cost_usd == 0.009 and result.meta.credits == 0.6


def test_default_v4_covers_every_finding_in_batches_then_reviews_and_synthesizes():
    source = request(ids=tuple(range(101, 118)))
    snapshot = source.model_dump_json(by_alias=True)
    provider = V4Provider(source)
    result = generate(provider, source)
    assert stages(provider) == ["MAP-001", "MAP-002", "MAP-003", "REVIEW-001", "REDUCE-001"]
    batches = [framed(call["prompt"])["findings"] for call in provider.calls[:3]]
    assert list(map(len, batches)) == [6, 6, 5]
    assert [finding["id"] for batch in batches for finding in batch] == list(range(101, 118))
    review = framed(provider.calls[3]["prompt"])
    assert [finding["id"] for finding in review["findings"]] == list(range(101, 106))
    assert len(review["findings"]) <= 12
    final = result.insights[0].assessments
    assert [entry.finding_id for entry in final] == list(range(101, 118))
    assert all(
        entry.axes.directness == entry.axes.impact == entry.axes.urgency == 3 for entry in final
    )
    assert result.meta.prompt_version == "report-insight.ko.v18"
    assert result.meta.input_tokens == 55 and result.meta.output_tokens == 35
    assert result.meta.cost_usd == 0.015 and result.meta.credits == 1
    assert source.model_dump_json(by_alias=True) == snapshot
    reduce_input = framed(provider.calls[-1]["prompt"])
    assert "assessedPriorities" not in reduce_input
    candidate_groups = reduce_input["decisionCandidates"]["CHIP_MAKER"]
    assert candidate_groups[0]["work"] == "PROCESS_QUALIFICATION"
    assert candidate_groups[0]["findings"][0]["connectionBasis"]["claimId"] == "101:0"
    assert "evidenceFrames" in reduce_input and "retrievedEvidence" in reduce_input
    assert all(provider.schema_validity)
    for value in provider.wire_payloads[:-1]:
        for draft in value["assessments"]["CHIP_MAKER"].values():
            assert set(draft) == {"findingId", "decision", "reason"}
            assert set(draft["decision"]["connection"]) == {
                "relation",
                "work",
                "condition",
                "basis",
            }
            assert set(draft["decision"]["effect"]) == {"impactScope", "basis"}
            assert set(draft["decision"]["timing"]) == {"urgencyState", "basis"}
            for field in ("connection", "effect", "timing"):
                basis = draft["decision"][field]["basis"]
                assert set(basis) == {"claimId", "sourceSpanId"}
                assert basis["sourceSpanId"].isascii()
    # REVIEW sees original sources without anchoring on previous categories/reasons.
    assert "previousDraft" not in review
    assert set(final[0].model_dump(by_alias=True)) == {
        "findingId",
        "reason",
        "basisClaimIds",
        "axes",
    }


def test_review_replaces_only_selected_values_and_final_order_is_original():
    source = request(ids=tuple(range(101, 110)))

    def hook(stage, _, data, value):
        if stage == "REVIEW-001":
            items = value["assessments"]["CHIP_MAKER"]
            replacement = item(source.findings[0], relation="UNDETERMINED")
            replacement["reason"] = "업무 연결의 범위를 판단할 원문이 부족하다."
            items["finding101"] = replacement
            value["assessments"]["CHIP_MAKER"] = dict(reversed(list(items.items())))
        return value

    provider = V4Provider(source, hook=hook)
    result = generate(provider, source)
    assessments = result.insights[0].assessments
    assert [entry.finding_id for entry in assessments] == list(range(101, 110))
    assert assessments[0].axes.directness is None and assessments[0].basis_claim_ids == []
    assert all(entry.axes.directness == 3 for entry in assessments[1:])
    reduce_input = framed(provider.calls[-1]["prompt"])
    assert all(
        finding["findingId"] != 101
        for group in reduce_input["decisionCandidates"]["CHIP_MAKER"]
        for finding in group["findings"]
    )


def test_all_unrelated_findings_skip_review_without_keywords_and_skip_reduce():
    source = request(ids=tuple(range(101, 111)), text="한화는 두 사업의 합병을 발표했다.")
    provider = V4Provider(source, relation="UNRELATED")
    result = generate(provider, source)
    assert stages(provider) == ["MAP-001", "MAP-002"]
    insight = result.insights[0]
    assert insight.overview == insight.implications == insight.watch_items == []
    assert all(entry.axes.directness == 0 for entry in insight.assessments)
    assert all(
        entry.axes.impact is None and entry.axes.urgency is None for entry in insight.assessments
    )
    assert all(importance_score(entry.axes) == 0.0 for entry in insight.assessments)
    assert all(importance_grade(entry.axes) == "low" for entry in insight.assessments)
    assert all(provider.schema_validity)
    for payload in provider.wire_payloads:
        for entry in payload["assessments"]["CHIP_MAKER"].values():
            assert entry["decision"]["connection"]["relation"] == "UNRELATED"
            assert entry["decision"]["connection"]["basis"] is not None
            assert entry["decision"]["effect"] == {"impactScope": "UNDETERMINED", "basis": None}
            assert entry["decision"]["timing"] == {"urgencyState": "UNDETERMINED", "basis": None}
    assert result.meta.credits == 0.4


def test_unrelated_role_keyword_candidates_are_reviewed_without_forcing_relevance():
    source = request(ids=(101, 102))
    provider = V4Provider(source, relation="UNRELATED")
    result = generate(provider, source)
    assert stages(provider) == ["MAP-001", "REVIEW-001"]
    assert all(entry.axes.directness == 0 for entry in result.insights[0].assessments)
    assert result.insights[0].overview == []


def test_map_span_repair_keeps_same_batch_schema_and_sums_all_stages():
    source = request()

    def wire_hook(stage, occurrence, _, value):
        if stage == "MAP-001" and occurrence == 1:
            value["assessments"]["CHIP_MAKER"]["finding101"]["decision"]["effect"]["basis"][
                "sourceSpanId"
            ] = "s101_0_99999"
        return value

    # A malformed provider can still return bytes that violate its native schema.
    provider = V4Provider(source, wire_hook=wire_hook, validate_wire=False)
    result = generate(provider, source)
    assert stages(provider) == ["MAP-001", "MAP-001", "REVIEW-001", "REDUCE-001"]
    assert provider.calls[0]["response_schema"] == provider.calls[1]["response_schema"]
    repaired_prompt = provider.calls[1]["prompt"]
    assert "sourceSpanId" in repaired_prompt and "findingId=101" in repaired_prompt
    assert result.meta.input_tokens == 44 and result.meta.output_tokens == 28
    assert result.meta.cost_usd == 0.012 and result.meta.credits == 0.8
    assert provider.schema_validity == [False, True, True, True]


def test_shifted_finding_span_is_native_invalid_and_locally_repaired_without_usage_loss():
    source = request(ids=(101, 102))
    foreign_quote = "반도체 제조사는 모든 생산라인의 가동 중단이 현재 지속된다고 발표했다."
    second = source.findings[1]
    source = source.model_copy(
        update={
            "findings": [
                source.findings[0],
                second.model_copy(
                    update={
                        "claims": [second.claims[0].model_copy(update={"text": foreign_quote})],
                        "sentences": [
                            second.sentences[0].model_copy(update={"text": foreign_quote})
                        ],
                    }
                ),
            ]
        }
    )
    snapshot = source.model_dump_json(by_alias=True)

    def wire_hook(stage, occurrence, _, value):
        if stage == "MAP-001" and occurrence == 1:
            entries = value["assessments"]["CHIP_MAKER"]
            basis = entries["finding101"]["decision"]["effect"]["basis"]
            foreign_span = entries["finding102"]["decision"]["effect"]["basis"]["sourceSpanId"]
            assert basis["claimId"] == "101:0"
            assert basis["sourceSpanId"] != foreign_span
            basis["sourceSpanId"] = foreign_span
        return value

    provider = V4Provider(source, wire_hook=wire_hook, validate_wire=False)
    result = generate(provider, source)
    assert stages(provider) == ["MAP-001", "MAP-001", "REVIEW-001", "REDUCE-001"]
    assert provider.schema_validity == [False, True, True, True]
    repair = provider.calls[1]
    repair_entries = repair["response_schema"]["properties"]["assessments"]["properties"][
        "CHIP_MAKER"
    ]
    assert set(repair_entries["properties"]) == {"finding101"}
    assert [finding["id"] for finding in framed(repair["prompt"])["findings"]] == [101]
    assert "sourceSpanId" in repair["prompt"] and "findingId=101" in repair["prompt"]
    assert result.meta.input_tokens == 44 and result.meta.output_tokens == 28
    assert result.meta.cost_usd == 0.012 and result.meta.credits == 0.8
    assert source.model_dump_json(by_alias=True) == snapshot


def _assert_failed_review_retains_exact_map(provider, source, result, caplog):
    original = validate_draft(
        ProviderResponse(provider.response_texts[0], "openai", "gpt-4.1-nano", ProviderUsage()),
        source,
    )
    assert result.insights[0].assessments == original.mapped.insights[0].assessments
    assert stages(provider) == ["MAP-001", "REVIEW-001", "REVIEW-001", "REDUCE-001"]
    assert provider.schema_validity == [True, False, False, True]
    assert result.meta.input_tokens == 44 and result.meta.output_tokens == 28
    assert result.meta.cost_usd == 0.012 and result.meta.credits == 0.8
    assert (
        "stage=REVIEW-001 outcome=VALIDATION_FAILED fallback=VALIDATED_MAP_RETAINED" in caplog.text
    )


@pytest.mark.parametrize("stage", ["MAP-001", "REVIEW-001"])
def test_native_invalid_span_still_gets_local_validation_and_at_most_one_repair(stage, caplog):
    source = request()

    def wire_hook(current, _, data, value):
        if current == stage:
            value["assessments"]["CHIP_MAKER"]["finding101"]["decision"]["connection"]["basis"][
                "sourceSpanId"
            ] = "s101_0_99999"
        return value

    provider = V4Provider(source, wire_hook=wire_hook, validate_wire=False)
    if stage == "REVIEW-001":
        result = generate(provider, source)
        _assert_failed_review_retains_exact_map(provider, source, result, caplog)
        return
    with pytest.raises(AgentError) as caught:
        generate(provider, source)
    assert caught.value.code == "SCHEMA_VIOLATION"
    assert stages(provider).count(stage) == 2
    assert not any(label.startswith("REDUCE") for label in stages(provider))
    assert caught.value.details["usage"]["inputTokens"] == len(provider.calls) * 11
    assert caught.value.details["executionMetadata"]["promptVersion"] == PROMPT_VERSION
    assert caught.value.details["validationFailure"]["stage"] == stage
    assert caught.value.details["validationFailure"]["attempt"] == 2
    assert caught.value.details["validationFailure"]["errorKinds"] == [
        "report_assessment_draft_invalid"
    ]
    assert provider.schema_validity == [label != stage for label in stages(provider)]
    assert caught.value.details["usage"]["costUsd"] == float(
        Decimal(len(provider.calls)) * Decimal("0.003")
    )


@pytest.mark.parametrize("stage", ["MAP-001", "REVIEW-001"])
def test_native_span_restores_quotes_and_newlines_exactly_after_one_repair(stage):
    text = '제조사는 "생산라인 전체의 가동 중단"\n이 현재 계속된다고 밝혔다.'
    source = request(text=text)
    snapshot = source.model_dump_json(by_alias=True)

    def wire_hook(current, occurrence, _, value):
        if current == stage and occurrence == 1:
            value["assessments"]["CHIP_MAKER"]["finding101"]["decision"]["effect"]["basis"][
                "sourceSpanId"
            ] = "s101_0_99999"
        return value

    provider = V4Provider(source, wire_hook=wire_hook, validate_wire=False)
    result = generate(provider, source)
    assert stages(provider).count(stage) == 2
    assert len(provider.calls) == 4 and provider.schema_validity.count(False) == 1
    repair_index = [index for index, label in enumerate(stages(provider)) if label == stage][1]
    wire = provider.wire_payloads[repair_index]
    validated = validate_draft(
        ProviderResponse(
            json.dumps(wire, ensure_ascii=False), "openai", "offline", ProviderUsage()
        ),
        source,
    )
    draft = validated.evidence["CHIP_MAKER"][101]
    assert all(
        basis.quote == text
        for basis in (draft.relation_basis, draft.impact_basis, draft.urgency_basis)
    )
    native_basis = wire["assessments"]["CHIP_MAKER"]["finding101"]["decision"]["effect"]["basis"]
    assert set(native_basis) == {"claimId", "sourceSpanId"}
    choices = framed(provider.calls[repair_index]["prompt"])["findings"][0]["sourceQuoteChoices"]
    assert json.dumps(text, ensure_ascii=False) in json.dumps(choices, ensure_ascii=False)
    assert result.meta.input_tokens == 44 and result.meta.output_tokens == 28
    assert result.meta.cost_usd == 0.012 and result.meta.credits == 0.8
    assert source.model_dump_json(by_alias=True) == snapshot


@pytest.mark.parametrize(
    "defect",
    [
        "direct_without_work",
        "direct_without_basis",
        "direct_with_condition",
        "conditional_without_condition",
        "unknown_effect_with_basis",
        "known_effect_without_basis",
        "unknown_timing_with_basis",
        "known_timing_without_basis",
        "work_for_another_audience",
    ],
)
def test_post_validated_category_correlations_repair_invalid_structure_once(defect):
    source = request()

    def wire_hook(stage, occurrence, _, value):
        if stage != "MAP-001" or occurrence != 1:
            return value
        draft = value["assessments"]["CHIP_MAKER"]["finding101"]
        if defect == "direct_without_work":
            draft["decision"]["connection"]["work"] = None
        elif defect == "direct_without_basis":
            draft["decision"]["connection"]["basis"] = None
        elif defect == "direct_with_condition":
            draft["decision"]["connection"]["condition"] = "추가 업무 연결을 확인하는 경우"
        elif defect == "conditional_without_condition":
            draft["decision"]["connection"]["relation"] = "CONDITIONAL"
        elif defect == "unknown_effect_with_basis":
            draft["decision"]["effect"]["impactScope"] = "UNDETERMINED"
        elif defect == "known_effect_without_basis":
            draft["decision"]["effect"]["basis"] = None
        elif defect == "unknown_timing_with_basis":
            draft["decision"]["timing"]["urgencyState"] = "UNDETERMINED"
        elif defect == "known_timing_without_basis":
            draft["decision"]["timing"]["basis"] = None
        else:
            draft["decision"]["connection"]["work"] = "POWER_COOLING"
        return value

    provider = V4Provider(source, wire_hook=wire_hook, validate_wire=False)
    result = generate(provider, source)
    assert stages(provider) == ["MAP-001", "MAP-001", "REVIEW-001", "REDUCE-001"]
    # Native axis branches now reject these combinations. A nonconforming
    # provider response still reaches the unchanged local guard and one repair.
    assert provider.schema_validity == [False, True, True, True]
    assert provider.calls[0]["response_schema"] == provider.calls[1]["response_schema"]
    assert all(name in provider.calls[1]["prompt"] for name in ("connection", "effect", "timing"))
    assert result.meta.input_tokens == 44 and result.meta.output_tokens == 28
    assert result.meta.cost_usd == 0.012 and result.meta.credits == 0.8


@pytest.mark.parametrize("stage", ["MAP-001", "REVIEW-001"])
def test_invalid_native_structure_stops_after_one_repair_and_reports_observed_usage(stage, caplog):
    source = request()

    def wire_hook(current, _, data, value):
        if current == stage:
            value["assessments"]["CHIP_MAKER"]["finding101"]["decision"]["effect"]["basis"] = None
        return value

    provider = V4Provider(source, wire_hook=wire_hook, validate_wire=False)
    if stage == "REVIEW-001":
        result = generate(provider, source)
        _assert_failed_review_retains_exact_map(provider, source, result, caplog)
        return
    with pytest.raises(AgentError) as caught:
        generate(provider, source)
    assert caught.value.code == "SCHEMA_VIOLATION"
    assert stages(provider).count(stage) == 2
    assert not any(label.startswith("REDUCE") for label in stages(provider))
    assert [
        valid
        for valid, label in zip(provider.schema_validity, stages(provider), strict=True)
        if label == stage
    ] == [False, False]
    assert caught.value.details["usage"]["inputTokens"] == len(provider.calls) * 11
    assert caught.value.details["usage"]["credits"] == float(
        Decimal(len(provider.calls)) * Decimal("0.2")
    )


@pytest.mark.parametrize("cap,expected_calls,credits", [("0.2", 1, 0.2), ("0.3", 2, 0.4)])
def test_one_shared_credit_cap_stops_review_or_reduce_without_extra_calls(
    cap, expected_calls, credits
):
    source = request()
    provider = V4Provider(source)
    with pytest.raises(AgentError) as caught:
        generate(provider, source, AGENT_HARD_CAP_CREDITS_PER_REQUEST=float(cap))
    assert caught.value.code == "BUDGET_EXCEEDED"
    assert len(provider.calls) == expected_calls
    assert caught.value.details["usage"]["credits"] == credits
    assert "REDUCE-001" not in stages(provider)


def test_all_batches_share_deadline_and_observed_usage(monkeypatch):
    source = request(ids=tuple(range(101, 118)))
    clock = [0.0]
    monkeypatch.setattr("app.llm.report_insight_pipeline.monotonic", lambda: clock[0])

    def hook(stage, _, data, value):
        if stage == "MAP-002":
            clock[0] = 11.0
        return value

    provider = V4Provider(source, hook=hook)
    with pytest.raises(AgentError) as caught:
        generate(provider, source, AGENT_REPORT_INSIGHT_TIMEOUT_SECONDS=10)
    assert stages(provider) == ["MAP-001", "MAP-002"]
    assert caught.value.details["requestDeadlineExceeded"] is True
    assert caught.value.details["usage"]["credits"] == 0.4
    assert caught.value.details["usage"]["inputTokens"] == 22


def test_full_report_latest_date_is_used_before_first_batch_and_repair():
    source = request(ids=tuple(range(101, 110)))
    deadline = "공정 검증 자료 제출 마감은 2026년 9월 27일이다."
    source = source.model_copy(
        update={
            "report": source.report.model_copy(
                update={"report_date": None, "report_end_date": None}
            ),
            "findings": [
                finding.model_copy(
                    update={
                        "published_at": date(2026, 9, 30)
                        if finding.id == 109
                        else date(2026, 9, 25),
                        "claims": [finding.claims[0].model_copy(update={"text": deadline})],
                        "sentences": [finding.sentences[0].model_copy(update={"text": deadline})],
                    }
                )
                for finding in source.findings
            ],
        }
    )
    provider = V4Provider(source)
    with pytest.raises(AgentError) as caught:
        generate(provider, source)
    assert stages(provider) == ["MAP-001", "MAP-001"]
    assert all(
        framed(call["prompt"])["reportReferenceDate"] == "2026-09-30" for call in provider.calls
    )
    assert caught.value.code == "SCHEMA_VIOLATION"
    assert caught.value.details["usage"]["credits"] == 0.4


def test_unknown_time_anchor_remains_null_in_all_stages():
    source = request()
    source = source.model_copy(
        update={
            "report": source.report.model_copy(update={"report_date": None}),
            "findings": [source.findings[0].model_copy(update={"published_at": None})],
        }
    )

    def hook(stage, _, data, value):
        if stage != "REDUCE-001":
            draft = value["assessments"]["CHIP_MAKER"]["finding101"]
            draft.update(urgencyState="UNDETERMINED", urgencyBasis=None)
        return value

    provider = V4Provider(source, hook=hook)
    result = generate(provider, source)
    assert all(framed(call["prompt"])["reportReferenceDate"] is None for call in provider.calls)
    assert result.insights[0].assessments[0].axes.urgency is None


@pytest.mark.parametrize("defect", ["actor", "stage", "placeholder"])
def test_default_reduce_quality_guards_reject_and_preserve_all_observed_usage(defect):
    text = (
        "TSMC의 투자가 현재 확대됐다. 삼성전자는 수혜 가능성을 검토한다."
        if defect != "stage"
        else "TSMC는 삼성전자를 위한 투자 계획을 발표했다."
    )
    source = request(text=text)
    if defect == "stage":
        source = source.model_copy(
            update={
                "findings": [
                    source.findings[0].model_copy(
                        update={
                            "claims": [
                                source.findings[0]
                                .claims[0]
                                .model_copy(update={"claim_type": "FORECAST"})
                            ]
                        }
                    )
                ]
            }
        )

    def hook(stage, _, data, value):
        if stage != "REDUCE-001":
            draft = value["assessments"]["CHIP_MAKER"]["finding101"]
            draft.update(
                impactScope="UNDETERMINED",
                impactBasis=None,
                urgencyState="UNDETERMINED",
                urgencyBasis=None,
                reason="투자 단계와 생산 준비의 연결 조건을 확인한다.",
            )
            return value
        insight = value["insights"][0]
        if defect == "actor":
            insight["overview"][0]["text"] = "삼성전자의 투자가 현재 확대됐다."
        elif defect == "stage":
            insight["overview"][0]["text"] = "TSMC의 투자가 현재 확대됐다."
        else:
            insight["implications"] = [
                {
                    "text": "투자 조건이 유지되면 생산 준비 판단을 검토할 수 있다.",
                    "mechanism": "근거 → 영향 → 판단",
                    "basisClaimIds": ["101:0"],
                    "assumption": "해당 투자가 생산 준비에 연결되는 경우",
                    "falsifiedBy": "해당 투자 계획이 철회되는 경우",
                }
            ]
        return value

    provider = V4Provider(source, hook=hook)
    with pytest.raises(AgentError) as caught:
        generate(provider, source)
    assert stages(provider) == ["MAP-001", "REVIEW-001", "REDUCE-001", "REDUCE-001"]
    assert caught.value.code == "SCHEMA_VIOLATION"
    assert caught.value.details["usage"]["credits"] == 0.8
    assert caught.value.details["usage"]["inputTokens"] == 44
    assert all(provider.schema_validity)


def test_default_api_mock_has_v4_metadata_and_unchanged_public_response():
    application = create_app()
    application.dependency_overrides[get_settings] = lambda: Settings(
        AGENT_MOCK=True,
        AGENT_SHARED_SECRET="local-v4-test-token",
    )
    with TestClient(application) as client:
        result = client.post(
            "/v1/report-insight",
            json=request().model_dump(mode="json", by_alias=True),
            headers={"X-Agent-Token": "local-v4-test-token"},
        )
    assert result.status_code == 200
    output = result.json()
    assert output["meta"]["promptVersion"] == "report-insight.ko.v18"
    assert output["meta"]["mock"] is True
    assert RUBRIC_VERSION == "report-importance.v6"
    assert set(output) == {"insights", "meta"}
    assessment = output["insights"][0]["assessments"][0]
    assert set(assessment) == {"findingId", "reason", "basisClaimIds", "axes"}
    assert assessment["axes"]["novelty"] is None


def test_draft_error_repairs_native_and_previously_unvisited_prose_failures():
    source, reasons, _ = partial_repair_fixture()

    def hook(stage, occurrence, _, value):
        for record in value["assessments"]["CHIP_MAKER"].values():
            record["reason"] = reasons[record["findingId"]]
        if stage == "MAP-001" and occurrence == 1:
            value["assessments"]["CHIP_MAKER"]["finding101"]["reason"] = (
                "검증을 통과한 claim 근거가 없어 중요도 판단을 보류합니다."
            )
            value["assessments"]["CHIP_MAKER"]["finding103"]["reason"] = (
                "2026년에는 검증 준비 조건을 확인해야 한다."
            )
        return value

    provider = V4Provider(source, relation="UNRELATED", hook=hook)
    result = generate(provider, source)
    assert stages(provider) == ["MAP-001", "MAP-001", "MAP-002"]
    assert provider.schema_validity == [True, True, True]
    assert [f["id"] for f in framed(provider.calls[1]["prompt"])["findings"]] == [101, 103]
    assert [record.reason for record in result.insights[0].assessments] == list(reasons.values())
    assert result.meta.input_tokens == 33 and result.meta.cost_usd == 0.009
