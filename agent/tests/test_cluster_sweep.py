from app.eval.cluster_sweep import _evaluate_rule, _topic_unions, sweep


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


def test_rule_uses_cross_topic_content_representative_pairs() -> None:
    articles = [
        _article(1, "HOLDOUT", "global representative", "event-a", topic_id=2),
        _article(2, "HOLDOUT", "local syndicated copy", "event-a"),
        _article(3, "HOLDOUT", "independent follow-up", "event-a"),
    ]
    for article in articles[:2]:
        article["fixedContentGroupId"] = "content-a"
        article["fixedContentGroupRepresentativeId"] = 1

    metrics = _evaluate_rule(
        articles,
        [_pair(1, 3, "HOLDOUT", title_jaccard=0.50)],
        "HOLDOUT",
        threshold=0.50,
        time_window_hours=48,
        entity_overlap_threshold=2,
    )

    assert metrics.precision == 1.0
    assert metrics.recall == 1.0


def test_rule_excludes_unrelated_topic_ids_and_other_split_proxies() -> None:
    articles = [
        _article(1, "HOLDOUT", "global representative", "event-a", topic_id=2),
        _article(2, "HOLDOUT", "local syndicated copy", "event-a"),
        _article(3, "HOLDOUT", "independent follow-up", "event-a"),
        _article(4, "HOLDOUT", "unrelated other topic", "event-b", topic_id=2),
        _article(5, "HOLDOUT", "unrelated local article", "event-c"),
        _article(6, "CALIBRATION", "other split representative", "event-d", topic_id=2),
        _article(7, "HOLDOUT", "other split content copy", "event-d"),
    ]
    for article in articles[:2]:
        article["fixedContentGroupId"] = "content-a"
        article["fixedContentGroupRepresentativeId"] = 1
    for article in articles[5:]:
        article["fixedContentGroupId"] = "content-d"
        article["fixedContentGroupRepresentativeId"] = 6

    metrics = _evaluate_rule(
        articles,
        [
            _pair(1, 3, "HOLDOUT", title_jaccard=0.50),
            _pair(1, 4, "HOLDOUT", title_jaccard=0.50),
            _pair(4, 5, "HOLDOUT", title_jaccard=0.50),
            _pair(6, 3, "CROSS", title_jaccard=0.50),
            _pair(999, 3, "HOLDOUT", title_jaccard=0.50),
            {**_pair(2, 3, "HOLDOUT", title_jaccard=0.50), "topicId": 99},
        ],
        "HOLDOUT",
        threshold=0.50,
        time_window_hours=48,
        entity_overlap_threshold=2,
    )

    assert metrics.precision == 1.0
    assert metrics.recall == 1.0


def test_sweep_reports_post_hoc_relabel_sensitivity() -> None:
    articles = [
        _article(1, "CALIBRATION", "same event first", "calibration-event"),
        _article(2, "CALIBRATION", "same event second", "calibration-event"),
        _article(3, "HOLDOUT", "holdout first", "holdout-event"),
        _article(4, "HOLDOUT", "holdout second", "holdout-event"),
    ]
    articles[3]["sourceArticleId"] = 2410
    result = sweep(
        {
            "datasetVersion": "test.post-hoc-relabel",
            "articleCount": 4,
            "postHocRelabeledSourceArticleIds": [2410],
            "configuredEntityOverlapThreshold": 2,
            "configuredCommonEntityDocumentRatio": 0.10,
            "configuredTitleJaccardThreshold": 0.50,
            "configuredEntityTimeWindowHours": 48,
            "articles": articles,
            "pairs": [
                _pair(1, 2, "CALIBRATION", title_jaccard=0.50),
                _pair(3, 4, "HOLDOUT", title_jaccard=0.0, entity_overlap=0),
            ],
        }
    )

    sensitivity = result["postHocRelabelSensitivity"]
    assert sensitivity["excludedSourceArticleIds"] == [2410]
    assert sensitivity["selectedHoldoutMetrics"]["recall"] == 1.0


def test_sweep_requires_every_organization_corroboration_predicate() -> None:
    articles = [
        _article(1, "CALIBRATION", "DGIST 반도체 검사 기술", "calibration-event"),
        _article(2, "CALIBRATION", "DGIST 초음파 센서 개발", "calibration-event"),
        _article(3, "CALIBRATION", "조직 없음 기준 하나", "no-org-a"),
        _article(4, "CALIBRATION", "조직 없음 기준 둘", "no-org-b"),
        _article(5, "CALIBRATION", "제목 기준 미달 하나", "weak-title-a"),
        _article(6, "CALIBRATION", "제목 기준 미달 둘", "weak-title-b"),
        _article(7, "CALIBRATION", "시간 기준 초과 하나", "late-a"),
        _article(8, "CALIBRATION", "시간 기준 초과 둘", "late-b"),
        _article(9, "CALIBRATION", "속보 시간 초과 하나", "breaking-late-a"),
        _article(10, "CALIBRATION", "속보 시간 초과 둘", "breaking-late-b"),
        _article(11, "CALIBRATION", "속보 경계 하나", "breaking-event"),
        _article(12, "CALIBRATION", "속보 경계 둘", "breaking-event"),
        _article(13, "CALIBRATION", "속보 제목 초과 하나", "breaking-title-a"),
        _article(14, "CALIBRATION", "속보 제목 초과 둘", "breaking-title-b"),
        _article(15, "CALIBRATION", "속보 엔티티 초과 하나", "breaking-entity-a"),
        _article(16, "CALIBRATION", "속보 엔티티 초과 둘", "breaking-entity-b"),
        _article(17, "HOLDOUT", "AMAT 메모리 병목 해법", "holdout-event"),
        _article(18, "HOLDOUT", "어플라이드 3D 공정 공개", "holdout-event"),
    ]
    result = sweep(
        {
            "datasetVersion": "test.organization-rule",
            "articleCount": 18,
            "configuredEntityOverlapThreshold": 2,
            "configuredCommonEntityDocumentRatio": 0.10,
            "configuredTitleJaccardThreshold": 0.50,
            "configuredEntityTimeWindowHours": 48,
            "configuredBreakingTimeWindowHours": 6,
            "configuredOrganizationTitleJaccardThreshold": 0.125,
            "configuredOrganizationTimeWindowHours": 24,
            "articles": articles,
            "pairs": [
                _pair(
                    1,
                    2,
                    "CALIBRATION",
                    entity_overlap=0,
                    title_jaccard=0.125,
                    organization_overlap=1,
                    hours_apart=24.0,
                ),
                _pair(
                    3,
                    4,
                    "CALIBRATION",
                    entity_overlap=0,
                    title_jaccard=0.125,
                    organization_overlap=0,
                ),
                _pair(
                    5,
                    6,
                    "CALIBRATION",
                    entity_overlap=0,
                    title_jaccard=0.124,
                    organization_overlap=1,
                ),
                _pair(
                    7,
                    8,
                    "CALIBRATION",
                    entity_overlap=0,
                    title_jaccard=0.125,
                    organization_overlap=1,
                    hours_apart=24.01,
                ),
                _pair(
                    9,
                    10,
                    "CALIBRATION",
                    entity_overlap=0,
                    title_jaccard=0.125,
                    organization_overlap=1,
                    hours_apart=6.01,
                    breaking_pair=True,
                ),
                _pair(
                    11,
                    12,
                    "CALIBRATION",
                    entity_overlap=0,
                    title_jaccard=0.125,
                    organization_overlap=1,
                    hours_apart=6.0,
                    breaking_pair=True,
                ),
                _pair(
                    13,
                    14,
                    "CALIBRATION",
                    entity_overlap=0,
                    title_jaccard=0.50,
                    hours_apart=6.01,
                    breaking_pair=True,
                ),
                _pair(
                    15,
                    16,
                    "CALIBRATION",
                    entity_overlap=2,
                    title_jaccard=0.0,
                    hours_apart=6.01,
                    breaking_pair=True,
                ),
                _pair(
                    17,
                    18,
                    "HOLDOUT",
                    entity_overlap=0,
                    title_jaccard=0.125,
                    organization_overlap=1,
                    hours_apart=24.0,
                ),
            ],
        }
    )

    assert result["bodySource"] == "unspecified"
    assert result["selected"]["organization_title_jaccard_threshold"] == 0.125
    assert result["selected"]["organization_time_window_hours"] == 24
    assert result["configured"]["calibrationMetrics"]["precision"] == 1.0
    assert result["configured"]["calibrationMetrics"]["recall"] == 1.0
    assert result["configured"]["holdoutMetrics"]["recall"] == 1.0
    assert result["decisionGatePassed"] is True


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
    organization_overlap: int = 0,
    hours_apart: float = 1.0,
    breaking_pair: bool = False,
) -> dict[str, object]:
    return {
        "leftArticleId": left,
        "rightArticleId": right,
        "topicId": 1,
        "titleJaccard": title_jaccard,
        "entityOverlap": entity_overlap,
        "organizationOverlap": organization_overlap,
        "breakingPair": breaking_pair,
        "hoursApart": hours_apart,
        "split": split,
    }
