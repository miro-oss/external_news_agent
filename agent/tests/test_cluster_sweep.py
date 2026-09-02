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
    assert result["decisionGatePassed"] is True
    assert "calibrationMetrics" in result["tfidfCharWbBaseline"]


def test_sweep_selects_ratio_output_and_preserves_fixed_content_groups() -> None:
    articles = [
        _article(1, "CALIBRATION", "same event first", "calibration-event"),
        _article(2, "CALIBRATION", "same event second", "calibration-event"),
        _article(3, "HOLDOUT", "unrelated alpha", "holdout-event"),
        _article(4, "HOLDOUT", "unrelated omega", "holdout-event"),
    ]
    articles[2]["fixedContentGroupId"] = "content-a"
    articles[3]["fixedContentGroupId"] = "content-a"
    result = sweep(
        {
            "datasetVersion": "test.v2",
            "articleCount": 4,
            "configuredEntityOverlapThreshold": 2,
            "configuredCommonEntityDocumentRatio": 0.10,
            "configuredTitleJaccardThreshold": 0.50,
            "configuredEntityTimeWindowHours": 48,
            "articles": articles,
            "pairEvaluations": [
                {
                    "commonEntityDocumentRatio": 0.05,
                    "pairs": [_pair(1, 2, "CALIBRATION", entity_overlap=0)],
                },
                {
                    "commonEntityDocumentRatio": 0.10,
                    "pairs": [_pair(1, 2, "CALIBRATION", entity_overlap=2)],
                },
            ],
        }
    )

    assert result["selected"]["common_entity_document_ratio"] == 0.10
    assert result["holdout"]["recall"] == 1.0
    assert result["configured"]["holdoutMetrics"]["recall"] == 1.0


def _article(
    article_id: int, split: str, title: str, issue_id: str | None = None
) -> dict[str, object]:
    return {
        "articleId": article_id,
        "topicId": 1,
        "title": title,
        "expectedIssueId": issue_id or f"{split.lower()}-issue",
        "split": split,
    }


def _pair(left: int, right: int, split: str, entity_overlap: int = 1) -> dict[str, object]:
    return {
        "leftArticleId": left,
        "rightArticleId": right,
        "titleJaccard": 0.0,
        "entityOverlap": entity_overlap,
        "hoursApart": 1.0,
        "split": split,
    }
