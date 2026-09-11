import argparse
import hashlib
import json
import logging
from collections import Counter
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.metrics import adjusted_rand_score, v_measure_score
from sklearn.metrics.cluster import pair_confusion_matrix
from sklearn.metrics.pairwise import cosine_similarity

_JACCARD_THRESHOLDS = tuple(round(0.40 + step * 0.05, 2) for step in range(8))
_TIME_WINDOWS = (24, 48, 72)
_ORGANIZATION_JACCARD_THRESHOLDS = (0.10, 0.125, 0.15, 0.20)
_ORGANIZATION_TIME_WINDOWS = (12, 24, 48)
_TITLE_ORGANIZATION_RULE_VERSION = "title-organization-conflict-v1"
_EVENT_CONFLICT_RULE_VERSION = "event-text-evidence-v4"
_EVENT_CONFLICT_RULE_VERSIONS = (_EVENT_CONFLICT_RULE_VERSION, "event-text-evidence-v5")
_EVENT_TEXT_RULE_VERSIONS = (
    "event-text-evidence-v2", "event-text-evidence-v3", *_EVENT_CONFLICT_RULE_VERSIONS,
)
SELECTION_POLICY_VERSION = "retain-configured-calibration-tie-v1"
_LOGGER = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class Metrics:
    precision: float
    recall: float
    adjusted_rand: float
    v_measure: float


@dataclass(frozen=True, slots=True)
class Candidate:
    title_jaccard_threshold: float
    time_window_hours: int
    common_entity_document_ratio: float
    organization_title_jaccard_threshold: float
    organization_time_window_hours: int
    metrics: Metrics


class UnionFind:
    def __init__(
        self,
        values: list[int],
        title_organizations: dict[int, frozenset[str]] | None = None,
        event_conflicts: dict[int, frozenset[int]] | None = None,
    ) -> None:
        self.parents = {value: value for value in values}
        self.member_ids = {value: {value} for value in values}
        self.event_conflicts = event_conflicts or {}
        organizations = title_organizations or {}
        self.organization_profiles = {
            value: {organizations[value]} if organizations.get(value) else set()
            for value in values
        }

    def root(self, value: int) -> int:
        parent = self.parents[value]
        if parent == value:
            return value
        root = self.root(parent)
        self.parents[value] = root
        return root

    def join(self, left: int, right: int) -> None:
        left_root = self.root(left)
        right_root = self.root(right)
        if left_root != right_root:
            kept_root = min(left_root, right_root)
            merged_root = max(left_root, right_root)
            self.parents[merged_root] = kept_root
            self.member_ids[kept_root].update(self.member_ids.pop(merged_root))
            self.organization_profiles[kept_root].update(
                self.organization_profiles.pop(merged_root)
            )

    def can_join(self, left: int, right: int) -> bool:
        left_root = self.root(left)
        right_root = self.root(right)
        if left_root == right_root:
            return True
        if any(
            not self.event_conflicts.get(article_id, frozenset()).isdisjoint(
                self.member_ids[right_root]
            )
            for article_id in self.member_ids[left_root]
        ):
            return False
        return all(
            not left_organizations.isdisjoint(right_organizations)
            for left_organizations in self.organization_profiles[left_root]
            for right_organizations in self.organization_profiles[right_root]
        )


def validate_clustering_metadata(java_output: dict[str, Any]) -> None:
    version = java_output.get("clusteringRuleVersion", "legacy")
    if version not in ("legacy", _TITLE_ORGANIZATION_RULE_VERSION, *_EVENT_TEXT_RULE_VERSIONS):
        raise ValueError(f"Unsupported clusteringRuleVersion: {version!r}")
    if version == "legacy":
        _LOGGER.warning(
            "Legacy clustering export: title organization conflict checks use only supplied "
            "profiles; missing titleOrganizations leave those articles unguarded."
        )
    _validate_title_organizations(
        java_output["articles"],
        required=version != "legacy",
    )
    _validate_event_conflicts(
        java_output["articles"], required=version in _EVENT_CONFLICT_RULE_VERSIONS
    )
    if version in _EVENT_TEXT_RULE_VERSIONS:
        evaluations = java_output.get("pairEvaluations") or [
            {"pairs": java_output.get("pairs", [])}
        ]
        for evaluation in evaluations:
            for pair in evaluation["pairs"]:
                for field in (
                    "eventTextMatch",
                    "entityTitleSupported",
                    "organizationTitleSupported",
                ):
                    if not isinstance(pair.get(field), bool):
                        raise ValueError(f"Event evidence requires boolean {field}")
                if version in _EVENT_CONFLICT_RULE_VERSIONS and not isinstance(
                    pair.get("specificEventMatch"), bool
                ):
                    raise ValueError("Event evidence requires boolean specificEventMatch")
                for field in ("titleTextSimilarity", "leadTextSimilarity"):
                    value = pair.get(field)
                    if (
                        isinstance(value, bool)
                        or not isinstance(value, (int, float))
                        or not 0 <= value <= 1
                    ):
                        raise ValueError(f"Event evidence requires 0..1 {field}")


def _validate_title_organizations(
    articles: list[dict[str, Any]], *, required: bool = False
) -> None:
    for article in articles:
        if "titleOrganizations" not in article and not required:
            continue
        organizations = article.get("titleOrganizations")
        if not isinstance(organizations, list) or any(
            not isinstance(value, str) or not value.strip() for value in organizations
        ):
            raise ValueError(
                f"Article {article.get('articleId')} titleOrganizations must be a list "
                "of nonempty strings (an empty list is allowed)"
            )


def _validate_event_conflicts(
    articles: list[dict[str, Any]], *, required: bool = False
) -> None:
    field = "eventConflictingArticleIds"
    if not required and not any(field in article for article in articles):
        return
    by_id: dict[int, dict[str, Any]] = {}
    for article in articles:
        article_id = article.get("articleId")
        if (
            isinstance(article_id, bool)
            or not isinstance(article_id, int)
            or article_id in by_id
        ):
            raise ValueError(f"{field} requires unique integer articleId values")
        by_id[article_id] = article
    for article_id, article in by_id.items():
        if field not in article and not required:
            continue
        conflicts = article.get(field)
        if not isinstance(conflicts, list) or any(
            isinstance(value, bool) or not isinstance(value, int) for value in conflicts
        ):
            raise ValueError(f"Article {article_id} {field} must be a list of integer IDs")
        if conflicts != sorted(set(conflicts)):
            raise ValueError(f"Article {article_id} {field} must be sorted without duplicates")
        for other_id in conflicts:
            if other_id == article_id:
                raise ValueError(f"Article {article_id} {field} must not reference itself")
            other = by_id.get(other_id)
            if other is None or other.get("split") != article.get("split"):
                raise ValueError(
                    f"Article {article_id} {field} references unknown or cross-split ID"
                )
            other_conflicts = other.get(field)
            if not isinstance(other_conflicts, list) or article_id not in other_conflicts:
                raise ValueError(f"Article {article_id} {field} must be symmetric")


def _calibration_input(java_output: dict[str, Any]) -> dict[str, Any]:
    # Deliberately discard HOLDOUT before validation, hashing, feature preparation,
    # or threshold selection. A malformed/unseen HOLDOUT cannot affect this step.
    articles = [
        article for article in java_output["articles"] if article.get("split") == "CALIBRATION"
    ]
    article_ids = {article["articleId"] for article in articles}
    result = {
        key: value for key, value in java_output.items()
        if key in (
            "configuredEntityOverlapThreshold", "configuredCommonEntityDocumentRatio",
            "configuredTitleJaccardThreshold", "configuredEntityTimeWindowHours",
            "configuredBreakingTimeWindowHours", "configuredOrganizationTitleJaccardThreshold",
            "configuredOrganizationTimeWindowHours", "clusteringRuleVersion",
        )
    }
    result.update(articles=articles, articleCount=len(articles))
    evaluations = java_output.get("pairEvaluations") or [{
        "commonEntityDocumentRatio": java_output["configuredCommonEntityDocumentRatio"],
        "pairs": java_output["pairs"],
    }]
    result["pairEvaluations"] = [
        {
            "commonEntityDocumentRatio": evaluation["commonEntityDocumentRatio"],
            "pairs": [
                pair for pair in evaluation["pairs"]
                if pair.get("split", "CALIBRATION") == "CALIBRATION"
                and pair["leftArticleId"] in article_ids
                and pair["rightArticleId"] in article_ids
            ],
        }
        for evaluation in evaluations
    ]
    return result


def _calibration_gate(metrics: Metrics) -> bool:
    return metrics.precision >= 0.90 and metrics.recall >= 0.85


def _candidate_from_record(record: dict[str, Any]) -> Candidate:
    return Candidate(**{**record, "metrics": Metrics(**record["metrics"])})


def select_calibration(java_output: dict[str, Any]) -> dict[str, Any]:
    """Select and describe a policy using CALIBRATION data exclusively.

    This JSON-serializable record can be sealed before a separate HOLDOUT run.
    An incumbent tied on calibration quality is retained in its entirety; the
    record exposes ambiguity instead of claiming a shorter time window is better.
    """
    java_output = _calibration_input(java_output)
    validate_clustering_metadata(java_output)
    articles = java_output["articles"]
    if not articles:
        raise ValueError("CALIBRATION articles are required for configuration selection")
    entity_overlap_threshold = int(java_output["configuredEntityOverlapThreshold"])
    configured_ratio = float(java_output["configuredCommonEntityDocumentRatio"])
    configured_title_threshold = float(java_output["configuredTitleJaccardThreshold"])
    configured_time_window = int(java_output["configuredEntityTimeWindowHours"])
    configured_breaking_time_window = int(
        java_output.get("configuredBreakingTimeWindowHours", 0)
    )
    configured_organization_title_threshold = float(
        java_output.get("configuredOrganizationTitleJaccardThreshold", 1.0)
    )
    configured_organization_time_window = int(
        java_output.get("configuredOrganizationTimeWindowHours", 0)
    )
    organization_title_candidates = (
        _ORGANIZATION_JACCARD_THRESHOLDS
        if "configuredOrganizationTitleJaccardThreshold" in java_output
        else (configured_organization_title_threshold,)
    )
    organization_time_candidates = (
        _ORGANIZATION_TIME_WINDOWS
        if "configuredOrganizationTimeWindowHours" in java_output
        else (configured_organization_time_window,)
    )
    pair_evaluations = java_output["pairEvaluations"]
    calibration = [
        Candidate(
            title_jaccard_threshold=threshold,
            time_window_hours=hours,
            common_entity_document_ratio=float(evaluation["commonEntityDocumentRatio"]),
            organization_title_jaccard_threshold=organization_threshold,
            organization_time_window_hours=organization_hours,
            metrics=_evaluate_rule(
                articles, evaluation["pairs"], "CALIBRATION", threshold, hours,
                entity_overlap_threshold, organization_threshold, organization_hours,
                configured_breaking_time_window,
            ),
        )
        for evaluation in pair_evaluations
        for threshold in _JACCARD_THRESHOLDS
        for hours in _TIME_WINDOWS
        for organization_threshold in organization_title_candidates
        for organization_hours in organization_time_candidates
    ]
    configured_pairs = _pairs_for_ratio(pair_evaluations, configured_ratio)
    configured_metrics = _evaluate_rule(
        articles, configured_pairs, "CALIBRATION", configured_title_threshold,
        configured_time_window, entity_overlap_threshold,
        configured_organization_title_threshold, configured_organization_time_window,
        configured_breaking_time_window,
    )
    incumbent = Candidate(
        configured_title_threshold, configured_time_window, configured_ratio,
        configured_organization_title_threshold, configured_organization_time_window,
        configured_metrics,
    )
    if incumbent not in calibration:
        # The deployed configuration remains a legitimate candidate even when a
        # deployment uses a value outside the predeclared search grid.
        calibration.append(incumbent)
    selected = _select(calibration, configured_time_window, configured_ratio)
    ties = _selection_metric_ties(calibration, selected)
    retained_incumbent = incumbent in ties
    if retained_incumbent:
        selected = incumbent
    gate_passed = _calibration_gate(selected.metrics)
    if not gate_passed:
        status = "CALIBRATION_FAILED"
    elif retained_incumbent:
        status = "RETAINED_CONFIGURED_IN_CALIBRATION_TIE" if len(ties) > 1 else (
            "RETAINED_CONFIGURED_CALIBRATION_BEST"
        )
    else:
        status = "SELECTED_CALIBRATION_BEST" if len(ties) == 1 else (
            "SELECTED_AMBIGUOUS_CALIBRATION_BEST"
        )
    tie_configurations = [_candidate_configuration(candidate) for candidate in ties]
    unidentified = [
        key for key in _candidate_configuration(selected)
        if len({configuration[key] for configuration in tie_configurations}) > 1
    ]
    baseline_threshold, baseline_calibration = _select_tfidf_threshold(
        articles, "CALIBRATION", True
    )
    standalone_threshold, standalone_calibration = _select_tfidf_threshold(
        articles, "CALIBRATION", False
    )
    return {
        "selectionPolicyVersion": SELECTION_POLICY_VERSION,
        "calibrationInputSha256": hashlib.sha256(
            json.dumps(java_output, sort_keys=True, ensure_ascii=False, allow_nan=False).encode()
        ).hexdigest(),
        "selectionSplit": "CALIBRATION",
        "selectionStatus": status,
        "selectionTieCount": len(ties),
        "unidentifiedParameters": unidentified,
        "calibrationGatePassed": gate_passed,
        "calibrationTopicMetrics": _topic_metrics(
            articles, _pairs_for_ratio(pair_evaluations, selected.common_entity_document_ratio),
            "CALIBRATION", selected, entity_overlap_threshold, configured_breaking_time_window,
        ),
        "selected": asdict(selected),
        "configuredEntityOverlapThreshold": entity_overlap_threshold,
        "configuredBreakingTimeWindowHours": configured_breaking_time_window,
        "configuredOrganizationTitleJaccardThreshold": configured_organization_title_threshold,
        "configuredOrganizationTimeWindowHours": configured_organization_time_window,
        "configured": {
            **_candidate_configuration(incumbent),
            "calibrationMetrics": asdict(configured_metrics),
        },
        "tfidfCharWbBaseline": {
            "usesTitleOrganizationGuard": False,
            "usesEventConflictGuard": False,
            "includesFixedContentGroups": True,
            "threshold": baseline_threshold,
            "calibrationMetrics": asdict(baseline_calibration),
        },
        "tfidfCharWbStandaloneBaseline": {
            "usesTitleOrganizationGuard": False,
            "usesEventConflictGuard": False,
            "includesFixedContentGroups": False,
            "threshold": standalone_threshold,
            "calibrationMetrics": asdict(standalone_calibration),
        },
        "selectionMetricTies": tie_configurations,
        "candidates": [asdict(candidate) for candidate in calibration],
    }


def sweep(
    java_output: dict[str, Any], *, selection: dict[str, Any] | None = None,
) -> dict[str, Any]:
    # Recompute only CALIBRATION to reject a stale or edited saved selection before
    # any HOLDOUT metric or TF-IDF feature is evaluated.
    validate_clustering_metadata(java_output)
    current_selection = select_calibration(java_output)
    if selection is not None and selection != current_selection:
        raise ValueError(
            "Frozen calibration selection differs from current CALIBRATION policy/input"
        )
    selection = current_selection
    articles = java_output["articles"]
    selected = _candidate_from_record(selection["selected"])
    configured = selection["configured"]
    entity_overlap_threshold = selection["configuredEntityOverlapThreshold"]
    breaking_time_window = selection["configuredBreakingTimeWindowHours"]
    pair_evaluations = java_output.get("pairEvaluations") or [{
        "commonEntityDocumentRatio": configured["commonEntityDocumentRatio"],
        "pairs": java_output["pairs"],
    }]
    selected_pairs = _pairs_for_ratio(pair_evaluations, selected.common_entity_document_ratio)
    configured_pairs = _pairs_for_ratio(pair_evaluations, configured["commonEntityDocumentRatio"])

    def holdout_metrics(
        candidate: Candidate, pairs: list[dict[str, Any]],
        excluded_ids: frozenset[int] = frozenset(),
    ) -> Metrics:
        return _evaluate_rule(
            articles, pairs, "HOLDOUT", candidate.title_jaccard_threshold,
            candidate.time_window_hours, entity_overlap_threshold,
            candidate.organization_title_jaccard_threshold,
            candidate.organization_time_window_hours, breaking_time_window, excluded_ids,
        )

    incumbent = Candidate(
        configured["titleJaccardThreshold"], configured["timeWindowHours"],
        configured["commonEntityDocumentRatio"], configured["organizationTitleJaccardThreshold"],
        configured["organizationTimeWindowHours"], Metrics(**configured["calibrationMetrics"]),
    )
    holdout = holdout_metrics(selected, selected_pairs)
    configured_holdout = holdout_metrics(incumbent, configured_pairs)
    baseline = selection["tfidfCharWbBaseline"]
    standalone = selection["tfidfCharWbStandaloneBaseline"]
    baseline_holdout = _evaluate_tfidf(articles, "HOLDOUT", baseline["threshold"], True)
    standalone_holdout = _evaluate_tfidf(articles, "HOLDOUT", standalone["threshold"], False)
    post_hoc_relabel_ids = frozenset(
        int(value) for value in java_output.get("postHocRelabeledSourceArticleIds", [])
    )
    post_hoc_relabel_sensitivity = None
    if post_hoc_relabel_ids:
        post_hoc_relabel_sensitivity = {
            "excludedSourceArticleIds": sorted(post_hoc_relabel_ids),
            "selectedHoldoutMetrics": asdict(
                holdout_metrics(selected, selected_pairs, post_hoc_relabel_ids)
            ),
            "configuredHoldoutMetrics": asdict(
                holdout_metrics(incumbent, configured_pairs, post_hoc_relabel_ids)
            ),
        }
    holdout_gate_passed = _calibration_gate(holdout)
    return {
        **selection,
        "datasetVersion": java_output["datasetVersion"],
        "clusteringRuleVersion": java_output.get("clusteringRuleVersion", "legacy"),
        "titleOrganizationGuard": {
            "implementationVersion": _TITLE_ORGANIZATION_RULE_VERSION,
            "metadataComplete": all("titleOrganizations" in article for article in articles),
            "profiledArticleCount": sum(
                bool(article.get("titleOrganizations")) for article in articles
            ),
        },
        "eventConflictGuard": {
            "implementationVersion": _EVENT_CONFLICT_RULE_VERSION,
            "metadataComplete": all(
                "eventConflictingArticleIds" in article for article in articles
            ),
            "conflictedArticleCount": sum(
                bool(article.get("eventConflictingArticleIds")) for article in articles
            ),
        },
        "articleCount": java_output["articleCount"],
        "bodySource": java_output.get("bodySource", "unspecified"),
        "validationSplit": "HOLDOUT",
        "evaluationScope": "METRIC_COMPARISON_ONLY",
        "independentAcceptance": False,
        "holdout": asdict(holdout),
        "holdoutTopicMetrics": _topic_metrics(
            articles, selected_pairs, "HOLDOUT", selected,
            entity_overlap_threshold, breaking_time_window,
        ),
        "configured": {**configured, "holdoutMetrics": asdict(configured_holdout)},
        "tfidfCharWbBaseline": {**baseline, "metrics": asdict(baseline_holdout)},
        "tfidfCharWbStandaloneBaseline": {**standalone, "metrics": asdict(standalone_holdout)},
        "precisionGate": 0.90,
        "precisionGatePassed": holdout.precision >= 0.90,
        "recallGate": 0.85,
        "recallGatePassed": holdout.recall >= 0.85,
        "holdoutGatePassed": holdout_gate_passed,
        "decisionGatePassed": selection["calibrationGatePassed"] and holdout_gate_passed,
        "postHocRelabelSensitivity": post_hoc_relabel_sensitivity,
    }


def _evaluate_rule(
    articles: list[dict[str, Any]],
    pairs: list[dict[str, Any]],
    split: str,
    threshold: float,
    time_window_hours: int,
    entity_overlap_threshold: int,
    organization_title_jaccard_threshold: float = 1.0,
    organization_time_window_hours: int = 0,
    breaking_time_window_hours: int = 0,
    excluded_source_article_ids: frozenset[int] = frozenset(),
    *, topic_id: int | None = None,
) -> Metrics:
    scoring_topic_id = topic_id
    selected = [
        article
        for article in articles
        if article["split"] == split
        and int(article.get("sourceArticleId", -1)) not in excluded_source_article_ids
    ]
    selected_ids = {int(article["articleId"]) for article in selected}
    unions = _topic_unions(selected, include_fixed_content_groups=True)
    voting_ids_by_topic = {
        topic_id: set(union.parents) & selected_ids for topic_id, union in unions.items()
    }

    # The conflict guard is component-dependent: match Java's ascending article-id
    # traversal even when an exported pair list arrives in another order.
    for pair in sorted(
        pairs,
        key=lambda pair: (
            int(pair["topicId"]),
            min(int(pair["leftArticleId"]), int(pair["rightArticleId"])),
            max(int(pair["leftArticleId"]), int(pair["rightArticleId"])),
        ),
    ):
        left = int(pair["leftArticleId"])
        right = int(pair["rightArticleId"])
        topic_id = int(pair["topicId"])
        # Java includes global content representatives as proxies in each observed topic.
        # A proxy may vote here only when its source article is in the selected split.
        voting_ids = voting_ids_by_topic.get(topic_id, set())
        if left not in voting_ids or right not in voting_ids:
            continue
        hours_apart = float(pair["hoursApart"])
        title_matches = float(pair["titleJaccard"]) >= threshold
        enough_entities = int(pair["entityOverlap"]) >= entity_overlap_threshold and pair.get(
            "entityTitleSupported", True
        )
        organization_matches = (
            organization_time_window_hours > 0
            and int(pair.get("organizationOverlap", 0)) >= 1
            and float(pair["titleJaccard"]) >= organization_title_jaccard_threshold
            and pair.get("organizationTitleSupported", True)
        )
        if bool(pair.get("breakingPair", False)):
            matches = hours_apart <= breaking_time_window_hours and (
                title_matches
                or enough_entities
                or organization_matches
                or pair.get("eventTextMatch", False)
                or pair.get("specificEventMatch", False)
            )
        else:
            matches = (
                title_matches
                or (enough_entities and hours_apart <= time_window_hours)
                or (organization_matches and hours_apart <= organization_time_window_hours)
                or (
                    pair.get("eventTextMatch", False)
                    and hours_apart <= organization_time_window_hours
                )
                or (pair.get("specificEventMatch", False) and hours_apart <= time_window_hours)
            )
        if matches and unions[topic_id].can_join(left, right):
            unions[topic_id].join(left, right)
    expected = [f"{article['topicId']}:{article['expectedIssueId']}" for article in selected]
    predicted = [
        f"{article['topicId']}:{unions[int(article['topicId'])].root(int(article['articleId']))}"
        for article in selected
    ]
    if scoring_topic_id is not None:
        indices = [
            index for index, article in enumerate(selected)
            if int(article["topicId"]) == scoring_topic_id
        ]
        expected = [expected[index] for index in indices]
        predicted = [predicted[index] for index in indices]
    return _metrics(expected, predicted)


def _topic_metrics(
    articles: list[dict[str, Any]], pairs: list[dict[str, Any]], split: str,
    candidate: Candidate, entity_overlap_threshold: int, breaking_time_window_hours: int,
) -> dict[str, Any]:
    result = {}
    topics = sorted({int(article["topicId"]) for article in articles if article["split"] == split})
    for topic_id in topics:
        topic_articles = [
            article for article in articles
            if article["split"] == split and int(article["topicId"]) == topic_id
        ]
        truth_counts = Counter(article["expectedIssueId"] for article in topic_articles)
        positive_pairs = sum(count * (count - 1) // 2 for count in truth_counts.values())
        article_count = len(topic_articles)
        # Keep the full split here: global content representatives can be proxies
        # from another topic. Only the scored rows are restricted to this topic.
        metrics = _evaluate_rule(
            articles, pairs, split, candidate.title_jaccard_threshold,
            candidate.time_window_hours, entity_overlap_threshold,
            candidate.organization_title_jaccard_threshold,
            candidate.organization_time_window_hours, breaking_time_window_hours,
            topic_id=topic_id,
        )
        result[str(topic_id)] = {
            "metrics": asdict(metrics),
            "articleCount": article_count,
            "uniqueSourceArticleCount": len({
                article.get("sourceArticleId", article["articleId"]) for article in topic_articles
            }),
            "positivePairs": positive_pairs,
            "negativePairs": article_count * (article_count - 1) // 2 - positive_pairs,
            "multiArticleEvents": sum(count >= 2 for count in truth_counts.values()),
            "truthEventCount": len(truth_counts),
            "qualityGatePassed": _calibration_gate(metrics),
        }
    return result


def _selected_ids_by_topic(
    selected: list[dict[str, Any]],
) -> dict[int, set[int]]:
    result: dict[int, set[int]] = {}
    for article in selected:
        result.setdefault(int(article["topicId"]), set()).add(int(article["articleId"]))
    return result


def _topic_unions(
    selected: list[dict[str, Any]], include_fixed_content_groups: bool
) -> dict[int, UnionFind]:
    _validate_title_organizations(selected)
    selected_ids_by_topic = _selected_ids_by_topic(selected)
    # Include all selected topics so a global content representative retains its
    # title organization profile when it votes as a proxy in another topic.
    title_organizations = {
        int(article["articleId"]): frozenset(article.get("titleOrganizations") or [])
        for article in selected
    }
    event_conflicts = {
        int(article["articleId"]): frozenset(article.get("eventConflictingArticleIds") or [])
        for article in selected
    }
    representative_by_group: dict[str, int] = {}
    for article in selected:
        group_id = article.get("fixedContentGroupId")
        if group_id is None:
            continue
        representative_id = article.get("fixedContentGroupRepresentativeId")
        if representative_id is not None:
            representative_by_group[str(group_id)] = int(representative_id)
        else:
            representative_by_group.setdefault(str(group_id), int(article["articleId"]))

    result: dict[int, UnionFind] = {}
    for topic_id, article_ids in selected_ids_by_topic.items():
        topic_articles = [article for article in selected if int(article["topicId"]) == topic_id]
        proxy_ids = {
            representative_by_group[str(article["fixedContentGroupId"])]
            for article in topic_articles
            if include_fixed_content_groups and article.get("fixedContentGroupId") is not None
        }
        union = UnionFind(sorted(article_ids | proxy_ids), title_organizations, event_conflicts)
        if include_fixed_content_groups:
            for article in topic_articles:
                group_id = article.get("fixedContentGroupId")
                if group_id is not None:
                    union.join(int(article["articleId"]), representative_by_group[str(group_id)])
        result[topic_id] = union
    return result


def _select_tfidf_threshold(
    articles: list[dict[str, Any]], split: str, include_fixed_content_groups: bool
) -> tuple[float, Metrics]:
    selected, topic_similarities = _prepare_tfidf(articles, split)
    candidates = [
        (
            threshold,
            _evaluate_prepared_tfidf(
                selected, topic_similarities, threshold, include_fixed_content_groups
            ),
        )
        for threshold in _JACCARD_THRESHOLDS
    ]
    if not candidates:
        raise ValueError("TF-IDF 비교군을 계산할 기사가 없습니다.")
    return max(candidates, key=lambda candidate: _metric_rank(candidate[1], candidate[0]))


def _evaluate_tfidf(
    articles: list[dict[str, Any]],
    split: str,
    threshold: float,
    include_fixed_content_groups: bool,
) -> Metrics:
    selected, topic_similarities = _prepare_tfidf(articles, split)
    return _evaluate_prepared_tfidf(
        selected, topic_similarities, threshold, include_fixed_content_groups
    )


def _evaluate_prepared_tfidf(
    selected: list[dict[str, Any]],
    topic_similarities: list[tuple[list[dict[str, Any]], Any]],
    threshold: float,
    include_fixed_content_groups: bool,
) -> Metrics:
    unions = _topic_unions(selected, include_fixed_content_groups)
    for topic_articles, similarities in topic_similarities:
        topic_id = int(topic_articles[0]["topicId"])
        for left in range(len(topic_articles)):
            for right in range(left + 1, len(topic_articles)):
                if similarities[left, right] >= threshold:
                    unions[topic_id].join(
                        int(topic_articles[left]["articleId"]),
                        int(topic_articles[right]["articleId"]),
                    )
    expected = [f"{article['topicId']}:{article['expectedIssueId']}" for article in selected]
    predicted = [
        f"{article['topicId']}:{unions[int(article['topicId'])].root(int(article['articleId']))}"
        for article in selected
    ]
    return _metrics(expected, predicted)


def _prepare_tfidf(
    articles: list[dict[str, Any]], split: str
) -> tuple[list[dict[str, Any]], list[tuple[list[dict[str, Any]], Any]]]:
    selected = [article for article in articles if article["split"] == split]
    result: list[tuple[list[dict[str, Any]], Any]] = []
    for topic_id in sorted({int(article["topicId"]) for article in selected}):
        topic_articles = [article for article in selected if int(article["topicId"]) == topic_id]
        vectors = TfidfVectorizer(analyzer="char_wb", ngram_range=(2, 4)).fit_transform(
            article["title"] for article in topic_articles
        )
        result.append((topic_articles, cosine_similarity(vectors)))
    return selected, result


def _metrics(expected: list[str], predicted: list[str]) -> Metrics:
    matrix = pair_confusion_matrix(expected, predicted)
    false_positive = int(matrix[0, 1])
    false_negative = int(matrix[1, 0])
    true_positive = int(matrix[1, 1])
    precision = _rate(true_positive, true_positive + false_positive)
    recall = _rate(true_positive, true_positive + false_negative)
    return Metrics(
        precision=precision,
        recall=recall,
        adjusted_rand=float(adjusted_rand_score(expected, predicted)),
        v_measure=float(v_measure_score(expected, predicted)),
    )


def _select(
    candidates: list[Candidate],
    configured_time_window: int,
    configured_ratio: float,
) -> Candidate:
    passing = [candidate for candidate in candidates if _calibration_gate(candidate.metrics)]
    if not passing:
        return max(
            candidates,
            key=lambda candidate: _candidate_rank(
                candidate,
                False,
                configured_time_window,
                configured_ratio,
            ),
        )
    return max(
        passing,
        key=lambda candidate: _candidate_rank(
            candidate,
            True,
            configured_time_window,
            configured_ratio,
        ),
    )


def _candidate_rank(
    candidate: Candidate,
    precision_gate_passed: bool,
    configured_time_window: int,
    configured_ratio: float,
) -> tuple[float, ...]:
    if precision_gate_passed:
        return (
            candidate.metrics.recall,
            candidate.metrics.precision,
            candidate.metrics.adjusted_rand,
            candidate.title_jaccard_threshold,
            candidate.organization_title_jaccard_threshold,
            -candidate.organization_time_window_hours,
            -abs(candidate.time_window_hours - configured_time_window),
            -abs(candidate.common_entity_document_ratio - configured_ratio),
        )
    return (
        candidate.metrics.precision,
        candidate.metrics.recall,
        candidate.metrics.adjusted_rand,
        candidate.title_jaccard_threshold,
        candidate.organization_title_jaccard_threshold,
        -candidate.organization_time_window_hours,
        -abs(candidate.time_window_hours - configured_time_window),
        -abs(candidate.common_entity_document_ratio - configured_ratio),
    )


def _selection_metric_ties(
    candidates: list[Candidate], selected: Candidate
) -> list[Candidate]:
    precision_gate_passed = any(_calibration_gate(candidate.metrics) for candidate in candidates)
    selected_rank = _selection_metric_rank(selected, precision_gate_passed)
    return [
        candidate
        for candidate in candidates
        if (not precision_gate_passed or _calibration_gate(candidate.metrics))
        and _selection_metric_rank(candidate, precision_gate_passed) == selected_rank
    ]


def _selection_metric_rank(
    candidate: Candidate, precision_gate_passed: bool
) -> tuple[float, ...]:
    if precision_gate_passed:
        return (
            candidate.metrics.recall,
            candidate.metrics.precision,
            candidate.metrics.adjusted_rand,
        )
    return (
        candidate.metrics.precision,
        candidate.metrics.recall,
        candidate.metrics.adjusted_rand,
    )


def _candidate_configuration(candidate: Candidate) -> dict[str, float | int]:
    return {
        "titleJaccardThreshold": candidate.title_jaccard_threshold,
        "timeWindowHours": candidate.time_window_hours,
        "commonEntityDocumentRatio": candidate.common_entity_document_ratio,
        "organizationTitleJaccardThreshold": (
            candidate.organization_title_jaccard_threshold
        ),
        "organizationTimeWindowHours": candidate.organization_time_window_hours,
    }


def _pairs_for_ratio(pair_evaluations: list[dict[str, Any]], ratio: float) -> list[dict[str, Any]]:
    for evaluation in pair_evaluations:
        if abs(float(evaluation["commonEntityDocumentRatio"]) - ratio) < 1e-9:
            return evaluation["pairs"]
    raise ValueError(f"common entity document ratio {ratio}의 Java 출력이 없습니다.")


def _metric_rank(metrics: Metrics, threshold: float) -> tuple[float, ...]:
    return metrics.precision, metrics.recall, metrics.adjusted_rand, threshold


def _rate(numerator: int, denominator: int) -> float:
    return 1.0 if denominator == 0 else numerator / denominator


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Java 이슈 클러스터링 임계값 스윕")
    parser.add_argument("--golden", type=Path, required=True)
    parser.add_argument("--java-pairs", type=Path, required=True)
    args = parser.parse_args(argv)

    # golden은 정본 경로가 맞는지와 버전을 교차 확인한다. feature 계산은 Java 출력만 사용한다.
    golden = json.loads(args.golden.read_text(encoding="utf-8"))
    java_output = json.loads(args.java_pairs.read_text(encoding="utf-8"))
    if golden["datasetVersion"] != java_output["datasetVersion"]:
        raise ValueError("골든셋과 Java 출력의 datasetVersion이 다릅니다.")
    result = sweep(java_output)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["decisionGatePassed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
