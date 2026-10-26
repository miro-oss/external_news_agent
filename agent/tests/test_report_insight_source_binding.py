"""Source-bound production schedules must not be assembled from unrelated events."""

from copy import deepcopy

import pytest

from app.core.errors import OutputValidationError
from app.llm.report_insight_synthesis_quality import validate_synthesis_quality
from app.schemas.report_insight import ReportInsightReduceAudience, ReportInsightRequest
from tests.test_report_insight import request_body


def production_request(*, second_owner="벨라", schedule="내년 하반기", unnamed=False):
    body = request_body(audiences=["CHIP_MAKER"])
    template = deepcopy(body["findings"][0])
    source_groups = [
        ["오로라의 양산 프로젝트는 네오팹의 6nm 공정을 이용한다."],
        [
            f"공장은 {schedule}부터 생산을 시작할 예정이다.",
            "이번 공장은 공조 장치 생산 시설이다."
            if unnamed
            else f"이번 공장 설립은 {second_owner}의 공조 사업 확장을 위한 것이다.",
        ],
    ]
    body["findings"] = []
    for offset, texts in enumerate(source_groups):
        finding = deepcopy(template)
        finding.update(
            id=1001 + offset,
            articleId=2001 + offset,
            articleTitle="공개 생산 동향",
            claims=[
                {
                    "id": f"{1001 + offset}:{index}",
                    "text": text,
                    "claimType": "FACT",
                    "attributedTo": None,
                    "evidenceSentenceIds": [index],
                }
                for index, text in enumerate(texts)
            ],
            sentences=[{"index": index, "text": text} for index, text in enumerate(texts)],
        )
        body["findings"].append(finding)
    return ReportInsightRequest.model_validate(body)


def validate(text, request=None, *, refs=None, field="overview"):
    request = request or production_request()
    refs = refs if refs is not None else ["1001:0", "1002:0"]
    data = {
        "audience": "CHIP_MAKER",
        "headline": text if field == "headline" else "생산 프로젝트 동향 확인",
        "overview": [{"text": text, "basisClaimIds": refs, "assumption": "각 계획이 유지되는 경우"}]
        if field == "overview"
        else [],
        "implications": [
            {
                "text": "해당 계획의 실행 조건을 확인한다.",
                "mechanism": text,
                "basisClaimIds": refs,
                "assumption": "각 계획이 유지되는 경우",
                "falsifiedBy": "해당 계획이 철회되는 경우",
            }
        ]
        if field == "mechanism"
        else [],
        "watchItems": [],
    }
    validate_synthesis_quality(
        ReportInsightReduceAudience.model_validate(data),
        request,
        [claim.id for finding in request.findings for claim in finding.claims],
    )


@pytest.mark.parametrize("field", ["overview", "headline", "mechanism"])
def test_distinct_process_and_factory_schedule_cannot_become_one_planned_event(field):
    with pytest.raises(OutputValidationError) as caught:
        validate("네오팹의 6nm 공장을 신설해 내년 하반기부터 생산을 시작할 예정이다.", field=field)
    assert "report_synthesis_source_binding" in caught.value.error_kinds
    path = {
        "overview": "overview[0].text",
        "headline": "headline",
        "mechanism": "implications[0].mechanism",
    }[field]
    assert path in str(caught.value)


@pytest.mark.parametrize("schedule", ["2033년 상반기", "2034년 3분기", "2035년 9월"])
def test_schedule_binding_does_not_depend_on_specific_dates_or_only_relative_times(schedule):
    with pytest.raises(OutputValidationError) as caught:
        validate(
            f"오로라의 양산 프로젝트는 {schedule}부터 생산을 시작할 예정이다.",
            production_request(schedule=schedule),
        )
    assert caught.value.error_kinds == ("report_synthesis_source_binding",)


@pytest.mark.parametrize(
    "text",
    [
        "오로라의 6nm 양산 프로젝트다. 벨라의 공장은 내년 하반기에 생산을 시작할 예정이다.",
        "오로라의 양산 프로젝트와 벨라의 내년 하반기 공장 생산 계획을 각각 검토한다.",
        "네오팹의 6nm 공정으로 양산하며, 벨라의 공장은 내년 하반기에 생산을 시작할 예정이다.",
        "네오팹의 6nm 양산과 별도로 벨라의 공장은 내년 하반기에 생산을 시작할 예정이다.",
        "네오팹의 6nm 공정으로 양산하고 벨라 공장은 내년 하반기에 생산을 시작할 예정이다.",
        "네오팹의 6nm 양산과 내년 하반기 가동 예정인 벨라 공장을 각각 검토한다.",
        "네오팹의 6nm 양산과 별도로 벨라 공장은 내년 하반기에 생산을 시작할 예정이다.",
        "네오팹의 6nm 양산이 진행되는 한편 벨라 공장은 내년 하반기에 가동할 예정이다.",
    ],
)
def test_separate_events_may_be_summarized_together(text):
    validate(text)


def test_another_article_can_corroborate_the_same_actors_schedule():
    validate(
        "네오팹의 6nm 공장에서 내년 하반기에 생산을 시작할 예정이다.",
        production_request(second_owner="오로라"),
    )


def test_explicit_cross_language_alias_can_link_two_sources():
    request = production_request(second_owner="Aurora")
    source = request.findings[0]
    source.claims[0].text = "오로라(Aurora)의 양산 프로젝트는 네오팹의 6nm 공정을 이용한다."
    source.sentences[0].text = source.claims[0].text
    validate("네오팹의 6nm 공장에서 내년 하반기에 생산을 시작할 예정이다.", request)


@pytest.mark.parametrize(
    "text",
    [
        "AUR의 6nm 공장에서 내년 하반기에 생산을 시작할 예정이다.",
        "6nm 공장에서 내년 하반기에 생산을 시작할 예정이다.",
        "네오팹의 6nm 공장이 내년 하반기부터 생산을 시작한다면 물량을 검토한다.",
        "네오팹의 6nm 공장이 내년 하반기부터 생산을 시작하는지는 미확인이다.",
    ],
)
def test_unknown_aliases_hypotheses_and_unconfirmed_connections_stay_open(text):
    validate(text)


@pytest.mark.parametrize(
    "text",
    [
        "오로라의 6nm 양산 일정은 내년 하반기로 확정되지 않았다.",
        "오로라의 6nm 생산 일정은 내년 하반기인지 미정이다.",
        "오로라의 6nm 양산 시점은 내년 하반기로 정해지지 않았다.",
        "오로라의 6nm 공장은 내년 하반기에 생산을 시작하지 않는다.",
    ],
)
def test_denied_or_undecided_schedules_do_not_assert_a_transferred_date(text):
    validate(text)


def test_connected_actions_without_a_separate_actor_cannot_hide_a_transferred_date():
    with pytest.raises(OutputValidationError) as caught:
        validate("네오팹의 6nm 공장을 신설하고 내년 하반기부터 생산을 시작할 예정이다.")
    assert caught.value.error_kinds == ("report_synthesis_source_binding",)


def test_unnamed_schedule_source_does_not_establish_a_different_actor():
    validate(
        "네오팹의 6nm 공장은 내년 하반기에 생산을 시작할 예정이다.",
        production_request(unnamed=True),
    )


def test_same_process_explicitly_present_in_schedule_source_is_not_contradiction():
    request = production_request()
    request.findings[1].claims[0].text = "공장은 내년 하반기에 6nm 생산을 시작할 예정이다."
    request.findings[1].sentences[0].text = request.findings[1].claims[0].text
    validate("네오팹의 6nm 공장은 내년 하반기에 생산을 시작할 예정이다.", request)


def test_uncited_findings_cannot_establish_a_mismatched_schedule():
    # The general number/source guard handles unsupported dates. This rule must
    # not claim there is a contradictory cited relation based on unrelated data.
    validate(
        "네오팹의 6nm 공장은 내년 하반기에 생산을 시작할 예정이다.",
        refs=["1001:0"],
    )


def test_source_snapshot_and_public_citations_remain_unchanged():
    request = production_request()
    original = request.model_dump(mode="json", by_alias=True)
    with pytest.raises(OutputValidationError):
        validate("네오팹의 6nm 공장은 내년 하반기에 생산을 시작할 예정이다.", request)
    assert request.model_dump(mode="json", by_alias=True) == original
