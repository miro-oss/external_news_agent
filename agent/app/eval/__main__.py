import argparse
import json
import math
import sys
from json import JSONDecodeError
from pathlib import Path

from pydantic import ValidationError

from app.core.errors import AgentError
from app.eval.dataset import load_dataset, load_report_fixture
from app.eval.live_provider import LiveProviderPolicy, default_live_policy
from app.eval.runner import run_evaluation
from app.eval.scorer import ComparisonError, compare_results

_GOLDEN_DIR = Path(__file__).resolve().parent / "golden"
_DEFAULT_DATASET = _GOLDEN_DIR / "semiconductor.v1.json"
_DEFAULT_REPORT_FIXTURE = _GOLDEN_DIR / "report.ko.v1.json"


def main(argv: list[str] | None = None) -> int:
    parser = _parser()
    args = parser.parse_args(argv)
    if args.allow_prompt_version_change and args.profile != "live":
        print(
            "eval configuration error: prompt version override is live-only",
            file=sys.stderr,
        )
        return 2
    if args.profile != "live" and (args.checkpoint is not None or args.resume):
        print(
            "eval configuration error: checkpoint and resume are live-only",
            file=sys.stderr,
        )
        return 2
    if args.output is not None and args.checkpoint is not None:
        if args.output.resolve() == args.checkpoint.resolve():
            print(
                "eval configuration error: output and checkpoint must use different paths",
                file=sys.stderr,
            )
            return 2

    try:
        live_policy = None
        if args.profile == "live":
            defaults = default_live_policy(args.plan)
            live_policy = LiveProviderPolicy(
                request_interval_seconds=(
                    args.request_interval_seconds
                    if args.request_interval_seconds is not None
                    else defaults.request_interval_seconds
                ),
                rate_limit_retry_attempts=args.rate_limit_retries,
                rate_limit_backoff_seconds=args.rate_limit_backoff_seconds,
                rate_limit_max_backoff_seconds=args.rate_limit_max_backoff_seconds,
            )
    except ValueError as error:
        print(f"eval configuration error: {error}", file=sys.stderr)
        return 2

    try:
        result = run_evaluation(
            load_dataset(args.dataset),
            profile=args.profile,
            plan=args.plan,
            report_fixture=(
                load_report_fixture(args.report_fixture) if args.profile == "replay" else None
            ),
            live_policy=live_policy,
            checkpoint_path=args.checkpoint,
            resume=args.resume,
        )
    except (AgentError, OSError, ValidationError, ValueError) as error:
        print(f"eval execution error: {error}", file=sys.stderr)
        return 2

    payload = result.to_dict()
    comparison: dict[str, object] | None = None
    if args.compare is not None:
        try:
            baseline = json.loads(args.compare.read_text(encoding="utf-8"))
            if not isinstance(baseline, dict):
                raise ComparisonError("baseline root must be an object")
            comparison = compare_results(
                payload,
                baseline,
                allow_prompt_version_change=args.allow_prompt_version_change,
            )
            payload["comparison"] = comparison
        except (OSError, JSONDecodeError, ComparisonError) as error:
            print(f"baseline comparison error: {error}", file=sys.stderr)
            return 2

    rendered = json.dumps(payload, ensure_ascii=False, indent=2)
    print(rendered)
    if args.output is not None:
        try:
            args.output.write_text(rendered + "\n", encoding="utf-8")
        except OSError as error:
            print(f"output write error: {error}", file=sys.stderr)
            return 2

    has_regression = bool(comparison and comparison["regressions"])
    return 1 if not result.complete or result.errors or has_regression else 0


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Agent Golden eval 실행기")
    parser.add_argument("--dataset", type=Path, default=_DEFAULT_DATASET)
    parser.add_argument("--report-fixture", type=Path, default=_DEFAULT_REPORT_FIXTURE)
    parser.add_argument("--profile", choices=("replay", "live"), default="replay")
    parser.add_argument("--plan", choices=("FREE", "PAID"), default="FREE")
    parser.add_argument("--compare", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--checkpoint", type=Path)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--request-interval-seconds", type=_non_negative_float)
    parser.add_argument("--rate-limit-retries", type=_non_negative_int, default=5)
    parser.add_argument("--rate-limit-backoff-seconds", type=_positive_float, default=15.0)
    parser.add_argument(
        "--rate-limit-max-backoff-seconds",
        type=_positive_float,
        default=60.0,
    )
    parser.add_argument("--allow-prompt-version-change", action="store_true")
    return parser


def _non_negative_float(value: str) -> float:
    parsed = float(value)
    if not _is_finite(parsed) or parsed < 0:
        raise argparse.ArgumentTypeError("0 이상의 숫자여야 합니다.")
    return parsed


def _positive_float(value: str) -> float:
    parsed = float(value)
    if not _is_finite(parsed) or parsed <= 0:
        raise argparse.ArgumentTypeError("0보다 큰 숫자여야 합니다.")
    return parsed


def _non_negative_int(value: str) -> int:
    parsed = int(value)
    if parsed < 0:
        raise argparse.ArgumentTypeError("0 이상의 정수여야 합니다.")
    return parsed


def _is_finite(value: float) -> bool:
    return math.isfinite(value)


if __name__ == "__main__":
    raise SystemExit(main())
