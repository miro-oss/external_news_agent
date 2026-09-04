"""Decode lossless SQL*Plus chunks and select a prediction-blind article sample."""

import argparse
import hashlib
import json
from collections import defaultdict
from pathlib import Path


def decode_chunks(path: Path) -> list[dict]:
    rows: dict[str, dict[int, str]] = defaultdict(dict)
    for line in path.read_text(encoding="utf-8").splitlines():
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
    rows = decode_chunks(args.chunks)
    by_topic: dict[int, list[dict]] = defaultdict(list)
    for row in rows:
        by_topic[row["topicId"]].append(row)
    selected = []
    for topic_id, candidates in sorted(by_topic.items()):
        if len(candidates) < args.per_topic:
            raise ValueError(f"Topic {topic_id} has only {len(candidates)} candidates")
        candidates.sort(key=lambda row: hashlib.sha256(
            f"{args.seed}:{topic_id}:{row['sourceArticleId']}".encode()
        ).hexdigest())
        selected.extend(candidates[:args.per_topic])
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
        articles.append(row)
    snapshot = {
        "datasetVersion": "clusters.independent.162.v1",
        "description": "Current stored text, independently sampled before labeling/prediction",
        "sourceRuns": sorted({run for row in articles for run in row["sourceRunIds"]}),
        "commonEntityDocumentRatioCandidates": [0.05, 0.10, 0.15, 0.20],
        "provenance": {
            "observationWindowEnd": args.window_end,
            "lookbackHours": args.lookback_hours,
            "firstSeenSince": args.first_seen_since,
            "textSnapshotTimes": sorted({row["snapshotAt"] for row in rows}),
            "textSemantics": "current-snapshot; not historical per-run replay",
            "candidateCounts": {str(key): len(value) for key, value in by_topic.items()},
            "samplePerTopic": args.per_topic,
            "sampling": (
                "lowest SHA-256(seed:topicId:sourceArticleId), without replacement per topic"
            ),
            "samplingSeed": args.seed,
            "articleIdMapping": (
                "sourceArticleId * (maxTopicId + 1) + topicId; preserves source-ID order"
            ),
            "sqlChunksSha256": hashlib.sha256(args.chunks.read_bytes()).hexdigest(),
        },
        "articles": articles,
    }
    with args.output.open("x", encoding="utf-8") as output:
        json.dump(snapshot, output, ensure_ascii=False, indent=2)
        output.write("\n")
    print(json.dumps({"articles": len(articles), "sourceRuns": snapshot["sourceRuns"],
                      "candidateCounts": snapshot["provenance"]["candidateCounts"]}))


if __name__ == "__main__":
    main()
