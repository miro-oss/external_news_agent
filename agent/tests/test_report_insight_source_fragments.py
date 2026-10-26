"""Source handles must not turn number suffixes or short clauses into evidence."""

import re

import pytest

from app.llm.report_insight_assessment import _source_quote_fragments


@pytest.mark.parametrize(
    "text",
    [
        "2029년까지 월 44,000개 웨이퍼를 생산하며, 1,600개 일자리 창출을 예상한다.",
        "The line makes 44,000 wafers, with 3.25 percent held for inspection.",
        "수작업 검증은 주관적 판단을 유발하며, 이는 현대 공정의 병목이다.",
        "  공정 조건은 다음과 같다: 3,25% 편차; 결과는 아직 미확인이다.  ",
    ],
)
def test_short_source_keeps_only_the_complete_unchanged_text(text):
    assert _source_quote_fragments(text) == (text,)


@pytest.mark.parametrize(
    "text",
    [
        # Recorded finding7883 sentence13 exposed '000 12-inch wafers per month,'.
        "At full capacity by 2029, the fab is expected to produce approximately "
        "44,000 12-inch wafers per month, create around 1,600 jobs, and further "
        "strengthen Singapore’s semiconductor ecosystem while creating new "
        "opportunities for collaboration and support long-term economic growth "
        "for the region’s high-tech industries.",
        # A hard 200-character window must not reintroduce numeric truncation.
        "Preparation " * 16 + "output 44,000.25 units and 1,600.75 measurements " * 4,
        "측정 준비 " * 32
        + " ".join(
            f"측정{index}의 정밀도 3.25% 및 유럽식 3,25% 수치가 유지된다." for index in range(4)
        ),
        "가" * 195 + "44,000.25개" + "나" * 30,
    ],
)
def test_long_source_covers_original_text_without_splitting_numeric_tokens(text):
    quotes = _source_quote_fragments(text)
    assert all(0 < len(quote) <= 200 and quote.strip() for quote in quotes)
    assert len(quotes) == len(set(quotes))
    assert "".join(quotes) == text
    offset = 0
    boundaries = []
    for quote in quotes:
        assert text[offset : offset + len(quote)] == quote
        offset += len(quote)
        boundaries.append(offset)
    for number in re.finditer(r"\d+(?:[.,]\d+)+", text):
        assert not any(number.start() < cut < number.end() for cut in boundaries)
    assert all(not quote.startswith("000 12-inch") for quote in quotes)


def test_long_multi_sentence_source_keeps_complete_sentences_before_bounded_chunks():
    first = "The line produced 44,000 units, with 3.25 percent reserved."
    second = " Production remains under evaluation, and the outcome is unknown."
    third = " The follow-up inspection is planned after the current review completes."
    text = first + second + third + " " + "검증" * 120
    quotes = _source_quote_fragments(text)
    assert quotes[:3] == (first, second, third)
    assert "".join(quotes) == text
    assert all(len(quote) <= 200 for quote in quotes)
