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


def case_identities(case: dict) -> dict:
    request = case["request"]
    return {
        "report": [str(request["report"]["id"])],
        "article": sorted({str(finding["articleId"]) for finding in request["findings"]}),
        "source": sorted(
            {
                digest(text)
                for finding in request["findings"]
                for sentence in finding["sentences"]
                if (text := _normalized(sentence["text"]))
            }
        ),
        "event_family": sorted(
            {
                _normalized(label).casefold()
                for labels in case.get("event_families", {}).values()
                for label in labels
            }
        ),
    }


def validate_exposure_registry(registry: dict) -> None:
    require(
        registry.get("registryHash")
        == digest({key: value for key, value in registry.items() if key != "registryHash"}),
        "EXPOSURE_REGISTRY_CHANGED",
    )
    require(registry.get("schemaVersion") == 1, "UNKNOWN_EXPOSURE_REGISTRY")
    require(isinstance(registry.get("entries"), list), "INVALID_EXPOSURE_ENTRIES")
    for entry in registry.get("entries", []):
        require(
            bool(entry.get("reference"))
            and bool(entry.get("caseId"))
            and all(
                isinstance(entry.get(key), list)
                and bool(entry[key])
                and all(isinstance(value, str) and bool(value) for value in entry[key])
                for key in DIMENSIONS
            ),
            "INVALID_EXPOSURE_ENTRY",
        )


def validate_report_splits(cases: list[dict], *, exposure_registry=None) -> dict:
    """Validate Python-mode ReportCase dumps, preserving development-only workflows.

    A holdout requires declared families for every finding in *both* splits.
    Same-split repeats, audience controls and prefixes remain legitimate. Any
    shared identity across splits is rejected, including transitive overlaps.
    """
    require(bool(cases), "EMPTY_CASES")
    has_holdout = any(case["split"] == "holdout" for case in cases)
    owners = {dimension: {} for dimension in DIMENSIONS}
    if exposure_registry is not None:
        validate_exposure_registry(exposure_registry)
        for entry in exposure_registry["entries"]:
            for dimension in DIMENSIONS:
                for value in entry[dimension]:
                    owners[dimension][value] = ("development", entry["caseId"])
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
        for labels in families.values():
            require(
                bool(labels)
                and all(isinstance(label, str) and bool(_normalized(label)) for label in labels),
                "INVALID_EVENT_FAMILY_LABEL",
            )
            normalized = [_normalized(label).casefold() for label in labels]
            require(len(normalized) == len(set(normalized)), "DUPLICATE_EVENT_FAMILY_LABEL")
        keys = case_identities(case)
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
        "independenceScope": "supplied-cases-and-declared-development-history"
        if exposure_registry is not None
        else "supplied-cases-only",
        "exposureRegistryHash": exposure_registry["registryHash"] if exposure_registry else None,
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
