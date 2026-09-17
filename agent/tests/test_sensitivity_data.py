import csv
import json
from copy import deepcopy
from pathlib import Path

import pytest

from app.eval.sensitivity_data import (
    AXES,
    LABEL_COLUMNS,
    feature_digest,
    load_labeled_cases,
    prepare_dataset,
)


def _features():
    return {
        "version": "sensitivity-features.v1", "promptVersion": "sensitivity.ko.v2",
        "cases": [
            {
                "caseId": f"article-{index}", "title": f"기사 {index}",
                "text": f"반도체 고객 공급 계약 본문 {index}",
                "canonicalUrl": f"https://news.example/{index}",
                "axes": dict(zip(AXES, scores, strict=True)),
            }
            for index, scores in enumerate(((3, None, 2, 0), (0, 1, 0, 1)), start=1)
        ],
    }


def _write_json(tmp_path, data, name="source.json"):
    path = tmp_path / name
    path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    return path


def _read_csv(path):
    with path.open(encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle))


def _write_labels(tmp_path, *, rows=None, fields=LABEL_COLUMNS):
    if rows is None:
        rows = [
            {"caseId": f"article-{index}", "groupId": f"event-{index}", "label": label,
             "reviewer": "김담당", "rationale": "고객 매출과 직접 관련된 기사"}
            for index, label in enumerate(("high", "low"), start=1)
        ]
    path = tmp_path / "labels.csv"
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)
    return path


def test_preparation_separates_blind_human_review_and_scores(tmp_path):
    data = _features()
    data["cases"][0]["topic"] = {"name": "HBM", "queryText": "HBM 공급"}
    data["cases"][0]["companyContext"] = {
        "name": "검토 기업", "products": ["식각 장비"],
        "label": "high", "sensitivity": {"score": 95},
    }
    source = _write_json(tmp_path, data)
    original = source.read_bytes()
    output = tmp_path / "review"
    report = prepare_dataset(source, output)
    assert source.read_bytes() == original
    assert set(path.name for path in output.iterdir()) == {
        "features.json", "labels.csv", "review.csv",
    }
    assert report["caseCount"] == 2
    assert report["sourceKind"] == "feature-export"
    assert report["humanLabelsPresent"] is False
    assert report["humanLabelCount"] == 0
    assert report["axisCoverage"]["dealSignal"] == {
        "availableCount": 1, "missingCount": 1, "distinctScores": [1], "distinctScoreCount": 1,
    }
    assert _read_csv(output / "labels.csv") == [
        {"caseId": f"article-{index}", "groupId": "", "label": "", "reviewer": "", "rationale": ""}
        for index in (1, 2)
    ]
    review = _read_csv(output / "review.csv")
    assert review[0]["text"] == data["cases"][0]["text"]
    assert json.loads(review[0]["topic"]) == {"name": "HBM", "queryText": "HBM 공급"}
    assert json.loads(review[0]["companyContext"]) == {
        "name": "검토 기업", "products": ["식각 장비"],
    }
    blind_text = (output / "review.csv").read_text(encoding="utf-8")
    assert all(axis not in blind_text for axis in AXES)
    assert "label" not in blind_text
    assert "score" not in blind_text
    prepared = json.loads((output / "features.json").read_text(encoding="utf-8"))
    assert prepared["cases"][0]["axes"] == data["cases"][0]["axes"]
    with pytest.raises(ValueError, match="groupId"):
        load_labeled_cases(output / "features.json", output / "labels.csv")


def test_repository_golden_data_keeps_demo_warning_and_missing_axis(tmp_path):
    source = Path(__file__).resolve().parents[1] / "app/eval/golden/semiconductor.v1.json"
    report = prepare_dataset(source, tmp_path / "review")
    assert report["caseCount"] == 24
    assert report["sourceKind"] == "golden-demo"
    assert report["sourceVersion"] == "semiconductor.v1"
    assert report["axisCoverage"]["dealSignal"]["availableCount"] == 0
    assert any("production-ready" in warning for warning in report["warnings"])
    prepared = json.loads((tmp_path / "review/features.json").read_text(encoding="utf-8"))
    assert prepared["sourceKind"] == "golden-demo"
    report_again = prepare_dataset(tmp_path / "review/features.json", tmp_path / "again")
    assert report_again["sourceKind"] == "golden-demo"
    assert report_again["sourceVersion"] == "semiconductor.v1"
    assert "classification" not in (tmp_path / "review/review.csv").read_text(encoding="utf-8")


def test_golden_uses_article_summary_only_when_body_unavailable(tmp_path):
    source = {
        "version": "golden-test.v1", "baselinePromptVersion": "test.v1", "cases": [{
            "caseId": "summary-case", "article": {
                "title": "원본 제목", "summary": "원본 요약", "bodyText": None,
            }, "replay": {"summaryKo": "모델 추론 요약은 사용하지 않음", "classification": {
                "sensitivity": {axis: {"score": 1} for axis in AXES},
            }},
        }],
    }
    prepare_dataset(_write_json(tmp_path, source), tmp_path / "review")
    assert _read_csv(tmp_path / "review/review.csv")[0]["text"] == "원본 요약"
    source["cases"][0]["article"]["summary"] = None
    with pytest.raises(ValueError, match="text"):
        prepare_dataset(_write_json(tmp_path, source), tmp_path / "invalid")
    assert not (tmp_path / "invalid").exists()


@pytest.mark.parametrize("mutation", [
    lambda data: data.update(promptVersion=" "),
    lambda data: data.update(cases=[]),
    lambda data: data.update(unexpected="extra"),
    lambda data: data.update(sourceKind="production"),
    lambda data: data.update(sourceVersion=""),
    lambda data: data["cases"].append(deepcopy(data["cases"][0])),
    lambda data: data["cases"][0].update(caseId=" padded "),
    lambda data: data["cases"][0].update(title=" "),
    lambda data: data["cases"][0].update(text=""),
    lambda data: data["cases"][0].update(canonicalUrl=123),
    lambda data: data["cases"][0].update(label="high"),
    lambda data: data["cases"][0].pop("canonicalUrl"),
    lambda data: data["cases"][0]["axes"].pop("dealSignal"),
    lambda data: data["cases"][0]["axes"].update(unknown=1),
    lambda data: data["cases"][0]["axes"].update(customerMove=True),
    lambda data: data["cases"][0]["axes"].update(customerMove=1.0),
    lambda data: data["cases"][0]["axes"].update(customerMove="1"),
    lambda data: data["cases"][0]["axes"].update(customerMove=4),
    lambda data: data["cases"][0]["axes"].update(customerMove=-1),
    lambda data: data["cases"][0].update(axes=dict.fromkeys(AXES)),
])
def test_rejects_invalid_features_before_creating_output(tmp_path, mutation):
    data = _features()
    mutation(data)
    with pytest.raises(ValueError):
        prepare_dataset(_write_json(tmp_path, data), tmp_path / "review")
    assert not (tmp_path / "review").exists()


@pytest.mark.parametrize("contents", [
    '{"version":"one","version":"two"}',
    '{"version": NaN}',
    '[]',
])
def test_rejects_ambiguous_json(tmp_path, contents):
    source = tmp_path / "source.json"
    source.write_text(contents, encoding="utf-8")
    with pytest.raises(ValueError):
        prepare_dataset(source, tmp_path / "review")


def test_preparation_never_clobbers_existing_reviews(tmp_path):
    source = _write_json(tmp_path, _features())
    output = tmp_path / "review"
    prepare_dataset(source, output)
    labels = output / "labels.csv"
    labels.write_text("valuable completed human labels", encoding="utf-8")
    with pytest.raises(ValueError, match="refusing to overwrite"):
        prepare_dataset(source, output)
    assert labels.read_text(encoding="utf-8") == "valuable completed human labels"


def test_loads_reviewed_labels_in_feature_order_and_hashes_body_not_title(tmp_path):
    data = _features()
    data["cases"][0]["text"] = "ＡＣＭＥ　announced\n  ＦＡＢ"
    data["cases"][1]["text"] = "acme announced fab"
    source = _write_json(tmp_path, data)
    labels = _write_labels(tmp_path)
    rows = _read_csv(labels)
    _write_labels(tmp_path, rows=list(reversed(rows)))
    cases = load_labeled_cases(source, labels)
    assert [case.case_id for case in cases] == ["article-1", "article-2"]
    assert cases[0].group_id == "event-1"
    assert cases[0].label == "high"
    assert cases[0].reviewer == "김담당"
    assert cases[0].axes["dealSignal"] is None
    assert cases[0].content_hash == cases[1].content_hash
    assert len(cases[0].content_hash) == 64


def test_preserves_canonical_article_identity_without_fragments_or_whitespace(tmp_path):
    data = _features()
    data["cases"][0]["canonicalUrl"] = "  https://news.example/article?id=7#section-a  "
    data["cases"][1]["canonicalUrl"] = "https://news.example/article?id=7#section-b"
    cases = load_labeled_cases(_write_json(tmp_path, data), _write_labels(tmp_path))
    assert cases[0].canonical_url == "https://news.example/article?id=7"
    assert cases[0].canonical_url == cases[1].canonical_url
    data["cases"][0]["canonicalUrl"] = ""
    cases = load_labeled_cases(_write_json(tmp_path, data), _write_labels(tmp_path))
    assert cases[0].canonical_url == ""


@pytest.mark.parametrize("field", ["groupId", "label", "reviewer", "rationale"])
def test_requires_every_human_field(tmp_path, field):
    source = _write_json(tmp_path, _features())
    labels = _write_labels(tmp_path)
    rows = _read_csv(labels)
    rows[0][field] = "  "
    _write_labels(tmp_path, rows=rows)
    with pytest.raises(ValueError, match=field):
        load_labeled_cases(source, labels)


@pytest.mark.parametrize("change,error", [
    ("missing", "Missing labels"), ("extra", "Unknown label"),
    ("duplicate", "Duplicate label"), ("invalid-label", "low, medium, or high"),
])
def test_requires_exactly_one_valid_label_per_feature(tmp_path, change, error):
    source = _write_json(tmp_path, _features())
    labels = _write_labels(tmp_path)
    rows = _read_csv(labels)
    if change == "missing":
        rows.pop()
    elif change == "extra":
        rows.append({**rows[0], "caseId": "unknown"})
    elif change == "duplicate":
        rows.append(rows[0])
    else:
        rows[0]["label"] = "HIGH"
    _write_labels(tmp_path, rows=rows)
    with pytest.raises(ValueError, match=error):
        load_labeled_cases(source, labels)


@pytest.mark.parametrize("contents", [
    "caseId,groupId,label,reviewer\n",
    "caseId,groupId,label,reviewer,rationale,extra\n",
    "caseId,groupId,label,reviewer,reviewer\n",
    "caseId,groupId,label,reviewer,rationale\narticle-1,event,high,person,why,extra\n",
    "caseId,groupId,label,reviewer,rationale\narticle-1,event,high\n",
    'caseId,groupId,label,reviewer,rationale\narticle-1,event,high,person,"unterminated',
    '"unterminated header',
    "",
])
def test_rejects_malformed_label_csv(tmp_path, contents):
    source = _write_json(tmp_path, _features())
    labels = tmp_path / "labels.csv"
    labels.write_text(contents, encoding="utf-8")
    with pytest.raises(ValueError):
        load_labeled_cases(source, labels)


def test_allows_reordered_columns_and_utf8_bom(tmp_path):
    source = _write_json(tmp_path, _features())
    labels = _write_labels(tmp_path, fields=tuple(reversed(LABEL_COLUMNS)))
    contents = labels.read_text(encoding="utf-8")
    labels.write_text("\ufeff" + contents, encoding="utf-8")
    assert len(load_labeled_cases(source, labels)) == 2


def test_feature_digest_ignores_json_format_but_tracks_prompt_scores_and_article(tmp_path):
    data = _features()
    source = _write_json(tmp_path, data)
    original_digest = feature_digest(source)
    source.write_text(json.dumps(data, sort_keys=True, indent=2), encoding="utf-8")
    assert feature_digest(source) == original_digest
    for field, value in (("promptVersion", "changed.v2"), ("cases", data["cases"][:1])):
        changed = deepcopy(data)
        changed[field] = value
        _write_json(tmp_path, changed)
        assert feature_digest(source) != original_digest
    data["cases"][0]["axes"]["dealSignal"] = 3
    _write_json(tmp_path, data)
    assert feature_digest(source) != original_digest
