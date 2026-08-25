import argparse
import json
from pathlib import Path

from app.eval.dataset import load_dataset
from app.eval.runner import run_evaluation
from app.eval.scorer import compare_metrics

_GOLDEN_DIR = Path(__file__).resolve().parent / "golden"
_DEFAULT_DATASET = _GOLDEN_DIR / "semiconductor.v1.json"


def main() -> int:
    parser = argparse.ArgumentParser(description="Agent Golden eval 실행기")
    parser.add_argument("--dataset", type=Path, default=_DEFAULT_DATASET)
    parser.add_argument("--profile", choices=("replay", "live"), default="replay")
    parser.add_argument("--plan", choices=("FREE", "PAID"), default="FREE")
    parser.add_argument("--compare", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    result = run_evaluation(
        load_dataset(args.dataset),
        profile=args.profile,
        plan=args.plan,
    )
    payload = result.to_dict()
    comparison: dict[str, object] | None = None
    if args.compare is not None:
        baseline = json.loads(args.compare.read_text(encoding="utf-8"))
        comparison = compare_metrics(result.metrics, baseline["metrics"])
        payload["comparison"] = comparison

    rendered = json.dumps(payload, ensure_ascii=False, indent=2)
    print(rendered)
    if args.output is not None:
        args.output.write_text(rendered + "\n", encoding="utf-8")

    has_regression = bool(comparison and comparison["regressions"])
    return 1 if result.errors or has_regression else 0


if __name__ == "__main__":
    raise SystemExit(main())
