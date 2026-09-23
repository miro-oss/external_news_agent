import json
from datetime import date, timedelta
from itertools import permutations

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.core.config import Settings, get_settings
from app.core.errors import AgentError
from app.llm.base import ProviderResponse, ProviderUsage
from app.llm.weekly_report_service import WeeklyReportWriterService
from app.main import create_app
from app.schemas.weekly_report import WeeklyReportRequest

START = date(2026, 9, 14)


def daily(offset=0, finding=501, issue=10, text="삼성전자가 HBM4 생산 계획을 발표했다."):
    return {
        "reportId": 100 + offset,
        "reportDate": str(START + timedelta(offset)),
        "title": "일일 통합 보고서",
        "reflectedFindingIds": [finding],
        "issueIdsByFinding": {str(finding): issue},
        "structuredContent": {
            "executiveSummary": [text],
            "importantEvents": [
                {
                    "title": "삼성전자 HBM4 생산",
                    "summaryKo": text,
                    "significance": text,
                    "sourceFindingIds": [finding],
                }
            ],
            "watchItems": [],
            "sourceNotes": [],
        },
    }


def payload(*sources):
    sources = sources or (daily(),)
    present = {source["reportDate"] for source in sources}
    return {
        "idempotencyKey": "weekly-report:77",
        "plan": "FREE",
        "reportId": 77,
        "reportDate": str(START),
        "reportEndDate": str(START + timedelta(6)),
        "sources": list(sources),
        "missingReportDates": [
            str(START + timedelta(offset))
            for offset in range(7)
            if str(START + timedelta(offset)) not in present
        ],
        "sourceNotes": ["저장된 일일 보고서만 사용했습니다."],
    }


def output(ids=None, **overrides):
    text = "삼성전자가 HBM4 생산 계획을 발표했다."
    return {
        "title": "발생하지 않은 날짜를 붙인 임의 제목",
        "executiveSummary": [text],
        "importantEvents": [
            {
                "title": "삼성전자 HBM4 생산",
                "summaryKo": text,
                "significance": "없는 날짜 2027-01-01에 계약했다.",
                "sourceFindingIds": ids or [501],
            }
        ],
        "watchItems": [],
        "sourceNotes": ["임의 출처"],
        **overrides,
    }


class Provider:
    def __init__(self, *results):
        self.results = list(results)
        self.calls = []

    def generate(self, **kwargs):
        self.calls.append(kwargs)
        return ProviderResponse(
            text=json.dumps(self.results.pop(0), ensure_ascii=False),
            provider="openai",
            model="weekly-model",
            usage=ProviderUsage(input_tokens=10, output_tokens=5),
        )


def write(data, *results):
    provider = Provider(*results)
    response = WeeklyReportWriterService(Settings(AGENT_MOCK=False), provider).write(
        WeeklyReportRequest.model_validate(data),
    )
    return response, provider


def test_synthesizes_saved_same_issue_and_expands_chronology_despite_subset_selection():
    data = payload(daily(), daily(3, 502, text="삼성전자가 HBM4 생산 일정을 공개했다."))
    response, provider = write(data, output())
    assert len(response.important_events) == 1
    event = response.important_events[0]
    assert event.source_finding_ids == [501, 502]
    assert event.significance.index("2026-09-14") < event.significance.index("2026-09-17")
    assert "생산 일정을 공개했다" in event.significance
    assert "2027-01-01" not in response.markdown_body
    assert "2026-09-14 ~ 2026-09-20 주간 통합" in response.title
    assert response.source_notes == data["sourceNotes"]
    assert response.meta.input_tokens == 10
    assert response.meta.prompt_version == "weekly-report.ko.v1"
    assert "전주 대비 변화" in provider.calls[0]["system_instruction"]
    assert "issueIdsByFinding" in provider.calls[0]["prompt"]


def test_different_known_issues_cannot_be_combined_by_provider_superset():
    first, second = daily(), daily(1, 502, 20, "SK하이닉스가 HBM4 생산 계획을 발표했다.")
    response, _ = write(payload(first, second), output([501, 502]))
    assert len(response.important_events) == 2
    assert [event.source_finding_ids for event in response.important_events] == [[501], [502]]
    assert "SK하이닉스" not in response.important_events[0].significance
    assert "삼성전자" not in response.important_events[1].significance


def test_same_generic_assertion_does_not_merge_different_known_issue_ids():
    response, _ = write(payload(daily(), daily(1, 502, 20)), output([501, 502]))
    assert len(response.important_events) == 2


def test_partial_reference_cannot_claim_support_of_multi_finding_daily_event():
    source = daily()
    source["reflectedFindingIds"] = [501, 502]
    source["structuredContent"]["importantEvents"][0]["sourceFindingIds"] = [501, 502]
    response, _ = write(payload(source), output([501]))
    assert response.important_events[0].source_finding_ids == [501, 502]


def test_unsupported_claims_and_invented_titles_are_replaced_with_saved_text():
    generated = output(executiveSummary=["삼성전자가 HBM4 생산을 완료했다."])
    generated["importantEvents"][0].update(
        {
            "title": "삼성전자 100조원 계약 체결",
            "summaryKo": "삼성전자가 HBM4 생산을 완료했다.",
        }
    )
    response, _ = write(payload(), generated)
    assert "완료" not in response.markdown_body
    assert "100조원" not in response.markdown_body
    assert response.executive_summary == ["삼성전자가 HBM4 생산 계획을 발표했다."]


def test_unknown_finding_ids_repair_once_and_accumulate_usage():
    response, provider = write(payload(), output([999]), output())
    assert len(provider.calls) == 2
    assert response.meta.input_tokens == 20
    assert response.important_events[0].source_finding_ids == [501]


@pytest.mark.parametrize("summary", [["x"] * 4, ["x" * 101], []])
def test_invalid_executive_summary_contract_rejected_with_usage(summary):
    provider = Provider(output(executiveSummary=summary), output(executiveSummary=summary))
    with pytest.raises(AgentError) as error:
        WeeklyReportWriterService(Settings(AGENT_MOCK=False), provider).write(
            WeeklyReportRequest.model_validate(payload()),
        )
    assert error.value.code == "SCHEMA_VIOLATION"
    assert error.value.details["usage"]["inputTokens"] == 20


@pytest.mark.parametrize(
    "change",
    [
        {"reportDate": "2026-09-15"},
        {"reportEndDate": "2026-09-21"},
        {"missingReportDates": []},
        {"missingReportDates": ["2026-09-14"]},
    ],
)
def test_week_requires_exact_disjoint_seven_day_range(change):
    with pytest.raises(ValidationError):
        WeeklyReportRequest.model_validate({**payload(), **change})


def test_no_sources_never_calls_provider_and_legacy_markdown_is_not_input():
    data = payload()
    data["sources"] = []
    data["missingReportDates"] = [str(START + timedelta(offset)) for offset in range(7)]
    response, provider = write(data)
    assert not provider.calls
    assert response.important_events == []
    source = daily()
    source["structuredContent"] = None
    response, provider = write(payload(source))
    assert not provider.calls
    assert response.important_events == []


def test_endpoint_auth_and_week_validation_use_existing_guards():
    app = create_app()
    app.dependency_overrides[get_settings] = lambda: Settings(
        AGENT_MOCK=True, AGENT_SHARED_SECRET="weekly-test"
    )
    with TestClient(app) as client:
        assert client.post("/v1/weekly-report", json=payload()).status_code == 401
        response = client.post(
            "/v1/weekly-report", json=payload(), headers={"X-Agent-Token": "weekly-test"}
        )
        assert response.status_code == 200
        assert response.json()["importantEvents"][0]["sourceFindingIds"] == [501]
        invalid = client.post(
            "/v1/weekly-report",
            json={**payload(), "reportEndDate": "2026-09-21"},
            headers={"X-Agent-Token": "weekly-test"},
        )
        assert invalid.status_code == 422


def test_distinct_watch_issues_keep_their_independent_sources_when_reason_is_identical():
    source = daily()
    reason = "최종 계약 체결 여부를 확인한다."
    source["reflectedFindingIds"] = [501, 502]
    source["issueIdsByFinding"] = {"501": 10, "502": 20}
    source["structuredContent"]["watchItems"] = [
        {"topic": topic, "reason": reason, "sourceFindingIds": [id_]}
        for topic, id_ in (("삼성전자 계약", 501), ("SK하이닉스 계약", 502))
    ]
    response, _ = write(
        payload(source), output(watchItems=source["structuredContent"]["watchItems"])
    )
    assert len(response.watch_items) == 2
    assert [item.source_finding_ids for item in response.watch_items] == [[501], [502]]


@pytest.mark.parametrize(
    ("saved", "invented"),
    [
        ("삼성전자가 SK하이닉스에 HBM을 공급했다.", "SK하이닉스가 삼성전자에 HBM을 공급했다."),
        ("삼성전자는 HBM4 공급을 확정하지 않았다.", "삼성전자는 HBM4 공급을 확정했다."),
        ("삼성전자가 HBM4 생산을 늘릴 전망이다.", "삼성전자가 HBM4 생산을 늘렸다."),
    ],
)
def test_saved_wording_preserves_roles_negation_and_forecasts(saved, invented):
    generated = output(executiveSummary=[invented])
    generated["importantEvents"][0]["summaryKo"] = invented
    response, _ = write(payload(daily(text=saved)), generated)
    assert response.executive_summary == [saved]
    assert response.important_events[0].summary_ko == saved
    assert invented not in response.markdown_body


def test_legacy_unknown_issue_ids_do_not_merge_different_titles_with_same_summary():
    first, second = daily(), daily(1, 502)
    first["issueIdsByFinding"] = second["issueIdsByFinding"] = {}
    first["structuredContent"]["importantEvents"][0]["title"] = "삼성전자 공장 계획"
    second["structuredContent"]["importantEvents"][0]["title"] = "마이크론 공장 계획"
    response, _ = write(payload(first, second), output([501, 502]))
    assert len(response.important_events) == 2


def test_real_fallback_daily_null_significance_remains_usable():
    source = daily()
    source["structuredContent"]["importantEvents"][0]["significance"] = None
    response, provider = write(payload(source), output())
    assert len(provider.calls) == 1
    assert response.important_events[0].source_finding_ids == [501]


@pytest.mark.parametrize("order", [(0, 1, 2), (2, 0, 1), (0, 2, 1)])
def test_unknown_identity_cannot_bridge_two_known_distinct_issues(order):
    sources = [daily(0, 501, 10), daily(1, 502, 20), daily(2, 503)]
    sources[2]["issueIdsByFinding"] = {}
    data = payload(*(sources[index] for index in order))
    response, _ = write(data, output([501, 502, 503]))
    for event in response.important_events:
        assert not {501, 502} <= set(event.source_finding_ids)


@pytest.mark.parametrize("order", list(permutations(range(3))))
@pytest.mark.parametrize("watch", [False, True])
def test_multi_issue_claim_cannot_bridge_distinct_histories_in_any_order(order, watch):
    from app.llm.weekly_report_service import SavedClaim, _claim_groups

    claims = [
        SavedClaim(START, "공급 계획", "확정 여부를 확인한다.", (501,), watch, (10,)),
        SavedClaim(
            START + timedelta(1), "공급 계획", "확정 여부를 확인한다.", (502,), watch, (20,)
        ),
        SavedClaim(
            START + timedelta(2), "공급 계획", "확정 여부를 확인한다.",
            (503, 504), watch, (10, 20)
        ),
    ]
    followup = SavedClaim(START + timedelta(3), "진행 경과", "검토 중이다.", (505,), watch, (10,))
    groups = _claim_groups([*(claims[index] for index in order), followup])
    assert {frozenset(id_ for claim in group for id_ in claim.ids) for group in groups} == {
        frozenset({501, 505}), frozenset({502}), frozenset({503, 504}),
    }


def test_long_daily_assertion_uses_saved_issue_title_in_executive_summary():
    saved = (
        "삼성전자는 "
        + "HBM 공급 계획과 후속 검토 일정을 여러 차례 설명했지만 " * 3
        + "공급을 확정하지 않았다."
    )
    source = daily(text=saved)
    response, _ = write(payload(source), output())
    assert response.executive_summary == ["삼성전자 HBM4 생산"]
    assert saved in response.important_events[0].significance
    assert "확정하지 않았다" in response.important_events[0].summary_ko
