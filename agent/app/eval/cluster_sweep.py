import argparse
import json
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.metrics import adjusted_rand_score, v_measure_score
from sklearn.metrics.cluster import pair_confusion_matrix
from sklearn.metrics.pairwise import cosine_similarity

_JACCARD_THRESHOLDS = tuple(round(0.40 + step * 0.05, 2) for step in range(8))
_TIME_WINDOWS = (24, 48, 72)


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
    metrics: Metrics


class UnionFind:
    def __init__(self, values: list[int]) -> None:
        self.parents = {value: value for value in values}

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
            self.parents[max(left_root, right_root)] = min(left_root, right_root)


def sweep(java_output: dict[str, Any]) -> dict[str, Any]:
    articles = java_output["articles"]
    pairs = java_output["pairs"]
    entity_overlap_threshold = int(java_output["configuredEntityOverlapThreshold"])
    calibration = [
        Candidate(
            title_jaccard_threshold=threshold,
            time_window_hours=hours,
            metrics=_evaluate_rule(
                articles,
                pairs,
                "CALIBRATION",
                threshold,
                hours,
                entity_overlap_threshold,
            ),
        )
        for threshold in _JACCARD_THRESHOLDS
        for hours in _TIME_WINDOWS
    ]
    selected = _select(calibration)
    holdout = _evaluate_rule(
        articles,
        pairs,
        "HOLDOUT",
        selected.title_jaccard_threshold,
        selected.time_window_hours,
        entity_overlap_threshold,
    )
    baseline_threshold, baseline_calibration = _select_tfidf_threshold(
        articles, "CALIBRATION"
    )
    baseline_holdout = _evaluate_tfidf(articles, "HOLDOUT", baseline_threshold)
    return {
        "datasetVersion": java_output["datasetVersion"],
        "articleCount": java_output["articleCount"],
        "selectionSplit": "CALIBRATION",
        "validationSplit": "HOLDOUT",
        "selected": asdict(selected),
        "holdout": asdict(holdout),
        "configuredEntityOverlapThreshold": entity_overlap_threshold,
        "tfidfCharWbBaseline": {
            "threshold": baseline_threshold,
            "calibrationMetrics": asdict(baseline_calibration),
            "metrics": asdict(baseline_holdout),
        },
        "precisionGate": 0.90,
        "precisionGatePassed": holdout.precision >= 0.90,
        "candidates": [asdict(candidate) for candidate in calibration],
    }


def _evaluate_rule(
    articles: list[dict[str, Any]],
    pairs: list[dict[str, Any]],
    split: str,
    threshold: float,
    time_window_hours: int,
    entity_overlap_threshold: int,
) -> Metrics:
    selected = [article for article in articles if article["split"] == split]
    article_ids = [int(article["articleId"]) for article in selected]
    selected_ids = set(article_ids)
    union = UnionFind(article_ids)
    for pair in pairs:
        left = int(pair["leftArticleId"])
        right = int(pair["rightArticleId"])
        if left not in selected_ids or right not in selected_ids:
            continue
        matches = float(pair["titleJaccard"]) >= threshold or (
            int(pair["entityOverlap"]) >= entity_overlap_threshold
            and float(pair["hoursApart"]) <= time_window_hours
        )
        if matches:
            union.join(left, right)
    expected = [f'{article["topicId"]}:{article["expectedIssueId"]}' for article in selected]
    predicted = [
        f'{article["topicId"]}:{union.root(int(article["articleId"]))}'
        for article in selected
    ]
    return _metrics(expected, predicted)


def _select_tfidf_threshold(
    articles: list[dict[str, Any]], split: str
) -> tuple[float, Metrics]:
    selected, topic_similarities = _prepare_tfidf(articles, split)
    candidates = [
        (threshold, _evaluate_prepared_tfidf(selected, topic_similarities, threshold))
        for threshold in _JACCARD_THRESHOLDS
    ]
    if not candidates:
        raise ValueError("TF-IDF 비교군을 계산할 기사가 없습니다.")
    return max(candidates, key=lambda candidate: _metric_rank(candidate[1], candidate[0]))


def _evaluate_tfidf(
    articles: list[dict[str, Any]], split: str, threshold: float
) -> Metrics:
    selected, topic_similarities = _prepare_tfidf(articles, split)
    return _evaluate_prepared_tfidf(selected, topic_similarities, threshold)


def _evaluate_prepared_tfidf(
    selected: list[dict[str, Any]],
    topic_similarities: list[tuple[list[dict[str, Any]], Any]],
    threshold: float,
) -> Metrics:
    ids = [int(article["articleId"]) for article in selected]
    union = UnionFind(ids)
    for topic_articles, similarities in topic_similarities:
        for left in range(len(topic_articles)):
            for right in range(left + 1, len(topic_articles)):
                if similarities[left, right] >= threshold:
                    union.join(
                        int(topic_articles[left]["articleId"]),
                        int(topic_articles[right]["articleId"]),
                    )
    expected = [f'{article["topicId"]}:{article["expectedIssueId"]}' for article in selected]
    predicted = [
        f'{article["topicId"]}:{union.root(int(article["articleId"]))}'
        for article in selected
    ]
    return _metrics(expected, predicted)


def _prepare_tfidf(
    articles: list[dict[str, Any]], split: str
) -> tuple[list[dict[str, Any]], list[tuple[list[dict[str, Any]], Any]]]:
    selected = [article for article in articles if article["split"] == split]
    result: list[tuple[list[dict[str, Any]], Any]] = []
    for topic_id in sorted({int(article["topicId"]) for article in selected}):
        topic_articles = [
            article for article in selected if int(article["topicId"]) == topic_id
        ]
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


def _select(candidates: list[Candidate]) -> Candidate:
    passing = [candidate for candidate in candidates if candidate.metrics.precision >= 0.90]
    if not passing:
        return max(candidates, key=lambda candidate: _candidate_rank(candidate, False))
    return max(passing, key=lambda candidate: _candidate_rank(candidate, True))


def _candidate_rank(candidate: Candidate, precision_gate_passed: bool) -> tuple[float, ...]:
    if precision_gate_passed:
        return (
            candidate.metrics.recall,
            candidate.metrics.precision,
            candidate.metrics.adjusted_rand,
            candidate.title_jaccard_threshold,
            -abs(candidate.time_window_hours - 48),
        )
    return (
        candidate.metrics.precision,
        candidate.metrics.recall,
        candidate.metrics.adjusted_rand,
        candidate.title_jaccard_threshold,
        -abs(candidate.time_window_hours - 48),
    )


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
    return 0 if result["precisionGatePassed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
