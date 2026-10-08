"""Synthetic retries retain field-local rules and already validated native values."""

import json
from copy import deepcopy

from test_report_insight_assessment import payload, request
from test_report_insight_native_field_diagnostics import rejected_projection
from test_report_insight_repair_actions import structured_diagnostics
from test_report_insight_v4_pipeline import V4Provider, stages

from app.core.config import Settings
from app.llm import report_insight_service as service
from app.llm.base import ProviderResponse, ProviderUsage
from app.llm.report_insight_assessment import draft_prompt, validate_template_draft
from app.llm.report_insight_fact_rendering import FACT_TEMPLATE_INSTRUCTIONS


def field_actions(error):
    actions = service._repair_action_entries(error)
    return {
        field: "\n".join(action for action in actions if f"nativeFields={field} " in action)
        for field in ("reason", "decision.connection.condition")
    }


def test_numeric_reason_and_company_condition_have_separate_literal_free_repair_actions():
    source = request()
    value = payload(source, relation="CONDITIONAL")
    target = value["assessments"]["CHIP_MAKER"]["finding101"]
    target["reason"] = "생산량은 999개다. 공정 검증 일정을 확인한다."
    target["condition"] = "삼성전자의 준비가 공정 검증 일정에 필요한 경우"
    before = deepcopy(value), source.model_dump_json()

    error = rejected_projection(source, value)
    actions = field_actions(error)

    assert "[unsupported_number]" in actions["reason"]
    assert "[company]" not in actions["reason"]
    assert "[company]" in actions["decision.connection.condition"]
    assert "[unsupported_number]" not in actions["decision.connection.condition"]
    for action in actions.values():
        assert "findingId=101" in action and "refs=['101:0']" in action
        assert "999" not in action and "삼성전자" not in action
    prompt = service._report_insight_repair_prompt(draft_prompt(source), "unused", error)
    diagnostics = prompt.split("<validation-error>", 1)[1].split("</validation-error>", 1)[0]
    rows = structured_diagnostics(prompt)
    reason_rules = {row["rule"] for row in rows if row["field"] == "assessments[101].reason"}
    condition_rules = {
        row["rule"]
        for row in rows
        if row["field"] == "assessments[101].decision.connection.condition"
    }
    assert "unsupported_number" in reason_rules and "company" not in reason_rules
    assert condition_rules == {"company"}
    assert len(diagnostics.strip()) <= 6000
    assert target["reason"] not in diagnostics and target["condition"] not in diagnostics
    assert "999" in str(error) and "삼성전자" in str(error)
    assert (value, source.model_dump_json()) == before


def test_multiple_rules_in_one_field_do_not_leak_into_the_other_fields_action():
    source = request()
    value = payload(source, relation="CONDITIONAL")
    target = value["assessments"]["CHIP_MAKER"]["finding101"]
    target["reason"] = "TSMC의 생산량은 999개다. 공정 검증 일정을 확인한다."
    target["condition"] = "삼성전자의 준비가 공정 검증 일정에 필요한 경우"

    actions = field_actions(rejected_projection(source, value))

    assert "[unsupported_number]" in actions["reason"]
    assert "[company]" in actions["reason"]
    assert "[company]" in actions["decision.connection.condition"]
    assert "[unsupported_number]" not in actions["decision.connection.condition"]
    assert all("TSMC" not in action and "999" not in action for action in actions.values())


def test_mock_retry_can_keep_grounded_company_product_and_quantity_without_changing_good_fields(
    monkeypatch,
):
    source = request(
        ids=(101, 102),
        text="삼성전자는 HBM4 생산라인 2개의 가동 중단이 현재 계속된다고 밝혔다.",
    )
    source_before = source.model_dump_json()
    corrected = "삼성전자의 생산라인 2개와 HBM4 공정 검증 영향을 확인한다."
    healthy = "현재 생산 제약에 따른 공정 검증 부담을 확인한다."

    def hook(stage, occurrence, _, value):
        if "assessments" not in value:
            return value
        for key, item in value["assessments"]["CHIP_MAKER"].items():
            item["reason"] = corrected if key == "finding101" else healthy
            item["condition"] = "HBM4 공정 검증 준비가 필요한 경우"
        if stage == "MAP-001" and occurrence == 1:
            value["assessments"]["CHIP_MAKER"]["finding101"]["reason"] = corrected.replace(
                "2개", "99개"
            )
        return value

    validated_batches = []
    original_validate = service._validated_map_output

    def capture_validated_map(response, validation_request, **kwargs):
        result = original_validate(response, validation_request, **kwargs)
        if len(validation_request.findings) == 2:
            validated_batches.append(deepcopy(kwargs["native_assessments"]))
        return result

    monkeypatch.setattr(service, "_validated_map_output", capture_validated_map)
    provider = V4Provider(source, relation="CONDITIONAL", hook=hook)
    engine = service.ReportInsightService(Settings(_env_file=None, AGENT_MOCK=False), provider)

    result = engine.generate(source)

    assert stages(provider) == ["MAP-001", "MAP-001", "REVIEW-001", "REDUCE-001"]
    first_wire, retry_wire = provider.wire_payloads[:2]
    assert set(retry_wire["assessments"]["CHIP_MAKER"]) == {"finding101"}
    assert (
        retry_wire["assessments"]["CHIP_MAKER"]["finding101"]["decision"]
        == (first_wire["assessments"]["CHIP_MAKER"]["finding101"]["decision"])
    )
    first_response = ProviderResponse(
        provider.response_texts[0], "mock", "offline", ProviderUsage()
    )
    initial = validate_template_draft(first_response, source).evidence["CHIP_MAKER"]
    repaired = validated_batches[0]["CHIP_MAKER"]
    assert repaired[102] == initial[102]
    assert repaired[101].model_dump(exclude={"reason"}) == initial[101].model_dump(
        exclude={"reason"}
    )
    assert repaired[101].reason == corrected
    assert corrected in result.insights[0].assessments[0].reason
    assert healthy in result.insights[0].assessments[1].reason
    retry_prompt = provider.calls[1]["prompt"]
    assert FACT_TEMPLATE_INSTRUCTIONS in retry_prompt
    assert "기업·기관·제품의 고유명사, 숫자와 날짜를 reason에 다시 쓰지 말고" not in retry_prompt
    assert "기업·숫자·연도·매출을 재요약하지 말고" not in retry_prompt
    assert "99개" not in retry_prompt.split("<validation-error>", 1)[1]
    assert source.model_dump_json() == source_before
    assert json.loads(provider.response_texts[0]) == first_wire
