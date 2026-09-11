import csv
import json
from pathlib import Path

import pytest

from app.eval import cluster_independent
from app.eval.cluster_independent import (
    LABEL_FIELDS,
    RATIOS,
    evaluate,
    freeze,
    main,
    prepare,
    select,
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
    articles = [{**article, "titleOrganizations": []} for article in golden["articles"]]
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
        "clusteringRuleVersion": "title-organization-conflict-v1",
        "goldenSha256": (pack / "golden.sha256").read_text().strip(),
        "articleCount": len(articles),
        "documentFrequencyScope": "SPLIT",
        "runtimeSourcesSha256": cluster_independent._runtime_sources(),
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
    with pytest.raises(ValueError, match="Precommitted protocol.*recallGate"):
        freeze(pack)


@pytest.mark.parametrize("sealed", [False, True])
def test_changed_source_hash_reports_exact_keys_and_preserves_original_pack(
    tmp_path: Path, monkeypatch, sealed: bool
) -> None:
    pack = _prepare(tmp_path)
    _label(pack)
    if sealed:
        freeze(pack)
    original = {path.name: path.read_bytes() for path in pack.iterdir()}
    changed = {**cluster_independent._protocol(), "sweepSha256": "changed"}
    monkeypatch.setattr(cluster_independent, "_protocol", lambda: changed)
    with pytest.raises(ValueError, match="code changed: sweepSha256") as failure:
        if sealed:
            evaluate(pack, tmp_path / "not-even-read.json")
        else:
            freeze(pack)
    assert "original code revision" in str(failure.value)
    assert {path.name: path.read_bytes() for path in pack.iterdir()} == original


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
    [
        "hash", "title", "source", "label", "ratio", "split", "df", "extra", "missing", "grid",
        "missing-organizations", "string-organizations",
        "blank-organization", "invalid-organization", "representative", "content_group",
    ],
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
    elif case == "missing-organizations":
        del java["articles"][0]["titleOrganizations"]
    elif case == "string-organizations":
        java["articles"][0]["titleOrganizations"] = "Samsung"
    elif case == "blank-organization":
        java["articles"][0]["titleOrganizations"] = [" "]
    elif case == "invalid-organization":
        java["articles"][0]["titleOrganizations"] = [1]
    elif case == "representative":
        java["articles"][0]["fixedContentGroupId"] = "CALIBRATION:content-0"
        java["articles"][0]["fixedContentGroupRepresentativeId"] = 21
    elif case == "content_group":
        for index in (0, 20):
            java["articles"][index]["fixedContentGroupId"] = "leaked-group"
            java["articles"][index]["fixedContentGroupRepresentativeId"] = index + 1
    else:
        java["articles"].pop()
    java_path = tmp_path / "java.json"
    _write_json(java_path, java)
    with pytest.raises(ValueError):
        evaluate(pack, java_path)
    assert not (pack / "report.json").exists()


@pytest.mark.parametrize("field,value", [
    ("titleJaccard", 2.0), ("titleJaccard", -0.1), ("titleJaccard", True),
    ("titleJaccard", float("nan")), ("titleJaccard", float("inf")),
    ("hoursApart", -1), ("hoursApart", True), ("hoursApart", float("inf")),
    ("entityOverlap", 1.5), ("entityOverlap", 2.0), ("entityOverlap", True),
    ("organizationOverlap", 1.5), ("organizationOverlap", -1),
    ("breakingPair", None), ("breakingPair", 0), ("breakingPair", "false"),
])
def test_evaluate_rejects_invalid_pair_features_before_consuming_holdout(
    tmp_path: Path, field: str, value
) -> None:
    pack = _prepare(tmp_path)
    _label(pack)
    freeze(pack)
    java = _java_output(pack)
    pair = java["pairEvaluations"][0]["pairs"][0]
    if value is None:
        pair.pop(field)
    else:
        pair[field] = value
    java_path = tmp_path / "java.json"
    _write_json(java_path, java)
    with pytest.raises(ValueError, match=f"invalid {field}"):
        evaluate(pack, java_path)
    assert not (pack / "report.json").exists()


@pytest.mark.parametrize("jaccard,breaking", [(0, False), (1.0, True)])
def test_java_pair_feature_boundaries_remain_valid(tmp_path: Path, jaccard, breaking):
    pack = _prepare(tmp_path)
    _label(pack)
    freeze(pack)
    java = _java_output(pack)
    java["pairEvaluations"][0]["pairs"][0].update(
        titleJaccard=jaccard, breakingPair=breaking, hoursApart=0.5,
        entityOverlap=0, organizationOverlap=0,
    )
    golden = json.loads((pack / "golden.json").read_text())
    cluster_independent._verify_java(golden, java, java["goldenSha256"])


@pytest.mark.parametrize("case", ["missing", "asymmetric", "cross-split", "proxy-missing"])
def test_v4_conflict_metadata_is_rejected_before_consuming_holdout(tmp_path: Path, case: str):
    pack = _prepare(tmp_path)
    _label(pack)
    freeze(pack)
    java = _java_output(pack)
    _add_v4_metadata(java)
    if case == "missing":
        del java["articles"][0]["eventConflictingArticleIds"]
    elif case == "asymmetric":
        java["articles"][0]["eventConflictingArticleIds"] = [2]
    elif case == "cross-split":
        java["articles"][0]["eventConflictingArticleIds"] = [21]
        java["articles"][20]["eventConflictingArticleIds"] = [1]
    else:
        # Article 41 is the global representative in another topic. Its source
        # row must carry conflicts even when it appears only as a proxy locally.
        for index in (0, 40):
            java["articles"][index].update(
                fixedContentGroupId="CALIBRATION:proxy",
                fixedContentGroupRepresentativeId=41,
            )
        del java["articles"][40]["eventConflictingArticleIds"]
    java_path = tmp_path / "java.json"
    _write_json(java_path, java)

    with pytest.raises(ValueError, match="eventConflictingArticleIds"):
        evaluate(pack, java_path)

    assert not (pack / "report.json").exists()


def test_v4_verification_accepts_symmetric_conflicts_with_other_topic_source(tmp_path: Path):
    pack = _prepare(tmp_path)
    _label(pack)
    freeze(pack)
    java = _java_output(pack)
    _add_v4_metadata(java)
    java["articles"][0]["eventConflictingArticleIds"] = [41]
    java["articles"][40]["eventConflictingArticleIds"] = [1]
    golden = json.loads((pack / "golden.json").read_text())

    cluster_independent._verify_java(golden, java, java["goldenSha256"])


def _add_v4_metadata(java: dict) -> None:
    java["clusteringRuleVersion"] = "event-text-evidence-v4"
    for article in java["articles"]:
        article["eventConflictingArticleIds"] = []
    for evaluation in java["pairEvaluations"]:
        for pair in evaluation["pairs"]:
            pair.update(
                eventTextMatch=True,
                specificEventMatch=False,
                entityTitleSupported=True,
                organizationTitleSupported=False,
                titleTextSimilarity=1.0,
                leadTextSimilarity=0.0,
            )


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
    assert main(["select", "--pack", str(pack), "--java-pairs", str(java_path)]) == 0
    assert main(["evaluate", "--pack", str(pack), "--java-pairs", str(java_path)]) == 1
    result = json.loads((pack / "report.json").read_text())
    assert result["clusteringRuleVersion"] == "title-organization-conflict-v1"
    assert result["holdout"]["precision"] == result["holdout"]["recall"] == 1.0
    assert result["metricGatePassed"] is True
    assert result["sampleAdequacy"]["passed"] is False
    assert result["independentAcceptance"] is False
    assert len(result["candidates"]) == 4 * 8 * 3 * 4 * 3
    with pytest.raises(FileExistsError):
        evaluate(pack, java_path)


def test_evaluate_requires_separate_calibration_freeze(tmp_path: Path) -> None:
    pack = _prepare(tmp_path)
    _label(pack)
    freeze(pack)
    java_path = tmp_path / "java.json"
    _write_json(java_path, _java_output(pack))
    with pytest.raises(ValueError, match="select command"):
        evaluate(pack, java_path)
    assert not (pack / "report.json").exists()
    select(pack, java_path)
    with pytest.raises(FileExistsError, match="already frozen"):
        select(pack, java_path)


@pytest.mark.parametrize("case", ["selection", "java", "runtime"])
def test_changed_frozen_selection_is_rejected_before_holdout(tmp_path: Path, case: str) -> None:
    pack = _prepare(tmp_path)
    _label(pack)
    freeze(pack)
    java_path = tmp_path / "java.json"
    java = _java_output(pack)
    _write_json(java_path, java)
    select(pack, java_path)
    if case == "selection":
        record = json.loads((pack / "selection.json").read_text())
        record["selection"]["selected"]["organization_time_window_hours"] = 99
        _write_json(pack / "selection.json", record)
    else:
        if case == "java":
            java["pairEvaluations"][0]["pairs"][-1]["hoursApart"] = 24
        else:
            java["runtimeSourcesSha256"] = {}
        _write_json(java_path, java)
    with pytest.raises(ValueError, match="selection|runtimeSourcesSha256"):
        evaluate(pack, java_path)
    assert not (pack / "report.json").exists()


def test_many_positive_pairs_from_one_event_do_not_prove_coverage(tmp_path: Path) -> None:
    pack = _prepare(tmp_path)
    labels = _label(pack)
    for label in labels:
        label["expectedIssueId"] = f"one-large-event-{label['split']}"
    # Preserve negative examples so the structurally valid corpus can be sealed.
    for label in labels:
        if int(label["articleId"]) % 20 == 0:
            label["expectedIssueId"] = f"singleton-{label['articleId']}"
    _write_labels(pack, labels)
    seal = freeze(pack)
    adequacy = cluster_independent._sample_adequacy(seal["coverage"])
    assert adequacy["passed"] is False
    for value in adequacy["splits"].values():
        assert value["positivePairs"] > 300
        assert value["multiArticleEvents"] == 1
        assert "INSUFFICIENT_DISTINCT_MULTI_ARTICLE_EVENTS" in value["reasonCodes"]


def test_changed_java_sources_invalidate_prepared_protocol(tmp_path: Path, monkeypatch) -> None:
    pack = _prepare(tmp_path)
    _label(pack)
    sources = cluster_independent._runtime_sources()
    changed = {**sources, next(iter(sources)): "0" * 64}
    monkeypatch.setattr(cluster_independent, "_runtime_sources", lambda: changed)
    with pytest.raises(ValueError, match="runtimeSourcesSha256"):
        freeze(pack)
    assert not (pack / "golden.json").exists()


def test_snapshot_repeated_source_topic_cannot_inflate_sample(tmp_path: Path) -> None:
    snapshot = _snapshot()
    snapshot["articles"][1]["sourceArticleId"] = snapshot["articles"][0]["sourceArticleId"]
    with pytest.raises(ValueError, match="repeats a source article"):
        _prepare(tmp_path, snapshot)


def test_failed_holdout_computation_still_consumes_single_attempt(tmp_path: Path, monkeypatch):
    pack = _prepare(tmp_path)
    _label(pack)
    freeze(pack)
    java_path = tmp_path / "java.json"
    _write_json(java_path, _java_output(pack))
    select(pack, java_path)

    def fail(*args, **kwargs):
        raise RuntimeError("Injected evaluation failure")

    monkeypatch.setattr(cluster_independent.cluster_sweep, "sweep", fail)
    with pytest.raises(RuntimeError, match="Injected"):
        evaluate(pack, java_path)
    assert json.loads((pack / "report.json").read_text()) == {
        "status": "FAILED", "holdoutConsumed": True,
    }
    with pytest.raises(FileExistsError, match="already been evaluated"):
        evaluate(pack, java_path)


def test_seal_coverage_cannot_be_edited_to_pass_quality(tmp_path: Path) -> None:
    pack = _prepare(tmp_path)
    _label(pack)
    seal = freeze(pack)
    for split in ("CALIBRATION", "HOLDOUT"):
        seal["coverage"][split]["positivePairs"] = 30
        seal["coverage"][split]["uniqueSourcePositivePairs"] = 30
    _write_json(pack / "seal.json", seal)
    with pytest.raises(ValueError, match="Sealed coverage differs"):
        select(pack, tmp_path / "unread-java.json")
    assert not (pack / "selection.json").exists()
    assert not (pack / "report.json").exists()


def _repeated_source_topics(tmp_path: Path) -> Path:
    snapshot = _snapshot()
    originals = snapshot["articles"][:40]
    snapshot["articles"] = [
        {**article, "articleId": index + (topic - 1) * 40 + 1, "topicId": topic}
        for topic in (1, 2, 3) for index, article in enumerate(originals)
    ]
    return _prepare(tmp_path, snapshot)


def test_same_source_pairs_in_three_topics_do_not_inflate_coverage(tmp_path: Path) -> None:
    pack = _repeated_source_topics(tmp_path)
    labels = _label(pack)
    for label in labels:
        index = (int(label["articleId"]) - 1) % 40
        label["expectedIssueId"] = f"event-{index // 2}"
    _write_labels(pack, labels)
    seal = freeze(pack)
    for values in seal["coverage"].values():
        assert values["positivePairs"] == 30
        assert values["uniqueSourcePositivePairs"] == 10
        assert values["multiArticleEvents"] == 10
    assert cluster_independent._sample_adequacy(seal["coverage"])["passed"] is False


def test_same_source_requires_same_event_across_topics(tmp_path: Path) -> None:
    pack = _repeated_source_topics(tmp_path)
    _label(pack)
    with pytest.raises(ValueError, match="one expectedIssueId across topics"):
        freeze(pack)


def test_same_source_requires_consistent_content_across_topics(tmp_path: Path) -> None:
    snapshot = _snapshot()
    snapshot["articles"][40]["sourceArticleId"] = snapshot["articles"][0]["sourceArticleId"]
    with pytest.raises(ValueError, match="same source content"):
        _prepare(tmp_path, snapshot)
