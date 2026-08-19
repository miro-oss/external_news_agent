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
