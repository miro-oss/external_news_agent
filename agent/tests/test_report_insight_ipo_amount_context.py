"""A subsidiary's explicit IPO amount must not inherit the parent's metrics."""

from types import SimpleNamespace

import pytest

from app.core.evidence import factual_mismatches
from app.llm.report_insight_guard import report_prose_mismatches
from app.llm.report_insight_ipo_amount_context import supported_ipo_amount_context
from app.llm.report_insight_service import _validate_prose

VALUATION = "SK하이닉스의 12개월 선행 PER은 3.70배로 낮아졌지만 마이크론은 5.9배를 유지했다."
FUNDING = "솔리다임은 이번 IPO를 통해 약 100억달러(약 13조4000억원)를 조달할 것으로 예상된다."
SOURCE = VALUATION + "\n" + FUNDING
# Captured report 2005, MAP-002 first response, selected claims 8307:0 and 8307:4.
PROSE = (
    "기사의 SK하이닉스 밸류에이션 할인 및 자회사 솔리다임의 IPO(약 100억달러 예상)는 "
    "주주가치·밸류에이션 구조에 직접적 영향 요인으로, 투자 관점의 밸류에이션·수익성 "
    "판단과 직접 연결된다."
)


@pytest.mark.parametrize("issuer", ["솔리다임", "가온", "파블로"])
def test_explicit_issuer_forecast_amount_does_not_inherit_parent_company(issuer):
    prose, source = PROSE.replace("솔리다임", issuer), SOURCE.replace("솔리다임", issuer)
    assert factual_mismatches(prose, source) == [
        "근거와 연결이 다른 숫자: SK하이닉스→10000000000usd"
    ]
    assert supported_ipo_amount_context(prose, source) == "SK하이닉스→10000000000usd"
    _validate_prose(
        [prose],
        ["8307:0", "8307:4"],
        {"8307:0": VALUATION, "8307:4": source.split("\n")[1]},
        {"8307:0": SimpleNamespace(text=VALUATION), "8307:4": SimpleNamespace(text="IPO 전망")},
    )


@pytest.mark.parametrize(
    "prose,source",
    [
        (PROSE.replace("솔리다임의", "SK하이닉스의"), SOURCE),
        (PROSE.replace("솔리다임의", "가온의"), SOURCE),
        (PROSE.replace("솔리다임의", "SK하이닉스와 솔리다임의"), SOURCE),
        (PROSE.replace("솔리다임의", "가온과 솔리다임의"), SOURCE),
        (PROSE.replace("100억달러", "101억달러"), SOURCE),
        (PROSE.replace("100억달러", "100억원"), SOURCE),
        (PROSE.replace("약 100억달러 예상", "100억달러 예상"), SOURCE),
        (PROSE.replace("예상", "확보"), SOURCE),
        (PROSE.replace("IPO(", "매출("), SOURCE),
        (PROSE.replace("IPO(", "2027년 IPO("), SOURCE),
        (PROSE + " SK하이닉스는 100억달러를 조달할 전망이다.", SOURCE),
        (PROSE + " 영업이익은 10조원이다.", SOURCE),
        (PROSE, SOURCE.replace("솔리다임은", "가온은")),
        (PROSE, SOURCE.replace("솔리다임은", "SK하이닉스와 솔리다임은")),
        (PROSE, SOURCE.replace("IPO를 통해", "사업을 통해")),
        (PROSE, SOURCE.replace("조달할 것으로 예상된다", "투자할 것으로 예상된다")),
        (PROSE, SOURCE.replace("예상된다", "예상되지 않는다")),
        (PROSE, SOURCE.replace("(약 13조4000억원)", "(투자 여부 미확인)")),
        (PROSE, VALUATION),
    ],
)
def test_annotation_cannot_borrow_another_issuer_amount_state_or_clock(prose, source):
    assert supported_ipo_amount_context(prose, source) is None


def test_only_legacy_context_message_is_removed():
    legacy = factual_mismatches(PROSE, SOURCE)
    unrelated = "근거와 연결이 다른 숫자: 동일 주체·사건·대상·시점의 수치 충돌"
    assert report_prose_mismatches(
        PROSE, SOURCE, [*legacy, unrelated], modality_reason=None, fact_source=SOURCE
    ) == [unrelated]


def test_parent_cannot_borrow_subsidiary_funding_forecast():
    prose = PROSE.replace("솔리다임의", "SK하이닉스의")
    with pytest.raises(ValueError, match="연결이 다른 숫자|주체·사건 연결"):
        _validate_prose(
            [prose],
            ["8307:0"],
            {"8307:0": SOURCE},
            {"8307:0": SimpleNamespace(text=SOURCE)},
        )


@pytest.mark.parametrize(
    "suffix",
    [
        "는 SK하이닉스의 확정 매출이다.",
        "는 SK하이닉스의 조달 금액이다.",
        "는 모회사의 확정 매출이다.",
        "는 매출이다.",
    ],
)
def test_forecast_annotation_does_not_prove_a_later_amount_reassignment(suffix):
    prose = "SK하이닉스의 할인 및 솔리다임의 IPO(약 100억달러 예상)" + suffix
    assert supported_ipo_amount_context(prose, SOURCE) is None
    with pytest.raises(ValueError, match="연결이 다른 숫자"):
        _validate_prose(
            [prose],
            ["8307:0"],
            {"8307:0": SOURCE},
            {"8307:0": SimpleNamespace(text=SOURCE)},
        )


def test_new_subject_after_a_comma_never_receives_annotation_exception():
    # The legacy clause splitter has a separate ellipsis limitation here. This
    # proof must not claim that the subsidiary amount supports a parent event.
    prose = "솔리다임의 IPO(약 100억달러 예상), SK하이닉스가 조달한다."
    assert supported_ipo_amount_context(prose, SOURCE) is None


@pytest.mark.parametrize(
    "prose",
    [
        "SK하이닉스의 확정 매출은 솔리다임의 IPO(약 100억달러 예상) 규모와 같다.",
        "SK하이닉스가 이미 확보한 금액은 솔리다임의 IPO(약 100억달러 예상) 규모다.",
        "SK하이닉스의 확정 매출은 솔리다임의 IPO(약 100억달러 예상) 규모라 투자 판단과 연결된다.",
        "SK하이닉스가 확보한 금액은 솔리다임의 IPO(약 100억달러 예상)로 투자 판단과 연결된다.",
        "SK하이닉스의 투자금과 솔리다임의 IPO(약 100억달러 예상)는 투자 판단과 연결된다.",
        "SK하이닉스와 솔리다임의 IPO(약 100억달러 예상)는 투자 판단과 연결된다.",
    ],
)
def test_forecast_annotation_cannot_prove_a_preceding_amount_assignment(prose):
    assert supported_ipo_amount_context(prose, SOURCE) is None
    with pytest.raises(ValueError, match="연결이 다른 숫자|주체·사건 연결"):
        _validate_prose(
            [prose],
            ["8307:0"],
            {"8307:0": SOURCE},
            {"8307:0": SimpleNamespace(text=SOURCE)},
        )


@pytest.mark.parametrize(
    "suffix",
    [
        "는 이미 모회사에 유입됐고 투자 판단과 직접 연결된다.",
        "는 모회사에 전액 배분됐고 투자 판단과 직접 연결된다.",
        "는 이미 SK하이닉스에 유입됐고 투자 판단과 직접 연결된다.",
        "는 가온에 전액 배분됐고 투자 판단과 직접 연결된다.",
        "는 자금 조달을 완료했으므로 투자 판단과 직접 연결된다.",
        "는 주주가치 영향 요인으로 모회사에 배분되어 투자 판단과 직접 연결된다.",
        "는 기업가치에 직접적 영향 요인으로, 이익금이 확정돼 투자 판단과 직접 연결된다.",
    ],
)
def test_analytical_closing_cannot_hide_a_transfer_or_completed_event(suffix):
    prose = PROSE[: PROSE.index(")는") + 1] + suffix
    assert supported_ipo_amount_context(prose, SOURCE) is None
    with pytest.raises(ValueError, match="연결이 다른 숫자|주체·사건 연결"):
        _validate_prose(
            [prose],
            ["8307:0"],
            {"8307:0": SOURCE},
            {"8307:0": SimpleNamespace(text=SOURCE)},
        )


def test_claim_summary_cannot_supply_missing_selected_raw_funding():
    lexical_source = SOURCE
    legacy = factual_mismatches(PROSE, lexical_source)
    assert (
        report_prose_mismatches(
            PROSE, lexical_source, legacy, modality_reason=None, fact_source=VALUATION
        )
        == legacy
    )


# report 2005 run14 MAP-002 repair, selected claims 8307:0 and 8307:4.
CAPTURED_REPORT = (
    "원문은 SK하이닉스의 밸류에이션 할인 분석과 솔리다임의 약 100억달러 규모 IPO 조달 "
    "전망을 함께 제시하고 있어, 자금조달·구조 변화 가능성에 따라 관련 프로젝트·시장 "
    "조건의 변화가 발생할 수 있으므로 IPO 진행과 가치 격차를 모니터링해야 한다."
)


def test_reported_forecast_amount_belongs_to_its_explicit_issuer_before_ipo():
    assert supported_ipo_amount_context(CAPTURED_REPORT, SOURCE) == "SK하이닉스→10000000000usd"
    _validate_prose(
        [CAPTURED_REPORT],
        ["8307:0", "8307:4"],
        {"8307:0": VALUATION, "8307:4": FUNDING},
        {"8307:0": SimpleNamespace(text=VALUATION), "8307:4": SimpleNamespace(text=FUNDING)},
    )


def test_reported_forecast_cannot_move_the_subsidiary_amount_to_its_parent():
    prose = CAPTURED_REPORT.replace("솔리다임의", "SK하이닉스의")
    assert supported_ipo_amount_context(prose, SOURCE) is None
    with pytest.raises(ValueError, match="연결이 다른 숫자|주체·사건 연결"):
        _validate_prose(
            [prose],
            ["8307:0", "8307:4"],
            {"8307:0": VALUATION, "8307:4": FUNDING},
            {"8307:0": SimpleNamespace(text=VALUATION), "8307:4": SimpleNamespace(text=FUNDING)},
        )
