import csv
import json
from pathlib import Path

import pytest

from app.eval.cluster_independent import (
    LABEL_FIELDS,
    RATIOS,
    evaluate,
    freeze,
    main,
    prepare,
)


def _snapshot() -> dict:
    return {
        "datasetVersion": "blind.synthetic.v1",
        "sourceRuns": [100, 101, 102],
        "commonEntityDocumentRatioCandidates": RATIOS,
        "articles": [
            {
                "articleId": article_id,
                "sourceArticleId": article_id + 1000,
                "sourceRunId": 100 + article_id % 3,
                "sourceRunIds": [100 + article_id % 3],
                "topicId": 1 + (article_id - 1) // 40,
                "title": f"Synthetic independent event {(article_id - 1) // 2}",
                "summary": None,
                "body": None if article_id % 2 else "Complete body retained without truncation.",
                "fetchStatus": "METADATA_ONLY" if article_id % 2 else "SUCCESS",
                "sourceId": 1,
                "publisher": "Synthetic publisher",
                "reliabilityScore": 0.8,
                "publishedAt": "2026-09-04T00:00:00Z",
                "observedAt": "2026-09-04T01:00:00Z",
                "topicKeywords": ["Synthetic"],
            }
            for article_id in range(1, 81)
        ],
    }


def _write_json(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False) + "\n", encoding="utf-8")


def _prepare(tmp_path: Path, snapshot: dict | None = None) -> Path:
    source = tmp_path / "input.json"
    _write_json(source, snapshot or _snapshot())
    pack = tmp_path / "pack"
    prepare(source, pack)
    return pack


def _read_labels(pack: Path) -> list[dict]:
    with (pack / "labels.csv").open(newline="", encoding="utf-8") as source:
        return list(csv.DictReader(source))


def _write_labels(pack: Path, labels: list[dict]) -> None:
    with (pack / "labels.csv").open("w", newline="", encoding="utf-8") as output:
        writer = csv.DictWriter(output, fieldnames=LABEL_FIELDS)
        writer.writeheader()
        writer.writerows(labels)


def _label(pack: Path) -> list[dict]:
    labels = _read_labels(pack)
    for label in labels:
        article_id = int(label["articleId"])
        label.update(
            expectedIssueId=f"event-{(article_id - 1) // 2}",
            split="CALIBRATION" if (article_id - 1) % 40 < 20 else "HOLDOUT",
            rationale="Same concrete event within each synthetic pair.",
        )
    _write_labels(pack, labels)
    return labels


def _java_output(pack: Path) -> dict:
    golden = json.loads((pack / "golden.json").read_text())
    articles = golden["articles"]
    pairs = [
        {
            "leftArticleId": left["articleId"],
            "rightArticleId": right["articleId"],
            "topicId": left["topicId"],
            "split": left["split"],
            "titleJaccard": 1.0,
            "entityOverlap": 2,
            "organizationOverlap": 0,
            "hoursApart": 0,
            "breakingPair": False,
        }
        for left, right in zip(articles[::2], articles[1::2], strict=True)
    ]
    return {
        "datasetVersion": golden["datasetVersion"],
        "goldenSha256": (pack / "golden.sha256").read_text().strip(),
        "articleCount": len(articles),
        "documentFrequencyScope": "SPLIT",
        "articles": articles,
        "configuredEntityOverlapThreshold": 2,
        "configuredCommonEntityDocumentRatio": 0.10,
        "configuredTitleJaccardThreshold": 0.50,
        "configuredEntityTimeWindowHours": 48,
        "configuredBreakingTimeWindowHours": 6,
        "configuredOrganizationTitleJaccardThreshold": 0.10,
        "configuredOrganizationTimeWindowHours": 24,
        "pairEvaluations": [
            {"commonEntityDocumentRatio": ratio, "pairs": pairs} for ratio in RATIOS
        ],
    }


def test_prepare_keeps_blind_content_and_deterministic_order(tmp_path: Path) -> None:
    pack = _prepare(tmp_path)
    annotations = [
        json.loads(line) for line in (pack / "annotation.jsonl").read_text().splitlines()
    ]
    assert sorted(annotations, key=lambda article: article["articleId"]) == _snapshot()["articles"]
    labels = _read_labels(pack)
    assert [int(row["articleId"]) for row in labels] == [row["articleId"] for row in annotations]
    assert all(row["expectedIssueId"] == row["split"] == row["rationale"] == "" for row in labels)
    other = tmp_path / "other"
    prepare(tmp_path / "input.json", other)
    assert (pack / "annotation.jsonl").read_bytes() == (other / "annotation.jsonl").read_bytes()
    with pytest.raises(FileExistsError):
        prepare(tmp_path / "input.json", pack)


@pytest.mark.parametrize("key", ["predictedIssueId", "expectedIssueId", "split", "unknown"])
def test_prepare_rejects_label_prediction_and_non_whitelist_article_fields(
    tmp_path: Path, key: str
) -> None:
    snapshot = _snapshot()
    snapshot["articles"][0][key] = "exposed"
    with pytest.raises(ValueError):
        _prepare(tmp_path, snapshot)
    assert not (tmp_path / "pack").exists()


def test_prepare_rejects_nested_prediction_metadata(tmp_path: Path) -> None:
    snapshot = _snapshot()
    snapshot["metadata"] = {"expectedLabels": []}
    with pytest.raises(ValueError, match="prohibited"):
        _prepare(tmp_path, snapshot)


@pytest.mark.parametrize("case", ["missing", "extra", "duplicate", "rationale", "split"])
def test_freeze_rejects_incomplete_or_invalid_label_rows(tmp_path: Path, case: str) -> None:
    pack = _prepare(tmp_path)
    labels = _label(pack)
    if case == "missing":
        labels.pop()
    elif case == "extra":
        labels.append({**labels[0], "articleId": "999"})
    elif case == "duplicate":
        labels.append(labels[0])
    elif case == "rationale":
        labels[0]["rationale"] = " "
    else:
        labels[0]["split"] = "TRAIN"
    _write_labels(pack, labels)
    with pytest.raises(ValueError):
        freeze(pack)
    assert not (pack / "golden.json").exists()


def test_freeze_rejects_same_issue_across_topics_and_splits(tmp_path: Path) -> None:
    pack = _prepare(tmp_path)
    labels = _label(pack)
    next(label for label in labels if label["articleId"] == "61")["expectedIssueId"] = "event-0"
    _write_labels(pack, labels)
    with pytest.raises(ValueError, match="including across topics"):
        freeze(pack)


def test_freeze_rejects_underpopulated_topic_split(tmp_path: Path) -> None:
    pack = _prepare(tmp_path)
    labels = _label(pack)
    for label in labels:
        if label["articleId"] in {"1", "2"}:
            label["split"] = "HOLDOUT"
    _write_labels(pack, labels)
    with pytest.raises(ValueError, match=">=20 articles each"):
        freeze(pack)


def test_freeze_rejects_changed_precommitted_grid(tmp_path: Path) -> None:
    pack = _prepare(tmp_path)
    _label(pack)
    manifest = json.loads((pack / "manifest.json").read_text())
    manifest["protocol"]["recallGate"] = 0.5
    _write_json(pack / "manifest.json", manifest)
    with pytest.raises(ValueError, match="Precommitted protocol"):
        freeze(pack)


@pytest.mark.parametrize("all_same", [False, True])
def test_freeze_rejects_truth_without_positive_or_negative_pairs(
    tmp_path: Path, all_same: bool
) -> None:
    pack = _prepare(tmp_path)
    labels = _label(pack)
    for label in labels:
        label["expectedIssueId"] = label["split"] if all_same else f"single-{label['articleId']}"
    _write_labels(pack, labels)
    with pytest.raises(ValueError, match="positive and negative"):
        freeze(pack)


@pytest.mark.parametrize("name", ["snapshot.json", "annotation.jsonl"])
def test_freeze_rejects_prepared_content_tampering(tmp_path: Path, name: str) -> None:
    pack = _prepare(tmp_path)
    _label(pack)
    with (pack / name).open("a", encoding="utf-8") as output:
        output.write(" ")
    with pytest.raises(ValueError, match="Prepared file changed"):
        freeze(pack)


@pytest.mark.parametrize(
    "name", ["snapshot.json", "annotation.jsonl", "manifest.json", "labels.csv", "golden.json"]
)
def test_evaluate_rejects_sealed_file_tampering(tmp_path: Path, name: str) -> None:
    pack = _prepare(tmp_path)
    _label(pack)
    freeze(pack)
    with (pack / name).open("a", encoding="utf-8") as output:
        output.write(" ")
    with pytest.raises(ValueError, match="Sealed file changed"):
        evaluate(pack, tmp_path / "not-even-read.json")
    assert not (pack / "report.json").exists()


@pytest.mark.parametrize(
    "case",
    ["hash", "title", "source", "label", "ratio", "split", "df", "extra", "missing", "grid"],
)
def test_evaluate_rejects_mismatched_java_input(tmp_path: Path, case: str) -> None:
    pack = _prepare(tmp_path)
    _label(pack)
    freeze(pack)
    java = _java_output(pack)
    if case == "hash":
        java["goldenSha256"] = "0" * 64
    elif case == "title":
        java["articles"][0]["title"] = "Changed input"
    elif case == "source":
        java["articles"][0]["sourceId"] = 999
    elif case == "label":
        java["articles"][0]["expectedIssueId"] = "relabeled"
    elif case == "ratio":
        java["pairEvaluations"][0]["commonEntityDocumentRatio"] = 0.08
    elif case == "split":
        java["pairEvaluations"][0]["pairs"][0]["rightArticleId"] = 21
    elif case == "df":
        java["documentFrequencyScope"] = "GLOBAL"
    elif case == "extra":
        java["articles"].append(java["articles"][0])
    elif case == "grid":
        del java["configuredOrganizationTitleJaccardThreshold"]
    else:
        java["articles"].pop()
    java_path = tmp_path / "java.json"
    _write_json(java_path, java)
    with pytest.raises(ValueError):
        evaluate(pack, java_path)
    assert not (pack / "report.json").exists()


def test_evaluate_reports_real_sweep_once_and_never_overwrites_freeze(tmp_path: Path) -> None:
    pack = _prepare(tmp_path)
    _label(pack)
    seal = freeze(pack)
    assert seal["coverage"]["HOLDOUT"]["positivePairs"] == 20
    assert seal["coverage"]["HOLDOUT"]["negativePairs"] == 360
    with pytest.raises(FileExistsError):
        freeze(pack)
    java_path = tmp_path / "java.json"
    _write_json(java_path, _java_output(pack))
    assert main(["evaluate", "--pack", str(pack), "--java-pairs", str(java_path)]) == 0
    result = json.loads((pack / "report.json").read_text())
    assert result["holdout"]["precision"] == result["holdout"]["recall"] == 1.0
    assert len(result["candidates"]) == 4 * 8 * 3 * 4 * 3
    with pytest.raises(FileExistsError):
        evaluate(pack, java_path)
