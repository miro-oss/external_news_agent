"""Check declared evaluation splits before any report generation is scheduled.

Event families must be supplied by the corpus author. Report IDs alone do not
make a holdout independent: articles and source text can recur in other reports.
These checks cover the supplied cases, not unseen production data or label truth.
"""

from __future__ import annotations

import unicodedata

from app.eval.report_insight_run import digest, require

DIMENSIONS = ("report", "article", "source", "event_family")


def _normalized(value: str) -> str:
    return " ".join(unicodedata.normalize("NFKC", value).split())


def validate_report_splits(cases: list[dict]) -> dict:
    """Validate Python-mode ReportCase dumps, preserving development-only workflows.

    A holdout requires declared families for every finding in *both* splits.
    Same-split repeats, audience controls and prefixes remain legitimate. Any
    shared identity across splits is rejected, including transitive overlaps.
    """
    require(bool(cases), "EMPTY_CASES")
    has_holdout = any(case["split"] == "holdout" for case in cases)
    owners = {dimension: {} for dimension in DIMENSIONS}
    identities = []
    for case in cases:
        case_id, split, request = case["case_id"], case["split"], case["request"]
        require(split in {"development", "holdout"}, "UNKNOWN_EVALUATION_SPLIT")
        findings = request["findings"]
        finding_ids = {finding["id"] for finding in findings}
        # JSON object keys are strings; ReportCase normalizes them to integers.
        families = case.get("event_families", {})
        require(
            set(families) == finding_ids if has_holdout or families else True,
            "HOLDOUT_EVENT_FAMILIES_REQUIRED" if has_holdout else "INVALID_EVENT_FAMILY_FINDINGS",
        )
        events = set()
        for labels in families.values():
            require(
                bool(labels)
                and all(isinstance(label, str) and bool(_normalized(label)) for label in labels),
                "INVALID_EVENT_FAMILY_LABEL",
            )
            normalized = [_normalized(label).casefold() for label in labels]
            require(len(normalized) == len(set(normalized)), "DUPLICATE_EVENT_FAMILY_LABEL")
            events.update(normalized)
        keys = {
            "report": {str(request["report"]["id"])},
            "article": {str(finding["articleId"]) for finding in findings},
            "source": {
                digest(text)
                for finding in findings
                for sentence in finding["sentences"]
                if (text := _normalized(sentence["text"]))
            },
            "event_family": events,
        }
        for dimension, values in keys.items():
            for value in values:
                previous = owners[dimension].get(value)
                require(
                    previous is None or previous[0] == split,
                    f"HOLDOUT_SPLIT_LEAKAGE:{dimension}:"
                    f"{previous[1] if previous else ''}:{case_id}",
                )
                owners[dimension].setdefault(value, (split, case_id))
        identities.append(
            {
                "caseId": case_id,
                "split": split,
                **{key: sorted(values) for key, values in keys.items()},
            }
        )
    result = {
        "schemaVersion": 1,
        "holdoutRequested": has_holdout,
        "independenceScope": "supplied-cases-only",
        "eventFamiliesComplete": all(
            set(case.get("event_families", {}))
            == {finding["id"] for finding in case["request"]["findings"]}
            for case in cases
        ),
        "caseCountsBySplit": {
            split: sum(case["split"] == split for case in cases)
            for split in ("development", "holdout")
        },
        "checkedDimensions": list(DIMENSIONS) if has_holdout else [],
        "identityHash": digest(sorted(identities, key=lambda row: row["caseId"])),
    }
    return {**result, "splitHash": digest(result)}
