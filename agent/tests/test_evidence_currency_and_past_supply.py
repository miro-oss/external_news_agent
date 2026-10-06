"""Recorded report prose retains equivalent currency amounts and past supply."""

import pytest

from app.core.evidence import _unitless_scaled_amounts, factual_mismatches, modality_overreach

GAS_SOURCE = (
    "Air Liquide announces a new investment of more than 170 million euros "
    "to supply ultra-high purity gases to a leading semiconductor manufacturer in Japan.\n"
    "Air Liquide가 일본의 반도체 제조업체에 170백만 유로 이상 투자한다."
)
SAMPLE_SOURCE = (
    "삼성전자는 지난 5월 HBM4E 12단 샘플을 글로벌 고객사에 공급하며 "
    "차세대 HBM 시장 공략에 속도를 냈다."
)


def test_recorded_korean_compound_currency_is_the_same_source_amount():
    claim = "Air Liquide가 일본의 반도체 제조업체를 지원하기 위해 1억 7천만 유로 이상을 투자한다."
    assert factual_mismatches(claim, GAS_SOURCE) == []


def test_recorded_past_sample_supply_supports_supplied_paraphrase():
    claim = "삼성전자는 HBM4E 12단 샘플을 글로벌 고객사에 공급했다고 보고한다."
    assert modality_overreach(claim, SAMPLE_SOURCE) is None
    assert factual_mismatches(claim, SAMPLE_SOURCE) == []


def test_recorded_currency_omission_preserves_compound_magnitude():
    source = "삼성전기는 6조7800억원 규모의 FC-BGA 투자를 진행한다."
    assert factual_mismatches("삼성전기는 6조7800억 규모의 FC-BGA 투자를 진행한다.", source) == []
    assert factual_mismatches("삼성전기는 6조8700억 규모의 FC-BGA 투자를 진행한다.", source)
    assert factual_mismatches("7800", source)
    assert factual_mismatches("100", "$100") == []


def test_negative_prefix_and_suffix_share_one_sign():
    assert factual_mismatches("-100달러", "-$100 dollars") == []
    assert factual_mismatches("-100달러", "$-100 dollars") == []
    assert factual_mismatches("100달러", "-$100 dollars")
    assert factual_mismatches("-100유로", "-$100 dollars")


def test_recorded_explicit_approximation_preserves_currency_and_precision():
    source = "테슬라는 2분기 말 현금으로 약 59조1000억원을 보유한 것으로 알려졌다."
    assert factual_mismatches("테슬라는 2분기 말 현금으로 59조원 가까이 보유했다.", source) == []
    assert factual_mismatches("테슬라는 2분기 말 현금으로 약 59조원을 보유했다.", source) == []
    assert factual_mismatches("테슬라는 2분기 말 현금으로 59조원을 보유했다.", source)
    assert factual_mismatches("테슬라는 2분기 말 현금으로 약 59.0조원을 보유했다.", source)
    assert factual_mismatches("테슬라는 2분기 말 현금으로 약 58조원을 보유했다.", source)
    assert factual_mismatches("테슬라는 2분기 말 현금으로 약 59조달러를 보유했다.", source)
    assert factual_mismatches("59조1000억원을 보유했다.", "약 59조원을 보유했다.")
    assert factual_mismatches(
        "약 59조원과 80조원을 보유했다.", "59조1000억원과 80조1000억원을 보유했다."
    )
    assert factual_mismatches("약 59조원을 보유했다.", "59조1000억원 또는 59조2000억원을 보유했다.")


def test_approximate_money_keeps_actor_and_year_context():
    source = (
        "삼성전자는 2027년에 59조1000억원을 투자한다. 삼성전자는 2028년에 80조1000억원을 투자한다."
    )
    assert factual_mismatches("삼성전자는 2027년에 약 59조원을 투자한다.", source) == []
    assert factual_mismatches("삼성전자는 2028년에 약 59조원을 투자한다.", source)
    assert factual_mismatches("SK하이닉스는 2027년에 약 59조원을 투자한다.", source)
    assert factual_mismatches(
        "삼성전자는 약59조원을 투자한다. SK하이닉스는 59조원을 투자한다.",
        "삼성전자는 59조1000억원을 투자한다. SK하이닉스는 59조1000억원을 투자한다.",
    )


def test_unitless_scale_does_not_discard_a_different_count_unit():
    assert factual_mismatches("1억명을 고용한다.", "1억원을 투자한다.")


@pytest.mark.parametrize("unit", ["개", "명", "건", "톤", "배"])
@pytest.mark.parametrize("separator", ["", " "])
@pytest.mark.parametrize("amount", ["1억", "1억 7천만"])
def test_scaled_count_units_cannot_become_currency(amount, separator, unit):
    count = f"{amount}{separator}{unit}"
    money = f"{amount}원"
    assert factual_mismatches(count, money)
    assert factual_mismatches(money, count)
    assert factual_mismatches(count, count) == []
    assert _unitless_scaled_amounts(count, []) == []


@pytest.mark.parametrize(
    "count",
    [
        "1억 개를",
        "1억\t명은",
        "1억\n건이",
        "1억 톤으로",
        "1억 배까지는",
        "1억 개와",
        "1억 명과",
        "1억 7천만 건과는",
        "6조 7800억 톤과도",
        "1억 7천만개",
        "1억7천만 명을",
        "1억 7000만 건의",
        "6조 7800억 톤",
    ],
)
def test_count_suffixes_do_not_backtrack_to_a_shorter_unitless_amount(count):
    assert _unitless_scaled_amounts(count, []) == []


@pytest.mark.parametrize(
    "claim,source",
    [
        ("1억을 투자한다.", "1억원을 투자한다."),
        ("1억 개발비", "1억원 개발비"),
        ("1억 명시", "1억원 명시"),
        ("1억 배정", "1억원 배정"),
        ("1억 7천만을 투자한다.", "1억7000만원을 투자한다."),
        ("6조 7800억 규모의 투자", "6조7800억원 규모의 투자"),
        ("1억 7천만 유로", "170 million euros"),
    ],
)
def test_count_unit_boundary_preserves_money_and_unrelated_following_words(claim, source):
    assert factual_mismatches(claim, source) == []


@pytest.mark.parametrize(
    "ending",
    [
        "시장을 공략할 것이라고 발표했다.",
        "경쟁력을 확보하겠다고 약속했다.",
        "속도를 냈다는 보도를 부인했다.",
    ],
)
def test_past_reporting_does_not_prove_future_or_denied_supply(ending):
    source = "삼성전자는 지난5월 샘플을 공급하며 " + ending
    assessment = modality_overreach("삼성전자는 샘플을 공급했다.", source)
    assert assessment is not None and assessment.difference >= 2


@pytest.mark.parametrize(
    "claim,source",
    [
        ("1억 7천만 유로", "170 million euros"),
        ("0.17 billion euros", "170백만 유로"),
        ("170,000,000 EUR", "1억7000만 유로"),
        ("1.7억 유로", "170000000 euros"),
        ("1억원", "100 million won"),
        ("2.5 million dollars", "250만 달러"),
        ("-1억 7천만 유로", "-170 million euros"),
        ("100달러", "$100"),
        ("100유로", "€100"),
        ("100원", "₩100"),
        ("1억 7천만 달러", "US$170 million"),
        ("170백만 유로", "EUR 170,000,000"),
        ("100달러", "$100 dollars"),
        ("-100달러", "-$100"),
        ("-100유로", "€-100"),
    ],
)
def test_explicit_currency_amounts_have_exact_shared_values(claim, source):
    assert factual_mismatches(claim, source) == []
    assert factual_mismatches(source, claim) == []


@pytest.mark.parametrize(
    "claim,source",
    [
        ("1억 8천만 유로", "170 million euros"),
        ("170 유로", "170 million euros"),
        ("1억 7천만 달러", "170 million euros"),
        ("-1억 7천만 유로", "170 million euros"),
        ("10%p 이상 증가", "73%에서 59%로 감소. 170 million euros 투자"),
        ("2027년 1억 7천만 유로", "2025년 170 million euros"),
        ("10% 증가", "1% 증가와 10 million euros 투자"),
        ("100달러", "€100"),
        ("1억 7천만 유로", "€17 million"),
        ("100유로", "$100 euros"),
    ],
)
def test_scaling_does_not_hide_wrong_values_units_dates_or_thresholds(claim, source):
    assert factual_mismatches(claim, source)


def test_currency_canonicalization_keeps_year_and_company_binding():
    source = (
        "삼성전자는 2027년에 170 million euros를 투자한다. "
        "삼성전자는 2028년에 180 million euros를 투자한다."
    )
    assert factual_mismatches("삼성전자는 2027년에 1억 7천만 유로를 투자한다.", source) == []
    assert any(
        "연결이 다른 숫자" in error
        for error in factual_mismatches("삼성전자는 2027년에 1억 8천만 유로를 투자한다.", source)
    )
    assert factual_mismatches("삼성전자는 2027년에 1억 7천만을 투자한다.", source) == []
    assert factual_mismatches("SK하이닉스는 2027년에 1억 7천만 유로를 투자한다.", source)


@pytest.mark.parametrize(
    "source",
    [
        "삼성전자는 내년 5월 샘플을 공급하며 시장 공략에 속도를 낼 예정이다.",
        "삼성전자는 지난 5월 샘플을 공급하며 시장 공략에 속도를 낼 계획이었다.",
        "삼성전자는 지난 5월 샘플을 공급하며 시장 공략에 속도를 냈다는 주장은 사실이 아니다.",
        "삼성전자는 지난 5월 샘플을 공급하며 시장 공략에 속도를 냈다면 성공했을 것이다.",
        "삼성전자는 지난 5월 샘플을 공급하며 시장 공략에 속도를 냈다고 가정한다.",
        "삼성전자는 샘플을 공급하며 시장 공략에 속도를 내는 경우를 검토한다.",
        "삼성전자는 지난 5월 샘플 공급 계획을 공개했다. 내년에 공급하며 시장을 공략할 전망이다.",
    ],
)
def test_future_unexecuted_negated_and_hypothetical_supply_stays_unsupported(source):
    assessment = modality_overreach("삼성전자는 샘플을 공급했다.", source)
    assert assessment is not None and assessment.difference >= 2
