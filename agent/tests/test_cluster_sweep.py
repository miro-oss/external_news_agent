from app.eval.cluster_sweep import _topic_unions, sweep


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
            "configuredCommonEntityDocumentRatio": 0.10,
            "configuredTitleJaccardThreshold": 0.50,
            "configuredEntityTimeWindowHours": 48,
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


def test_sweep_selects_ratio_output_and_exposes_content_group_false_positive() -> None:
    articles = [
        _article(1, "CALIBRATION", "same event first", "calibration-event"),
        _article(2, "CALIBRATION", "same event second", "calibration-event"),
        _article(3, "HOLDOUT", "unrelated alpha", "holdout-event-a"),
        _article(4, "HOLDOUT", "unrelated omega", "holdout-event-b"),
        _article(5, "HOLDOUT", "related first", "holdout-event-c"),
        _article(6, "HOLDOUT", "related second", "holdout-event-c"),
    ]
    articles[2]["fixedContentGroupId"] = "content-a"
    articles[2]["fixedContentGroupRepresentativeId"] = 3
    articles[3]["fixedContentGroupId"] = "content-a"
    articles[3]["fixedContentGroupRepresentativeId"] = 3
    result = sweep(
        {
            "datasetVersion": "test.v2",
            "articleCount": 6,
            "configuredEntityOverlapThreshold": 2,
            "configuredCommonEntityDocumentRatio": 0.10,
            "configuredTitleJaccardThreshold": 0.50,
            "configuredEntityTimeWindowHours": 48,
            "articles": articles,
            "pairEvaluations": [
                {
                    "commonEntityDocumentRatio": 0.05,
                    "pairs": [
                        _pair(1, 2, "CALIBRATION", entity_overlap=0),
                        _pair(5, 6, "HOLDOUT", title_jaccard=0.42),
                    ],
                },
                {
                    "commonEntityDocumentRatio": 0.10,
                    "pairs": [
                        _pair(
                            1,
                            2,
                            "CALIBRATION",
                            entity_overlap=0,
                            title_jaccard=0.42,
                        ),
                        _pair(5, 6, "HOLDOUT", title_jaccard=0.42),
                    ],
                },
            ],
        }
    )

    assert result["selected"]["common_entity_document_ratio"] == 0.10
    assert result["selected"]["title_jaccard_threshold"] == 0.40
    assert result["holdout"]["precision"] < 1.0
    assert result["holdout"]["recall"] == 1.0
    assert result["configured"]["holdoutMetrics"]["recall"] == 0.0
    assert result["decisionGatePassed"] is False
    assert result["tfidfCharWbBaseline"]["includesFixedContentGroups"] is True
    assert result["tfidfCharWbStandaloneBaseline"]["includesFixedContentGroups"] is False


def test_fixed_content_group_preserves_cross_topic_java_representative() -> None:
    articles = [
        _article(1, "HOLDOUT", "topic one", "event-a", topic_id=4386),
        _article(2, "HOLDOUT", "topic two", "event-b", topic_id=4387),
    ]
    for article in articles:
        article["fixedContentGroupId"] = "content-a"
        article["fixedContentGroupRepresentativeId"] = 1

    unions = _topic_unions(articles, include_fixed_content_groups=True)

    assert unions[4386].root(1) == 1
    assert unions[4387].root(2) == 1


def _article(
    article_id: int,
    split: str,
    title: str,
    issue_id: str | None = None,
    topic_id: int = 1,
) -> dict[str, object]:
    return {
        "articleId": article_id,
        "topicId": topic_id,
        "title": title,
        "expectedIssueId": issue_id or f"{split.lower()}-issue",
        "split": split,
    }


def _pair(
    left: int,
    right: int,
    split: str,
    entity_overlap: int = 1,
    title_jaccard: float = 0.0,
) -> dict[str, object]:
    return {
        "leftArticleId": left,
        "rightArticleId": right,
        "topicId": 1,
        "titleJaccard": title_jaccard,
        "entityOverlap": entity_overlap,
        "hoursApart": 1.0,
        "split": split,
    }
