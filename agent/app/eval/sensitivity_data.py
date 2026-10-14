"""Offline, blind human-review inputs for sensitivity coefficient calibration.

Feature scores and human decisions deliberately live in separate files.  Existing
golden replay scores are useful for exercising the workflow; they are not human
labels and preparing or reviewing them does not turn them into production data.
"""

import csv
import hashlib
import json
import unicodedata
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from app.eval.sensitivity_scoring import AXES

FEATURE_VERSION = "sensitivity-features.v1"
LABEL_COLUMNS = ("caseId", "groupId", "label", "reviewer", "rationale")
LABELS = ("low", "medium", "high")
_CONTEXT_FIELDS = ("topic", "company", "companyContext", "context")
_CONTEXT_TEXT_FIELDS = {
    "name", "queryText", "description", "companyName", "industry", "businessDescription",
    "business", "products", "services", "customers", "competitors", "requiredKeywords",
    "optionalKeywords", "perspective", "audience",
}


@dataclass(frozen=True)
class LabeledCase:
    case_id: str
    group_id: str
    label: str
    reviewer: str
    rationale: str
    axes: dict[str, int | None]
    content_hash: str
    canonical_url: str = ""


def _object(value: Any, field: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError(f"{field} must be an object")
    return value


def _text(value: Any, field: str, *, allow_empty: bool = False) -> str:
    if not isinstance(value, str) or (not allow_empty and not value.strip()):
        raise ValueError(f"{field} must be a {'string' if allow_empty else 'nonblank string'}")
    return value


def _json_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def _invalid_constant(value: str) -> None:
    raise ValueError(f"Invalid JSON number: {value}")


def _read_json(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as source:
        return _object(json.load(
            source, object_pairs_hook=_json_pairs, parse_constant=_invalid_constant,
        ), "dataset")


def _axes(value: Any, case_id: str) -> dict[str, int | None]:
    values = _object(value, f"{case_id}.axes")
    if set(values) != set(AXES):
        raise ValueError(f"{case_id}.axes must contain exactly: {', '.join(AXES)}")
    for axis, score in values.items():
        if score is not None and (type(score) is not int or score not in range(4)):
            raise ValueError(f"{case_id}.{axis} score must be an integer from 0 to 3 or null")
    if all(score is None for score in values.values()):
        raise ValueError(f"{case_id}.axes cannot all be null")
    return {axis: values[axis] for axis in AXES}


def _context(value: Any, field: str) -> Any:
    """Keep article/company context, excluding model decisions and numeric scores."""
    if value is None or isinstance(value, str):
        return value
    if isinstance(value, list):
        return [_context(item, field) for item in value]
    if isinstance(value, dict):
        return {
            key: _context(item, f"{field}.{key}")
            for key, item in value.items()
            if key in _CONTEXT_TEXT_FIELDS
        }
    raise ValueError(f"{field} must contain contextual text, lists, or objects")


def _validate_features(data: dict[str, Any]) -> dict[str, Any]:
    required = {"version", "promptVersion", "cases"}
    allowed = required | {"sourceKind", "sourceVersion"}
    if not required <= data.keys() or data.keys() - allowed:
        raise ValueError("Feature dataset fields must be version, promptVersion, cases, "
                         "and optional sourceKind/sourceVersion")
    if data["version"] != FEATURE_VERSION:
        raise ValueError(f"Feature version must be {FEATURE_VERSION}")
    _text(data["promptVersion"], "promptVersion")
    if "sourceKind" in data and data["sourceKind"] not in ("golden-demo", "feature-export"):
        raise ValueError("sourceKind must be golden-demo or feature-export")
    if "sourceVersion" in data:
        _text(data["sourceVersion"], "sourceVersion")
    if not isinstance(data["cases"], list) or not data["cases"]:
        raise ValueError("cases must be a nonempty list")
    ids = set()
    cases = []
    required_case = {"caseId", "title", "text", "canonicalUrl", "axes"}
    for raw_case in data["cases"]:
        case = _object(raw_case, "case")
        if not required_case <= case.keys() or case.keys() - required_case - set(_CONTEXT_FIELDS):
            raise ValueError("Feature cases require caseId, title, text, canonicalUrl, axes "
                             "and only optional topic/company/companyContext/context")
        case_id = _text(case["caseId"], "caseId")
        if case_id != case_id.strip():
            raise ValueError("caseId cannot have leading or trailing whitespace")
        if case_id in ids:
            raise ValueError(f"Duplicate caseId: {case_id}")
        ids.add(case_id)
        clean = {
            "caseId": case_id,
            "title": _text(case["title"], f"{case_id}.title"),
            "text": _text(case["text"], f"{case_id}.text"),
            "canonicalUrl": _text(case["canonicalUrl"], f"{case_id}.canonicalUrl",
                                  allow_empty=True),
            "axes": _axes(case["axes"], case_id),
        }
        for field in _CONTEXT_FIELDS:
            if field in case:
                clean[field] = _context(case[field], f"{case_id}.{field}")
        cases.append(clean)
    return {**data, "cases": cases}


def _normalize_source(data: dict[str, Any]) -> dict[str, Any]:
    if data.get("version") == FEATURE_VERSION:
        features = _validate_features(data)
        features.setdefault("sourceKind", "feature-export")
        features.setdefault("sourceVersion", FEATURE_VERSION)
        return features
    version = _text(data.get("version"), "version")
    prompt_version = _text(data.get("baselinePromptVersion"), "baselinePromptVersion")
    if not isinstance(data.get("cases"), list) or not data["cases"]:
        raise ValueError("cases must be a nonempty list")
    cases = []
    for raw_case in data["cases"]:
        case = _object(raw_case, "case")
        case_id = _text(case.get("caseId"), "caseId")
        article = _object(case.get("article"), f"{case_id}.article")
        replay = _object(case.get("replay"), f"{case_id}.replay")
        classification = _object(replay.get("classification"), f"{case_id}.classification")
        sensitivity = _object(classification.get("sensitivity"), f"{case_id}.sensitivity")
        if set(sensitivity) != set(AXES):
            raise ValueError(f"{case_id}.sensitivity must contain exactly: {', '.join(AXES)}")
        axes = {}
        for axis in AXES:
            axis_value = _object(sensitivity[axis], f"{case_id}.{axis}")
            if "score" not in axis_value:
                raise ValueError(f"{case_id}.{axis} is missing score")
            axes[axis] = axis_value["score"]
        body = article.get("bodyText")
        summary = article.get("summary")
        for field, value in (("bodyText", body), ("summary", summary)):
            if value is not None:
                _text(value, f"{case_id}.{field}", allow_empty=True)
        text = body if isinstance(body, str) and body.strip() else summary
        normalized = {
            "caseId": case_id,
            "title": article.get("title"),
            "text": text,
            "canonicalUrl": article.get("canonicalUrl", ""),
            "axes": axes,
        }
        for field in _CONTEXT_FIELDS:
            if field in case:
                normalized[field] = case[field]
        cases.append(normalized)
    return _validate_features({
        "version": FEATURE_VERSION,
        "promptVersion": prompt_version,
        "sourceKind": "golden-demo",
        "sourceVersion": version,
        "cases": cases,
    })


def prepare_dataset(source: Path, output_dir: Path) -> dict[str, Any]:
    """Prepare feature and blind review files; never infer human labels or groups."""
    if output_dir.exists():
        raise ValueError(f"Output directory already exists; refusing to overwrite: {output_dir}")
    features = _normalize_source(_read_json(source))
    cases = features["cases"]
    output_dir.mkdir(parents=True, exist_ok=False)
    (output_dir / "features.json").write_text(
        json.dumps(features, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
    )
    with (output_dir / "labels.csv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=LABEL_COLUMNS)
        writer.writeheader()
        writer.writerows({"caseId": case["caseId"]} for case in cases)
    review_columns = ("caseId", "title", "text", "canonicalUrl", *_CONTEXT_FIELDS)
    with (output_dir / "review.csv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=review_columns)
        writer.writeheader()
        for case in cases:
            row = {key: case[key] for key in review_columns[:4]}
            for field in _CONTEXT_FIELDS:
                value = case.get(field)
                row[field] = (value if isinstance(value, str) else
                              json.dumps(value, ensure_ascii=False) if value is not None else "")
            writer.writerow(row)
    warnings = [
        "No human labels or event groups were inferred. Complete labels.csv before training.",
    ]
    if features["sourceKind"] == "golden-demo":
        warnings.append(
            "Golden/demo replay data is for workflow validation. Human review does not make "
            "this source production-ready; collect representative real articles.",
        )
    coverage = {}
    for axis in AXES:
        scores = [case["axes"][axis] for case in cases if case["axes"][axis] is not None]
        distinct = sorted(set(scores))
        coverage[axis] = {
            "availableCount": len(scores), "missingCount": len(cases) - len(scores),
            "distinctScores": distinct, "distinctScoreCount": len(distinct),
        }
        if len(distinct) < 2:
            warnings.append(f"{axis} has fewer than two distinct observed scores; "
                            "its weight cannot be learned reliably from this dataset.")
    return {
        "version": "sensitivity-preparation.v1", "caseCount": len(cases),
        "promptVersion": features["promptVersion"], "sourceKind": features["sourceKind"],
        "sourceVersion": features["sourceVersion"], "humanLabelsPresent": False,
        "humanLabelCount": 0, "axisCoverage": coverage, "warnings": warnings,
        "files": {name: str(output_dir / name)
                  for name in ("features.json", "labels.csv", "review.csv")},
    }


def _content_hash(text: str) -> str:
    normalized = " ".join(unicodedata.normalize("NFKC", text).casefold().split())
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


def load_labeled_cases(features_path: Path, labels_path: Path) -> tuple[LabeledCase, ...]:
    """Join independently reviewed labels with validated, immutable feature inputs."""
    features = _validate_features(_read_json(features_path))
    feature_ids = {case["caseId"] for case in features["cases"]}
    labels = {}
    with labels_path.open(encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle, strict=True)
        try:
            if (reader.fieldnames is None or len(reader.fieldnames) != len(LABEL_COLUMNS)
                    or set(reader.fieldnames) != set(LABEL_COLUMNS)):
                raise ValueError(f"Label CSV columns must be exactly: {', '.join(LABEL_COLUMNS)}")
            for line, row in enumerate(reader, start=2):
                if None in row or any(value is None for value in row.values()):
                    raise ValueError(f"Label CSV row {line} has the wrong number of fields")
                case_id = _text(row["caseId"], f"labels row {line} caseId")
                if case_id in labels:
                    raise ValueError(f"Duplicate label caseId: {case_id}")
                if case_id not in feature_ids:
                    raise ValueError(f"Unknown label caseId: {case_id}")
                for field in LABEL_COLUMNS[1:]:
                    _text(row[field], f"{case_id}.{field}")
                if row["label"] not in LABELS:
                    raise ValueError(f"{case_id}.label must be low, medium, or high")
                labels[case_id] = row
        except csv.Error as error:
            raise ValueError(f"Malformed label CSV: {error}") from error
    missing = feature_ids - labels.keys()
    if missing:
        raise ValueError(f"Missing labels for caseId: {', '.join(sorted(missing))}")
    return tuple(
        LabeledCase(
            case_id=case["caseId"], group_id=labels[case["caseId"]]["groupId"].strip(),
            label=labels[case["caseId"]]["label"],
            reviewer=labels[case["caseId"]]["reviewer"].strip(),
            rationale=labels[case["caseId"]]["rationale"].strip(), axes=case["axes"],
            content_hash=_content_hash(case["text"]),
            canonical_url=case["canonicalUrl"].strip().split("#", maxsplit=1)[0],
        ) for case in features["cases"]
    )


def feature_digest(features_path: Path) -> str:
    """Stable digest of validated features, including prompt and source provenance."""
    features = _validate_features(_read_json(features_path))
    canonical = json.dumps(features, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()
