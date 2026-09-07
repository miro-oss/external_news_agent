"""Audit partial human feedback and a separately attributed AI regression review."""

import argparse
import json
import re
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

from app.eval.cluster_review import snapshot_digest


def _check_identity(snapshot: dict[str, Any], review: dict[str, Any]) -> list[dict[str, Any]]:
    if type(review.get("schemaVersion")) is not int or review["schemaVersion"] != 1:
        raise ValueError("Unsupported review schema")
    if review.get("datasetVersion") != snapshot.get("datasetVersion"):
        raise ValueError("Review dataset differs")
    expected = {row["articleId"]: row for row in snapshot["articles"]}
    rows = review.get("articles")
    if not expected or len(expected) != len(snapshot["articles"]) or not isinstance(rows, list):
        raise ValueError("Invalid snapshot or review rows")
    seen = set()
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError("Invalid review row")
        article_id = row.get("articleId")
        if type(article_id) is not int or article_id not in expected or article_id in seen:
            raise ValueError("Unknown or duplicate review article")
        seen.add(article_id)
        if any(
            type(row.get(key)) is not int or row[key] != expected[article_id][key]
            for key in ("sourceArticleId", "topicId")
        ):
            raise ValueError("Review source/topic differs")
    if seen != set(expected):
        raise ValueError("Review must retain all rows, including unreviewed articles")
    return rows


def audit_partial_human_review(snapshot: dict[str, Any], human: dict[str, Any]) -> dict[str, Any]:
    rows = _check_identity(snapshot, human)
    if human.get("purpose") != "human-regression-truth-review":
        raise ValueError("Not a human regression review")
    digest = human.get("snapshotSha256")
    if digest is not None and digest != snapshot_digest(snapshot):
        raise ValueError("Human review snapshot digest differs")
    counts: Counter[str] = Counter()
    for row in rows:
        if row.get("decision") not in ("", "CONFIRMED", "UNCERTAIN") or any(
            not isinstance(row.get(key), str) for key in ("expectedIssueId", "rationale")
        ):
            raise ValueError("Invalid human annotation fields")
        decision = row["decision"]
        counts[decision or "UNREVIEWED"] += 1
        counts["touched"] += bool(decision or row["expectedIssueId"] or row["rationale"])
        counts["eventAssignments"] += bool(row["expectedIssueId"])
        counts["notes"] += bool(row["rationale"].strip())
    return {
        "articleCount": len(rows),
        "counts": dict(counts),
        "identityBinding": "snapshot-digest" if digest else "legacy-article-source-topic-map",
        "humanReviewComplete": False,
        "independentValidationComplete": False,
    }


def validate_assisted_review(
    snapshot: dict[str, Any], human: dict[str, Any], assisted: dict[str, Any]
) -> dict[str, Any]:
    human_audit = audit_partial_human_review(snapshot, human)
    rows = _check_identity(snapshot, assisted)
    if (
        assisted.get("purpose") != "ai-assisted-regression-review"
        or assisted.get("snapshotSha256") != snapshot_digest(snapshot)
        or assisted.get("humanReviewDigest") != snapshot_digest(human)
    ):
        raise ValueError("Assisted review purpose or input digests differ")
    source_events: dict[int, set[str]] = defaultdict(set)
    counts: Counter[str] = Counter()
    for row in rows:
        if row.get("reviewerType") != "AI":
            raise ValueError("AI review must not be attributed to a human")
        event = row.get("expectedIssueId")
        if not isinstance(event, str) or re.fullmatch(r"[\w.:-]{1,200}", event) is None:
            raise ValueError("Invalid assisted event ID")
        if row.get("decision") not in ("CONFIRMED", "PROVISIONAL"):
            raise ValueError("Every article needs an explicit AI decision")
        if not isinstance(row.get("rationale"), str) or not row["rationale"].strip():
            raise ValueError("Every AI decision needs a rationale")
        counts[row["decision"]] += 1
        source_events[row["sourceArticleId"]].add(event)
    if any(len(events) != 1 for events in source_events.values()):
        raise ValueError("Same source article has conflicting AI events")
    return {
        "human": human_audit,
        "aiReviewedArticleCount": len(rows),
        "aiDecisionCounts": dict(counts),
        "eventCount": len({row["expectedIssueId"] for row in rows}),
        "assistedReviewCoverageComplete": True,
        "humanReviewComplete": False,
        "independentValidationComplete": False,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot", required=True, type=Path)
    parser.add_argument("--human", required=True, type=Path)
    parser.add_argument("--assisted", type=Path)
    args = parser.parse_args()
    snapshot = json.loads(args.snapshot.read_text(encoding="utf-8"))
    human = json.loads(args.human.read_text(encoding="utf-8"))
    result = (
        validate_assisted_review(
            snapshot, human, json.loads(args.assisted.read_text(encoding="utf-8"))
        )
        if args.assisted
        else audit_partial_human_review(snapshot, human)
    )
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
