import json

import pytest
from test_analyze_service import FakeProvider, provider_response, request, valid_output

from app.core.config import Settings
from app.llm.analyze_service import ArticleAnalyzeService


def analyze_bullet(text, body, evidence_ids=None, groundedness="grounded"):
    raw = json.loads(valid_output(evidence_ids))
    raw["sections"][0]["bullets"][0].update(text=text, groundedness=groundedness)
    provider = FakeProvider(provider_response(json.dumps(raw, ensure_ascii=False)))
    return ArticleAnalyzeService(Settings(), provider).analyze(request(body)).sections[0].bullets[0]


@pytest.mark.parametrize("suffix", ["[1]", " [1, 2]", "[1][2]", " [ 1 ] [2] "])
def test_removes_duplicate_sentence_citations_before_factual_verification(suffix, caplog):
    text = "HBM4 양산 일정이 앞당겨졌다."
    bullet = analyze_bullet(text + suffix, text + " 수율도 개선됐다.", [1, 2])

    assert bullet.text == text
    assert bullet.evidence_sentence_ids == [1, 2]
    assert bullet.groundedness == "grounded"
    assert bullet.confidence == 0.9
    assert "강등" not in caplog.text


@pytest.mark.parametrize(
    ("text", "body"),
    [
        ("HBM4 양산은 2027년에 시작한다.", "HBM4 양산은 2026년에 시작한다."),
        ("증가율은 20%다.", "증가율은 10%다."),
        ("비중은 1/3이다.", "비중은 1/2이다."),
    ],
)
def test_citation_removal_does_not_hide_incorrect_facts(text, body):
    bullet = analyze_bullet(text + "[1]", body)

    assert bullet.text == text
    assert bullet.groundedness == "ungrounded"
    assert bullet.confidence == 0


@pytest.mark.parametrize("suffix", ["[2]", "[1][3]", " 1", "[1/3]", "[2027]"])
def test_preserves_numbers_that_are_not_confirmed_sentence_citations(suffix):
    text = "HBM4 양산 일정이 앞당겨졌다."
    bullet = analyze_bullet(text + suffix, text)

    assert bullet.text == text + suffix
    assert bullet.groundedness == "ungrounded"


@pytest.mark.parametrize(
    ("text", "body"),
    [
        ("분류값은 [1]", "분류값은 [1]"),
        ("분류값은 [1]. [1]", "분류값은 [1]."),
        ("분류값을 확인했다. [1]", "분류값은 [ 1 ]이다."),
    ],
)
def test_preserves_bracketed_values_from_the_source(text, body):
    assert analyze_bullet(text, body).text == text


def test_removing_citations_does_not_upgrade_model_uncertainty():
    text = "HBM4 양산 일정이 앞당겨졌다."
    bullet = analyze_bullet(text + "[1]", text, groundedness="weak")

    assert bullet.text == text
    assert bullet.groundedness == "weak"
    assert bullet.confidence == 0.9
