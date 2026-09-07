from copy import deepcopy

import pytest

from app.eval.cluster_assisted_review import audit_partial_human_review, validate_assisted_review
from app.eval.cluster_review import snapshot_digest


def inputs():
    snapshot = {
        "datasetVersion": "synthetic",
        "articles": [
            {"articleId": 1, "sourceArticleId": 10, "topicId": 7},
            {"articleId": 2, "sourceArticleId": 11, "topicId": 7},
        ],
    }
    human = {
        "schemaVersion": 1,
        "datasetVersion": "synthetic",
        "purpose": "human-regression-truth-review",
        "articles": [
            {
                **row,
                "decision": "CONFIRMED" if row["articleId"] == 1 else "",
                "expectedIssueId": "E1" if row["articleId"] == 1 else "",
                "rationale": "",
            }
            for row in snapshot["articles"]
        ],
    }
    assisted = {
        "schemaVersion": 1,
        "datasetVersion": "synthetic",
        "purpose": "ai-assisted-regression-review",
        "snapshotSha256": snapshot_digest(snapshot),
        "humanReviewDigest": snapshot_digest(human),
        "articles": [
            {
                **row,
                "reviewerType": "AI",
                "decision": "CONFIRMED",
                "expectedIssueId": "event",
                "rationale": "Both cover the same dated announcement",
            }
            for row in snapshot["articles"]
        ],
    }
    return snapshot, human, assisted


def test_partial_human_notes_are_preserved_without_fabricating_completion():
    snapshot, human, assisted = inputs()
    before = deepcopy((snapshot, human, assisted))
    result = validate_assisted_review(snapshot, human, assisted)
    assert result["human"]["counts"]["CONFIRMED"] == 1
    assert result["human"]["counts"]["UNREVIEWED"] == 1
    assert result["human"]["identityBinding"] == "legacy-article-source-topic-map"
    assert result["aiReviewedArticleCount"] == 2
    assert result["assistedReviewCoverageComplete"]
    assert not result["humanReviewComplete"]
    assert not result["independentValidationComplete"]
    assert (snapshot, human, assisted) == before


@pytest.mark.parametrize(
    "change",
    [
        "human-digest",
        "snapshot-digest",
        "missing",
        "duplicate",
        "source",
        "actor",
        "decision",
        "rationale",
        "event",
    ],
)
def test_rejects_assisted_review_provenance_and_coverage_errors(change):
    snapshot, human, assisted = inputs()
    if change == "human-digest":
        human["articles"][0]["rationale"] = "changed"
    elif change == "snapshot-digest":
        snapshot["articles"][0]["title"] = "changed"
    elif change == "missing":
        assisted["articles"].pop()
    elif change == "duplicate":
        assisted["articles"][1] = dict(assisted["articles"][0])
    else:
        field = {"source": "sourceArticleId", "actor": "reviewerType"}.get(change, change)
        assisted["articles"][0][field if field != "event" else "expectedIssueId"] = ""
    with pytest.raises(ValueError):
        validate_assisted_review(snapshot, human, assisted)


def test_present_but_wrong_human_snapshot_digest_is_never_ignored():
    snapshot, human, _ = inputs()
    human["snapshotSha256"] = "wrong"
    with pytest.raises(ValueError, match="digest"):
        audit_partial_human_review(snapshot, human)


def test_provisional_ai_labels_remain_visible_and_shared_sources_must_agree():
    snapshot, human, assisted = inputs()
    for data in (snapshot, human, assisted):
        data["articles"][1]["sourceArticleId"] = 10
    assisted["snapshotSha256"] = snapshot_digest(snapshot)
    assisted["humanReviewDigest"] = snapshot_digest(human)
    assisted["articles"][1]["decision"] = "PROVISIONAL"
    result = validate_assisted_review(snapshot, human, assisted)
    assert result["aiDecisionCounts"]["PROVISIONAL"] == 1
    assisted["articles"][1]["expectedIssueId"] = "another-event"
    with pytest.raises(ValueError, match="conflicting"):
        validate_assisted_review(snapshot, human, assisted)
