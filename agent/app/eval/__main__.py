import argparse
import json
import sys
from json import JSONDecodeError
from pathlib import Path

from pydantic import ValidationError

from app.eval.dataset import load_dataset, load_report_fixture
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

    try:
        result = run_evaluation(
            load_dataset(args.dataset),
            profile=args.profile,
            plan=args.plan,
            report_fixture=(
                load_report_fixture(args.report_fixture) if args.profile == "replay" else None
            ),
        )
    except (OSError, ValidationError, ValueError) as error:
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
    return 1 if result.errors or has_regression else 0


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Agent Golden eval 실행기")
    parser.add_argument("--dataset", type=Path, default=_DEFAULT_DATASET)
    parser.add_argument("--report-fixture", type=Path, default=_DEFAULT_REPORT_FIXTURE)
    parser.add_argument("--profile", choices=("replay", "live"), default="replay")
    parser.add_argument("--plan", choices=("FREE", "PAID"), default="FREE")
    parser.add_argument("--compare", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--allow-prompt-version-change", action="store_true")
    return parser


if __name__ == "__main__":
    raise SystemExit(main())
