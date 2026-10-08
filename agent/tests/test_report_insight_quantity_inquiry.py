"""A local numeric inquiry does not assert a value or waive a later assertion."""

import pytest

from app.llm.report_insight_fact_verification import compare_source_facts, fact_graph_mismatches

SOURCE_PRICE = "별빛장비는 검사 장비 3대를 총 9억 원에 공급했다."
PRICE = "별빛장비가 공급한 검사 장비의 대당 가격"
SOURCE_JOINT = (
    "해솔과 은하가 공동으로 투자하는 총액은 80억 원이며 각 회사의 분담액은 공개하지 않았다."
)
JOINT = "해솔의 개별 투자액"


@pytest.mark.parametrize(
    "suffix", ["인지 확인한다.", "인지를 점검한다.", "일지 검토한다.", "이었는지 확인한다."]
)
@pytest.mark.parametrize(
    "source,subject,value", [(SOURCE_PRICE, PRICE, "9억 원"), (SOURCE_JOINT, JOINT, "80억 원")]
)
def test_numeric_inquiry_is_unknown_without_hard_quantity_or_group_conflict(
    source, subject, value, suffix
):
    claim = f"{subject}이 {value}{suffix}"
    checks = compare_source_facts(claim, [source])
    assert all(check.outcome == "unknown" for check in checks)
    assert not fact_graph_mismatches(claim, [source])


@pytest.mark.parametrize(
    "source,subject,value", [(SOURCE_PRICE, PRICE, "9억 원"), (SOURCE_JOINT, JOINT, "80억 원")]
)
@pytest.mark.parametrize("inquiry_first", [False, True])
def test_inquiry_does_not_exempt_an_independent_assertion(source, subject, value, inquiry_first):
    inquiry = f"{subject}이 {value}인지 확인한다."
    assertion = f"{subject}은 {value}이다."
    claim = f"{inquiry} {assertion}" if inquiry_first else f"{assertion} {inquiry}"
    assert fact_graph_mismatches(claim, [source])


def test_checking_another_task_does_not_convert_a_unit_price_assertion_to_inquiry():
    claim = f"검증이 완료됐는지 확인한다. {PRICE}은 9억 원이다."
    assert fact_graph_mismatches(claim, [SOURCE_PRICE])


def test_completed_numeric_assertion_remains_rejected():
    claim = f"{PRICE}은 9억 원으로 확인됐다."
    assert fact_graph_mismatches(claim, [SOURCE_PRICE])
