"""Corporate-finance binding regressions; no live provider or article fetches."""

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
    "amount",
    [
        "100억원을",
        "100억달러(13조4000억원)를",
        "100억달러(약 13조4000억원)를",
        "100억달러(약13조4000억원)를",
        "100억달러（약 13조4000억원）를",
        "100억원(약 $7 million)을",
        "$100 million을",
        "100억원",
    ],
)
def test_currency_amount_being_raised_supports_funding_for_its_issuer(amount):
    source = f"새봄은 {amount} 조달할 것으로 예상된다."
    assert corporate_event_mismatches("새봄의 자금 조달은 조건부 판단 근거다.", [source]) == []
    assert corporate_event_mismatches("가온의 자금 조달은 조건부 판단 근거다.", [source])


def test_report_8307_approximate_currency_conversion_preserves_funding_and_issuer():
    source = "솔리다임은 이번 IPO를 통해 약 100억달러(약 13조4000억원)를 조달할 것으로 예상된다."
    assert (
        corporate_event_mismatches(
            "솔리다임의 IPO와 자금 조달 전망은 기업가치 판단에 연결된다.", [source]
        )
        == []
    )
    assert corporate_event_mismatches("가온의 자금 조달 전망은 기업가치 판단에 연결된다.", [source])


@pytest.mark.parametrize(
    "source",
    [
        "새봄은 매출 100억원을 기록했고 부품을 조달했다.",
        "새봄은 100억원으로 부품을 조달했다.",
        "새봄은 100개를 조달했다.",
        "새봄은 100억원(부품 구매)을 조달했다.",
        "새봄은 100억원(약 부품 구매)을 조달했다.",
        "새봄은 100억원(약 100억원 규모의 부품)을 조달했다.",
        "새봄은 100억원(약 100억원 부품 구매)을 조달했다.",
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


@pytest.mark.parametrize(
    "change",
    [
        "구조를 개선했다",
        "구조 개편은 투자 판단에 연결된다",
        "비용이 감소했다",
        "조건이 개선됐다",
        "여력이 확대됐다",
        "전략을 실행했다",
        "전략 확정은 투자 판단에 연결된다",
    ],
)
def test_concrete_financing_changes_are_not_exempt_as_analytical_nouns(change):
    value = f"새봄의 자금 조달 {change}."
    assert corporate_event_mismatches(value, ["새봄의 실적이 증가했다."])
    assert corporate_event_mismatches(value, [f"가온의 자금 조달 {change}."])
    assert corporate_event_mismatches(value, [value]) == []


@pytest.mark.parametrize(
    "analysis",
    [
        "구조와 조건을 검토할 정보다",
        "조건 개선 여부를 검토할 필요가 있다",
        "여력 확대 가능성을 검토할 정보다",
    ],
)
def test_financing_analysis_without_asserted_change_remains_allowed(analysis):
    assert (
        corporate_event_mismatches(
            f"밸류에이션은 자금 조달 {analysis}.", ["밸류에이션은 할인받고 있다."]
        )
        == []
    )


@pytest.mark.parametrize("modifier", ["나스닥에", "미국 증시에", "높은 기업가치로", "공모로"])
def test_shared_venue_or_modifier_cannot_replace_the_actual_issuer(modifier):
    source = f"새봄은 {modifier} IPO를 추진한다. 가온은 실적을 발표했다."
    assert corporate_event_mismatches(f"가온은 {modifier} IPO를 추진한다.", [source])
    assert corporate_event_mismatches("새봄의 IPO는 판단 근거다.", [source]) == []
    assert corporate_event_mismatches(f"새봄은 {modifier} IPO를 추진한다.", [source]) == []


@pytest.mark.parametrize("issuer", ["파블로", "삼성전자"])
def test_issuer_name_with_adverb_like_ending_is_preserved(issuer):
    source = f"{issuer}의 IPO가 예정됐다."
    assert corporate_event_mismatches(f"{issuer} IPO는 판단 근거다.", [source]) == []


@pytest.mark.parametrize("relationship", ["자회사", "계열사", "관계사", "법인"])
def test_nested_issuer_name_with_adverb_like_ending_does_not_transfer_its_event(relationship):
    source = f"새봄의 {relationship} 파블로 IPO가 예정됐다."
    assert corporate_event_mismatches("새봄의 IPO는 판단 근거다.", [source])
    assert corporate_event_mismatches("파블로의 IPO는 판단 근거다.", [source]) == []


def test_joint_issuer_name_with_adverb_like_ending_is_preserved():
    source = "새봄 및 파블로 IPO가 예정됐다."
    assert corporate_event_mismatches("새봄 및 파블로의 IPO는 판단 근거다.", [source]) == []


def test_named_financing_intermediary_is_not_the_issuer():
    source = "새봄은 삼성전자에서 자금 조달을 추진한다."
    assert corporate_event_mismatches("새봄의 자금 조달은 판단 근거다.", [source]) == []
    assert corporate_event_mismatches("삼성전자의 자금 조달은 판단 근거다.", [source])


@pytest.mark.parametrize(
    "value",
    [
        "솔리다임의 나스닥 상장 추진과 신주 발행 논의는 조달 자금을 미국 내 생산시설 투자에 "
        "투입할 경우 현지 생산·증설 자금 조달과 통상 대응에 영향을 주어 자금조달·CAPEX "
        "판단과 조건부로 연결된다.",
        "신주 발행으로 조달자금이 솔리다임에 유입된다는 근거와(신주 발행 관련 문장), "
        "블룸버그 보도로 이르면 내년 약 100억달러 조달 전망이 제시된 점을 근거로, "
        "회사가 실제로 IPO로 자금을 확보해 이를 미국 내 생산시설 투자에 배분할 경우 "
        "낸드 투자 재원 확보와 현지 투자 확대 영향이 발생할 수 있다는 조건부 판단이다.",
    ],
)
def test_report_8288_live_reasons_do_not_turn_predicates_or_business_nouns_into_issuers(value):
    sources = [
        "블룸버그통신에 따르면 솔리다임은 미국 IPO를 위한 대표 주관사를 선정했고, "
        "이르면 내년 상장을 통해 약 100억달러(13조4000억원)를 조달할 것으로 예상된다.",
        "신주 발행은 솔리다임이 새 주식을 발행하는 방식으로 기존 주주의 지분율은 "
        "낮아지지만 자금은 솔리다임에 들어온다.",
        "기존 주주가 보유한 주식을 매각하는 구주매출과 달리 신주 발행은 조달 자금이 "
        "솔리다임에 직접 유입되기 때문에 낸드 사업 투자 재원 확보에 유리하다.",
        "도널드 트럼프 행정부가 반도체 기업들의 현지 투자를 압박하는 가운데 나스닥 상장이 "
        "생산시설 투자 재원을 마련하는 통로가 될 수 있어서다.",
        "솔리다임이 IPO 자금을 미국 내 생산시설에 투입한다면 현지 생산 확대를 요구하는 "
        "미국 정부의 정책 기조에 대응하고 고객사와의 협력도 강화할 수 있을 것으로 보인다.",
    ]
    assert corporate_event_mismatches(value, sources) == []
    assert corporate_event_mismatches(value, ["솔리다임의 실적은 개선됐다."])


def test_report_8307_live_repair_keeps_comparison_companies_out_of_later_event_ownership():
    value = (
        "원문 선택 근거에 따르면 SK하이닉스의 밸류에이션이 마이크론 대비 할인된 상태로 "
        "분석되며, 동일 기사에서 언급된 IPO 규모 전망은 특정 자금조달·프로젝트 여건 변화를 "
        "시사해 관련 준비·조건 변동 가능성을 초래할 수 있다."
    )
    valuation = (
        "SK하이닉스의 밸류에이션(실적 대비 주가 수준)이 미국 메모리 반도체 기업 "
        "마이크론테크놀로지에 비해 큰 폭으로 할인받고 있다는 분석이 나왔다."
    )
    funding = "솔리다임은 이번 IPO를 통해 약 100억달러(약 13조4000억원)를 조달할 것으로 예상된다."
    assert corporate_event_mismatches(value, [valuation, funding]) == []
    assert corporate_event_mismatches(value, [valuation])
    assert corporate_event_mismatches(
        value.replace("언급된 IPO", "언급된 마이크론의 IPO"), [valuation, funding]
    )


@pytest.mark.parametrize("issuer", ["새봄", "삼성전자", "파블로"])
@pytest.mark.parametrize(
    "prefix",
    [
        "{issuer}은 IPO를 추진했고, ",
        "{issuer}의 IPO가 계획됐고, ",
    ],
)
def test_explicit_subjects_and_event_owners_survive_a_coordinated_clause(issuer, prefix):
    source = prefix.format(issuer=issuer) + "100억원을 조달할 것으로 예상된다."
    assert corporate_event_mismatches(f"{issuer}의 자금 조달은 판단 근거다.", [source]) == []
    assert corporate_event_mismatches("가온의 자금 조달은 판단 근거다.", [source])


@pytest.mark.parametrize("owner", ["새봄 및 가온", "새봄, 가온", "새봄의 자회사 가온"])
def test_real_issuers_are_preserved_after_a_comparison_clause(owner):
    value = f"삼성전자 실적은 SK하이닉스 대비 늘었으며, {owner}의 IPO는 판단 근거다."
    assert corporate_event_mismatches(value, [f"{owner}의 IPO가 예정됐다."]) == []
    assert corporate_event_mismatches(value, ["삼성전자의 IPO가 예정됐다."])


@pytest.mark.parametrize(
    "value",
    [
        "가온의 사업 계획에 따르면, IPO가 예정되어 투자 판단의 근거가 된다.",
        "가온의 계획을 보면, IPO는 예정대로 추진된다.",
    ],
)
def test_a_comma_does_not_erase_the_owner_of_a_reported_plan(value):
    source = "새봄은 IPO를 추진한다. 가온의 사업 전망이 발표됐다."
    assert corporate_event_mismatches(value, [source])
    assert corporate_event_mismatches(value, ["가온은 IPO를 추진한다."]) == []


@pytest.mark.parametrize(
    "prefix,matching_source",
    [
        ("가온의 계획은", "가온은 IPO를 추진한다."),
        ("가온의 사업 계획에 따르면", "가온은 IPO를 추진한다."),
        ("가온은", "가온은 IPO를 추진한다."),
        ("가온 및 새봄은", "가온 및 새봄은 IPO를 추진한다."),
        ("새봄과 가온의 계획은", "새봄과 가온은 IPO를 추진한다."),
        ("새봄의 자회사 가온은", "가온은 IPO를 추진한다."),
        ("새봄의 자회사 가온의 계획은", "가온은 IPO를 추진한다."),
    ],
)
def test_source_reference_preserves_a_continuing_plain_joint_or_nested_owner(
    prefix, matching_source
):
    source = "새봄은 IPO를 추진한다. 가온의 사업 전망이 발표됐다."
    value = prefix + ", 동일 기사에서 언급된 IPO를 추진하는 것이다."
    assert corporate_event_mismatches(value, [source])
    assert corporate_event_mismatches(value, [matching_source]) == []


@pytest.mark.parametrize("reference", ["동일 기사에서 언급된", "해당 원문에서 제시된"])
def test_source_reference_in_a_reported_plan_does_not_reset_its_owner(reference):
    value = f"가온의 사업 계획에 따르면, {reference} IPO가 예정됐다."
    source = "새봄은 IPO를 추진한다. 가온의 사업 전망이 발표됐다."
    assert corporate_event_mismatches(value, [source])
    assert corporate_event_mismatches(value, ["가온은 IPO를 추진한다."]) == []


@pytest.mark.parametrize("reference", ["동일 기사에서 언급된", "해당 원문에서 제시된"])
@pytest.mark.parametrize(
    "completed_clause",
    [
        "가온의 사업 전망이 소개됐으며",
        "가온의 사업 전망이 보도되며",
        "가온의 실적이 분석됐으며",
        "가온의 기업가치가 저평가된 상태로 분석되며",
    ],
)
def test_a_new_source_reference_keeps_its_explicit_issuer_and_requires_event_evidence(
    reference, completed_clause
):
    prefix = f"{completed_clause}, {reference} "
    source = "새봄은 IPO를 추진한다. 가온의 사업 전망이 발표됐다."
    assert corporate_event_mismatches(prefix + "IPO는 판단 근거다.", [source]) == []
    assert corporate_event_mismatches(prefix + "새봄의 IPO는 판단 근거다.", [source]) == []
    assert corporate_event_mismatches(prefix + "가온의 IPO는 판단 근거다.", [source])
    assert corporate_event_mismatches(prefix + "IPO는 판단 근거다.", ["새봄의 실적이 늘었다."])


@pytest.mark.parametrize("carrier", ["기사", "원문", "보도", "보고서", "자료"])
def test_source_carriers_are_not_issuers_but_keep_explicit_company_ownership(carrier):
    source = "새봄은 IPO를 추진한다. 가온의 사업 전망이 발표됐다."
    assert (
        corporate_event_mismatches(f"{carrier}의 전망은 IPO가 판단 근거라는 것이다.", [source])
        == []
    )
    assert corporate_event_mismatches(f"가온의 {carrier}는 IPO가 판단 근거라는 것이다.", [source])
    assert corporate_event_mismatches(
        f"{carrier}의 전망은 IPO가 판단 근거라는 것이다.", ["새봄의 실적이 늘었다."]
    )


@pytest.mark.parametrize("modifier", ["연결되는", "연계되는", "추진되는"])
def test_a_verbal_modifier_does_not_replace_the_financing_issuer(modifier):
    source = "새봄은 자금 조달을 추진한다. 가온의 사업 전망이 발표됐다."
    value = f"새봄의 사업에 직접 {modifier} 자금 조달은 판단 근거다."
    assert corporate_event_mismatches(value, [source]) == []
    assert corporate_event_mismatches(value.replace("새봄", "가온"), [source])


@pytest.mark.parametrize("issuer", ["기사랩", "보고서테크", "새봄", "파블로"])
def test_source_nouns_and_verb_filters_preserve_unknown_explicit_issuer_names(issuer):
    source = f"{issuer}은 IPO와 자금 조달을 추진한다."
    assert corporate_event_mismatches(f"{issuer}의 IPO와 자금 조달은 판단 근거다.", [source]) == []
    assert corporate_event_mismatches("가온의 IPO와 자금 조달은 판단 근거다.", [source])


def test_report_8288_live_source_carrier_repair_accepts_only_selected_event_evidence():
    value = (
        "기사의 전망은 IPO로 조달한 자금을 미국 내 생산시설에 투입한다면 현지 정책 대응과 "
        "협력 강화에 도움이 될 수 있다고 서술하므로, 자금 투입이 실제로 이뤄질 경우 CAPEX "
        "집행과 제한적 준비가 연결된다고 판단하되, 기사 자체는 자금 배분의 확정 사실을 "
        "제시하지 않아 전제(자금의 실제 투입)가 필요하다."
    )
    sources = [
        "도널드 트럼프 행정부가 반도체 기업들의 현지 투자를 압박하는 가운데 나스닥 상장이 "
        "생산시설 투자 재원을 마련하는 통로가 될 수 있어서다.",
        "솔리다임이 IPO 자금을 미국 내 생산시설에 투입한다면 현지 생산 확대를 요구하는 "
        "미국 정부의 정책 기조에 대응하고 고객사와의 협력도 강화할 수 있을 것으로 보인다.",
    ]
    assert corporate_event_mismatches(value, sources) == []
    assert corporate_event_mismatches(value, ["솔리다임의 실적이 늘었다."])


@pytest.mark.parametrize("valuation", ["할인", "프리미엄", "재평가"])
def test_valuation_nouns_cannot_join_the_following_company_as_an_issuer(valuation):
    source = "솔리다임은 IPO를 추진한다."
    value = f"SK하이닉스의 밸류에이션 {valuation}과 솔리다임 IPO는 판단 근거다."
    assert corporate_event_mismatches(value, [source]) == []
    assert corporate_event_mismatches(value.replace("솔리다임 IPO", "새봄 IPO"), [source])
    assert corporate_event_mismatches("새봄과 솔리다임 IPO는 판단 근거다.", [source])


@pytest.mark.parametrize("modifier", ["다만", "또한", "특히", "반면", "한편", "따라서", "그러나"])
def test_discourse_modifiers_are_not_issuers_and_do_not_remove_explicit_issuers(modifier):
    source = "솔리다임은 IPO를 추진한다."
    value = f"{modifier} IPO 예측은 향후 일정·실행 여부를 확정하지 않는다."
    assert corporate_event_mismatches(value, [source]) == []
    assert corporate_event_mismatches(value, ["솔리다임의 실적이 늘었다."])
    assert corporate_event_mismatches(f"{modifier} 가온의 IPO는 판단 근거다.", [source])


@pytest.mark.parametrize("issuance", ["신주 발행", "신주를 발행", "새 주식 발행", "새 주식을 발행"])
def test_new_share_issuance_requires_its_own_selected_event_and_issuer(issuance):
    value = "새봄의 신주 발행 가능성은 판단 근거다."
    assert corporate_event_mismatches(value, [f"새봄은 {issuance}을 계획한다."]) == []
    assert corporate_event_mismatches(value, [f"가온은 {issuance}을 계획한다."])
    assert corporate_event_mismatches(value, ["새봄은 IPO를 추진한다."])
    assert corporate_event_mismatches(value, ["새봄은 자금을 조달한다."])


def test_new_share_issuance_and_paid_in_capital_increase_are_distinct_events():
    # New shares may also be issued without a paid-in capital increase.
    assert corporate_event_mismatches(
        "새봄의 유상증자는 판단 근거다.", ["새봄은 신주 발행을 계획한다."]
    )
    assert corporate_event_mismatches(
        "새봄의 신주 발행은 판단 근거다.", ["새봄은 유상증자를 추진한다."]
    )
    assert corporate_event_mismatches(
        "새봄의 신주 발행은 판단 근거다.", ["새봄은 신주 발행을 취소했다."]
    )
    assert (
        corporate_event_mismatches(
            "새봄의 신주 발행 취소는 판단 근거다.", ["새봄은 신주 발행을 취소했다."]
        )
        == []
    )


def test_report_8288_cannot_infer_new_share_issuance_from_selected_ipo_funding_only():
    value = (
        "솔리다임의 나스닥 상장 추진 및 신주 발행 가능성은 회사 측 자금조달 경로로서 낸드 "
        "투자 재원과 현지 생산 투자(미국) 집행에 직접적 영향을 미칠 수 있어 투자자의 "
        "CAPEX 집행 관점과 관련된다."
    )
    ipo_funding = (
        "블룸버그통신에 따르면 솔리다임은 미국 IPO를 위한 대표 주관사를 선정했고, "
        "이르면 내년 상장을 통해 약 100억달러(13조4000억원)를 조달할 것으로 예상된다."
    )
    new_shares = (
        "신주 발행은 솔리다임이 새 주식을 발행하는 방식으로 기존 주주의 지분율은 낮아지지만 "
        "자금은 솔리다임에 들어온다."
    )
    assert corporate_event_mismatches(value, [ipo_funding]) == [
        "근거의 주체·사건 연결과 다릅니다: 솔리다임 → 신주 발행"
    ]
    assert corporate_event_mismatches(value, [ipo_funding, new_shares]) == []


@pytest.mark.parametrize(
    "nominal",
    [
        "조달규모",
        "자금조달규모",
        "투자금액",
        "공모조건",
        "발행계획",
        "조달실행여부",
        "선정",
        "주관사선정",
        "대표주관사선정",
    ],
)
def test_financial_compound_nouns_do_not_become_financing_issuers(nominal):
    value = f"새봄의 {nominal}과 자금 조달은 판단 근거다."
    source = "새봄은 자금 조달을 추진한다."
    assert corporate_event_mismatches(value, [source]) == []
    assert corporate_event_mismatches(value.replace("새봄", "가온"), [source])


@pytest.mark.parametrize("issuer", ["조달테크", "선정산업", "투자미래", "주관사랩"])
def test_financial_noun_filter_keeps_unknown_company_names_and_joint_issuers(issuer):
    source = f"{issuer}은 자금 조달을 추진한다."
    assert corporate_event_mismatches(f"{issuer}의 자금 조달은 판단 근거다.", [source]) == []
    assert corporate_event_mismatches(f"새봄과 {issuer}의 자금 조달은 판단 근거다.", [source])


@pytest.mark.parametrize(
    "value,source",
    [
        (
            "보도된 IPO 조달규모(약 100억달러)는 회사의 자금 조달·프로젝트 조건에 직접적 "
            "관련이 있어 수익성 관점의 업무 연결과 특정 프로젝트·준비 변경의 근거로 "
            "판단되며, 기사 문장 자체가 조달 예상치를 제시한다.",
            "솔리다임은 이번 IPO를 통해 약 100억달러(약 13조4000억원)를 조달할 것으로 예상된다.",
        ),
        (
            "대표 주관사 선정과 이르면 내년 약 100억달러 조달 보도는 회사의 자금조달 계획과 "
            "자본집행 준비에 직접적 영향을 줄 수 있으나, 보도는 전망(예상 금액·시점)을 전하며 "
            "확정된 조달 실행 여부와 최종 규모는 기사에서 확인되지 않는다.",
            "블룸버그통신에 따르면 솔리다임은 미국 IPO를 위한 대표 주관사를 선정했고, "
            "이르면 내년 상장을 통해 약 100억달러(13조4000억원)를 조달할 것으로 예상된다.",
        ),
    ],
)
def test_captured_financial_nominal_repairs_keep_the_selected_funding_event(value, source):
    assert corporate_event_mismatches(value, [source]) == []
    assert corporate_event_mismatches(value, ["솔리다임의 실적이 늘었다."])


@pytest.mark.parametrize("nominal", ["사실", "내용", "점", "것", "전망"])
@pytest.mark.parametrize(
    "frame",
    [
        "솔리다임이 약 100억달러를 조달한다는 {nominal}은 자금조달 판단의 근거다.",
        "보도는 솔리다임이 약 100억달러를 조달할 것으로 예상된다는 {nominal}을 전하며 "
        "자금조달 판단의 근거를 제공한다.",
        "솔리다임의 자금 조달 {nominal}은 자금조달 판단의 근거다.",
    ],
)
def test_nominalized_funding_statements_keep_the_explicit_issuer(nominal, frame):
    value = frame.format(nominal=nominal)
    source = "솔리다임은 약 100억달러를 조달할 것으로 예상된다."
    assert corporate_event_mismatches(value, [source]) == []
    assert corporate_event_mismatches(value.replace("솔리다임", "가온"), [source])
    assert corporate_event_mismatches(value, ["솔리다임의 실적이 늘었다."])


@pytest.mark.parametrize(
    "source_nominal", ["보도내용", "기사내용", "원문내용", "보고서내용", "자료내용"]
)
def test_compound_source_nominal_is_not_an_issuer(source_nominal):
    value = f"솔리다임의 {source_nominal}은 자금조달 판단의 근거다."
    source = "솔리다임은 자금 조달을 추진한다."
    assert corporate_event_mismatches(value, [source]) == []
    assert corporate_event_mismatches(value.replace("솔리다임", "가온"), [source])


@pytest.mark.parametrize("issuer", ["사실테크", "내용랩", "정보시스템", "바이트랩"])
def test_nominalization_filter_preserves_unknown_explicit_company_names(issuer):
    source = f"{issuer}은 자금 조달을 추진한다."
    assert corporate_event_mismatches(f"{issuer}의 자금 조달은 판단 근거다.", [source]) == []
    assert corporate_event_mismatches(f"새봄과 {issuer}의 자금 조달은 판단 근거다.", [source])


def test_report_8288_captured_funding_fact_nominal_does_not_replace_solidigm():
    value = (
        "블룸버그통신 보도에 따르면 솔리다임이 미국 IPO를 위한 대표 주관사를 선정했고 "
        "이르면 내년 상장을 통해 약 100억달러 조달을 예상한다는 사실은 자금조달을 통한 "
        "특정 프로젝트·자본집행 가능성에 직접 연결되나, 보도의 전망은 최종 조달규모·시점이 "
        "불확실함을 전제로 한다."
    )
    source = (
        "블룸버그통신에 따르면 솔리다임은 미국 IPO를 위한 대표 주관사를 선정했고, "
        "이르면 내년 상장을 통해 약 100억달러(13조4000억원)를 조달할 것으로 예상된다."
    )
    assert corporate_event_mismatches(value, [source]) == []
    assert corporate_event_mismatches(value.replace("솔리다임", "가온"), [source])


@pytest.mark.parametrize(
    "nominal,particle",
    [
        ("영향", "은"),
        ("효과", "는"),
        ("결과", "는"),
        ("평가", "는"),
        ("투자효과", "는"),
        ("조달영향", "은"),
        ("상장평가", "는"),
        ("평가결과", "는"),
    ],
)
def test_analytical_outcomes_are_not_issuers_and_cannot_supply_missing_events(nominal, particle):
    value = f"{nominal}{particle} IPO 자금의 용처에 달려 있다."
    source = "솔리다임은 IPO를 추진한다."
    assert corporate_event_mismatches(value, [source]) == []
    assert corporate_event_mismatches(value, ["솔리다임의 실적이 늘었다."])
    assert corporate_event_mismatches(f"{nominal}{particle} 가온의 IPO에 달려 있다.", [source])


@pytest.mark.parametrize("issuer", ["효과랩", "영향테크", "결과산업", "평가소프트"])
def test_analytical_outcome_filter_keeps_unknown_company_names_and_joint_issuers(issuer):
    source = f"{issuer}은 IPO를 추진한다."
    assert corporate_event_mismatches(f"{issuer}의 IPO는 판단 근거다.", [source]) == []
    assert corporate_event_mismatches(f"새봄과 {issuer}의 IPO는 판단 근거다.", [source])


def test_report_8288_captured_effect_sentence_keeps_its_selected_ipo_evidence():
    value = (
        "솔리다임의 나스닥 상장(IPO)은 회사의 조달 여력을 높여 생산시설 투자 자금으로 "
        "전용될 경우 미국 내 생산 확대 및 통상정책 대응에 영향을 줄 수 있다. "
        "효과는 IPO 자금의 용처에 달려 있다."
    )
    sources = [
        "도널드 트럼프 행정부가 반도체 기업들의 현지 투자를 압박하는 가운데 나스닥 상장이 "
        "생산시설 투자 재원을 마련하는 통로가 될 수 있어서다.",
        "솔리다임이 IPO 자금을 미국 내 생산시설에 투입한다면 현지 생산 확대를 요구하는 "
        "미국 정부의 정책 기조에 대응하고 고객사와의 협력도 강화할 수 있을 것으로 보인다.",
    ]
    assert corporate_event_mismatches(value, sources) == []
    assert corporate_event_mismatches(value, ["솔리다임의 실적이 늘었다."])


@pytest.mark.parametrize(
    "value,source",
    [
        (
            "원문은 블룸버그 보도를 인용해 미국 IPO를 위한 대표 주관사 선정 및 이르면 내년 "
            "상장을 통한 약 100억달러 조달 전망을 전하고 있어, 자금 조달 추진이 프로젝트 "
            "일정·자원 배분에 영향을 줄 가능성이 높다.",
            "블룸버그통신에 따르면 솔리다임은 미국 IPO를 위한 대표 주관사를 선정했고, "
            "이르면 내년 상장을 통해 약 100억달러(13조4000억원)를 조달할 것으로 예상된다.",
        ),
        (
            "원문은 SK하이닉스의 밸류에이션 할인이 관측된다는 분석을 제시하고(시장 비교), "
            "별도 보도에서 솔리다임의 약 100억달러 규모 IPO 예상을 전하므로 업계 자금조달 "
            "사건이 특정 프로젝트·자원 배분에 영향을 줄 가능성이 있어 프로젝트 조건 변화를 "
            "관점으로 모니터링한다.",
            "솔리다임은 이번 IPO를 통해 약 100억달러(약 13조4000억원)를 조달할 것으로 예상된다.",
        ),
    ],
)
def test_run12_report_connector_and_industry_are_not_corporate_owners(value, source):
    assert corporate_event_mismatches(value, [source]) == []
    assert corporate_event_mismatches(value, ["솔리다임의 실적이 늘었다."])


def test_run13_financing_route_analysis_is_not_a_new_funding_event():
    value = (
        "솔리다임의 미국 IPO 추진과 조달 전망은 낸드 사업의 자금조달 경로와 미국 내 "
        "생산투자 여건에 직접적인 연결을 가져와 CAPEX 실행 판단에 영향을 준다."
    )
    source = "솔리다임은 미국 IPO를 추진한다."
    assert corporate_event_mismatches(value, [source]) == []
    assert corporate_event_mismatches(value, ["솔리다임의 실적이 늘었다."])


@pytest.mark.parametrize(
    "value",
    [
        "기사의 분석은 SK하이닉스 밸류에이션 할인과 솔리다임 IPO 가능성 등 주가·자금 조달 "
        "관련 요인을 다루므로 투자 판단(가이던스·기업가치 영향)과 직접 연결된다.",
        "원문은 SK하이닉스의 밸류에이션 할인 분석과 솔리다임의 약 100억달러 규모 IPO 조달 "
        "전망을 함께 제시하고 있어, 자금조달·구조 변화 가능성에 따라 관련 프로젝트·시장 "
        "조건의 변화가 발생할 수 있으므로 IPO 진행과 가치 격차를 모니터링해야 한다.",
    ],
)
def test_run14_financing_analysis_and_change_noun_keep_selected_ipo_owner(value):
    source = "솔리다임은 이번 IPO를 통해 약 100억달러(약 13조4000억원)를 조달할 것으로 예상된다."
    assert corporate_event_mismatches(value, [source]) == []
    assert corporate_event_mismatches(value, ["솔리다임의 실적이 늘었다."])


def test_run15_conditional_connective_is_not_an_unmarked_company_name():
    value = (
        "원문(블룸버그통신 보도)은 대표 주관사 선정 및 이르면 내년 상장을 통해 약 100억달러 "
        "조달 예상을 보도하고 있어, 해당 보도대로라면 자금 조달·상장 추진은 자본적지출 "
        "집행·프로젝트 일정에 직접적인 영향이 될 것으로 판단된다."
    )
    source = (
        "블룸버그통신에 따르면 솔리다임은 미국 IPO를 위한 대표 주관사를 선정했고, "
        "이르면 내년 상장을 통해 약 100억달러(13조4000억원)를 조달할 것으로 예상된다."
    )
    assert corporate_event_mismatches(value, [source]) == []
    assert corporate_event_mismatches(value, ["솔리다임의 실적이 늘었다."])


def test_conditional_suffix_keeps_a_company_with_an_explicit_possessive_particle():
    value = "새봄라면의 IPO는 투자 판단의 근거다."
    assert corporate_event_mismatches(value, ["새봄라면은 IPO를 추진한다."]) == []
    assert corporate_event_mismatches(value, ["솔리다임은 IPO를 추진한다."])


@pytest.mark.parametrize(
    "value",
    [
        "기사에서 신주 발행을 통한 자금조달이 솔리다임에 직접 유입되어 낸드 관련 생산·투자 "
        "재원 확보에 유리하다고 설명하므로, 상장과 연계한 설비·투자 집행 영향이 직접 연결된다.",
        "선택된 근거에 따르면(신주 발행은 조달자금이 회사에 직접 유입된다는 기술적 설명) "
        "신주 발행이 낸드 사업 투자 자금 확보와 직접 연결되므로 CAPEX 집행 관점에서 "
        "직접적 관련이 성립한다. 또한 대표 주관사 선정·내년 상장으로 약 100억달러 조달 "
        "전망이 제시되어 준비 일정 변경(자금 조달에 맞춘 프로젝트 준비)이 필요할",
    ],
)
def test_run16_financial_event_names_do_not_become_the_next_event_owner(value):
    sources = [
        "신주 발행은 솔리다임이 새 주식을 발행하는 방식으로 기존 주주의 지분율은 낮아지지만 "
        "자금은 솔리다임에 들어온다.",
        "블룸버그통신에 따르면 솔리다임은 미국 IPO를 위한 대표 주관사를 선정했고, "
        "이르면 내년 상장을 통해 약 100억달러(13조4000억원)를 조달할 것으로 예상된다.",
    ]
    assert corporate_event_mismatches(value, sources) == []
    assert corporate_event_mismatches(value, ["솔리다임의 실적이 늘었다."])
