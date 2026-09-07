import pytest

from app.eval.cluster_sweep import (
    _evaluate_rule,
    _evaluate_tfidf,
    _topic_unions,
    sweep,
    validate_clustering_metadata,
)


def test_sweep_uses_exported_entity_threshold_and_calibrates_tfidf() -> None:
    articles = [
        _article(1, "CALIBRATION", "alpha"),
        _article(2, "CALIBRATION", "omega"),
        _article(3, "HOLDOUT", "bravo"),
        _article(4, "HOLDOUT", "zulu"),
    ]
    for article in articles:
        article["titleOrganizations"] = []
    articles[0]["titleOrganizations"] = ["Samsung"]
    result = sweep(
        {
            "datasetVersion": "test.v1",
            "clusteringRuleVersion": "title-organization-conflict-v1",
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
    assert result["clusteringRuleVersion"] == "title-organization-conflict-v1"
    assert result["titleOrganizationGuard"] == {
        "implementationVersion": "title-organization-conflict-v1",
        "metadataComplete": True,
        "profiledArticleCount": 1,
    }
    assert "calibrationMetrics" in result["tfidfCharWbBaseline"]
    assert result["tfidfCharWbBaseline"]["usesTitleOrganizationGuard"] is False
    assert result["tfidfCharWbStandaloneBaseline"]["usesTitleOrganizationGuard"] is False


def test_sweep_selects_ratio_output_and_exposes_content_group_false_positive(
    caplog: pytest.LogCaptureFixture,
) -> None:
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
    assert result["clusteringRuleVersion"] == "legacy"
    assert result["titleOrganizationGuard"]["metadataComplete"] is False
    assert result["titleOrganizationGuard"]["profiledArticleCount"] == 0
    assert "missing titleOrganizations leave those articles unguarded" in caplog.text


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


@pytest.mark.parametrize(
    "metadata",
    [
        {},
        {"titleOrganizations": None},
        {"titleOrganizations": "Samsung"},
        {"titleOrganizations": [""]},
        {"titleOrganizations": [" "]},
        {"titleOrganizations": ["Samsung", 1]},
        {"titleOrganizations": [["Samsung"]]},
    ],
)
def test_sweep_rejects_missing_or_malformed_versioned_title_organizations(
    metadata: dict[str, object],
) -> None:
    with pytest.raises(ValueError, match="titleOrganizations must be a list of nonempty strings"):
        sweep(
            {
                "clusteringRuleVersion": "title-organization-conflict-v1",
                "articles": [{**_article(1, "HOLDOUT", "Report"), **metadata}],
            }
        )


def test_direct_rule_replay_rejects_malformed_title_organizations() -> None:
    article = _article(1, "HOLDOUT", "Report")
    article["titleOrganizations"] = "Samsung"

    with pytest.raises(ValueError, match="titleOrganizations"):
        _evaluate_rule([article], [], "HOLDOUT", 0.50, 48, 2)


@pytest.mark.parametrize("version", ["unknown-rule-v2", None, 1])
def test_sweep_rejects_unsupported_rule_versions(version: object) -> None:
    with pytest.raises(ValueError, match="Unsupported clusteringRuleVersion"):
        sweep({"clusteringRuleVersion": version, "articles": []})


def test_explicit_legacy_version_warns_when_metadata_is_optional(
    caplog: pytest.LogCaptureFixture,
) -> None:
    validate_clustering_metadata(
        {"clusteringRuleVersion": "legacy", "articles": [_article(1, "HOLDOUT", "Report")]}
    )

    assert "Legacy clustering export" in caplog.text


@pytest.mark.parametrize("with_metadata", [False, True])
def test_title_organization_conflict_blocks_direct_merge_only_with_metadata(
    with_metadata: bool,
) -> None:
    articles = [
        _article(1, "HOLDOUT", "Samsung exhibit announcement", "event-a"),
        _article(2, "HOLDOUT", "SK Hynix exhibit announcement", "event-b"),
    ]
    if with_metadata:
        articles[0]["titleOrganizations"] = ["Samsung"]
        articles[1]["titleOrganizations"] = ["SK Hynix"]

    metrics = _evaluate_rule(
        articles, [_pair(1, 2, "HOLDOUT", title_jaccard=0.90)], "HOLDOUT", 0.50, 48, 2
    )

    assert metrics.precision == (1.0 if with_metadata else 0.0)


@pytest.mark.parametrize("bridge_organizations", [[], ["Samsung", "SK Hynix"]])
def test_title_organization_profiles_block_unknown_and_multi_organization_bridges(
    bridge_organizations: list[str],
) -> None:
    articles = [
        _article(1, "HOLDOUT", "Samsung exhibit announcement", "event-a"),
        _article(2, "HOLDOUT", "Exhibit announcement coverage", "event-a"),
        _article(3, "HOLDOUT", "SK Hynix exhibit announcement", "event-b"),
    ]
    articles[0]["titleOrganizations"] = ["Samsung"]
    articles[1]["titleOrganizations"] = bridge_organizations
    articles[2]["titleOrganizations"] = ["SK Hynix"]
    # The input order must not choose the bridge's component before article 1 does.
    pairs = [
        _pair(2, 3, "HOLDOUT", title_jaccard=0.90),
        _pair(1, 2, "HOLDOUT", title_jaccard=0.90),
    ]

    metrics = _evaluate_rule(list(reversed(articles)), pairs, "HOLDOUT", 0.50, 48, 2)

    assert metrics.precision == 1.0
    assert metrics.recall == 1.0


def test_title_organization_metadata_keeps_canonical_alias_pair() -> None:
    articles = [
        _article(1, "HOLDOUT", "AMAT unveils memory process", "same-event"),
        _article(2, "HOLDOUT", "어플라이드 메모리 공정 공개", "same-event"),
    ]
    for article in articles:
        article["titleOrganizations"] = ["Applied Materials"]

    matches = _evaluate_rule(
        articles, [_pair(1, 2, "HOLDOUT", title_jaccard=0.50)], "HOLDOUT", 0.50, 48, 2
    )
    no_positive_evidence = _evaluate_rule(
        articles, [_pair(1, 2, "HOLDOUT")], "HOLDOUT", 0.50, 48, 2
    )

    assert matches.recall == 1.0
    assert no_positive_evidence.recall == 0.0


def test_mixed_fixed_group_keeps_new_single_vendor_followup_separate() -> None:
    articles = [
        _article(1, "HOLDOUT", "Samsung original report", "forced-event"),
        _article(2, "HOLDOUT", "SK Hynix syndicated title", "forced-event"),
        _article(3, "HOLDOUT", "Samsung separate report", "other-event"),
    ]
    articles[0]["titleOrganizations"] = ["Samsung"]
    articles[1]["titleOrganizations"] = ["SK Hynix"]
    articles[2]["titleOrganizations"] = ["Samsung"]
    for article in articles[:2]:
        article["fixedContentGroupId"] = "content-a"
        article["fixedContentGroupRepresentativeId"] = 1

    # Preserve the mixed legacy group without allowing a matching single-vendor
    # follow-up to expand it. Java locks the same three-article policy down.
    union = _topic_unions(articles, include_fixed_content_groups=True)[1]
    metrics = _evaluate_rule(
        articles, [_pair(1, 3, "HOLDOUT", title_jaccard=0.90)], "HOLDOUT", 0.50, 48, 2
    )

    assert union.root(1) == union.root(2)
    assert union.can_join(1, 2) is True
    assert union.can_join(1, 3) is False
    assert metrics.precision == 1.0
    assert metrics.recall == 1.0


def test_title_organization_guard_retains_cross_topic_proxy_profile() -> None:
    articles = [
        _article(1, "HOLDOUT", "Samsung global representative", "event-a", topic_id=2),
        _article(2, "HOLDOUT", "Local syndicated copy", "event-a"),
        _article(3, "HOLDOUT", "SK Hynix separate report", "event-b"),
    ]
    articles[0]["titleOrganizations"] = ["Samsung"]
    articles[1]["titleOrganizations"] = []
    articles[2]["titleOrganizations"] = ["SK Hynix"]
    for article in articles[:2]:
        article["fixedContentGroupId"] = "content-a"
        article["fixedContentGroupRepresentativeId"] = 1

    metrics = _evaluate_rule(
        articles, [_pair(1, 3, "HOLDOUT", title_jaccard=0.90)], "HOLDOUT", 0.50, 48, 2
    )

    assert metrics.precision == 1.0
    assert metrics.adjusted_rand == 1.0


@pytest.mark.parametrize("new_profile", [[], ["Samsung", "SK Hynix"]])
def test_mixed_fixed_group_accepts_unknown_or_all_overlapping_profile_when_title_matches(
    new_profile: list[str],
) -> None:
    articles = [
        _article(1, "HOLDOUT", "Samsung original", "forced-event"),
        _article(2, "HOLDOUT", "SK Hynix syndicated title", "forced-event"),
        _article(3, "HOLDOUT", "Coverage", "forced-event"),
    ]
    articles[0]["titleOrganizations"] = ["Samsung"]
    articles[1]["titleOrganizations"] = ["SK Hynix"]
    articles[2]["titleOrganizations"] = new_profile
    for article in articles[:2]:
        article["fixedContentGroupId"] = "content-a"
        article["fixedContentGroupRepresentativeId"] = 1

    metrics = _evaluate_rule(
        articles, [_pair(1, 3, "HOLDOUT", title_jaccard=0.90)], "HOLDOUT", 0.50, 48, 2
    )

    assert metrics.precision == 1.0
    assert metrics.recall == 1.0


def test_tfidf_baseline_does_not_use_title_organization_guard() -> None:
    articles = [
        _article(1, "HOLDOUT", "Identical exhibit announcement", "event-a"),
        _article(2, "HOLDOUT", "Identical exhibit announcement", "event-b"),
    ]
    articles[0]["titleOrganizations"] = ["Samsung"]
    articles[1]["titleOrganizations"] = ["SK Hynix"]

    metrics = _evaluate_tfidf(articles, "HOLDOUT", 0.50, include_fixed_content_groups=True)

    assert metrics.precision == 0.0


@pytest.mark.parametrize(
    "breaking,hours,expected_recall",
    [
        (False, 24, 1.0),
        (False, 24.01, 0.0),
        (True, 6, 1.0),
        (True, 6.01, 0.0),
    ],
)
def test_event_text_replay_preserves_runtime_time_windows(breaking, hours, expected_recall):
    articles = [_article(1, "HOLDOUT", "first"), _article(2, "HOLDOUT", "second")]
    pair = {
        **_pair(1, 2, "HOLDOUT", entity_overlap=0, hours_apart=hours, breaking_pair=breaking),
        "eventTextMatch": True,
    }
    metrics = _evaluate_rule(articles, [pair], "HOLDOUT", 0.5, 48, 2, 0.125, 24, 6)
    assert metrics.recall == expected_recall


@pytest.mark.parametrize("edge", ["entity", "organization", "lexical"])
def test_event_text_replay_preserves_background_and_organization_guards(edge):
    articles = [_article(1, "HOLDOUT", "first", "a"), _article(2, "HOLDOUT", "second", "b")]
    pair = _pair(1, 2, "HOLDOUT", entity_overlap=2, title_jaccard=0.2, organization_overlap=1)
    pair.update(entityTitleSupported=False, organizationTitleSupported=False)
    if edge == "entity":
        pair["organizationOverlap"] = 0
    elif edge == "organization":
        pair["entityOverlap"] = 0
    else:
        pair["eventTextMatch"] = True
        articles[0]["titleOrganizations"] = ["Samsung"]
        articles[1]["titleOrganizations"] = ["SK Hynix"]
    metrics = _evaluate_rule(articles, [pair], "HOLDOUT", 0.5, 48, 2, 0.125, 24, 6)
    assert metrics.precision == 1.0


@pytest.mark.parametrize(
    "field,value",
    [
        ("eventTextMatch", None),
        ("entityTitleSupported", 1),
        ("organizationTitleSupported", "true"),
        ("titleTextSimilarity", float("nan")),
        ("titleTextSimilarity", True),
        ("leadTextSimilarity", 1.01),
    ],
)
def test_event_text_metadata_rejects_missing_or_invalid_features(field, value):
    article = {**_article(1, "HOLDOUT", "first"), "titleOrganizations": []}
    pair = {
        **_pair(1, 2, "HOLDOUT"),
        "eventTextMatch": True,
        "entityTitleSupported": False,
        "organizationTitleSupported": False,
        "titleTextSimilarity": 0.4,
        "leadTextSimilarity": 0.2,
    }
    output = {
        "clusteringRuleVersion": "event-text-evidence-v2",
        "articles": [article],
        "pairEvaluations": [{"pairs": [pair]}],
    }
    validate_clustering_metadata(output)
    pair[field] = value
    with pytest.raises(ValueError, match="Event evidence"):
        validate_clustering_metadata(output)


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
