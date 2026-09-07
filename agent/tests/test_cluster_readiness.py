"""Exercise readiness decisions using synthetic provenance, never article content."""

import copy
import hashlib
import json
import subprocess
import sys
from datetime import datetime
from pathlib import Path

import pytest

from app.eval import cluster_readiness as readiness

NOW = datetime.fromisoformat("2026-09-07T17:00:00+09:00")
FROZEN_FILE = "BE/src/main/java/com/example/be/domain/collection/cluster/IssueClusterer.java"


def preregistration(tmp_path: Path) -> dict:
    frozen = tmp_path / FROZEN_FILE
    frozen.parent.mkdir(parents=True, exist_ok=True)
    frozen.write_text("class Frozen {}\n", encoding="utf-8")
    return {
        "topicIds": [1, 2],
        "samplePerTopic": 2,
        "samplingSeed": "readiness-test-v1",
        "firstRunStartedAfter": "2026-09-07T14:37:01+09:00",
        "observationLookbackHours": 48,
        "minimumSourceRuns": 3,
        "excludeKnownSourceArticleIds": [99],
        "runtimeFilesSha256": {FROZEN_FILE: hashlib.sha256(frozen.read_bytes()).hexdigest()},
    }


def row(topic: int, article: int, run: int) -> dict:
    return {"kind": "eligible_article", "topicId": topic, "articleId": article, "runId": run}


def ready_rows() -> list[dict]:
    return [row(1, 11, 101), row(1, 12, 102), row(2, 21, 102), row(2, 22, 103)]


def query_output(rows: list[dict]) -> str:
    values = [*rows, {"kind": "query_complete", "rowCount": len(rows)}]
    return "\n".join(json.dumps(value) for value in values) + "\n"


def cli_files(tmp_path: Path) -> tuple[dict, Path, Path, list[str]]:
    pr = preregistration(tmp_path)
    manifest = tmp_path / "preregistration.json"
    manifest.write_text(json.dumps(pr), encoding="utf-8")
    output = tmp_path / "readiness.json"
    args = ["--preregistration", str(manifest), "--repo-root", str(tmp_path),
            "--output", str(output), "--now", NOW.isoformat()]
    return pr, manifest, output, args


def test_three_runs_across_topics_are_enough_without_three_per_topic(tmp_path: Path) -> None:
    pr = preregistration(tmp_path)
    original = copy.deepcopy(pr)
    rows = ready_rows()
    result = readiness.assess_readiness(pr, [*rows, rows[0]], NOW)

    assert result["status"] == "READY_FOR_SNAPSHOT"
    assert result["allSourceRunIds"] == [101, 102, 103]
    assert result["selectedSourceRunIds"] == [101, 102, 103]
    assert [item["articles"] for item in result["counts"]] == [2, 2]
    assert [item["sourceRuns"] for item in result["counts"]] == [2, 2]
    assert pr == original


def test_shared_runs_are_not_added_twice_across_topics(tmp_path: Path) -> None:
    rows = [row(1, 11, 101), row(1, 12, 102), row(2, 21, 101), row(2, 22, 102)]
    result = readiness.assess_readiness(preregistration(tmp_path), rows, NOW)

    assert result["status"] == "WAITING_FOR_SOURCE_RUNS"
    assert result["allSourceRunIds"] == [101, 102]
    assert result["reasonCodes"]


def test_empty_query_is_waiting_with_explicit_zero_counts(tmp_path: Path) -> None:
    rows = readiness.parse_query_output(query_output([]))
    result = readiness.assess_readiness(preregistration(tmp_path), rows, NOW)

    assert result["status"] == "WAITING_FOR_FRESH_ARTICLES"
    assert [item["articles"] for item in result["counts"]] == [0, 0]
    assert result["allSourceRunIds"] == []


def test_many_runs_cannot_replace_missing_articles(tmp_path: Path) -> None:
    rows = [row(1, 11, run) for run in [101, 102, 103]] + [row(2, 21, 103)]
    result = readiness.assess_readiness(preregistration(tmp_path), rows, NOW)
    assert result["status"] == "WAITING_FOR_FRESH_ARTICLES"
    assert [item["articles"] for item in result["counts"]] == [1, 1]


def test_selected_sample_must_also_cover_three_runs(tmp_path: Path) -> None:
    pr = preregistration(tmp_path)
    ranked = sorted([11, 12, 13], key=lambda article: hashlib.sha256(
        f"{pr['samplingSeed']}:1:{article}".encode()).hexdigest())
    rows = [row(1, ranked[0], 101), row(1, ranked[1], 102), row(1, ranked[2], 103),
            row(2, 21, 101), row(2, 22, 102)]
    result = readiness.assess_readiness(pr, rows, NOW)

    assert result["allSourceRunIds"] == [101, 102, 103]
    assert result["selectedSourceRunIds"] == [101, 102]
    assert set(result["selectedArticleIdsByTopic"]["1"]) == set(ranked[:2])
    assert result["status"] == "WAITING_FOR_SOURCE_RUNS"
    assert result == readiness.assess_readiness(pr, list(reversed(rows)), NOW)


@pytest.mark.parametrize("change", [
    {"topicIds": [1, 1]}, {"topicIds": [True, 2]}, {"samplePerTopic": 0},
    {"minimumSourceRuns": 0}, {"observationLookbackHours": -1},
    {"firstRunStartedAfter": "2026-09-07T14:37:01"},
    {"samplingSeed": " "}, {"observationTriggerType": "MANUAL"},
    {"excludeKnownSourceArticleIds": [False]}, {"runtimeFilesSha256": {}},
])
def test_invalid_registration_is_rejected(tmp_path: Path, change: dict) -> None:
    pr = preregistration(tmp_path)
    pr.update(change)
    with pytest.raises(ValueError):
        readiness.validate_preregistration(pr)


def test_frozen_file_changes_report_evidence_without_assert(tmp_path: Path) -> None:
    pr = preregistration(tmp_path)
    assert readiness.verify_frozen_files(tmp_path, pr) == []
    (tmp_path / FROZEN_FILE).write_text("class Changed {}\n", encoding="utf-8")
    mismatch = readiness.verify_frozen_files(tmp_path, pr)
    assert len(mismatch) == 1
    assert mismatch[0]["path"] == FROZEN_FILE
    assert mismatch[0]["expectedSha256"] == pr["runtimeFilesSha256"][FROZEN_FILE]
    assert mismatch[0]["actualSha256"] != mismatch[0]["expectedSha256"]
    (tmp_path / FROZEN_FILE).unlink()
    assert readiness.verify_frozen_files(tmp_path, pr)[0]["actualSha256"] is None


@pytest.mark.parametrize("invalid", ["", "SP2-0734: unknown command\n", "{}\n",
    '{"kind":"query_complete","rowCount":1}\n',
    '{"kind":"query_complete","rowCount":0}\n{"kind":"query_complete","rowCount":0}\n',
])
def test_incomplete_or_malformed_query_does_not_look_like_zero_candidates(invalid: str) -> None:
    with pytest.raises(ValueError):
        readiness.parse_query_output(invalid)


def test_query_parser_rejects_non_whitelisted_content() -> None:
    with pytest.raises(ValueError):
        readiness.parse_query_output(query_output([{**row(1, 11, 101), "body": "forbidden"}]))


@pytest.mark.parametrize("invalid_row", [row(3, 11, 101), row(1, 99, 101),
    row(1, 0, 101), row(1, True, 101)])
def test_unexpected_query_identity_fails_closed(tmp_path: Path, invalid_row: dict) -> None:
    with pytest.raises(ValueError):
        readiness.assess_readiness(preregistration(tmp_path), [invalid_row], NOW)


def test_cli_preserves_manifest_and_reports_provenance(tmp_path: Path, monkeypatch) -> None:
    _, manifest, output, args = cli_files(tmp_path)
    before = manifest.read_bytes()
    calls = []

    def run(command, **kwargs):
        calls.append((command, kwargs))
        return subprocess.CompletedProcess(command, 0, query_output(ready_rows()), "")

    monkeypatch.setattr(readiness.subprocess, "run", run)
    assert readiness.main(args) == 0
    result = json.loads(output.read_text())
    assert result["status"] == "READY_FOR_SNAPSHOT"
    assert hashlib.sha256(before).hexdigest() in json.dumps(result)
    assert result["queryExecuted"] is True
    assert result["predictionsRead"] is False
    assert result["rawArticleTextRead"] is False
    assert result["normalRunCostMeasurementStarted"] is False
    assert manifest.read_bytes() == before
    assert len(calls) == 1
    assert calls[0][0][0] == "docker"
    assert "set transaction read only" in calls[0][1]["input"].lower()


def test_cli_query_failure_replaces_stale_ready_result(tmp_path: Path, monkeypatch) -> None:
    _, _, output, args = cli_files(tmp_path)
    output.write_text('{"status":"READY_FOR_SNAPSHOT"}', encoding="utf-8")
    monkeypatch.setattr(readiness.subprocess, "run", lambda *a, **k:
                        subprocess.CompletedProcess(a[0], 1, "", "connection failed"))
    assert readiness.main(args) == 2
    assert json.loads(output.read_text())["status"] == "QUERY_FAILED"


def test_code_change_during_query_cannot_report_ready(tmp_path: Path, monkeypatch) -> None:
    _, _, output, args = cli_files(tmp_path)

    def run(command, **kwargs):
        (tmp_path / FROZEN_FILE).write_text("class ChangedWhileQuerying {}", encoding="utf-8")
        return subprocess.CompletedProcess(command, 0, query_output(ready_rows()), "")

    monkeypatch.setattr(readiness.subprocess, "run", run)
    assert readiness.main(args) == 2
    assert json.loads(output.read_text())["status"] == "FROZEN_CODE_MISMATCH"


@pytest.mark.parametrize("error,reason", [
    (FileNotFoundError("docker unavailable"), "QUERY_FAILED"),
    (subprocess.TimeoutExpired("docker", 60), "QUERY_TIMEOUT"),
])
def test_unfinished_query_is_not_recorded_as_executed(
    tmp_path: Path, monkeypatch, error: Exception, reason: str,
) -> None:
    _, _, output, args = cli_files(tmp_path)

    def run(*args, **kwargs):
        raise error

    monkeypatch.setattr(readiness.subprocess, "run", run)
    assert readiness.main(args) == 2
    result = json.loads(output.read_text())
    assert result["status"] == "QUERY_FAILED"
    assert result["reasonCodes"] == [reason]
    assert result["queryExecuted"] is False


@pytest.mark.parametrize("remove_manifest,reason", [
    (False, "PREREGISTRATION_CHANGED_DURING_QUERY"),
    (True, "PREREGISTRATION_UNREADABLE_AFTER_QUERY"),
])
def test_concurrent_integrity_failures_preserve_all_query_evidence(
    tmp_path: Path, monkeypatch, remove_manifest: bool, reason: str,
) -> None:
    _, manifest, output, args = cli_files(tmp_path)

    def run(*args, **kwargs):
        (tmp_path / FROZEN_FILE).write_text("class Changed {}", encoding="utf-8")
        if remove_manifest:
            manifest.unlink()
        else:
            manifest.write_text(manifest.read_text() + "\n", encoding="utf-8")
        raise subprocess.TimeoutExpired("docker", 60)

    monkeypatch.setattr(readiness.subprocess, "run", run)
    assert readiness.main(args) == 2
    result = json.loads(output.read_text())
    assert result["status"] == "INVALID_PREREGISTRATION"
    assert result["reasonCodes"] == ["QUERY_TIMEOUT", "FROZEN_CODE_MISMATCH", reason]
    assert result["hashMismatches"][0]["phase"] == "AFTER_QUERY"
    assert result["queryExecuted"] is False


def test_optimized_python_still_enforces_frozen_code(tmp_path: Path) -> None:
    _, _, output, args = cli_files(tmp_path)
    (tmp_path / FROZEN_FILE).write_text("class Changed {}", encoding="utf-8")
    result = subprocess.run([sys.executable, "-O", "-m", "app.eval.cluster_readiness", *args],
                            cwd=Path(__file__).resolve().parents[1], capture_output=True,
                            text=True, timeout=10, check=False)
    assert result.returncode == 2
    assert json.loads(output.read_text())["status"] == "FROZEN_CODE_MISMATCH"


@pytest.mark.parametrize("destination", ["manifest", "frozen"])
def test_output_cannot_overwrite_preregistration_or_source(
    tmp_path: Path, monkeypatch, capsys, destination: str,
) -> None:
    _, manifest, _, args = cli_files(tmp_path)
    protected = manifest if destination == "manifest" else tmp_path / FROZEN_FILE
    before = protected.read_bytes()
    args[args.index("--output") + 1] = str(protected)
    monkeypatch.setattr(readiness.subprocess, "run", lambda *a, **k: pytest.fail("No DB call"))

    assert readiness.main(args) == 2
    assert protected.read_bytes() == before
    assert "OUTPUT_PATH_CONFLICT" in capsys.readouterr().out


def test_frozen_symlink_is_rejected_before_reading_its_target(tmp_path: Path) -> None:
    pr = preregistration(tmp_path)
    original = tmp_path / FROZEN_FILE
    target = tmp_path / "synthetic-private.txt"
    target.write_text("Synthetic content must not be read by the hash check.", encoding="utf-8")
    original.unlink()
    original.symlink_to(target)

    mismatch = readiness.verify_frozen_files(tmp_path, pr)
    assert mismatch[0]["reason"] == "UNSAFE_PATH"
    assert mismatch[0]["actualSha256"] is None


def test_registration_change_during_query_cannot_report_ready(tmp_path: Path, monkeypatch) -> None:
    _, manifest, output, args = cli_files(tmp_path)

    def run(command, **kwargs):
        manifest.write_text(manifest.read_text() + "\n", encoding="utf-8")
        return subprocess.CompletedProcess(command, 0, query_output(ready_rows()), "")

    monkeypatch.setattr(readiness.subprocess, "run", run)
    assert readiness.main(args) == 2
    assert json.loads(output.read_text())["status"] == "INVALID_PREREGISTRATION"


def test_sql_uses_scheduled_observation_and_preserves_utc_cutoff(tmp_path: Path) -> None:
    pr = preregistration(tmp_path)
    pr["firstRunStartedAfter"] = "2026-09-07T05:37:01+00:00"
    sql = readiness.build_sql(pr, NOW)
    assert "r.trigger_type = 'SCHEDULED'" in sql
    assert "f.trigger_type" not in sql
    assert "f.started_at > timestamp '2026-09-07 14:37:01.000000'" in sql
    assert "r.finished_at <= timestamp '2026-09-07 17:00:00.000000'" in sql
    assert "numtodsinterval(48, 'HOUR')" in sql
    assert "o.article_id not in (99)" in sql
    assert "set transaction read only;" in sql
