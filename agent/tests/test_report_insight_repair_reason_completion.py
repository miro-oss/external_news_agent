"""A concise retry keeps decisions and grounding; unfinished provenance still fails."""

import json
from copy import deepcopy
from dataclasses import replace

import pytest
from report_insight_schema_assertions import assert_same_native_constraints
from test_report_insight_assessment import framed, payload, request, response
from test_report_insight_connection_evidence_dependency import full_validate
from test_report_insight_mixed_connection_repair import corrected, mixed_case
from test_report_insight_preserved_decision_repair import repair_payload, wire_validator
from test_report_insight_v4_pipeline import V4Provider, stages

from app.core.config import Settings
from app.core.errors import AgentError, OutputValidationError
from app.llm import report_insight_service as service
from app.llm.report_insight_assessment import draft_prompt, draft_schema, review_prompt
from app.llm.report_insight_instructions import (
    ASSESSMENT_FINAL_BASIS_RULE,
    ASSESSMENT_REASON_RULE,
)

SOURCE = "HBM4 메모리 가격은 내년에 상승할 것으로 전망된다."
GOOD_REASON = (
    "HBM4 가격 전망은 시스템 조달과 연결된다. "
    "구체적인 조달 변경과 대응 시점은 선택 근거에서 확인되지 않는다."
)
LONG_REASON = (
    "선택된 인용은 HBM4 메모리 가격 상승에 대한 시장 전망으로 시스템 조달과 연결된다. "
    "그러나 해당 문장은 특정 프로젝트의 자원·일정 변경이나 실제 조달 준비 활동을 "
    "명시하지 않아 원문 사건과 관점 업무의 연결은 유지하되 "
    "영향 범위와 대응 시점은 근거로 확인할 수 없다."
)
ID_SUFFIX = " 선택된 근거(claimId=101:0, source"


def reason_case():
    # Invented text and IDs: the production report and provider output stay local.
    source, value = mixed_case(neighbor=True)
    finding = source.findings[0]
    finding.claims[0].text = SOURCE
    finding.sentences[0].text = SOURCE
    other_source = "TSMC는 HBM4 메모리 가격이 내년에 상승할 것으로 전망했다."
    finding.claims[1].text = other_source
    finding.claims[1].evidence_sentence_ids = [1]
    finding.sentences.append(
        finding.sentences[0].model_copy(update={"index": 1, "text": other_source})
    )
    entry = value["assessments"]["IT_INFRA"]["finding101"]
    for field in ("relationBasis", "impactBasis", "urgencyBasis"):
        entry[field]["quote"] = SOURCE
    entry["reason"] = "HBM5의 가격 전망은 시스템 조달과 연결된다."
    return source, value


def corrected_reason(value, defect=None):
    fixed = corrected(value)
    fixed["assessments"]["IT_INFRA"]["finding101"]["reason"] = {
        None: GOOD_REASON,
        "internal_id": LONG_REASON + ID_SUFFIX,
        "unselected_company": "TSMC의 " + GOOD_REASON,
        "wrong_product": GOOD_REASON.replace("HBM4", "HBM5"),
    }[defect]
    return fixed


def build_repair(source, value, prompt):
    raw = response(value, source)
    error = service._native_assessment_repair_errors(raw, source, template_wire=True)
    assert error is not None
    assert set(error.native_connection_repairs) == {101}
    assert not error.native_prose_repairs
    assert {issue.rule for issue in error.validation_issues} == {
        "report_axis_market_forecast_only_project_change",
        "report_axis_market_forecast_only_scheduled_preparation",
        "source_binding",
    }
    validated = []

    def validate(candidate):
        validated.append(json.loads(candidate.text))
        return full_validate(candidate, source)

    repair = object.__new__(service.ReportInsightService)._repair_call(
        prompt(source), draft_schema(source), raw.text, error, validate
    )
    return repair, raw, validated


@pytest.mark.parametrize("prompt", [draft_prompt, review_prompt], ids=["MAP", "REVIEW"])
def test_complete_reason_repairs_mixed_errors_without_rewriting_proven_connection(prompt):
    source, value = reason_case()
    before = source.model_dump_json(), deepcopy(value)
    repair, raw, validated = build_repair(source, value, prompt)
    fixed = repair_payload(repair, source, corrected_reason(value))
    assert set(fixed["assessments"]["IT_INFRA"]) == {"finding101"}
    wire_validator(repair).validate(fixed)
    accepted = repair.validate(replace(raw, text=json.dumps(fixed, ensure_ascii=False)))
    original = json.loads(raw.text)["assessments"]["IT_INFRA"]
    merged = validated[0]["assessments"]["IT_INFRA"]
    assert merged["finding202"] == original["finding202"]
    assert (
        merged["finding101"]["decision"]["connection"]
        == (original["finding101"]["decision"]["connection"])
    )
    item = accepted.evidence["IT_INFRA"][101]
    assert item.reason == GOOD_REASON
    assert item.impact_scope == item.urgency_state == "UNDETERMINED"
    assert item.impact_basis is item.urgency_basis is None
    assert before == (source.model_dump_json(), value)


@pytest.mark.parametrize("prompt", [draft_prompt, review_prompt], ids=["MAP", "REVIEW"])
@pytest.mark.parametrize("defect", ["internal_id", "unselected_company", "wrong_product"])
def test_same_repaired_decisions_still_reject_unsupported_reason_without_sanitizing(prompt, defect):
    source, value = reason_case()
    repair, raw, validated = build_repair(source, value, prompt)
    fixed = repair_payload(repair, source, corrected_reason(value, defect))
    reason = fixed["assessments"]["IT_INFRA"]["finding101"]["reason"]
    if defect == "internal_id":
        assert len(reason) == 180
        # The complete text preceding the invalid appendix independently passes.
        complete = corrected_reason(value)
        complete["assessments"]["IT_INFRA"]["finding101"]["reason"] = LONG_REASON
        full_validate(response(complete, source), source)
    elif defect == "unselected_company":
        assert "TSMC" in source.findings[0].claims[1].text
        assert "TSMC" not in source.findings[0].claims[0].text
    wire_validator(repair).validate(fixed)
    candidate = replace(raw, text=json.dumps(fixed, ensure_ascii=False))
    with pytest.raises(service.ReportAssessmentValidationError) as caught:
        repair.validate(candidate)
    assert {issue.rule for issue in caught.value.validation_issues} == {
        "internal_id": {"internal_reference_in_prose", "unsupported_number"},
        "unselected_company": {"company"},
        "wrong_product": {"source_binding"},
    }[defect]
    assert all(
        issue.field == "assessments[101].reason" and issue.claim_ids == ("101:0",)
        for issue in caught.value.validation_issues
    )
    assert validated[0]["assessments"]["IT_INFRA"]["finding101"]["reason"] == reason
    assert json.loads(candidate.text) == fixed


def test_reason_completion_rule_follows_repair_data_without_changing_its_sources():
    source, value = reason_case()
    repair, _, _ = build_repair(source, value, draft_prompt)
    assert ASSESSMENT_REASON_RULE in repair.prompt.split("</validation-error>", 1)[1]
    assert [finding["id"] for finding in framed(repair.prompt)["findings"]] == [101]
    assert [claim["id"] for claim in framed(repair.prompt)["findings"][0]["claims"]] == [
        "101:0",
        "101:1",
    ]


def recorded_retry_case(finding_id):
    # Only the three selected source sentences and failed prose are retained.
    # Captured files and full articles are not required at test runtime.
    if finding_id == 8290:
        texts = {
            2: "일본 요코하마에는 350억엔을 들여 첨단 패키징 연구 거점(APL)을 짓고 있다.",
            3: "베트남 타이응우옌에는 15억달러를 들여 칩 출하 전 검사와 패키징을 맡는 "
            "후공정 거점을 추진하고 있다.",
            4: "미국 텍사스주 테일러에는 미국 반도체법 보조금과 연계한 첨단 파운드리 "
            "생산 라인을 짓고 있다.",
        }
        selected = (4, 2, 3)
        bad = (
            "원문은 텍사스 테일러의 미국 반도체법 보조금 연계 파운드리 라인(8290:4), "
            "요코하마의 350억엔 규모 APL(8290:2), 타이응우옌의 15억달러 후공정 "
            "추진(8290:3)을 보도해 프로젝트의 자원·일정 변경 가능성을 시사한다. "
            "다만 기사에 투자주체와 구체 일정·완료 상태가 명시되지 않아 영향 범위와 "
            "책임 주체는불"
        )
        initial = (
            "삼성전자의 일본·베트남·미국 등 다지역 연구·생산 거점 구축은 메모리 공급 "
            "부족 대응을 위한 공급망·생산 역량 확충으로 투자·생산(공정) 측면의 수급 "
            "제약 완화와 설비투자 변경과 직접 연관된다."
        )
        clean = (
            "파운드리 생산 라인과 패키징 연구·후공정 거점 구축은 수급 제약 판단과 "
            "연결되며, 거점별 투자 규모와 추진 단계를 구분해 평가할 근거다."
        )
        work, urgency = "SUPPLY_DEMAND_CONSTRAINT", "SCHEDULED_PREPARATION"
    else:
        assert finding_id == 8286
        texts = {8: "이 같은 전망을 토대로 미즈호는 주요 업체의 목표주가를 잇달아 상향 조정했다."}
        selected = (8, 8, 8)
        bad = (
            "미즈호의 분석을 근거로 주요 업체들의 목표주가가 상향 조정된 사실이 원문에 "
            "확인되어, GUIDANCE 관점에서 관련 기업의 투자전망·분석을 즉시 재검토해야 "
            "할 근거로 판단된다. 원문은 구체적 매매 권고나 목표가격 수치를 제시하지 않는다."
        )
        initial = bad
        clean = (
            "미즈호가 주요 업체의 목표주가를 상향 조정한 보도는 증권사의 기업 전망이 "
            "달라졌음을 보여주므로 투자 전망 판단에 반영할 근거다."
        )
        work, urgency = "GUIDANCE", "IMMEDIATE"
    source = request(ids=(finding_id,), audiences=("MARKET_INVESTOR",))
    finding = source.findings[0]
    claim, sentence = finding.claims[0], finding.sentences[0]
    finding.claims = [
        claim.model_copy(
            update={"id": f"{finding_id}:{index}", "text": text, "evidence_sentence_ids": [index]}
        )
        for index, text in texts.items()
    ]
    finding.sentences = [
        sentence.model_copy(update={"index": index, "text": text}) for index, text in texts.items()
    ]
    value = payload(source)
    entry = value["assessments"]["MARKET_INVESTOR"][f"finding{finding_id}"]
    entry.update(work=work, impactScope="PROJECT_CHANGE", urgencyState=urgency, reason=initial)
    for field, index in zip(
        ("relationBasis", "impactBasis", "urgencyBasis"), selected, strict=True
    ):
        entry[field] = {"claimId": f"{finding_id}:{index}", "quote": texts[index]}
    return source, value, bad, clean


@pytest.mark.parametrize("prompt", [draft_prompt, review_prompt], ids=["MAP", "REVIEW"])
@pytest.mark.parametrize("finding_id", [8290, 8286], ids=["internal-id", "policy-disclaimer"])
def test_recorded_retry_requires_source_reason_and_preserves_decisions(prompt, finding_id):
    source, value, bad, clean = recorded_retry_case(finding_id)
    before = source.model_dump_json(), deepcopy(value)
    raw = response(value, source)
    with pytest.raises(service.ReportAssessmentValidationError) as caught:
        full_validate(raw, source)
    assert set(caught.value.native_prose_repairs) == {finding_id}
    assert not caught.value.native_connection_repairs
    repair = object.__new__(service.ReportInsightService)._repair_call(
        prompt(source),
        draft_schema(source),
        raw.text,
        caught.value,
        lambda candidate: full_validate(candidate, source),
    )
    tail = repair.prompt.split("</validation-error>", 1)[1]
    assert ASSESSMENT_FINAL_BASIS_RULE in tail
    assert "출력 직전 수정 가능한 reason·condition을 확인한다" in tail
    assert "괄호 인용·근거 목록 형태로도 자연어에 복사하지 않는다" in tail
    assert "진단에 지목된 이름이나 표현이 없다는 안내로 대체하지 않는다" in tail
    assert "매매 권고·목표가격을 제시하지 않았다는 면책" in tail
    (finding,) = framed(repair.prompt)["findings"]
    assert finding["proseRepair"]["editableFields"] == ["reason"]
    assert finding["sentences"] == [
        {"index": sentence.index, "text": sentence.text}
        for sentence in source.findings[0].sentences
    ]

    replacement = deepcopy(value)
    entry = replacement["assessments"]["MARKET_INVESTOR"][f"finding{finding_id}"]
    original_decision = json.loads(raw.text)["assessments"]["MARKET_INVESTOR"][
        f"finding{finding_id}"
    ]["decision"]
    for reason in (bad, clean):
        entry["reason"] = reason
        fixed = repair_payload(repair, source, replacement)
        wire_validator(repair).validate(fixed)
        assert (
            fixed["assessments"]["MARKET_INVESTOR"][f"finding{finding_id}"]["decision"]
            == original_decision
        )
        candidate = replace(raw, text=json.dumps(fixed, ensure_ascii=False))
        if reason == bad:
            with pytest.raises(OutputValidationError) as failed:
                repair.validate(candidate)
            expected = "unsupported_number" if finding_id == 8290 else "report_expression_policy"
            assert expected in {issue.rule for issue in failed.value.validation_issues}
            assert json.loads(candidate.text) == fixed
        else:
            accepted = repair.validate(candidate)
            assert accepted.evidence["MARKET_INVESTOR"][finding_id].reason == clean
    assert before == (source.model_dump_json(), value)


@pytest.mark.parametrize("prompt", [draft_prompt, review_prompt], ids=["MAP", "REVIEW"])
def test_unlocalized_native_full_repair_still_reapplies_reason_completion_rule(prompt):
    source, value = reason_case()
    schema = draft_schema(source)
    fixed = response(corrected_reason(value), source)
    repair = object.__new__(service.ReportInsightService)._repair_call(
        prompt(source),
        schema,
        '{"assessments":',
        ValueError("The original JSON is incomplete."),
        lambda candidate: full_validate(candidate, source),
    )
    assert_same_native_constraints(repair.response_schema, schema)
    assert ASSESSMENT_REASON_RULE in repair.prompt.split("</validation-error>", 1)[1]
    assert [finding["id"] for finding in framed(repair.prompt)["findings"]] == [101, 202]
    assert repair.validate(fixed).evidence["IT_INFRA"][101].reason == GOOD_REASON


def test_legacy_map_repair_keeps_its_public_contract_without_native_reason_suffix():
    source, value = reason_case()
    original = response(corrected_reason(value), source)
    native = full_validate(original, source)
    fixed = replace(original, text=native.mapped.model_dump_json(by_alias=True))
    schema = service.report_insight_map_schema(source)
    repair = object.__new__(service.ReportInsightLegacyService)._repair_call(
        service._report_insight_prompt(source),
        schema,
        '{"insights":',
        ValueError("The original JSON is incomplete."),
        lambda candidate: service._validated_map_output(candidate, source),
    )
    assert repair.response_schema == schema
    assert repair.prompt.split("</validation-error>", 1)[1].strip() == ""
    assert ASSESSMENT_REASON_RULE not in repair.prompt
    assert repair.validate(fixed) == native.mapped


def test_reduce_repair_does_not_receive_native_assessment_reason_suffix():
    source, value = reason_case()
    native = full_validate(response(corrected_reason(value), source), source)
    retrieved = {
        insight.audience: service.retrieve_report_insight_evidence(
            source, insight.audience, insight.assessments, preserve_assessment_bases=True
        )
        for insight in native.mapped.insights
    }
    allowed = {audience: result.claim_ids for audience, result in retrieved.items()}
    schema = service.report_insight_reduce_schema(source, allowed, structured=True)

    def validate(candidate):
        return service._validated_v4_reduce_output(
            candidate,
            source,
            native.mapped,
            allowed,
            native_assessments=native.evidence,
            template_wire=True,
        )

    repair = object.__new__(service.ReportInsightService)._repair_call(
        service._reduce_v4_prompt(source, native, retrieved, allowed),
        schema,
        '{"insights":',
        ValueError("The original JSON is incomplete."),
        validate,
    )
    assert repair.response_schema == schema
    assert repair.validate is validate
    assert repair.prompt.split("</validation-error>", 1)[1].strip() == ""
    assert ASSESSMENT_REASON_RULE not in repair.prompt
    assert "retrievedEvidence" in framed(repair.prompt)


@pytest.mark.parametrize("defect", [None, "internal_id", "unselected_company"])
def test_pipeline_keeps_one_retry_and_observed_usage_when_reason_remains_invalid(defect):
    source, value = reason_case()

    def hook(stage, occurrence, data, result):
        if stage.startswith("REDUCE"):
            for insight in result["insights"]:
                insight.update(
                    headline="시스템 조달의 변경과 준비 일정을 살펴본다.",
                    overview=[
                        {
                            "text": (
                                "서버 도입 자원과 준비 일정 변경은 시스템 조달 업무에 영향을 준다."
                            ),
                            "basisClaimIds": ["202:0"],
                            "assumption": "서버 도입 프로젝트의 자원 배분이 변경되는 경우",
                        }
                    ],
                )
            return result
        proposed = deepcopy(
            value if stage == "MAP-001" and occurrence == 1 else corrected_reason(value, defect)
        )
        wanted = {f"finding{finding['id']}" for finding in data["findings"]}
        proposed["assessments"]["IT_INFRA"] = {
            key: entry
            for key, entry in proposed["assessments"]["IT_INFRA"].items()
            if key in wanted
        }
        return proposed

    provider = V4Provider(source, hook=hook)
    engine = service.ReportInsightService(Settings(_env_file=None, AGENT_MOCK=False), provider)
    if defect is None:
        result = engine.generate(source)
        assert stages(provider) == ["MAP-001", "MAP-001", "REVIEW-001", "EXPLAIN-001", "REDUCE-001"]
        assert result.meta.input_tokens == 55 and result.meta.output_tokens == 35
        assert {item.finding_id for item in result.insights[0].assessments} == {101, 202}
    else:
        with pytest.raises(AgentError) as caught:
            engine.generate(source)
        assert caught.value.code == "SCHEMA_VIOLATION"
        expected = ["MAP-001", "MAP-001"]
        if defect in {"internal_id", "unselected_company"}:
            expected += ["REVIEW-001", "REVIEW-001", "EXPLAIN-001", "EXPLAIN-001"]
            assert caught.value.details["validationFailure"]["stage"] == "EXPLAIN-001"
        assert stages(provider) == expected
        assert caught.value.details["usage"]["inputTokens"] == 11 * len(expected)
        assert caught.value.details["usage"]["outputTokens"] == 7 * len(expected)
    initial, repaired = provider.wire_payloads[:2]
    assert set(repaired["assessments"]["IT_INFRA"]) == {"finding101"}
    assert (
        repaired["assessments"]["IT_INFRA"]["finding101"]["decision"]["connection"]
        == (initial["assessments"]["IT_INFRA"]["finding101"]["decision"]["connection"])
    )
    assert all(provider.schema_validity)
