from app.eval.cluster_sweep import sweep


def test_sweep_uses_exported_entity_threshold_and_calibrates_tfidf() -> None:
    articles = [
        _article(1, "CALIBRATION", "alpha"),
        _article(2, "CALIBRATION", "omega"),
        _article(3, "HOLDOUT", "bravo"),
        _article(4, "HOLDOUT", "zulu"),
    ]
    result = sweep(
        {
            "datasetVersion": "test.v1",
            "articleCount": 4,
            "configuredEntityOverlapThreshold": 1,
            "articles": articles,
            "pairs": [
                _pair(1, 2, "CALIBRATION"),
                _pair(3, 4, "HOLDOUT"),
            ],
        }
    )

    assert result["selected"]["metrics"]["recall"] == 1.0
    assert result["holdout"]["recall"] == 1.0
    assert "calibrationMetrics" in result["tfidfCharWbBaseline"]


def _article(article_id: int, split: str, title: str) -> dict[str, object]:
    return {
        "articleId": article_id,
        "topicId": 1,
        "title": title,
        "expectedIssueId": f"{split.lower()}-issue",
        "split": split,
    }


def _pair(left: int, right: int, split: str) -> dict[str, object]:
    return {
        "leftArticleId": left,
        "rightArticleId": right,
        "titleJaccard": 0.0,
        "entityOverlap": 1,
        "hoursApart": 1.0,
        "split": split,
    }
