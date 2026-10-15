"""Append-only declared development exposure; no corpus text or live generation.

Declare any holdout inspected for tuning here before preparing the next run.
This records human declarations, not proof of unseen historical exposure.
"""

import argparse
import json
from copy import deepcopy
from pathlib import Path

from app.eval.report_insight_run import atomic_save, digest, require
from app.eval.report_insight_split import (
    case_identities,
    validate_exposure_registry,
    validate_report_splits,
)


def record_development_exposure(cases: list[dict], *, reference: str, previous=None) -> dict:
    require(isinstance(reference, str) and bool(reference.strip()), "EXPOSURE_REFERENCE_REQUIRED")
    # Holdout-mode validation requires explicit complete event labels. It does
    # not mean these exposed cases remain holdout: every entry excludes them.
    normalized = deepcopy(cases)
    for case in normalized:
        case["split"] = "holdout"
        case["event_families"] = {
            int(key): value for key, value in case.get("event_families", {}).items()
        }
    validate_report_splits(normalized)
    entries = []
    if previous is not None:
        validate_exposure_registry(previous)
        entries.extend(deepcopy(previous["entries"]))
    for case in normalized:
        entry = {"caseId": case["case_id"], "reference": reference, **case_identities(case)}
        if entry not in entries:
            entries.append(entry)
    body = {
        "schemaVersion": 1,
        "entries": entries,
        "scope": "declared-development-exposure; incomplete-history-cannot-prove-independence",
    }
    return {**body, "registryHash": digest(body)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", type=Path, required=True)
    parser.add_argument("--reference", required=True)
    parser.add_argument("--previous", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    require(not args.output.exists(), "EXPOSURE_OUTPUT_EXISTS")
    registry = record_development_exposure(
        json.loads(args.cases.read_text()),
        reference=args.reference,
        previous=json.loads(args.previous.read_text()) if args.previous else None,
    )
    atomic_save(args.output, registry)


if __name__ == "__main__":
    main()
