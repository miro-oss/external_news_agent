"""One-command nano generation, blind review, and local judgment aggregation."""

from __future__ import annotations

import argparse
import getpass
import json
import logging
import os
import shlex
import statistics
import sys
import warnings
import webbrowser
from decimal import Decimal
from pathlib import Path

from app.eval.report_insight_compare import render_comparison
from app.eval.report_insight_corpus import DEFAULT_DATASET, load_corpus, review_candidates
from app.eval.report_insight_review import QUALITY_RUBRIC
from app.eval.report_insight_run import (
    AGENT_ROOT,
    EvaluationStopped,
    atomic_save,
    prepare,
    require,
    run,
    summary,
    verify,
)

DEFAULT_OUTPUT = AGENT_ROOT.parent / "docs/report-insight-evaluation-20260930/nano-comparison-v1"
WINNERS = {"A", "B", "tie", "both_fail"}
VARIANTS = ("single_call", "staged")


def read_state(output_dir: Path) -> dict:
    manifest = json.loads((output_dir / "manifest.json").read_text(encoding="utf-8"))
    state = json.loads((output_dir / "result.json").read_text(encoding="utf-8"))
    verify(output_dir, manifest, state)
    return state


def generation_metrics(state: dict) -> dict:
    metrics = {}
    for variant in VARIANTS:
        results = [item for item in state["results"] if item["variant"] == variant]
        attempts = [item for item in state["attempts"] if item["variant"] == variant]
        latencies = [
            item["latencyMs"]
            for item in results
            if item["status"] == "success" and item.get("latencyMs") is not None
        ]
        attempt_latency = {item["attemptId"]: item.get("latencyMs") for item in attempts}
        provider_latencies = [
            sum(attempt_latency[identifier] for identifier in item["attemptIds"])
            for item in results
            if item["status"] == "success"
            and item["attemptIds"]
            and all(
                attempt_latency.get(identifier) is not None for identifier in item["attemptIds"]
            )
        ]
        metrics[variant] = {
            "plannedResults": len(results),
            "successResults": sum(item["status"] == "success" for item in results),
            "failedResults": sum(item["status"] == "failed" for item in results),
            "pendingResults": sum(item["status"] in {"pending", "running"} for item in results),
            "providerAttempts": len(attempts),
            "observedCostEstimatedUsd": str(
                sum((Decimal(item["costEstimatedUsd"]) for item in attempts), Decimal(0))
            ),
            "unsettledReservedUsd": str(
                sum((Decimal(item["unsettledReservedUsd"]) for item in attempts), Decimal(0))
            ),
            "successfulLatencyMedianMs": statistics.median(latencies) if latencies else None,
            "successfulProviderLatencyMedianMs": (
                statistics.median(provider_latencies) if provider_latencies else None
            ),
        }
    return metrics


def export_artifacts(output_dir: Path, state: dict) -> Path:
    page, private_key = render_comparison(state)
    html_path = output_dir / "blind-review.html"
    html_path.write_text(page, encoding="utf-8")
    html_path.chmod(0o600)
    atomic_save(output_dir / "blind-key.private.json", private_key)
    corpus = load_corpus(Path(state["provenance"]["datasetPath"]))
    reviews = {}
    for variant in VARIANTS:
        candidates = {
            item["caseId"]: {"insights": item["response"]["insights"]}
            for item in state["results"]
            if item["variant"] == variant and item["status"] == "success"
        }
        reviews[variant] = review_candidates(corpus, candidates)
    atomic_save(output_dir / "automatic-review.json", reviews)
    atomic_save(
        output_dir / "generation-summary.json",
        {
            "schemaVersion": 1,
            "provenance": state["provenance"],
            "synthetic": corpus.synthetic,
            "qualityMeasured": False,
            "requiresHumanReview": True,
            "variants": generation_metrics(state),
            "totals": state["totals"],
        },
    )
    return html_path


def aggregate_judgments(state: dict, judgments: dict) -> dict:
    """Decode a matching blind export without treating missing scores as zero."""
    _, key = render_comparison(state)
    require(isinstance(judgments, dict), "INVALID_JUDGMENTS")
    require(
        judgments.get("schemaVersion") == 1
        and judgments.get("manifestHash") == key["manifestHash"],
        "JUDGMENTS_EXPERIMENT_MISMATCH",
    )
    identities = {item["reviewId"]: item for item in key["cases"]}
    eligible = {
        identifier
        for identifier, item in identities.items()
        if all(side["status"] == "success" for side in item["sides"].values())
    }
    ratings = judgments.get("ratings")
    require(isinstance(ratings, list), "INVALID_JUDGMENTS")
    seen = set()
    wins = dict.fromkeys(VARIANTS, 0)
    paired_scores = {criterion: [] for criterion in QUALITY_RUBRIC}
    factual_defects = dict.fromkeys(VARIANTS, 0)
    judged, ties, both_fail = 0, 0, 0
    cases = []
    for rating in ratings:
        require(isinstance(rating, dict), "INVALID_JUDGMENTS")
        identifier = rating.get("reviewId")
        require(
            isinstance(identifier, str) and identifier in eligible and identifier not in seen,
            "INVALID_OR_DUPLICATE_REVIEW_ID",
        )
        seen.add(identifier)
        winner = rating.get("winner")
        winner = "" if winner is None else winner
        require(isinstance(winner, str) and winner in WINNERS | {""}, "INVALID_PREFERENCE")
        identity = identities[identifier]
        scores_by_variant = {}
        raw_sides = rating.get("sides", {})
        require(isinstance(raw_sides, dict), "INVALID_SCORES")
        for side in ("A", "B"):
            raw_side = raw_sides.get(side, {})
            require(isinstance(raw_side, dict), "INVALID_SCORES")
            scores = raw_side.get("scores", {})
            require(
                isinstance(scores, dict) and set(scores) <= set(QUALITY_RUBRIC), "INVALID_SCORES"
            )
            require(
                all(type(value) is int and 0 <= value <= 3 for value in scores.values()),
                "INVALID_SCORES",
            )
            scores_by_variant[identity["sides"][side]["variant"]] = scores
        if not winner:
            continue
        judged += 1
        if winner == "tie":
            ties += 1
        elif winner == "both_fail":
            both_fail += 1
        else:
            wins[identity["sides"][winner]["variant"]] += 1
        for variant, scores in scores_by_variant.items():
            if scores.get("factual_integrity") == 0:
                factual_defects[variant] += 1
        for criterion, pairs in paired_scores.items():
            values = [scores_by_variant[variant].get(criterion) for variant in VARIANTS]
            if all(value is not None for value in values):
                pairs.append(values)
        cases.append(
            {
                "caseId": identity["caseId"],
                "preference": (
                    identity["sides"][winner]["variant"] if winner in {"A", "B"} else winner
                ),
            }
        )
    averages = {}
    for criterion, pairs in paired_scores.items():
        averages[criterion] = {
            "pairedScoreCount": len(pairs),
            "singleCallMean": statistics.mean(pair[0] for pair in pairs) if pairs else None,
            "stagedMean": statistics.mean(pair[1] for pair in pairs) if pairs else None,
            "stagedMinusSingleMean": (
                statistics.mean(pair[1] - pair[0] for pair in pairs) if pairs else None
            ),
        }
    corpus = load_corpus(Path(state["provenance"]["datasetPath"]))
    source_cases = {item.case_id: item for item in corpus.cases}
    audience_counts = {}
    for item in cases:
        source = source_cases[item["caseId"]]
        item.update(audience=source.request.audiences[0], scenario=source.scenario)
        counts = audience_counts.setdefault(
            item["audience"],
            {"judgedCount": 0, **dict.fromkeys([*VARIANTS, "tie", "both_fail"], 0)},
        )
        counts["judgedCount"] += 1
        counts[item["preference"]] += 1
    return {
        "schemaVersion": 1,
        "provenance": state["provenance"],
        "synthetic": corpus.synthetic,
        "comparisonScope": "same detailed prompt: single-call versus staged MAP/RAG/REDUCE",
        "qualityImprovementClaimed": False,
        "plannedCases": len(identities),
        "eligiblePairs": len(eligible),
        "excludedPairs": len(identities) - len(eligible),
        "judgedCount": judged,
        "unjudgedEligiblePairs": len(eligible) - judged,
        "wins": wins,
        "ties": ties,
        "bothUnacceptable": both_fail,
        "stagedWinShareOfJudged": wins["staged"] / judged if judged else None,
        "factualIntegrityZeroCounts": factual_defects,
        "optionalPairedCriteria": averages,
        "generation": generation_metrics(state),
        "cases": cases,
        "audiencePreferences": audience_counts,
        "limitations": [
            "Synthetic scenarios; production generalization and repeat stability are unmeasured.",
            "Audience cases share scenarios; no independent-case confidence interval is claimed.",
            "Missing ratings and failures are excluded from preference; coverage shows them.",
        ],
    }


def measure(output_dir: Path, *, prepare_only=False, open_browser=True) -> dict:
    prepare(DEFAULT_DATASET, output_dir, max_cost_usd=Decimal("1.00"), max_calls=144)
    state = read_state(output_dir)
    needs_generation = any(item["status"] == "pending" for item in state["results"])
    if not prepare_only and needs_generation:
        require(
            all(item.get("safeToContinue") for item in state["errors"]),
            "RECORDED_FAILURE_REQUIRES_REVIEW",
        )
        require(state["inFlight"] is None, "INTERRUPTED_ATTEMPT_REQUIRES_REVIEW")
        original = os.environ.get("OPENAI_API_KEY")
        try:
            if not original or not original.strip():
                require(sys.stdin.isatty(), "KEY_INPUT_REQUIRES_TERMINAL")
                print("모델: gpt-4.1-nano · 추정 비용 한도 $1 · 실제 호출 최대 144회")
                print("API 키는 터미널에서 숨겨서 입력하며 파일에 저장하지 않습니다.")
                with warnings.catch_warnings():
                    warnings.simplefilter("error", getpass.GetPassWarning)
                    try:
                        key = getpass.getpass("OpenAI API 키 입력: ").strip()
                    except getpass.GetPassWarning:
                        raise EvaluationStopped("HIDDEN_KEY_INPUT_UNAVAILABLE") from None
                require(bool(key), "OPENAI_API_KEY_REQUIRED")
                os.environ["OPENAI_API_KEY"] = key
            print("합성 사례 24개 × 두 방식의 답변을 생성합니다. 비용 한도에 도달하면 멈춥니다.")
            run(output_dir, phase="pilot")
            print("첫 4개 사례 생성이 끝났습니다. 나머지 사례를 이어서 생성합니다.")
            run(output_dir, phase="remaining")
        except EvaluationStopped:
            export_artifacts(output_dir, read_state(output_dir))
            raise
        finally:
            if original is None:
                os.environ.pop("OPENAI_API_KEY", None)
            else:
                os.environ["OPENAI_API_KEY"] = original
    html_path = export_artifacts(output_dir, read_state(output_dir))
    if open_browser and not prepare_only:
        webbrowser.open(html_path.as_uri())
    return {**summary(output_dir), "reviewHtml": str(html_path), "requiresHumanReview": True}


def launcher_text() -> str:
    executable = AGENT_ROOT / ".venv/bin/python"
    return (
        "#!/bin/zsh\nset -eu\n"
        f"cd {shlex.quote(str(AGENT_ROOT))}\n"
        f"{shlex.quote(str(executable))} -m app.eval.report_insight_measure\n"
        'printf "\\n종료되었습니다. 창을 닫아도 됩니다.\\n"\n'
    )


def main() -> None:
    previous_logging_disable = logging.root.manager.disable
    logging.disable(logging.CRITICAL)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("measure", "score"), nargs="?", default="measure")
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--no-open", action="store_true")
    parser.add_argument("--judgments", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "score":
            require(args.judgments is not None, "JUDGMENTS_PATH_REQUIRED")
            state = read_state(args.output_dir)
            ratings = json.loads(args.judgments.read_text(encoding="utf-8"))
            result = aggregate_judgments(state, ratings)
            atomic_save(args.output_dir / "quality-summary.json", result)
        else:
            result = measure(
                args.output_dir, prepare_only=args.prepare_only, open_browser=not args.no_open
            )
            if args.prepare_only and args.output_dir == DEFAULT_OUTPUT:
                path = args.output_dir.parent / "측정 시작.command"
                path.write_text(launcher_text(), encoding="utf-8")
                path.chmod(0o700)
                result["launcher"] = str(path)
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except Exception as error:
        code = str(error) if isinstance(error, EvaluationStopped) else "MEASUREMENT_INPUT_ERROR"
        print(json.dumps({"status": "stopped", "code": code}, ensure_ascii=False))
        raise SystemExit(2) from None
    except KeyboardInterrupt:
        print(json.dumps({"status": "stopped", "code": "USER_INTERRUPTED"}))
        raise SystemExit(130) from None
    finally:
        logging.disable(previous_logging_disable)


if __name__ == "__main__":
    main()
