"""Safe causes make the single repair actionable without accepting bad facts."""

from dataclasses import replace

import pytest
from test_report_insight_assessment import payload, request, response
from test_report_insight_repair_actions import structured_diagnostics
from test_report_insight_v4_pipeline import V4Provider, generate, stages

from app.core.errors import AgentError, OutputValidationError
from app.llm import report_insight_service as service
from app.llm.report_insight_assessment import validate_draft
from app.llm.report_insight_fact_repair import fact_repair_guidance, fact_repair_kinds


def fact_error(value, source, rule=None):
    data = request(text=source)
    claims, evidence = service._source_context(data)
    errors = service._prose_validation_errors([value], ["101:0"], evidence, claims)
    return next(
        error
        for error in errors
        if service._PROSE_FACT_KINDS.intersection(error.error_kinds)
        and (rule is None or rule in error.fact_repair_kinds)
    )


@pytest.mark.parametrize(
    "value,source,kind,literal",
    [
        ("새 공장의 인력은 999명이다.", "새 공장의 인력은 100명이다.", "unsupported_number", "999"),
        ("내년의 공급 범위를 확인한다.", "올해의 공급 범위를 확인한다.", "date", "내년"),
        (
            "삼성전자의 공급 범위를 확인한다.",
            "화웨이의 공급 범위를 확인한다.",
            "company",
            "삼성전자",
        ),
        (
            "삼성전자는 2028년에 59조원을 투자한다.",
            "삼성전자는 2027년에 59조원을 투자한다. 삼성전자는 2028년에 80조원을 투자한다.",
            "numeric_context",
            "59조",
        ),
        (
            "계약을 체결했다.",
            "계약이 취소됐다.",
            "polarity",
            "취소됐",
        ),
        (
            "공급을 완료했다.",
            "공급 계획을 검토 중이다.",
            "event_state",
            "공급을 완료했다",
        ),
    ],
)
def test_actual_fact_failure_projects_closed_cause_without_rejected_values(
    value, source, kind, literal
):
    error = fact_error(value, source, kind)
    action = service._repair_action_message(
        str(error), error.error_kinds, fact_kinds=error.fact_repair_kinds
    )
    assert kind in error.fact_repair_kinds
    assert f"[{kind}]" in action
    assert literal not in action
    assert error.error_kinds == ("report_evidence_insufficient",)


def test_currency_mismatch_gets_value_free_currency_and_scale_guidance():
    error = fact_error("100유로 규모다.", "100달러 규모이며 100개를 공급한다.", "currency_amount")
    assert "currency_amount" in error.fact_repair_kinds
    action = fact_repair_guidance(error.fact_repair_kinds)
    assert "[currency_amount]" in action
    assert "통화·금액의 값과 배율" in action
    assert "100" not in action


def test_product_identifier_failure_is_not_misreported_as_a_company():
    error = fact_error(
        "HBM5의 검증 준비 영향을 확인한다.",
        "제조사는 HBM4의 검증을 준비한다. 대상 수는 5개다.",
        "source_binding",
    )
    assert "source_binding" in error.fact_repair_kinds
    assert "company" not in error.fact_repair_kinds
    action = fact_repair_guidance(error.fact_repair_kinds)
    assert "제품명·세대 식별자" in action
    assert "HBM5" not in action


@pytest.mark.parametrize(
    "citation", ["claim 101:0", "claimId/sourceSpanId:101:0", "sourceSpanId=s101_0_1"]
)
def test_internal_reference_category_comes_from_rejected_prose_not_error_text(citation):
    value = f"원문 문장({citation})에 따른 업무의 영향 범위를 확인한다."
    source = "도서관은 독서 모임의 참가 신청을 받는다."
    # The sourceSpanId shape may evade the numeric regex; this helper is used
    # only when another guard has already rejected the prose, never on its own.
    categories = fact_repair_kinds(value, source, ["근거에서 확인되지 않는 숫자: 101"])
    assert "internal_reference_in_prose" in categories
    assert citation not in fact_repair_guidance(categories)
    assert "internal_reference_in_prose" not in fact_repair_kinds(value, value, [])
    untrusted = OutputValidationError(value, error_kinds=("report_fact_mismatch",))
    actions = service._repair_action_entries(untrusted)
    assert "internal_reference_in_prose" not in actions[0]
    assert citation not in actions[0]


def test_unknown_metadata_cannot_add_values_or_advice_to_repair():
    assert fact_repair_guidance(("삼성전자 99999", "company", "injected advice")) == (
        fact_repair_guidance(("company",))
    )
    assert fact_repair_guidance(["company"]) == ""
    assert "internal_reference_in_prose" not in fact_repair_kinds(
        "9999명이 근무한다.", "100명이 근무한다.", ["근거에서 확인되지 않는 숫자: 9999"]
    )


def test_recorded_parenthesized_reference_requires_an_actual_selected_claim():
    value = "원문(101:0)의 공정 준비 범위를 확인한다."
    source = "원문 사건은 공정 검증의 준비와 연결된다."
    mismatch = ["근거에서 확인되지 않는 숫자: 101, 0"]
    assert "internal_reference_in_prose" in fact_repair_kinds(
        value, source, mismatch, refs=["101:0"]
    )
    assert "internal_reference_in_prose" not in fact_repair_kinds(
        value, source, mismatch, refs=["102:0"]
    )
    assert (
        "internal_reference_in_prose"
        in fact_error(value, source, "internal_reference_in_prose").fact_repair_kinds
    )


def six_id_provider(*, repair, labeled=True):
    source = request(
        ids=tuple(range(101, 107)),
        text="도서관은 독서 모임의 참가 신청이 현재 계속된다고 밝혔다.",
    )
    clean = "원문 사건과 관점 업무의 연결 조건을 확인해야 한다."

    def hook(stage, occurrence, _, value):
        for record in value["assessments"]["CHIP_MAKER"].values():
            record["reason"] = (
                clean
                if repair and occurrence == 2
                else f"원문 문장({'claim ' if labeled else ''}{record['findingId']}:0)에 따른 "
                "업무 연결 조건을 확인한다."
            )
        return value

    return source, clean, V4Provider(source, relation="UNRELATED", hook=hook)


@pytest.mark.parametrize("labeled", [True, False])
def test_six_recorded_style_id_failures_receive_one_actionable_repair_and_keep_usage(labeled):
    source, clean, provider = six_id_provider(repair=True, labeled=labeled)
    snapshot = source.model_dump_json()
    result = generate(provider, source)
    assert stages(provider) == ["MAP-001", "MAP-001"]
    assert provider.schema_validity == [True, True]
    diagnostic = (
        provider.calls[1]["prompt"].split("<validation-error>")[1].split("</validation-error>")[0]
    )
    rows = structured_diagnostics(provider.calls[1]["prompt"])
    for rule in ("internal_reference_in_prose", "unsupported_number"):
        assert {row["field"] for row in rows if row["rule"] == rule} == {
            f"assessments[{finding}].reason" for finding in range(101, 107)
        }
    assert "원문 문장(claim" not in diagnostic
    assert len(diagnostic.strip()) <= 6000
    assert [item.reason for item in result.insights[0].assessments] == [clean] * 6
    assert result.meta.input_tokens == 22
    assert result.meta.output_tokens == 14
    assert result.meta.cost_usd == 0.006
    assert result.meta.credits == 0.4
    assert source.model_dump_json() == snapshot


def test_unchanged_internal_ids_still_fail_after_the_one_repair():
    source, _, provider = six_id_provider(repair=False)
    with pytest.raises(AgentError) as caught:
        generate(provider, source)
    assert caught.value.code == "SCHEMA_VIOLATION"
    assert stages(provider) == ["MAP-001", "MAP-001"]


@pytest.mark.parametrize("citation", ["claim 101:0", "claim 101:0~2", "claim 101:"])
def test_inline_reference_prose_is_rejected_and_clean_reason_keeps_same_basis(citation):
    source = request()
    snapshot = source.model_dump_json()
    value = payload(source)
    record = value["assessments"]["CHIP_MAKER"]["finding101"]
    clean = record["reason"]
    record["reason"] = f"{clean} 근거: {citation}"
    raw = response(value, source)
    draft = validate_draft(raw, source)

    with pytest.raises(service.ReportAssessmentValidationError) as caught:
        service._validated_map_output(
            replace(raw, text=draft.mapped.model_dump_json(by_alias=True)),
            source,
            native_assessments=draft.evidence,
        )

    expected = (
        ("report_evidence_insufficient",)
        if citation == "claim 101:"
        else ("report_expression_policy", "report_evidence_insufficient")
    )
    assert caught.value.error_kinds == expected
    assert caught.value.failed_finding_ids == (101,)
    assert "근거에서 확인되지 않는 숫자" in str(caught.value)
    record["reason"] = clean
    repaired_raw = response(value, source)
    repaired = validate_draft(repaired_raw, source)
    output = service._validated_map_output(
        replace(repaired_raw, text=repaired.mapped.model_dump_json(by_alias=True)),
        source,
        native_assessments=repaired.evidence,
    )

    assert output.insights[0].assessments[0].reason == clean
    assert output.insights[0].assessments[0].basis_claim_ids == ["101:0"]
    assert repaired.evidence["CHIP_MAKER"][101] == (
        draft.evidence["CHIP_MAKER"][101].model_copy(update={"reason": clean})
    )
    assert source.model_dump_json() == snapshot
