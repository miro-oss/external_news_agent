"""Check a preregistered cluster sample using article/run IDs only, without predictions."""

import argparse
import hashlib
import json
import os
import re
import subprocess
import tempfile
from copy import deepcopy
from datetime import datetime, timedelta
from pathlib import Path, PurePosixPath
from zoneinfo import ZoneInfo

SEOUL = ZoneInfo("Asia/Seoul")
CLUSTER_DIRECTORY = PurePosixPath(
    "BE/src/main/java/com/example/be/domain/collection/cluster"
)
ARTICLE_ROW_FIELDS = {"kind", "topicId", "articleId", "runId"}
NORMAL_STATUSES = {
    "READY_FOR_SNAPSHOT", "WAITING_FOR_FRESH_ARTICLES", "WAITING_FOR_SOURCE_RUNS",
}


def _positive_integer(value: object, field: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
        raise ValueError(f"{field} must be a positive integer")
    return value


def _aware_datetime(value: object, field: str) -> datetime:
    if isinstance(value, str):
        try:
            value = datetime.fromisoformat(value)
        except ValueError as error:
            raise ValueError(f"{field} must be an aware ISO datetime") from error
    if not isinstance(value, datetime) or value.utcoffset() is None:
        raise ValueError(f"{field} must be an aware ISO datetime")
    return value.astimezone(SEOUL)


def _integer_list(value: object, field: str, *, minimum: int = 0) -> list[int]:
    if not isinstance(value, list) or len(value) < minimum:
        raise ValueError(f"{field} must be a list with at least {minimum} entries")
    result = [_positive_integer(item, field) for item in value]
    if len(set(result)) != len(result):
        raise ValueError(f"{field} contains duplicate IDs")
    return sorted(result)


def _allowed_frozen_path(value: object) -> PurePosixPath:
    if not isinstance(value, str) or not value or "\\" in value:
        raise ValueError("Frozen file paths must name repository cluster Java files")
    path = PurePosixPath(value)
    if (path.is_absolute() or ".." in path.parts or path.suffix != ".java"
            or not path.is_relative_to(CLUSTER_DIRECTORY)):
        raise ValueError("Frozen file paths must stay inside the repository cluster Java directory")
    return path


def validate_preregistration(data: dict) -> dict:
    """Return a normalized copy; never rewrite the preregistration or its frozen hashes."""
    if not isinstance(data, dict):
        raise ValueError("Preregistration must be a JSON object")
    required = {
        "topicIds", "samplePerTopic", "samplingSeed", "firstRunStartedAfter",
        "observationLookbackHours", "minimumSourceRuns", "excludeKnownSourceArticleIds",
        "runtimeFilesSha256",
    }
    if required - data.keys():
        missing = ", ".join(sorted(required - data.keys()))
        raise ValueError("Missing preregistration fields: " + missing)
    normalized = deepcopy(data)
    normalized["topicIds"] = _integer_list(data["topicIds"], "topicIds", minimum=2)
    normalized["excludeKnownSourceArticleIds"] = _integer_list(
        data["excludeKnownSourceArticleIds"], "excludeKnownSourceArticleIds"
    )
    for field in ("samplePerTopic", "observationLookbackHours", "minimumSourceRuns"):
        normalized[field] = _positive_integer(data[field], field)
    if not isinstance(data["samplingSeed"], str) or not data["samplingSeed"].strip():
        raise ValueError("samplingSeed must be a nonblank string")
    normalized["firstRunStartedAfter"] = _aware_datetime(
        data["firstRunStartedAfter"], "firstRunStartedAfter"
    ).isoformat()
    trigger = data.get("observationTriggerType", "SCHEDULED")
    if trigger != "SCHEDULED":
        raise ValueError("observationTriggerType must be SCHEDULED")
    normalized["observationTriggerType"] = trigger
    hashes = data["runtimeFilesSha256"]
    if not isinstance(hashes, dict) or not hashes:
        raise ValueError("runtimeFilesSha256 must be a nonempty object")
    normalized_hashes = {}
    for name, digest in hashes.items():
        path = str(_allowed_frozen_path(name))
        if path in normalized_hashes:
            raise ValueError("runtimeFilesSha256 contains equivalent duplicate paths")
        if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", digest):
            raise ValueError("Each frozen file digest must be a SHA-256 hex string")
        normalized_hashes[path] = digest.lower()
    normalized["runtimeFilesSha256"] = dict(sorted(normalized_hashes.items()))
    return normalized


def load_preregistration(path: Path) -> dict:
    try:
        data = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ValueError("Could not read a valid preregistration JSON file") from error
    return validate_preregistration(data)


def verify_frozen_files(repo_root: Path, pr: dict) -> list[dict]:
    """Return explicit missing/mismatch records, including when Python runs with -O."""
    root = Path(repo_root).resolve()
    allowed = root.joinpath(*CLUSTER_DIRECTORY.parts)
    mismatches = []
    for name, expected in sorted(pr["runtimeFilesSha256"].items()):
        detail = {"path": name, "expectedSha256": expected, "actualSha256": None}
        try:
            relative = _allowed_frozen_path(name)
            path = root.joinpath(*relative.parts)
            # Reject symlink files and parents before reading any target, including secrets.
            if any(item.is_symlink() for item in (path, *path.parents)
                   if item.is_relative_to(root)):
                mismatches.append({**detail, "reason": "UNSAFE_PATH"})
                continue
            path = path.resolve()
            if not path.is_relative_to(allowed) or path.suffix != ".java":
                mismatches.append({**detail, "reason": "UNSAFE_PATH"})
                continue
            if not path.is_file():
                mismatches.append({**detail, "reason": "MISSING_FILE"})
                continue
            actual = hashlib.sha256(path.read_bytes()).hexdigest()
            if actual != expected:
                mismatches.append({**detail, "actualSha256": actual, "reason": "HASH_MISMATCH"})
        except (ValueError, RuntimeError):
            mismatches.append({**detail, "reason": "UNSAFE_PATH"})
        except OSError:
            mismatches.append({**detail, "reason": "READ_FAILED"})
    return mismatches


def _timestamp_literal(value: datetime) -> str:
    return "timestamp '" + value.strftime("%Y-%m-%d %H:%M:%S.%f") + "'"


def build_sql(pr: dict, now: datetime) -> str:
    """Build one read-only query. The first-seen run supplies freshness, not trigger type."""
    pr = validate_preregistration(pr)
    now = _aware_datetime(now, "now")
    cutoff = _aware_datetime(pr["firstRunStartedAfter"], "firstRunStartedAfter")
    end = _timestamp_literal(now)
    topics = ",".join(str(value) for value in pr["topicIds"])
    excluded = pr["excludeKnownSourceArticleIds"]
    exclusion_clauses = []
    # Remain usable on Oracle releases with a 1,000-expression IN-list limit.
    for start in range(0, len(excluded), 900):
        batch = ",".join(str(value) for value in excluded[start:start + 900])
        exclusion_clauses.append(f" and o.article_id not in ({batch})")
    exclusions = "\n".join(exclusion_clauses)
    return f"""whenever oserror exit failure rollback
whenever sqlerror exit failure rollback
set pagesize 0 feedback off heading off verify off echo off trimspool on
set linesize 32767
set sqlblanklines on
alter session set container=FREEPDB1;
set transaction read only;
with eligible as (
 select distinct o.topic_id, o.article_id, r.id run_id
 from NEWS_AGENT.news_collection_run_articles o
 join NEWS_AGENT.news_collection_runs r on r.id = o.run_id
 join NEWS_AGENT.news_articles a on a.id = o.article_id
 join NEWS_AGENT.news_collection_runs f on f.id = a.first_seen_run_id
 where o.topic_id in ({topics})
 and r.trigger_type = 'SCHEDULED'
 and r.status in ('SUCCESS', 'PARTIAL') and r.finished_at is not null
 and r.started_at >= {end} - numtodsinterval({pr['observationLookbackHours']}, 'HOUR')
 and r.finished_at <= {end}
 and f.started_at > {_timestamp_literal(cutoff)}
{exclusions}
)
select payload from (
 select 0 record_order, topic_id, article_id, run_id,
        json_object('kind' value 'eligible_article', 'topicId' value topic_id,
                    'articleId' value article_id, 'runId' value run_id) payload
 from eligible
 union all
 select 1, null, null, null,
        json_object('kind' value 'query_complete', 'rowCount' value count(*))
 from eligible
)
order by record_order, topic_id, article_id, run_id;
rollback;
exit
"""


def _validate_article_row(row: object) -> dict:
    if not isinstance(row, dict) or set(row) != ARTICLE_ROW_FIELDS:
        raise ValueError("Eligible rows must contain only kind/topicId/articleId/runId metadata")
    if row["kind"] != "eligible_article":
        raise ValueError("Unexpected metadata row kind")
    for field in ("topicId", "articleId", "runId"):
        _positive_integer(row[field], field)
    return row


def parse_query_output(stdout: str) -> list[dict]:
    """Reject incomplete output, SQL errors, unexpected text and sentinel/count mismatch."""
    rows = []
    sentinel_seen = False
    for line_number, line in enumerate(stdout.split("\n"), start=1):
        if not line.strip():
            continue
        if sentinel_seen:
            raise ValueError("Unexpected output after query_complete sentinel")
        try:
            row = json.loads(line)
        except json.JSONDecodeError as error:
            raise ValueError(f"Unparsed SQL output at line {line_number}") from error
        if not isinstance(row, dict):
            raise ValueError("Query output rows must be JSON objects")
        if row.get("kind") == "query_complete":
            if set(row) != {"kind", "rowCount"}:
                raise ValueError("Unexpected query_complete sentinel fields")
            count = row["rowCount"]
            if (not isinstance(count, int) or isinstance(count, bool)
                    or count < 0 or count != len(rows)):
                raise ValueError("query_complete rowCount does not match emitted metadata rows")
            sentinel_seen = True
        else:
            rows.append(_validate_article_row(row))
    if not sentinel_seen:
        raise ValueError("Missing query_complete sentinel")
    return rows


def assess_readiness(pr: dict, rows: list[dict], now: datetime) -> dict:
    """Assess both candidate and deterministic selected-sample provenance, without text."""
    pr = validate_preregistration(pr)
    now = _aware_datetime(now, "now")
    if not isinstance(rows, list):
        raise ValueError("Metadata rows must be a list")
    by_topic: dict[int, dict[int, set[int]]] = {topic: {} for topic in pr["topicIds"]}
    excluded = set(pr["excludeKnownSourceArticleIds"])
    for raw in rows:
        row = _validate_article_row(raw)
        if row["topicId"] not in by_topic:
            raise ValueError("Query returned a topic outside the preregistration")
        if row["articleId"] in excluded:
            raise ValueError("Query returned a preregistered excluded article")
        by_topic[row["topicId"]].setdefault(row["articleId"], set()).add(row["runId"])

    counts = []
    all_run_ids: set[int] = set()
    selected_run_ids: set[int] = set()
    selected_ids = {}
    for topic, articles in sorted(by_topic.items()):
        topic_runs = {run for runs in articles.values() for run in runs}
        all_run_ids.update(topic_runs)
        selected = sorted(articles, key=lambda article: (
            hashlib.sha256(f"{pr['samplingSeed']}:{topic}:{article}".encode()).hexdigest(), article
        ))[:pr["samplePerTopic"]]
        selected_ids[str(topic)] = sorted(selected)
        selected_topic_runs = {run for article in selected for run in articles[article]}
        selected_run_ids.update(selected_topic_runs)
        counts.append({
            "topicId": topic, "articles": len(articles), "sourceRuns": len(topic_runs),
            "selectedArticles": len(selected), "selectedSourceRuns": len(selected_topic_runs),
            "missingArticles": max(0, pr["samplePerTopic"] - len(articles)),
        })
    conditions = {
        "perTopicArticleCountsSatisfied": all(
            item["articles"] >= pr["samplePerTopic"] for item in counts
        ),
        "allSourceRunMinimumSatisfied": len(all_run_ids) >= pr["minimumSourceRuns"],
        "selectedSourceRunMinimumSatisfied": len(selected_run_ids) >= pr["minimumSourceRuns"],
    }
    reason_codes = []
    if not conditions["perTopicArticleCountsSatisfied"]:
        reason_codes.append("INSUFFICIENT_ARTICLES_PER_TOPIC")
    if not conditions["allSourceRunMinimumSatisfied"]:
        reason_codes.append("INSUFFICIENT_ALL_SOURCE_RUN_UNION")
    if not conditions["selectedSourceRunMinimumSatisfied"]:
        reason_codes.append("INSUFFICIENT_SELECTED_SOURCE_RUN_UNION")
    if not conditions["perTopicArticleCountsSatisfied"]:
        status = "WAITING_FOR_FRESH_ARTICLES"
    elif not all(conditions.values()):
        status = "WAITING_FOR_SOURCE_RUNS"
    else:
        status = "READY_FOR_SNAPSHOT"
    return {
        "status": status, "checkedAt": now.isoformat(), "counts": counts,
        "requiredPerTopic": pr["samplePerTopic"], "minimumSourceRuns": pr["minimumSourceRuns"],
        "allSourceRunIds": sorted(all_run_ids), "selectedSourceRunIds": sorted(selected_run_ids),
        "selectedArticleIdsByTopic": selected_ids,
        "conditions": conditions, "reasonCodes": reason_codes,
        "window": {
            "startedAtOrAfter": (now - timedelta(hours=pr["observationLookbackHours"])).isoformat(),
            "finishedAtOrBefore": now.isoformat(),
            "firstRunStartedStrictlyAfter": pr["firstRunStartedAfter"],
            "observationLookbackHours": pr["observationLookbackHours"],
            "observationTriggerType": "SCHEDULED", "allowedStatuses": ["SUCCESS", "PARTIAL"],
            "firstSeenRunTriggerRestricted": False,
        },
        "excludedArticleCount": len(excluded), "samplingSeed": pr["samplingSeed"],
        "predictionsRead": False, "rawArticleTextRead": False,
        "normalRunCostMeasurementStarted": False, "llmCallsPerformed": False,
        "independentAccuracyGatePassed": False,
    }


def _write_output(path: Path, record: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent,
                                         prefix=path.name + ".", delete=False) as stream:
            temporary = Path(stream.name)
            json.dump(record, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
        os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def _output_path_conflicts(output: Path, preregistration: Path, repo_root: Path) -> bool:
    """Keep source inputs protected even when the preregistration cannot be parsed."""
    try:
        resolved = output.resolve()
        cluster_directory = repo_root.resolve().joinpath(*CLUSTER_DIRECTORY.parts)
        return (resolved == preregistration.resolve()
                or resolved.is_relative_to(cluster_directory)
                or output.absolute().is_relative_to(cluster_directory))
    except (OSError, RuntimeError):
        return True


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--preregistration", type=Path, required=True)
    parser.add_argument("--repo-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--now", help="Aware ISO datetime; defaults to current Asia/Seoul time")
    args = parser.parse_args(argv)
    if _output_path_conflicts(args.output, args.preregistration, args.repo_root):
        print(json.dumps({"status": "INVALID_PREREGISTRATION",
                          "reasonCodes": ["OUTPUT_PATH_CONFLICT"]}))
        return 2
    record = {
        "status": "INVALID_PREREGISTRATION", "reasonCodes": [], "hashMismatches": [],
        "predictionsRead": False, "rawArticleTextRead": False,
        "normalRunCostMeasurementStarted": False, "llmCallsPerformed": False,
        "independentAccuracyGatePassed": False, "queryExecuted": False,
        "frozenFilesCheckedBeforeQuery": False, "frozenFilesCheckedAfterQuery": False,
    }
    try:
        now = (_aware_datetime(args.now, "now") if args.now
               else datetime.now(SEOUL).replace(microsecond=0))
        record["checkedAt"] = now.isoformat()
        raw_preregistration = args.preregistration.read_bytes()
        record["preregistrationSha256"] = hashlib.sha256(raw_preregistration).hexdigest()
        pr = validate_preregistration(json.loads(raw_preregistration))
        record["frozenClusterFilesRequired"] = len(pr["runtimeFilesSha256"])
    except (ValueError, OSError, UnicodeError) as error:
        record.update(reasonCodes=["INVALID_PREREGISTRATION"], error=str(error))
    else:
        mismatches = verify_frozen_files(args.repo_root, pr)
        record["frozenFilesCheckedBeforeQuery"] = True
        record["frozenClusterFilesVerified"] = len(pr["runtimeFilesSha256"]) - len(mismatches)
        if mismatches:
            record.update(status="FROZEN_CODE_MISMATCH", reasonCodes=["FROZEN_CODE_MISMATCH"],
                          hashMismatches=[{**item, "phase": "BEFORE_QUERY"} for item in mismatches])
        else:
            try:
                sql = build_sql(pr, now)
                record["queryExecuted"] = True
                result = subprocess.run(
                    ["docker", "exec", "-i", "-e", "NLS_LANG=.AL32UTF8", "oracle-free",
                     "sqlplus", "-s", "/", "as", "sysdba"],
                    input=sql, text=True, encoding="utf-8", capture_output=True, timeout=60,
                    check=False,
                )
                if result.returncode != 0:
                    raise ValueError(f"Oracle metadata query exited with code {result.returncode}")
                if result.stderr.strip():
                    raise ValueError("Oracle metadata query emitted unexpected stderr")
                rows = parse_query_output(result.stdout)
                assessed = assess_readiness(pr, rows, now)
                record.update(assessed)
            except subprocess.TimeoutExpired:
                record.update(status="QUERY_FAILED", reasonCodes=["QUERY_TIMEOUT"],
                              error="Oracle metadata query exceeded 60 seconds")
            except (ValueError, OSError, UnicodeError) as error:
                record.update(status="QUERY_FAILED", reasonCodes=["QUERY_FAILED"], error=str(error))
            # A successful query must not publish readiness against code changed during the query.
            mismatches = verify_frozen_files(args.repo_root, pr)
            record["frozenFilesCheckedAfterQuery"] = True
            record["frozenClusterFilesVerified"] = len(pr["runtimeFilesSha256"]) - len(mismatches)
            if mismatches:
                record.update(
                    status="FROZEN_CODE_MISMATCH", reasonCodes=["FROZEN_CODE_MISMATCH"],
                    hashMismatches=[{**item, "phase": "AFTER_QUERY"} for item in mismatches],
                )
            try:
                current_preregistration_hash = hashlib.sha256(
                    args.preregistration.read_bytes()
                ).hexdigest()
                if current_preregistration_hash != record["preregistrationSha256"]:
                    record.update(status="INVALID_PREREGISTRATION",
                                  reasonCodes=["PREREGISTRATION_CHANGED_DURING_QUERY"])
            except OSError:
                record.update(status="INVALID_PREREGISTRATION",
                              reasonCodes=["PREREGISTRATION_UNREADABLE_AFTER_QUERY"])
    if _output_path_conflicts(args.output, args.preregistration, args.repo_root):
        print(json.dumps({"status": "INVALID_PREREGISTRATION",
                          "reasonCodes": ["OUTPUT_PATH_CONFLICT"]}))
        return 2
    try:
        _write_output(args.output, record)
    except OSError:
        print(json.dumps({"status": "QUERY_FAILED", "reasonCodes": ["OUTPUT_WRITE_FAILED"]}))
        return 2
    # Detailed article/run identifiers stay in the local artifact, not console or CI output.
    print(json.dumps({
        "status": record["status"], "checkedAt": record.get("checkedAt"),
        "counts": record.get("counts", []), "reasonCodes": record["reasonCodes"],
        "allSourceRunCount": len(record.get("allSourceRunIds", [])),
        "selectedSourceRunCount": len(record.get("selectedSourceRunIds", [])),
        "output": str(args.output),
    }, ensure_ascii=False))
    return 0 if record["status"] in NORMAL_STATUSES else 2


if __name__ == "__main__":
    raise SystemExit(main())
