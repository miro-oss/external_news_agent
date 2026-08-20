from app.core.sentences import split_sentences, split_sentences_with_meta


def test_preserves_newline_sentence_boundaries() -> None:
    assert split_sentences("첫 문단입니다\n\n둘째 문단입니다", 200) == [
        "첫 문단입니다",
        "둘째 문단입니다",
    ]


def test_reports_when_sentence_limit_truncates_content() -> None:
    result = split_sentences_with_meta("A. B. C.", 2)

    assert result.sentences == ["A.", "B."]
    assert result.truncated is True


def test_keeps_decimal_and_english_abbreviation_in_same_sentence() -> None:
    assert split_sentences(
        "Dr. Kim reported a 3.2% yield increase. U.S. regulators agreed.",
        200,
    ) == [
        "Dr. Kim reported a 3.2% yield increase.",
        "U.S. regulators agreed.",
    ]


def test_keeps_uppercase_word_after_us_acronym_in_same_sentence() -> None:
    assert split_sentences(
        "The U.S. Commerce Department expanded controls. Firms responded.",
        200,
    ) == [
        "The U.S. Commerce Department expanded controls.",
        "Firms responded.",
    ]


def test_keeps_spaced_korean_date_in_same_sentence() -> None:
    assert split_sentences(
        "정부는 2026. 8. 20. 규제를 발표했다. 업계는 반발했다.",
        200,
    ) == [
        "정부는 2026. 8. 20. 규제를 발표했다.",
        "업계는 반발했다.",
    ]


def test_keeps_numbered_list_markers_in_same_line() -> None:
    assert split_sentences("1. 첫째 항목 2. 둘째 항목", 200) == [
        "1. 첫째 항목 2. 둘째 항목"
    ]


def test_keeps_korean_closing_quote_with_sentence() -> None:
    assert split_sentences('회사는 "양산을 앞당긴다."고 밝혔다. 다음 문장이다.', 200) == [
        '회사는 "양산을 앞당긴다."고 밝혔다.',
        "다음 문장이다.",
    ]
