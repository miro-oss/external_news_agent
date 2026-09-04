"""Prepare a blind corpus, seal truth labels, then consume one Java evaluation.

Hashes make accidental edits and input substitution detectable; they are a local
audit trail, not a signature or proof that a human has never seen predictions.
"""

import argparse
import csv
import hashlib
import json
import math
import re
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

from app.eval import cluster_sweep

ARTICLE_FIELDS = frozenset(
    {
        "articleId",
        "sourceArticleId",
        "sourceRunId",
        "sourceRunIds",
        "topicId",
        "title",
        "summary",
        "body",
        "fetchStatus",
        "sourceId",
        "publisher",
        "reliabilityScore",
        "publishedAt",
        "observedAt",
        "topicKeywords",
    }
)
LABEL_FIELDS = ["articleId", "expectedIssueId", "split", "rationale"]
IDENTITY_FIELDS = (
    "articleId",
    "sourceArticleId",
    "sourceRunId",
    "sourceId",
    "topicId",
    "title",
    "expectedIssueId",
    "split",
)
RATIOS = [0.05, 0.10, 0.15, 0.20]
SPLITS = ("CALIBRATION", "HOLDOUT")
MIN_TOPICS = 2
MIN_ARTICLES_PER_TOPIC_SPLIT = 20


def _sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _read(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path.name} must be a JSON object")
    return value


def _write(path: Path, value: Any) -> None:
    with path.open("x", encoding="utf-8") as output:
        json.dump(value, output, ensure_ascii=False, indent=2, allow_nan=False)
        output.write("\n")


def _protocol() -> dict[str, Any]:
    return {
        "commonEntityDocumentRatioCandidates": RATIOS,
        "titleJaccardThresholds": list(cluster_sweep._JACCARD_THRESHOLDS),
        "timeWindowsHours": list(cluster_sweep._TIME_WINDOWS),
        "organizationTitleJaccardThresholds": list(cluster_sweep._ORGANIZATION_JACCARD_THRESHOLDS),
        "organizationTimeWindowsHours": list(cluster_sweep._ORGANIZATION_TIME_WINDOWS),
        "precisionGate": 0.90,
        "recallGate": 0.85,
        "minimumTopicsPerSplit": MIN_TOPICS,
        "minimumArticlesPerTopicPerSplit": MIN_ARTICLES_PER_TOPIC_SPLIT,
        "documentFrequencyScope": "SPLIT",
        "sweepSha256": _sha(Path(cluster_sweep.__file__)),
        "packToolSha256": _sha(Path(__file__)),
    }


def _reject_prediction_keys(value: Any) -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            if re.search(r"predict|expected|split|cluster|fixedcontent|posthoc", key, re.I):
                raise ValueError(f"Snapshot contains prohibited prediction/label key: {key}")
            _reject_prediction_keys(child)
    elif isinstance(value, list):
        for child in value:
            _reject_prediction_keys(child)


def _positive_int(value: Any, field: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
        raise ValueError(f"{field} must be a positive integer")
    return value


def _validate_snapshot(snapshot: dict[str, Any]) -> None:
    _reject_prediction_keys(snapshot)
    if not isinstance(snapshot.get("datasetVersion"), str) or not snapshot["datasetVersion"]:
        raise ValueError("datasetVersion is required")
    runs = snapshot.get("sourceRuns", [])
    if not isinstance(runs, list) or len(set(runs)) < 3:
        raise ValueError("At least three distinct sourceRuns are required")
    for run in runs:
        _positive_int(run, "sourceRuns")
    if snapshot.get("commonEntityDocumentRatioCandidates") != RATIOS:
        raise ValueError("The precommitted common entity ratio grid must be [0.05,0.10,0.15,0.20]")
    articles = snapshot.get("articles")
    if not isinstance(articles, list) or not articles:
        raise ValueError("articles must be a nonempty list")
    article_ids = set()
    for article in articles:
        if not isinstance(article, dict) or set(article) != ARTICLE_FIELDS:
            raise ValueError("Article fields must exactly match the blind raw-article whitelist")
        for field in ("articleId", "sourceArticleId", "sourceRunId", "topicId", "sourceId"):
            _positive_int(article[field], field)
        article_id = article["articleId"]
        if article_id in article_ids:
            raise ValueError(f"Duplicate articleId: {article_id}")
        article_ids.add(article_id)
        if not isinstance(article["title"], str) or not article["title"].strip():
            raise ValueError(f"Article {article_id} has no title")
        for field in ("summary", "body"):
            if article[field] is not None and not isinstance(article[field], str):
                raise ValueError(f"Article {article_id} has invalid {field}")
        article_runs = article["sourceRunIds"]
        if (
            not isinstance(article_runs, list)
            or not article_runs
            or not all(isinstance(run, int) and not isinstance(run, bool) for run in article_runs)
            or not set(article_runs).issubset(runs)
            or article["sourceRunId"] not in article_runs
        ):
            raise ValueError(f"Article {article_id} source run provenance is inconsistent")


def prepare(snapshot_path: Path, output_dir: Path) -> dict[str, Any]:
    snapshot = _read(snapshot_path)
    _validate_snapshot(snapshot)
    # A new directory prevents reusing a previously exposed holdout by accident.
    output_dir.mkdir(parents=True, exist_ok=False)
    (output_dir / "snapshot.json").write_bytes(snapshot_path.read_bytes())
    snapshot_hash = _sha(output_dir / "snapshot.json")
    articles = sorted(
        snapshot["articles"],
        key=lambda article: hashlib.sha256(
            f"{snapshot_hash}:{article['articleId']}".encode()
        ).digest(),
    )
    with (output_dir / "annotation.jsonl").open("x", encoding="utf-8") as output:
        for article in articles:
            output.write(json.dumps(article, ensure_ascii=False, allow_nan=False) + "\n")
    with (output_dir / "labels.csv").open("x", encoding="utf-8", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=LABEL_FIELDS)
        writer.writeheader()
        writer.writerows({"articleId": article["articleId"]} for article in articles)
    manifest = {
        "schemaVersion": 1,
        "datasetVersion": snapshot["datasetVersion"],
        "articleCount": len(articles),
        "snapshotSha256": snapshot_hash,
        "annotationSha256": _sha(output_dir / "annotation.jsonl"),
        "protocol": _protocol(),
    }
    _write(output_dir / "manifest.json", manifest)
    return manifest


def _verify_prepared(pack: Path) -> tuple[dict[str, Any], dict[str, Any]]:
    manifest = _read(pack / "manifest.json")
    for name, key in (
        ("snapshot.json", "snapshotSha256"),
        ("annotation.jsonl", "annotationSha256"),
    ):
        if _sha(pack / name) != manifest[key]:
            raise ValueError(f"Prepared file changed: {name}")
    if manifest["protocol"] != _protocol():
        raise ValueError("Precommitted protocol or evaluation code changed")
    snapshot = _read(pack / "snapshot.json")
    _validate_snapshot(snapshot)
    if manifest["datasetVersion"] != snapshot["datasetVersion"] or manifest["articleCount"] != len(
        snapshot["articles"]
    ):
        raise ValueError("Manifest and snapshot identity differ")
    return snapshot, manifest


def _labels(pack: Path, articles: list[dict[str, Any]]) -> tuple[dict[int, Any], dict[str, Any]]:
    result: dict[int, Any] = {}
    with (pack / "labels.csv").open(encoding="utf-8-sig", newline="") as source:
        reader = csv.DictReader(source)
        if reader.fieldnames != LABEL_FIELDS:
            raise ValueError(f"labels.csv columns must be {LABEL_FIELDS}")
        for row in reader:
            if None in row or any(value is None for value in row.values()):
                raise ValueError("Malformed labels.csv row")
            try:
                article_id = int(row["articleId"])
            except ValueError as error:
                raise ValueError("Label articleId must be an integer") from error
            if article_id in result:
                raise ValueError(f"Duplicate label articleId: {article_id}")
            if not re.fullmatch(r"[\w.:-]{1,200}", row["expectedIssueId"]):
                raise ValueError(f"Article {article_id} needs a valid expectedIssueId")
            if row["split"] not in SPLITS:
                raise ValueError(f"Article {article_id} needs CALIBRATION or HOLDOUT split")
            if not row["rationale"].strip():
                raise ValueError(f"Article {article_id} needs a rationale")
            result[article_id] = {key: row[key] for key in LABEL_FIELDS if key != "articleId"}
    if set(result) != {article["articleId"] for article in articles}:
        raise ValueError("Labels must exactly cover snapshot article IDs (no missing or extra IDs)")

    issue_splits: dict[str, set[str]] = defaultdict(set)
    source_splits: dict[int, set[str]] = defaultdict(set)
    populations: dict[str, Counter] = {split: Counter() for split in SPLITS}
    truth_counts: dict[str, Counter] = {split: Counter() for split in SPLITS}
    for article in articles:
        label = result[article["articleId"]]
        split, issue_id = label["split"], label["expectedIssueId"]
        issue_splits[issue_id].add(split)
        source_splits[article["sourceArticleId"]].add(split)
        populations[split][article["topicId"]] += 1
        truth_counts[split][article["topicId"], issue_id] += 1
    if any(len(splits) != 1 for splits in issue_splits.values()):
        raise ValueError("The same expectedIssueId cannot cross splits, including across topics")
    if any(len(splits) != 1 for splits in source_splits.values()):
        raise ValueError("The same sourceArticleId cannot cross splits")
    coverage = {}
    for split in SPLITS:
        counts = populations[split]
        if len(counts) < MIN_TOPICS or any(
            count < MIN_ARTICLES_PER_TOPIC_SPLIT for count in counts.values()
        ):
            raise ValueError(f"{split} requires at least two topics with >=20 articles each")
        positive_pairs = sum(count * (count - 1) // 2 for count in truth_counts[split].values())
        all_pairs = sum(count * (count - 1) // 2 for count in counts.values())
        negative_pairs = all_pairs - positive_pairs
        if not positive_pairs or not negative_pairs:
            raise ValueError(f"{split} needs positive and negative same-topic truth pairs")
        coverage[split] = {
            "topicArticleCounts": dict(counts),
            "positivePairs": positive_pairs,
            "negativePairs": negative_pairs,
        }
    return result, coverage


def freeze(pack: Path) -> dict[str, Any]:
    if any((pack / name).exists() for name in ("golden.json", "golden.sha256", "seal.json")):
        raise FileExistsError(
            "This pack already has a golden file or seal; freeze never overwrites"
        )
    snapshot, manifest = _verify_prepared(pack)
    labels, coverage = _labels(pack, snapshot["articles"])
    golden = {
        **snapshot,
        "articles": [
            {**article, **labels[article["articleId"]]} for article in snapshot["articles"]
        ],
    }
    _write(pack / "golden.json", golden)
    golden_hash = _sha(pack / "golden.json")
    with (pack / "golden.sha256").open("x", encoding="utf-8") as output:
        output.write(golden_hash + "\n")
    seal = {
        "schemaVersion": 1,
        "protocol": manifest["protocol"],
        "coverage": coverage,
        "files": {
            name: _sha(pack / name)
            for name in (
                "snapshot.json",
                "annotation.jsonl",
                "manifest.json",
                "labels.csv",
                "golden.json",
                "golden.sha256",
            )
        },
    }
    _write(pack / "seal.json", seal)
    return seal


def _verify_java(golden: dict[str, Any], java: dict[str, Any], golden_hash: str) -> None:
    cluster_sweep.validate_clustering_metadata(java)
    if java.get("goldenSha256") != golden_hash:
        raise ValueError("Java output goldenSha256 does not match the sealed golden file")
    if java.get("datasetVersion") != golden["datasetVersion"]:
        raise ValueError("Java output datasetVersion differs")
    if java.get("documentFrequencyScope") != "SPLIT":
        raise ValueError("Java output must use split-isolated document frequency")
    for field in (
        "configuredEntityOverlapThreshold",
        "configuredTitleJaccardThreshold",
        "configuredEntityTimeWindowHours",
        "configuredBreakingTimeWindowHours",
        "configuredOrganizationTitleJaccardThreshold",
        "configuredOrganizationTimeWindowHours",
    ):
        value = java.get(field)
        if (
            not isinstance(value, (int, float))
            or isinstance(value, bool)
            or not math.isfinite(value)
            or value < 0
        ):
            raise ValueError(f"Java output needs a finite nonnegative {field}")
    expected = {article["articleId"]: article for article in golden["articles"]}
    actual = java.get("articles", [])
    if java.get("articleCount") != len(expected) or len(actual) != len(expected):
        raise ValueError("Java output articleCount differs")
    seen = set()
    fixed_group_splits: dict[str, set[str]] = defaultdict(set)
    topic_members: dict[tuple[int, str], set[int]] = defaultdict(set)
    for article in actual:
        article_id = article["articleId"]
        if article_id in seen or article_id not in expected:
            raise ValueError("Java output contains duplicate or unknown article IDs")
        seen.add(article_id)
        if any(article.get(field) != expected[article_id][field] for field in IDENTITY_FIELDS):
            raise ValueError(f"Java article identity or frozen label mismatch: {article_id}")
        topic_members[article["topicId"], article["split"]].add(article_id)
        group = article.get("fixedContentGroupId")
        if group is not None:
            fixed_group_splits[str(group)].add(article["split"])
        representative_id = article.get("fixedContentGroupRepresentativeId")
        if representative_id is not None and (
            representative_id not in expected
            or expected[representative_id]["split"] != article["split"]
        ):
            raise ValueError("Fixed content representative crosses splits or is unknown")
        if representative_id is not None:
            topic_members[article["topicId"], article["split"]].add(representative_id)
    if any(len(splits) > 1 for splits in fixed_group_splits.values()):
        raise ValueError("Fixed content group crosses splits")
    evaluations = java.get("pairEvaluations", [])
    ratios = [item.get("commonEntityDocumentRatio") for item in evaluations]
    if sorted(ratios) != RATIOS or java.get("configuredCommonEntityDocumentRatio") not in RATIOS:
        raise ValueError("Java ratio candidates differ from precommitted grid")
    for evaluation in evaluations:
        pair_keys = set()
        for pair in evaluation["pairs"]:
            left, right = pair["leftArticleId"], pair["rightArticleId"]
            if left not in expected or right not in expected or left == right:
                raise ValueError("Java pair references invalid article IDs")
            pair_key = (pair["topicId"], *sorted((left, right)))
            if pair_key in pair_keys:
                raise ValueError("Java output contains duplicate pairs")
            pair_keys.add(pair_key)
            if (
                left not in topic_members[pair["topicId"], pair["split"]]
                or right not in topic_members[pair["topicId"], pair["split"]]
                or expected[left]["split"] != expected[right]["split"]
                or pair["split"] != expected[left]["split"]
            ):
                raise ValueError("Java pair topic/split differs from sealed articles")
            for field in ("hoursApart", "titleJaccard", "entityOverlap", "organizationOverlap"):
                value = pair.get(field)
                if not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0:
                    raise ValueError(f"Java pair has invalid {field}")


def evaluate(pack: Path, java_pairs: Path) -> dict[str, Any]:
    if (pack / "report.json").exists():
        raise FileExistsError("This holdout has already been evaluated; report.json is immutable")
    seal = _read(pack / "seal.json")
    for name in (
        "snapshot.json",
        "annotation.jsonl",
        "manifest.json",
        "labels.csv",
        "golden.json",
        "golden.sha256",
    ):
        if _sha(pack / name) != seal["files"].get(name):
            raise ValueError(f"Sealed file changed: {name}")
    if seal["protocol"] != _protocol():
        raise ValueError("Sealed protocol or evaluation code changed")
    snapshot, _ = _verify_prepared(pack)
    labels, _ = _labels(pack, snapshot["articles"])
    golden = _read(pack / "golden.json")
    if golden != {
        **snapshot,
        "articles": [
            {**article, **labels[article["articleId"]]} for article in snapshot["articles"]
        ],
    }:
        raise ValueError("Golden file differs from frozen snapshot and labels")
    golden_hash = _sha(pack / "golden.json")
    if (pack / "golden.sha256").read_text(encoding="utf-8").strip() != golden_hash:
        raise ValueError("golden.sha256 differs")
    java = _read(java_pairs)
    _verify_java(golden, java, golden_hash)
    # Reserve the only report before touching the holdout so concurrent invocations
    # cannot both evaluate it. A failed computation leaves an explicit failed report.
    with (pack / "report.json").open("x", encoding="utf-8") as output:
        try:
            result = {
                **cluster_sweep.sweep(java),
                "independentValidation": {
                    "goldenSha256": golden_hash,
                    "sealSha256": _sha(pack / "seal.json"),
                    "javaOutputSha256": _sha(java_pairs),
                    "coverage": seal["coverage"],
                    "documentFrequencyScope": "SPLIT",
                },
            }
        except Exception:
            json.dump({"status": "FAILED", "holdoutConsumed": True}, output)
            raise
        json.dump(result, output, ensure_ascii=False, indent=2, allow_nan=False)
        output.write("\n")
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Blind independent cluster validation pack")
    commands = parser.add_subparsers(dest="command", required=True)
    prepare_parser = commands.add_parser("prepare")
    prepare_parser.add_argument("--snapshot", type=Path, required=True)
    prepare_parser.add_argument("--output-dir", type=Path, required=True)
    freeze_parser = commands.add_parser("freeze")
    freeze_parser.add_argument("--pack", type=Path, required=True)
    evaluate_parser = commands.add_parser("evaluate")
    evaluate_parser.add_argument("--pack", type=Path, required=True)
    evaluate_parser.add_argument("--java-pairs", type=Path, required=True)
    args = parser.parse_args(argv)
    if args.command == "prepare":
        result = prepare(args.snapshot, args.output_dir)
    elif args.command == "freeze":
        result = freeze(args.pack)
    else:
        result = evaluate(args.pack, args.java_pairs)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if args.command != "evaluate" or result["decisionGatePassed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
