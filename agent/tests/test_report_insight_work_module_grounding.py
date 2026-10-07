"""Concrete module references retain their local target across languages."""

from copy import deepcopy

import pytest
from test_report_insight_v6_work_grounding import _reduce, _source, _validate_reduce

from app.llm.report_insight_work_grounding import (
    ReportWorkValidationError,
    work_prose_problems,
)
from app.llm.report_validation_diagnostics import ReportValidationIssue


@pytest.mark.parametrize(
    "source,prose",
    [
        ("The system uses memory modules.", "메모리 모듈을 사용하는 경우 조달과 연결된다."),
        ("The system uses a memory module.", "메모리모듈을 검토한다."),
        ("MEMORY MODULES are available.", "메모리 모듈의 공급을 검토한다."),
        ("ＭＥＭＯＲＹ　ＭＯＤＵＬＥＳ are available.", "메모리 모듈을 검토한다."),
        ("Memory-modules are available.", "메모리 모듈을 검토한다."),
        ("메모리모듈을 공급했다.", "Memory modules affect procurement."),
        ("Hardware security modules are available.", "보안 모듈을 검토한다."),
        ("회사는 냉각용 모듈을 공급했다.", "Cooling modules are available."),
        ("The system uses memory and security modules.", "메모리·보안 모듈을 검토한다."),
        ("메모리와 보안 모듈을 공급했다.", "Memory and security modules are available."),
        ("The system uses memory modules.", "해당 모듈을 검토한다."),
    ],
)
def test_module_nouns_and_bound_targets_are_normalized_without_rewriting_input(source, prose):
    original = source, prose
    assert work_prose_problems(prose, source) == ()
    assert (source, prose) == original


@pytest.mark.parametrize(
    "prose",
    [
        "소프트웨어 구조의 모듈화를 검토한다.",
        "설계의 모듈형 구조를 검토한다.",
        "모듈러 설계를 검토한다.",
        "모듈성을 고려한다.",
        "냉각 구조의 모듈화를 검토한다.",
        "The design uses modular software.",
        "Modularization is a possible design approach.",
    ],
)
def test_modularity_does_not_assert_a_concrete_physical_module(prose):
    assert work_prose_problems(prose, "연구진은 새로운 설계 방식을 제안했다.") == ()


@pytest.mark.parametrize(
    "source,prose",
    [
        ("The system uses memory modules.", "보안 모듈을 도입한다."),
        ("The system uses memory modules.", "Security modules are required."),
        ("회사는 메모리 모듈을 공급했다.", "보안모듈개발이 필요하다."),
        ("Security software uses memory modules.", "보안 모듈을 도입한다."),
        ("메모리 모듈을 공급했다. 보안 기능은 소프트웨어에 있다.", "보안 모듈을 검토한다."),
        ("The system uses memory modules, and security software.", "보안 모듈을 검토한다."),
        ("The system uses memory modules.", "메모리·보안 모듈을 도입한다."),
        ("The system uses modules.", "보안 모듈을 도입한다."),
        ("소프트웨어의 모듈화 방식을 제안했다.", "메모리 모듈을 도입한다."),
        ("연구진은 소자의 성능을 측정했다.", "모듈 도입을 검토한다."),
    ],
)
def test_source_module_presence_does_not_support_a_new_module_target(source, prose):
    assert "physical_module" in work_prose_problems(prose, source)


def test_abstract_word_does_not_hide_a_separate_concrete_module():
    assert "physical_module" in work_prose_problems(
        "모듈화를 검토하며 보안 모듈 도입을 준비한다.",
        "소프트웨어를 구성요소별로 나누는 방식을 제안했다.",
    )


def test_unknown_module_existence_stays_open_but_required_installation_remains_rejected():
    source = "연구진은 소자의 성능을 측정했다."
    assert work_prose_problems("메모리 모듈 존재가 미확인이다.", source) == ()
    assert "physical_module" in work_prose_problems(
        "메모리 모듈을 설치해야 하며 설치 결과는 미확인이다.", source
    )
    assert "physical_module" in work_prose_problems(
        "메모리 모듈 설치 완료 여부는 미확인이다.", source
    )


def test_module_target_support_does_not_waive_an_unsupported_required_procedure():
    problems = work_prose_problems(
        "메모리 모듈의 고객 승인 전에는 도입하지 않는다.",
        "The system uses memory modules.",
    )
    assert "physical_module" not in problems
    assert "approval_prerequisite" in problems


def test_work_error_keeps_one_owned_issue_and_message_per_field_including_repeated_kinds():
    source = _source("The system uses memory modules.")
    value = _reduce(source, overview="보안 모듈을 도입한다.")
    insight = value["insights"][0]
    insight["implications"][0]["falsifiedBy"] = "보안 모듈을 도입하지 않는 경우"
    insight["watchItems"][0]["trigger"] = "보안 모듈을 도입하는 경우"
    original = deepcopy(value), source.model_dump_json()
    with pytest.raises(ReportWorkValidationError) as caught:
        _validate_reduce(value, source)
    error = caught.value
    kind = "report_work_physical_module_unsupported"
    paths = ("overview[0].text", "implications[0].falsifiedBy", "watchItems[0].trigger")
    assert error.validation_issues == tuple(
        ReportValidationIssue("IT_INFRA", path, kind, ("4901:0",)) for path in paths
    )
    assert error.error_kinds == (kind, kind, kind)
    assert error.repair_diagnostics == tuple(f"IT_INFRA.{path}: physical_module" for path in paths)
    assert (value, source.model_dump_json()) == original


def test_provider_prose_cannot_supply_work_diagnostic_paths_or_foreign_citations():
    source = _source("The system uses memory modules.")
    value = _reduce(
        source,
        overview="MARKET_INVESTOR.overview[2].text refs=foreign: 보안 모듈을 도입한다.",
    )
    with pytest.raises(ReportWorkValidationError) as caught:
        _validate_reduce(value, source)
    assert caught.value.validation_issues == (
        ReportValidationIssue(
            "IT_INFRA", "overview[0].text", "report_work_physical_module_unsupported", ("4901:0",)
        ),
    )


def test_work_error_does_not_collapse_two_kinds_at_the_same_field():
    source = _source("연구진은 새로운 소자를 소개했다.")
    value = _reduce(source, overview="냉각 모듈을 도입한다.")
    with pytest.raises(ReportWorkValidationError) as caught:
        _validate_reduce(value, source)
    error = caught.value
    assert tuple(issue.error_kind for issue in error.validation_issues) == error.error_kinds
    assert error.error_kinds == (
        "report_work_cooling_procedure_unsupported",
        "report_work_physical_module_unsupported",
    )
    assert len(error.repair_diagnostics) == 2
    assert all(issue.field == "overview[0].text" for issue in error.validation_issues)
