"""A native-valid REVIEW is adopted only after its selected-source prose passes."""

from copy import deepcopy

import pytest
from test_report_insight_assessment import framed, request
from test_report_insight_review_time_admission import assert_usage
from test_report_insight_v4_pipeline import V4Provider, generate, stages

from app.core.errors import AgentError
from app.llm import report_insight_service as service

AUDIENCE = "MARKET_INVESTOR"
CONSTRUCTION = "일본 요코하마에는 350억엔을 들여 첨단 패키징 연구 거점(APL)을 짓고 있다."
FORECAST = (
    "삼성전자가 메모리 반도체 가격 상승과 고대역폭메모리(HBM) 공급 확대에 힘입어 "
    "남은 4·4분기와 내년에 실적 성장세를 이어갈 것으로 예상된다."
)
BAD_REFERENCE_REASON = (
    "요코하마의 첨단 패키징 연구 거점 건설은 설비투자 집행에 연결된다. "
    "근거는 동일 문장(8290:2)이다."
)
BAD_SUMMARY_REASON = "HBM4 공급 확대 전망은 삼성전자의 수익성 개선 가능성과 연결된다."
GOOD_REASONS = {
    8290: "첨단 패키징 연구 거점의 건설은 설비투자 집행 판단에 직접 연결된다.",
    8298: "메모리 가격 상승과 HBM 공급 확대 전망은 삼성전자의 수익성 판단에 연결된다.",
}


def review_source():
    # Preserve the observed shape: the claim summary says HBM4, but its raw
    # source only says HBM. The other failed prose copied its structural ID.
    source = request(ids=(8290, 8298), audiences=(AUDIENCE,))
    for finding, identifier, summary, raw in (
        (
            source.findings[0],
            "8290:2",
            "일본 요코하마에 첨단 패키징 연구 거점(APL)을 350억엔 규모로 짓고 있다.",
            CONSTRUCTION,
        ),
        (
            source.findings[1],
            "8298:0",
            "증권가는 메모리 가격 상승과 HBM4 공급 확대가 "
            "삼성전자 이익 성장을 이끌 것으로 전망한다.",
            FORECAST,
        ),
    ):
        finding.claims[0].id = identifier
        finding.claims[0].text = summary
        finding.sentences[0].text = raw
    source.findings[1].claims[0].claim_type = "FORECAST"
    return source


def review_provider(source, *, mandatory=False, invalid_explanation=False, defect=None):
    findings = {finding.id: finding for finding in source.findings}

    def hook(stage, occurrence, data, value):
        if stage.startswith("REDUCE"):
            value["insights"][0].update(
                headline="설비투자와 수익성 전망을 확인한다.",
                overview=[
                    {
                        "text": GOOD_REASONS[8290],
                        "basisClaimIds": ["8290:2"],
                        "assumption": "해당 연구 거점의 건설이 계속되는 경우",
                    }
                ],
            )
            return value
        records = value["assessments"][AUDIENCE]
        if stage.startswith("EXPLAIN"):
            for record in records.values():
                record["reason"] = (
                    BAD_SUMMARY_REASON
                    if invalid_explanation and record["findingId"] == 8298
                    else GOOD_REASONS[record["findingId"]]
                )
            return value
        for record in records.values():
            identifier = record["findingId"]
            finding = findings[identifier]
            basis = {"claimId": finding.claims[0].id, "quote": finding.sentences[0].text}
            record.update(
                work="CAPEX_EXECUTION" if identifier == 8290 else "GUIDANCE",
                relation="DIRECT",
                relationBasis=deepcopy(basis),
                impactScope="UNDETERMINED",
                impactBasis=None,
                urgencyState="MONITOR",
                urgencyBasis=deepcopy(basis),
                reason=GOOD_REASONS[identifier],
            )
            if stage.startswith("MAP"):
                if mandatory:
                    record["reason"] = "9999년의 투자 전망을 확인한다."
                continue
            if identifier == 8290:
                record.update(
                    impactScope="PROJECT_CHANGE",
                    impactBasis=deepcopy(basis),
                    urgencyState="SCHEDULED_PREPARATION",
                    reason=(
                        "삼성전자의 연구 거점 건설은 설비투자 집행에 연결된다."
                        if occurrence == 1
                        else BAD_REFERENCE_REASON
                    ),
                )
            else:
                record.update(work="PROFITABILITY", reason=BAD_SUMMARY_REASON)
                if occurrence == 1 or defect == "native":
                    record.update(
                        impactScope="PROJECT_CHANGE",
                        impactBasis=deepcopy(basis),
                        urgencyState="SCHEDULED_PREPARATION",
                    )
                else:
                    record.update(urgencyState="UNDETERMINED", urgencyBasis=None)
        return value

    return V4Provider(
        source,
        hook=hook,
        raw_hook=lambda stage, occurrence, _data, raw: (
            "{" if defect == "stale_parse" and stage == "REVIEW-001" and occurrence == 2 else raw
        ),
    )


@pytest.mark.parametrize("mandatory", [False, True], ids=["optional", "required_after_map"])
def test_review_axis_repair_then_prose_failure_is_explained_before_adoption(
    mandatory, monkeypatch, caplog
):
    source = review_source()
    snapshot = source.model_dump_json()
    monkeypatch.setattr(service, "select_review", lambda *_: (8290, 8298))
    provider = review_provider(source, mandatory=mandatory)

    with caplog.at_level("INFO"):
        result = generate(provider, source)

    expected = ["MAP-001"] * (2 if mandatory else 1)
    expected += ["REVIEW-001", "REVIEW-001", "EXPLAIN-001", "REDUCE-001"]
    assert stages(provider) == expected
    assert_usage(result.meta.model_dump(by_alias=True), len(expected))
    assert "stage=REVIEW-001 outcome=AWAITING_EXPLANATION findingCount=2" in caplog.text
    assert "stage=REVIEW-001 outcome=ADOPTED findingIds=(8290, 8298)" in caplog.text
    assert "fallback=VALIDATED_MAP_RETAINED" not in caplog.text
    explain_index = expected.index("EXPLAIN-001")
    explain_input = framed(provider.calls[explain_index]["prompt"])
    assert [finding["id"] for finding in explain_input["findings"]] == [8290, 8298]
    assert all(len(finding["claims"]) == 1 for finding in explain_input["findings"])
    assert "HBM4" not in provider.calls[explain_index]["prompt"]
    assert BAD_REFERENCE_REASON not in provider.calls[explain_index]["prompt"]
    reviewed = provider.wire_payloads[explain_index - 1]["assessments"][AUDIENCE]
    explained = provider.wire_payloads[explain_index]["assessments"][AUDIENCE]
    assert {key: row["decision"] for key, row in explained.items()} == {
        key: row["decision"] for key, row in reviewed.items()
    }
    assessments = {entry.finding_id: entry for entry in result.insights[0].assessments}
    assert assessments[8290].axes.impact == 2
    assert assessments[8290].axes.urgency == 2
    assert assessments[8298].axes.impact is assessments[8298].axes.urgency is None
    assert "HBM4" not in assessments[8298].reason
    assert "8290:2" not in assessments[8290].reason
    assert all(provider.schema_validity)
    assert source.model_dump_json() == snapshot


def test_invalid_explanation_cannot_adopt_review_or_reach_reduce(monkeypatch, caplog):
    source = review_source()
    monkeypatch.setattr(service, "select_review", lambda *_: (8290, 8298))
    provider = review_provider(source, invalid_explanation=True)

    with caplog.at_level("INFO"), pytest.raises(AgentError) as caught:
        generate(provider, source)

    assert stages(provider) == [
        "MAP-001",
        "REVIEW-001",
        "REVIEW-001",
        "EXPLAIN-001",
        "EXPLAIN-001",
    ]
    assert caught.value.code == "SCHEMA_VIOLATION"
    assert caught.value.details["validationFailure"]["stage"] == "EXPLAIN-001"
    assert_usage(caught.value.details["usage"], 5)
    assert "outcome=AWAITING_EXPLANATION" in caplog.text
    assert "outcome=ADOPTED" not in caplog.text
    assert all(provider.schema_validity)


@pytest.mark.parametrize("defect", ["native", "stale_parse"])
def test_invalid_native_or_stale_review_snapshot_keeps_validated_map(defect, monkeypatch, caplog):
    source = review_source()
    monkeypatch.setattr(service, "select_review", lambda *_: (8290, 8298))
    provider = review_provider(source, defect=defect)

    with caplog.at_level("INFO"):
        result = generate(provider, source)

    assert stages(provider) == ["MAP-001", "REVIEW-001", "REVIEW-001", "REDUCE-001"]
    assert_usage(result.meta.model_dump(by_alias=True), 4)
    assert "stage=REVIEW-001 outcome=VALIDATION_FAILED" in caplog.text
    assert "fallback=VALIDATED_MAP_RETAINED" in caplog.text
    assert "outcome=AWAITING_EXPLANATION" not in caplog.text
    assert "outcome=ADOPTED" not in caplog.text
    assert all(entry.axes.impact is None for entry in result.insights[0].assessments)
    assert all(entry.axes.urgency == 1 for entry in result.insights[0].assessments)
    assert all(provider.schema_validity)
