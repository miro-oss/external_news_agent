from copy import deepcopy

import pytest

from app.eval.cluster_review import snapshot_digest, validate_review


def data():
    snapshot = {
        "datasetVersion": "human.synthetic.v1",
        "articles": [
            {"articleId": 1, "sourceArticleId": 10, "topicId": 7},
            {"articleId": 2, "sourceArticleId": 10, "topicId": 8},
        ],
    }
    review = {
        "schemaVersion": 1,
        "datasetVersion": snapshot["datasetVersion"],
        "snapshotSha256": snapshot_digest(snapshot),
        "purpose": "human-regression-truth-review",
        "articles": [
            {**row, "expectedIssueId": "E001", "decision": "CONFIRMED", "rationale": "Same event"}
            for row in snapshot["articles"]
        ],
    }
    return snapshot, review


def test_complete_human_review_does_not_claim_independent_validation():
    snapshot, review = data()
    before = deepcopy((snapshot, review))
    result = validate_review(snapshot, review)
    assert result["reviewComplete"]
    assert not result["independentValidationComplete"]
    assert result["eventCount"] == 1
    assert (snapshot, review) == before


@pytest.mark.parametrize(
    "field,value",
    [
        ("decision", "UNCERTAIN"),
        ("decision", ""),
        ("expectedIssueId", ""),
        ("rationale", " "),
        ("sourceArticleId", 99),
        ("topicId", 99),
        ("articleId", True),
    ],
)
def test_rejects_incomplete_or_substituted_labels(field, value):
    snapshot, review = data()
    review["articles"][0][field] = value
    with pytest.raises(ValueError):
        validate_review(snapshot, review)


def test_rejects_inconsistent_events_for_shared_source_article():
    snapshot, review = data()
    review["articles"][1]["expectedIssueId"] = "E002"
    with pytest.raises(ValueError, match="conflicting"):
        validate_review(snapshot, review)


@pytest.mark.parametrize(
    "change", ["duplicate", "missing", "dataset", "snapshot", "boolean-schema"]
)
def test_rejects_missing_duplicate_or_different_dataset(change):
    snapshot, review = data()
    if change == "duplicate":
        review["articles"][1] = dict(review["articles"][0])
    elif change == "missing":
        review["articles"].pop()
    elif change == "dataset":
        review["datasetVersion"] = "other"
    elif change == "snapshot":
        snapshot["articles"][0]["title"] = "Changed after review"
    else:
        review["schemaVersion"] = True
    with pytest.raises(ValueError):
        validate_review(snapshot, review)
