"""Validate a completed human review without modifying any sealed evaluation pack."""

import argparse
import hashlib
import json
import re
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any


def snapshot_digest(snapshot: dict[str, Any]) -> str:
    canonical = json.dumps(snapshot, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def validate_review(snapshot: dict[str, Any], review: dict[str, Any]) -> dict[str, Any]:
    if (
        type(review.get("schemaVersion")) is not int
        or review["schemaVersion"] != 1
        or review.get("datasetVersion") != snapshot.get("datasetVersion")
    ):
        raise ValueError("Review schema or dataset identity differs")
    if review.get("snapshotSha256") != snapshot_digest(snapshot):
        raise ValueError("Review snapshot digest differs")
    if review.get("purpose") != "human-regression-truth-review":
        raise ValueError("This review is for regression truth, not fresh independent validation")
    expected = {article["articleId"]: article for article in snapshot["articles"]}
    if len(expected) != len(snapshot["articles"]):
        raise ValueError("Snapshot has duplicate article IDs")
    seen = set()
    events = Counter()
    source_events: dict[int, set[str]] = defaultdict(set)
    unresolved = []
    for row in review.get("articles", []):
        article_id = row.get("articleId")
        if (
            isinstance(article_id, bool)
            or not isinstance(article_id, int)
            or article_id not in expected
        ):
            raise ValueError("Unknown review article ID")
        if article_id in seen:
            raise ValueError("Duplicate review article ID")
        seen.add(article_id)
        if any(
            type(row.get(key)) is not int or row[key] != expected[article_id][key]
            for key in ("sourceArticleId", "topicId")
        ):
            raise ValueError("Review source/topic differs from the snapshot")
        event = row.get("expectedIssueId")
        rationale = row.get("rationale")
        if (
            row.get("decision") != "CONFIRMED"
            or not isinstance(event, str)
            or re.fullmatch(r"[\w.:-]{1,200}", event) is None
            or not isinstance(rationale, str)
            or not rationale.strip()
        ):
            unresolved.append(article_id)
            continue
        events[event] += 1
        source_events[row["sourceArticleId"]].add(event)
    if seen != set(expected):
        raise ValueError("Review must cover every snapshot article exactly once")
    if unresolved:
        raise ValueError(
            f"Unresolved human labels: {len(unresolved)} articles; no labels are inferred"
        )
    if any(len(values) != 1 for values in source_events.values()):
        raise ValueError("The same source article has conflicting human events across topics")
    return {
        "datasetVersion": snapshot["datasetVersion"],
        "snapshotSha256": review["snapshotSha256"],
        "articleCount": len(seen),
        "eventCount": len(events),
        "reviewComplete": True,
        "independentValidationComplete": False,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot", type=Path, required=True)
    parser.add_argument("--review", type=Path, required=True)
    args = parser.parse_args()
    print(
        json.dumps(
            validate_review(
                json.loads(args.snapshot.read_text(encoding="utf-8")),
                json.loads(args.review.read_text(encoding="utf-8")),
            ),
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
