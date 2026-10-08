"""Synthetic bilingual research evidence never borrows novelty from another event."""

import pytest
from test_report_insight_assessment import payload, request
from test_report_insight_grounded_prose import _checked_map, _checked_reduce, _reduce_payload

from app.llm.report_insight_fact_assertions import unsupported_fact_assertions
from app.llm.report_insight_research_facts import supported_research_novelty
from app.llm.report_insight_service import _prose_validation_errors, _source_context

PUBLICATION = "연구진은 논문을 공개했다."
NOVELTY = "논문은 클록 메쉬의 설계 공간을 처음으로 탐색했다고 보고한다."
FIRST_SOURCE = "We present the first design-space exploration of clock meshes."


def prose_errors(value, source):
    req = request(text=source)
    claims, evidence = _source_context(req)
    return _prose_validation_errors([value], ["101:0"], evidence, claims, request=req)


@pytest.mark.parametrize("stage", ["MAP", "REDUCE"])
@pytest.mark.parametrize(
    ("prose", "original"),
    [
        (PUBLICATION, "Researchers published a technical paper on clock meshes."),
        (PUBLICATION, "A technical paper was published on clock meshes."),
        (NOVELTY, FIRST_SOURCE),
        (
            "논문은 메모리 회로의 설계 공간을 처음으로 탐색했다고 보고한다.",
            "The paper reports the first exploration of the design space of memory circuits.",
        ),
    ],
)
def test_bilingual_research_fact_survives_native_and_public_validation(stage, prose, original):
    source = request(text=original + " 제조사는 생산라인 전체의 가동 중단이 현재 계속된다.")
    if stage == "MAP":
        value = payload(source)
        value["assessments"]["CHIP_MAKER"]["finding101"]["reason"] = prose
        assert _checked_map(source, value)[1].insights[0].assessments[0].reason == prose
    else:
        value = _reduce_payload()
        value["insights"][0]["overview"][0]["text"] = prose
        assert _checked_reduce(source, value).insights[0].overview[0].text == prose


@pytest.mark.parametrize(
    "original",
    [
        "Researchers discussed a technical paper on clock meshes.",
        "Researchers did not publish a technical paper on clock meshes.",
        "A technical paper on clock meshes was not published.",
        "Researchers will publish a technical paper on clock meshes.",
        "If researchers published a technical paper, we would examine it.",
        "The paper on clock meshes was retracted.",
        "The company published financial results. Researchers discussed a technical paper.",
        "Researchers published a price list and drafted a paper.",
        "The paper was reviewed while its separate dataset was published.",
    ],
)
def test_missing_negative_or_future_publication_is_not_a_completed_disclosure(original):
    assert unsupported_fact_assertions(PUBLICATION, original)
    assert prose_errors(PUBLICATION, original)


@pytest.mark.parametrize(
    "original",
    [
        "We present a design-space exploration of clock meshes.",
        "This is the first production line. We present a design-space exploration of clock meshes.",
        "We first published the paper about design-space exploration of clock meshes.",
        "We present the first design-space exploration of cooling networks.",
        "We may present the first design-space exploration of clock meshes.",
        "We will present the first design-space exploration of clock meshes.",
        "This is not the first design-space exploration of clock meshes.",
        "The claim of the first design-space exploration of clock meshes was retracted.",
        "We present the first design-space exploration. Clock meshes are also discussed.",
        "We present the first design-space exploration of clock meshes and memory circuits.",
        "We present the first design-space exploration of clock meshes for memory circuits.",
        "We present the first design-space exploration of clock meshes in Europe.",
    ],
)
def test_first_marker_requires_the_same_asserted_research_event_and_target(original):
    assert not supported_research_novelty(NOVELTY, original)
    assert prose_errors(NOVELTY, original)


def test_research_novelty_does_not_authorize_another_named_owners_exploration():
    original = (
        "TSMC presents the first design-space exploration of clock meshes. "
        "Samsung Electronics discussed clock meshes."
    )
    prose = "삼성전자는 클록 메쉬의 설계 공간을 처음으로 탐색했다고 보고한다."
    assert not supported_research_novelty(prose, original)
    assert prose_errors(prose, original)


@pytest.mark.parametrize("comparison", ["지난 보고서보다 개선됐다.", "전주 대비 새롭게 확인됐다."])
def test_article_novelty_does_not_authorize_prior_report_comparisons(comparison):
    assert prose_errors(NOVELTY + " " + comparison, FIRST_SOURCE)


def test_another_selected_or_unselected_finding_cannot_supply_research_novelty():
    req = request(ids=(101, 102), text="We present a design-space exploration of clock meshes.")
    req.findings[1].claims[0].text = FIRST_SOURCE
    req.findings[1].sentences[0].text = FIRST_SOURCE
    claims, evidence = _source_context(req)
    assert _prose_validation_errors([NOVELTY], ["101:0"], evidence, claims, request=req)
    assert not _prose_validation_errors([NOVELTY], ["102:0"], evidence, claims, request=req)
    # A paraphrased claim is not raw evidence of source novelty.
    req.findings[0].claims[0].text = FIRST_SOURCE
    claims, evidence = _source_context(req)
    assert _prose_validation_errors([NOVELTY], ["101:0"], evidence, claims, request=req)


def test_a_supported_first_clause_cannot_hide_a_second_unsupported_first_clause():
    prose = NOVELTY + " 논문은 냉각 네트워크의 설계 공간을 처음으로 탐색했다."
    assert not supported_research_novelty(prose, FIRST_SOURCE)
    assert prose_errors(prose, FIRST_SOURCE)


@pytest.mark.parametrize(
    "prose",
    [
        "논문을 소개하면서 장비 가격을 공개했다.",
        "논문은 언급했지만 장비 설계를 공개했다.",
        "논문 Beta를 공개했다.",
        "Beta 논문을 공개했다.",
        "클록 메쉬 논문을 공개했다.",
    ],
)
def test_paper_elsewhere_does_not_license_disclosure_of_a_different_object(prose):
    original = (
        "Researchers published a paper called Alpha about cooling networks and discussed Beta."
    )
    assert unsupported_fact_assertions(prose, original)
    assert prose_errors(prose, original)


def test_novelty_target_is_bound_to_exploration_not_another_object_in_the_clause():
    original = (
        "Researchers published the first design-space exploration of memory circuits "
        "and discussed backside clock meshes."
    )
    prose = "메모리 회로의 영향을 논의하며 백사이드 클록 메쉬의 설계 공간을 처음으로 탐색했다."
    assert not supported_research_novelty(prose, original)
    assert prose_errors(prose, original)
