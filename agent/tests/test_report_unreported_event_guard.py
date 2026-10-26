"""An omitted event description is not an assertion that the event occurred."""

from types import SimpleNamespace

import pytest

from app.llm.report_insight_service import _asserted_event_stage, _validate_prose

SOURCE = "9월 반도체 수출액은 8월보다 30%, 1년 전보다는 4배 가까이 늘었습니다."
RECORDED_REASON = (
    "원문은 반도체 수출액(603억 달러 등)과 전월·전년 대비 큰 증가를 기술한다. "
    "높은 수출 실적은 제조 측면의 생산능력·수율 관련 영향 가능성을 시사하나, "
    "기사에서 특정 제조사의 수율·능력 배분 변화나 확정된 생산 증설은 명시되지 않는다."
)


def validate(value, source=SOURCE):
    _validate_prose(
        [value], ["7872:2"], {"7872:2": source}, {"7872:2": SimpleNamespace(text=source)}
    )


def test_recorded_reason_does_not_assert_the_unreported_expansion():
    # The original still fails its genuinely wrong selected reference for 603억.
    with pytest.raises(ValueError, match="숫자") as error:
        validate(RECORDED_REASON)
    assert "사실을 단정" not in str(error.value)
    validate(RECORDED_REASON.replace("(603억 달러 등)", ""))


@pytest.mark.parametrize(
    "value",
    [
        "확정된 생산 증설은 명시되지 않는다.",
        "확정된 생산 증설은 원문에 명시되지 않았다.",
        "기사에서 확정된 생산 증설이 명시되어 있지 않다.",
    ],
)
def test_only_the_source_description_is_reported_as_missing(value):
    assert _asserted_event_stage(value) == 0
    validate(value)


@pytest.mark.parametrize(
    "value",
    [
        "확정된 생산 증설은 없다.",
        "확정된 생산 증설은 아니다.",
        "확정된 생산 증설은 명시되지 않는다는 뜻은 아니다.",
        "확정된 생산 증설은 명시되지 않는다. 실제로 생산 증설을 확정했다.",
        "확정된 생산 증설은 명시되지 않지만 생산 증설을 확정했다.",
        "확정된 생산 증설은 명시되지 않는다. 고객 공급을 시작했다.",
        "확정된 생산 증설이 있고 후속 일정은 명시되지 않는다.",
        "확정된 생산 증설이 있으며 다른 결정은 명시되지 않는다.",
        "확정된 생산 증설이 없고 다른 결정은 명시되지 않는다.",
        "확정된 생산 증설이 없다는 보도를 부인했다.",
        "확정된 생산 증설이 없다고 가정했다. 고객 공급은 시작했다.",
        "확정된 생산 증설 뒤 추가 투자는 명시되지 않는다.",
        "확정된 생산 증설 이후 후속 일정은 명시되지 않는다.",
        "확정된 생산 증설로 인한 수율 변화는 명시되지 않는다.",
        "확정된 생산 증설의 후속 일정은 명시되지 않는다.",
        "확정된 생산 증설 규모는 명시되지 않는다.",
        "확정된 생산 증설 영향은 명시되지 않는다.",
        "확정된 생산 증설 내용은 명시되지 않는다.",
    ],
)
def test_missing_description_cannot_hide_a_separate_asserted_event_or_event_denial(value):
    with pytest.raises(ValueError):
        validate(value)


def test_unreported_event_cannot_establish_execution_in_the_selected_source():
    with pytest.raises(ValueError):
        validate("생산 증설을 확정했다.", "확정된 생산 증설은 원문에 명시되지 않았다.")
