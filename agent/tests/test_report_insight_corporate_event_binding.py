"""Synthetic corporate-finance bindings; no live provider or article fetches."""

import pytest

from app.llm.report_insight_corporate_event_binding import corporate_event_mismatches


@pytest.mark.parametrize(
    "value,sources",
    [
        (
            "새봄의 IPO·밸류에이션 이슈는 모회사 주주가치와 자금 조달 구조에 영향을 준다.",
            ["모회사의 밸류에이션은 경쟁사보다 할인받고 있다."],
        ),
        (
            "새봄 IPO는 기업가치 판단의 근거다.",
            ["새봄의 실적이 증가했다.", "가온은 IPO를 추진한다."],
        ),
        ("새봄 IPO는 기업가치 판단의 근거다.", ["새봄은 실적이 증가했고 가온은 IPO를 추진한다."]),
        ("새봄 IPO는 기업가치 판단의 근거다.", ["새봄의 자회사 가온의 IPO가 할인 요인이다."]),
        ("새봄의 자금 조달은 투자 판단에 영향을 준다.", ["새봄은 IPO를 추진한다."]),
        (
            "새봄의 자금 조달은 투자 판단에 영향을 준다.",
            ["가온은 자금 조달을 추진한다.", "새봄의 실적이 증가했다."],
        ),
        ("새봄의 유상증자는 기업가치 판단의 근거다.", ["가온은 유상증자를 추진한다."]),
        ("새봄의 회사채 발행은 기업가치 판단의 근거다.", ["가온은 회사채 발행을 계획했다."]),
    ],
)
def test_missing_event_or_wrong_nearby_owner_is_rejected(value, sources):
    assert corporate_event_mismatches(value, sources)


@pytest.mark.parametrize(
    "value,sources",
    [
        ("새봄 IPO는 기업가치 판단의 근거다.", ["새봄의 뉴욕증시 기업공개가 할인 요인이다."]),
        ("새봄의 기업공개는 기업가치 판단의 근거다.", ["새봄은 이번 IPO를 추진한다."]),
        ("새봄 상장은 기업가치 판단의 근거다.", ["새봄은 미국 증시 상장을 계획했다."]),
        ("가온 IPO는 기업가치 판단의 근거다.", ["새봄의 자회사 가온의 IPO가 할인 요인이다."]),
        ("Samsung Electronics의 IPO는 기업가치 판단의 근거다.", ["삼성전자는 IPO를 추진한다."]),
        (
            "새봄의 자금 조달은 투자 판단에 영향을 준다.",
            ["새봄은 IPO를 통해 자금 조달을 추진한다."],
        ),
        ("새봄의 자금 조달은 투자 판단에 영향을 준다.", ["새봄은 투자금 유치를 계획했다."]),
        ("새봄의 유상증자는 기업가치 판단의 근거다.", ["새봄은 유상 증자를 추진한다."]),
        ("새봄의 회사채 발행은 기업가치 판단의 근거다.", ["새봄은 회사채 발행을 계획했다."]),
    ],
)
def test_selected_same_event_and_owner_support_an_observation(value, sources):
    assert corporate_event_mismatches(value, sources) == []


@pytest.mark.parametrize(
    "value",
    [
        "상장회사들의 PER 비교는 투자 판단 업무에 참고된다.",
        "상장 기업의 PER 비교는 투자 판단 업무에 참고된다.",
        "상장사의 밸류에이션은 시장 전망을 검토할 근거다.",
        "상장 주식의 가치는 시장 전망을 검토할 근거다.",
        "밸류에이션은 자금 조달 여력을 검토할 정보다.",
        "밸류에이션은 자금 조달 구조와 자금 조달 조건을 검토할 정보다.",
        "시장 전망과 투자 모델 업무를 검토한다.",
    ],
)
def test_general_company_attributes_and_analytical_work_are_not_new_events(value):
    assert corporate_event_mismatches(value, ["밸류에이션이 할인받고 있다."]) == []


def test_source_and_value_are_not_modified():
    sources = ["새봄의 실적이 증가했다.", "가온의 IPO가 예상된다."]
    value = "새봄 IPO는 기업가치 판단의 근거다."
    before = value, sources[:]
    assert corporate_event_mismatches(value, sources)
    assert (value, sources) == before


@pytest.mark.parametrize(
    "value,source",
    [
        ("새봄의 IPO는 기업가치 판단의 근거다.", "새봄의 IPO는 취소됐다."),
        ("새봄의 자금 조달은 기업가치 판단의 근거다.", "새봄은 자금 조달을 포기했다."),
        ("새봄의 IPO는 기업가치 판단의 근거다.", "새봄의 취소된 IPO는 불확실성을 키운다."),
        ("새봄의 회사채 발행은 기업가치 판단의 근거다.", "새봄은 유상증자를 추진한다."),
        ("새봄의 IPO는 기업가치 판단의 근거다.", "새봄은 회사채 발행으로 자금을 조달했다."),
        ("새봄과 가온의 IPO는 기업가치 판단의 근거다.", "가온의 IPO가 예정됐다."),
        ("새봄 및 가온의 IPO는 기업가치 판단의 근거다.", "가온의 IPO가 예정됐다."),
        ("새봄의 취소된 IPO는 판단 근거다.", "가온의 취소된 IPO는 판단 근거다."),
        ("새봄의 자금 조달 구조는 직접적 영향을 미친다.", "새봄의 PER이 할인받고 있다."),
        ("새봄의 자금 조달 구조를 변경했다.", "새봄의 PER이 할인받고 있다."),
    ],
)
def test_cancellation_financing_method_and_concrete_structure_change_are_bound(value, source):
    assert corporate_event_mismatches(value, [source])


@pytest.mark.parametrize(
    "value,source",
    [
        ("새봄의 IPO 취소는 기업가치 판단의 근거다.", "새봄의 IPO가 취소됐다."),
        ("새봄의 취소된 IPO는 판단 근거다.", "새봄의 IPO가 취소됐다."),
        ("새봄의 IPO는 판단 근거다.", "새봄의 IPO 계획은 취소되지 않았다."),
        ("새봄의 IPO는 판단 근거다.", "새봄은 실적 호조에도 IPO를 추진한다."),
        ("새봄과 가온의 IPO는 기업가치 판단의 근거다.", "새봄과 가온의 IPO가 예정됐다."),
        ("새봄 및 가온의 IPO는 판단 근거다.", "새봄 및 가온의 IPO가 예정됐다."),
        (
            "Samsung Electronics 및 가온의 IPO는 판단 근거다.",
            "삼성전자 및 가온의 IPO가 예정됐다.",
        ),
        (
            "새봄의 IPO와 자금 조달은 기업가치 판단의 근거다.",
            "새봄은 IPO를 통해 자금 조달을 추진한다.",
        ),
        ("새봄의 자금 조달 구조를 검토할 정보다.", "새봄의 PER이 할인받고 있다."),
    ],
)
def test_explicit_joint_owners_and_negative_observations_are_supported(value, source):
    assert corporate_event_mismatches(value, [source]) == []


def test_plural_listed_company_attribute_is_not_a_listing_event():
    assert (
        corporate_event_mismatches(
            "상장사들의 밸류에이션은 투자 판단 근거다.", ["밸류에이션은 할인됐다."]
        )
        == []
    )


@pytest.mark.parametrize("modifier", ["올해", "내년", "상반기", "이르면 내년 상반기", "2028년 말"])
def test_time_modifiers_do_not_replace_the_event_owner(modifier):
    assert (
        corporate_event_mismatches(
            "새봄의 IPO는 판단 근거다.", [f"새봄은 {modifier} IPO를 추진한다."]
        )
        == []
    )


@pytest.mark.parametrize(
    "source",
    [
        "새봄은 IPO를 추진하고 가온은 사업을 중단했다.",
        "새봄은 IPO를 추진한다고 밝혔고, 가온은 투자 계획을 취소했다.",
    ],
)
def test_another_subjects_cancellation_does_not_change_event_polarity(source):
    assert corporate_event_mismatches("새봄의 IPO는 판단 근거다.", [source]) == []
    assert corporate_event_mismatches("새봄의 IPO 취소는 판단 근거다.", [source])


@pytest.mark.parametrize(
    "value",
    [
        "솔리다임의 나스닥 상장 추진과 조달 기대는 상장 실행 여부에 따라 조달자금의 "
        "CAPEX 투입 가능성과 미국 내 생산 확대에 직접적인 영향을 줄 수 있어 투자자의 "
        "CAPEX 집행 관점에서 조건부로 중요하다.",
        "보도에 따르면 대표 주관사 선정 및 이르면 내년 IPO로 약 100억달러 조달 전망이 "
        "제기되었으므로, 해당 자금 조달이 실제로 성사되는 경우에 한해 미국 내 생산시설 "
        "투입과 자본집행이 발생할 가능성이 있어 자금조달 성사 전제를 필요로 한다.",
        "솔리다임의 나스닥 상장 추진과 예정 조달 규모는 자금조달을 통한 생산시설 투자 "
        "가능성을 제기해 낸드 사업의 자본구조 및 장기 수익성에 영향을 줄 수 있다.",
    ],
)
def test_report_8288_currency_funding_and_analytical_nouns_do_not_invent_owners(value):
    # This guard only binds the corporate-finance event; these observations'
    # other business inferences must still pass the caller's existing guards.
    source = (
        "블룸버그통신에 따르면 솔리다임은 미국 IPO를 위한 대표 주관사를 선정했고, "
        "이르면 내년 상장을 통해 약 100억달러(13조4000억원)를 조달할 것으로 예상된다."
    )
    assert corporate_event_mismatches(value, [source]) == []


@pytest.mark.parametrize(
    "amount", ["100억원을", "100억달러(13조4000억원)를", "$100 million을", "100억원"]
)
def test_currency_amount_being_raised_supports_funding_for_its_issuer(amount):
    source = f"새봄은 {amount} 조달할 것으로 예상된다."
    assert corporate_event_mismatches("새봄의 자금 조달은 조건부 판단 근거다.", [source]) == []
    assert corporate_event_mismatches("가온의 자금 조달은 조건부 판단 근거다.", [source])


@pytest.mark.parametrize(
    "source",
    [
        "새봄은 매출 100억원을 기록했고 부품을 조달했다.",
        "새봄은 100억원으로 부품을 조달했다.",
        "새봄은 100개를 조달했다.",
        "새봄은 100억원(부품 구매)을 조달했다.",
    ],
)
def test_nonfinancial_procurement_and_unverified_amount_parentheses_do_not_support_funding(source):
    assert corporate_event_mismatches("새봄의 자금 조달은 판단 근거다.", [source])


def test_another_issuer_in_same_clause_cannot_supply_currency_funding():
    source = "새봄은 실적을 발표했고 가온은 100억원을 조달할 것으로 예상된다."
    assert corporate_event_mismatches("새봄의 자금 조달은 판단 근거다.", [source])
    assert corporate_event_mismatches("가온의 자금 조달은 판단 근거다.", [source]) == []


def test_analytical_effect_is_not_the_owner_of_a_following_funding_reference():
    source = (
        "삼성전자는 미국 IPO 대표 주관사를 선정했고, 내년 상장을 통해 약 100억달러를 "
        "조달할 것으로 예상된다."
    )
    value = (
        "대표 주관사 선정 및 내년 IPO로 약 100억달러 조달 전망이 제기되었다. "
        "자금 조달이 성사되는 경우에 한해 투자 판단에 영향이 있어 자금조달 성사 전제를 필요로 한다."
    )
    assert corporate_event_mismatches(value, [source]) == []


@pytest.mark.parametrize(
    "event,source_event",
    [
        ("자금 조달", "자금을 조달"),
        ("투자금 유치", "투자금을 유치"),
        ("회사채 발행", "회사채를 발행"),
    ],
)
def test_object_particle_forms_keep_the_same_event_and_issuer(event, source_event):
    source = f"새봄은 {source_event}할 계획이다."
    assert corporate_event_mismatches(f"새봄의 {event} 계획은 판단 근거다.", [source]) == []
    assert corporate_event_mismatches(f"가온의 {event} 계획은 판단 근거다.", [source])
    assert corporate_event_mismatches(f"새봄의 {event} 취소는 판단 근거다.", [source])
