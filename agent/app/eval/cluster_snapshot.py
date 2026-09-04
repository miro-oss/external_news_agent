"""Decode lossless SQL*Plus chunks and select a prediction-blind article sample."""

import argparse
import hashlib
import json
from collections import defaultdict
from pathlib import Path

from app.eval.cluster_independent import ARTICLE_FIELDS


def decode_chunks(path: Path) -> list[dict]:
    rows: dict[str, dict[int, str]] = defaultdict(dict)
    # JSON strings can legally contain NEL and Unicode line/paragraph separators.
    # Only SQL*Plus record separators delimit chunks, not str.splitlines()'s set.
    for raw_line in path.read_text(encoding="utf-8").split("\n"):
        line = raw_line.removesuffix("\r")
        if not line.strip():
            continue
        key, number, fragment = line.split("|", 2)
        if not fragment.endswith("|END"):
            raise ValueError(f"Missing SQL chunk sentinel: {key}/{number}")
        fragment = fragment[:-4]
        index = int(number)
        if index in rows[key]:
            raise ValueError(f"Duplicate SQL chunk: {key}/{index}")
        rows[key][index] = fragment
    result = []
    for key, chunks in sorted(rows.items()):
        if sorted(chunks) != list(range(1, len(chunks) + 1)):
            raise ValueError(f"Missing SQL chunk: {key}")
        row = json.loads("".join(chunks[index] for index in sorted(chunks)))
        if key != f"{row['topicId']}:{row['sourceArticleId']}":
            raise ValueError(f"SQL row identity mismatch: {key}")
        result.append(row)
    if not result:
        raise ValueError("No article rows")
    return result


def build_snapshot(
    rows: list[dict], *, per_topic: int, seed: str, window_end: str,
    lookback_hours: int, first_seen_since: str, sql_chunks_sha256: str,
) -> dict:
    if per_topic < 40 or lookback_hours not in (24, 48):
        raise ValueError("Use >=40 articles/topic and a 24h or 48h observation window")
    if not rows:
        raise ValueError("No article rows")
    by_topic: dict[int, list[dict]] = defaultdict(list)
    identities = set()
    for row in rows:
        for field in ("topicId", "sourceArticleId"):
            value = row[field]
            if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
                raise ValueError(f"{field} must be a positive integer")
        identity = (row["topicId"], row["sourceArticleId"])
        if identity in identities:
            raise ValueError(f"Duplicate source article within topic: {identity}")
        identities.add(identity)
        by_topic[row["topicId"]].append(row)
    selected = []
    for topic_id, candidates in sorted(by_topic.items()):
        if len(candidates) < per_topic:
            raise ValueError(f"Topic {topic_id} has only {len(candidates)} candidates")
        candidates.sort(key=lambda row: hashlib.sha256(
            f"{seed}:{topic_id}:{row['sourceArticleId']}".encode()
        ).hexdigest())
        selected.extend(candidates[:per_topic])
    articles = []
    id_stride = max(by_topic) + 1
    for raw in selected:
        row = dict(raw)
        # Keep source-ID ordering for the Java representative's final tie-break,
        # while identifying repeated source articles independently in each topic.
        row["articleId"] = row["sourceArticleId"] * id_stride + row["topicId"]
        if not 0 < row["articleId"] <= 2**63 - 1:
            raise ValueError("Virtual article ID is outside Java long range")
        row.pop("snapshotAt")
        # Selected topic queries contain whitespace-separated Korean keywords.
        # Retain the exact required/optional keyword lists before query tokens.
        row["topicKeywords"] = list(dict.fromkeys(
            (row.pop("topicRequiredKeywords") or [])
            + (row.pop("topicOptionalKeywords") or [])
            + (row.pop("topicQueryText") or "").split()
        ))
        if set(row) != ARTICLE_FIELDS:
            raise ValueError("Snapshot mapping must exactly produce the blind article whitelist")
        articles.append(row)
    return {
        "datasetVersion": "clusters.independent.162.v1",
        "description": "Current stored text, independently sampled before labeling/prediction",
        "sourceRuns": sorted({run for row in articles for run in row["sourceRunIds"]}),
        "commonEntityDocumentRatioCandidates": [0.05, 0.10, 0.15, 0.20],
        "provenance": {
            "observationWindowEnd": window_end,
            "lookbackHours": lookback_hours,
            "firstSeenSince": first_seen_since,
            "textSnapshotTimes": sorted({row["snapshotAt"] for row in rows}),
            "textSemantics": "current-snapshot; not historical per-run replay",
            "candidateCounts": {str(key): len(value) for key, value in by_topic.items()},
            "samplePerTopic": per_topic,
            "sampling": (
                "lowest SHA-256(seed:topicId:sourceArticleId), without replacement per topic"
            ),
            "samplingSeed": seed,
            "articleIdMapping": (
                "sourceArticleId * (maxTopicId + 1) + topicId; preserves source-ID order"
            ),
            "sqlChunksSha256": sql_chunks_sha256,
        },
        "articles": articles,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--chunks", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--per-topic", type=int, default=80)
    parser.add_argument("--seed", default="independent-162-v1")
    parser.add_argument("--window-end", required=True)
    parser.add_argument("--lookback-hours", type=int, default=48)
    parser.add_argument("--first-seen-since", required=True)
    args = parser.parse_args()
    if args.per_topic < 40 or args.lookback_hours not in (24, 48):
        parser.error("Use >=40 articles/topic and a 24h or 48h observation window")
    snapshot = build_snapshot(
        decode_chunks(args.chunks), per_topic=args.per_topic, seed=args.seed,
        window_end=args.window_end, lookback_hours=args.lookback_hours,
        first_seen_since=args.first_seen_since,
        sql_chunks_sha256=hashlib.sha256(args.chunks.read_bytes()).hexdigest(),
    )
    with args.output.open("x", encoding="utf-8") as output:
        json.dump(snapshot, output, ensure_ascii=False, indent=2)
        output.write("\n")
    print(json.dumps({"articles": len(snapshot["articles"]), "sourceRuns": snapshot["sourceRuns"],
                      "candidateCounts": snapshot["provenance"]["candidateCounts"]}))


if __name__ == "__main__":
    main()
