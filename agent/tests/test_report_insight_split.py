"""Holdouts must not reuse report, article, source or declared event identities."""

from copy import deepcopy

import pytest
from test_report_insight_assessment import request

from app.eval import report_insight_operational as operational
from app.eval.report_insight_run import EvaluationStopped, digest
from app.eval.report_insight_split import validate_report_splits


def case(name, *, split="development", report_id=1, article_id=11, text="첫 사건 원문"):
    source = request(text=text).model_dump(mode="json", by_alias=True)
    source["report"]["id"] = report_id
    source["findings"][0]["articleId"] = article_id
    return {
        "case_id": name,
        "split": split,
        "request": source,
        "event_families": {101: [f"supplied-event-{name}"]},
    }


def holdout():
    return case("heldout", split="holdout", report_id=2, article_id=22, text="별도 사건 원문")


def check(cases):
    return validate_report_splits(
        [operational.ReportCase.model_validate(value).model_dump(by_alias=True) for value in cases]
    )


@pytest.mark.parametrize("dimension", ["report", "article", "source", "event_family"])
def test_cross_split_identity_overlap_is_rejected(dimension):
    development, heldout = case("development"), holdout()
    if dimension == "report":
        heldout["request"]["report"]["id"] = 1
    elif dimension == "article":
        heldout["request"]["findings"][0]["articleId"] = 11
    elif dimension == "source":
        # Whitespace changes do not make an already used source independent.
        heldout["request"]["findings"][0]["sentences"][0]["text"] = " 첫\n 사건  원문 "
    else:
        heldout["event_families"] = {101: [" SUPPLIED-EVENT-DEVELOPMENT "]}
    with pytest.raises(EvaluationStopped, match=f"HOLDOUT_SPLIT_LEAKAGE:{dimension}"):
        check([development, heldout])


def test_indirect_report_article_event_chain_cannot_cross_splits():
    first = case("first")
    # First and second share a report; second and third share an event.
    second = case("second", article_id=33, text="동일 보고서의 다른 기사 원문")
    third = holdout()
    third["event_families"] = deepcopy(second["event_families"])
    with pytest.raises(EvaluationStopped, match="HOLDOUT_SPLIT_LEAKAGE:event_family"):
        check([first, second, third])


@pytest.mark.parametrize("split_index", [0, 1])
@pytest.mark.parametrize("families", [{}, {999: ["another-event"]}, {101: []}, {101: [" "]}])
def test_every_split_requires_complete_nonblank_event_metadata(split_index, families):
    cases = [case("development"), holdout()]
    cases[split_index]["event_families"] = families
    with pytest.raises(EvaluationStopped, match="EVENT_FAMIL"):
        check(cases)


def test_partial_finding_metadata_and_duplicate_family_names_are_rejected():
    heldout = holdout()
    extra = deepcopy(heldout["request"]["findings"][0])
    extra.update(id=102, articleId=23)
    extra["claims"][0]["id"] = "102:0"
    heldout["request"]["findings"].append(extra)
    with pytest.raises(EvaluationStopped, match="HOLDOUT_EVENT_FAMILIES_REQUIRED"):
        check([heldout])
    heldout["event_families"][102] = ["event", " EVENT "]
    with pytest.raises(EvaluationStopped, match="DUPLICATE_EVENT_FAMILY_LABEL"):
        check([heldout])


def test_same_split_audience_controls_and_source_prefixes_are_allowed():
    original = case("development")
    control = deepcopy(original)
    control.update(case_id="no-review", review_policy="disabled")
    control["request"]["audiences"] = ["MARKET_INVESTOR"]
    larger = deepcopy(original)
    larger["case_id"] = "two-findings"
    extra = deepcopy(larger["request"]["findings"][0])
    extra.update(id=102, articleId=12)
    extra["claims"][0]["id"] = "102:0"
    larger["request"]["findings"].append(extra)
    larger["event_families"][102] = ["supplied-second-event"]
    values = [original, control, larger, holdout()]
    before = deepcopy(values)
    result = check(values)
    assert values == before
    assert result["caseCountsBySplit"] == {"development": 3, "holdout": 1}
    assert result["eventFamiliesComplete"]
    assert result["checkedDimensions"] == ["report", "article", "source", "event_family"]
    assert result["independenceScope"] == "supplied-cases-only"


def test_development_only_cases_still_work_without_family_labels():
    value = case("development")
    value.pop("event_families")
    result = check([value])
    assert not result["holdoutRequested"]
    assert not result["eventFamiliesComplete"]
    assert result["checkedDimensions"] == []


def test_split_fingerprint_is_order_independent_and_changes_with_evidence_or_metadata():
    values = [case("development"), holdout()]
    original = check(values)
    assert check(list(reversed(values))) == original
    assert original["splitHash"] == digest(
        {key: value for key, value in original.items() if key != "splitHash"}
    )
    changed = deepcopy(values)
    changed[1]["event_families"][101].append("another-supplied-family")
    assert check(changed)["splitHash"] != original["splitHash"]
    changed = deepcopy(values)
    changed[1]["request"]["findings"][0]["sentences"][0]["text"] = "새로운 원문"
    assert check(changed)["splitHash"] != original["splitHash"]
    assert "첫 사건 원문" not in str(original)


def test_prepare_rejects_leakage_before_writing_or_scheduling(tmp_path, monkeypatch):
    # Validation must happen before runtime probing or model execution.
    monkeypatch.setattr(
        operational, "runtime_hashes", lambda: pytest.fail("leaking cases reached runtime probe")
    )
    development, heldout = case("development"), holdout()
    heldout["request"]["findings"][0]["articleId"] = 11
    policy = {
        "model": "offline-test",
        "prices_usd_per_million": ["1", "1", "1"],
        "max_calls": 1,
        "max_estimated_usd": "1",
    }
    with pytest.raises(EvaluationStopped, match="HOLDOUT_SPLIT_LEAKAGE:article"):
        operational.prepare([development, heldout], policy, tmp_path)
    assert not (tmp_path / "manifest.json").exists()
    assert not (tmp_path / "result.json").exists()


def test_prepare_records_split_identity_without_adding_labels_to_request(tmp_path, monkeypatch):
    monkeypatch.setattr(operational, "runtime_hashes", lambda: {"test-runtime": "frozen"})
    monkeypatch.setattr(operational, "dependencies", lambda: {})
    values = [case("development"), holdout()]
    # External JSON uses string finding IDs, which ReportCase validates before checking.
    values[1]["event_families"] = {"101": ["explicit-reviewed-event"]}
    policy = {
        "model": "offline-test",
        "prices_usd_per_million": ["1", "1", "1"],
        "max_calls": 1,
        "max_estimated_usd": "1",
    }
    manifest = operational.prepare(values, policy, tmp_path)
    assert manifest["splitValidation"] == check(values)
    assert manifest["cases"][1]["event_families"] == {"101": ["explicit-reviewed-event"]}
    assert all("event_families" not in value["request"] for value in manifest["cases"])
    # The existing frozen manifest protects the declared split metadata on resume.
    _, _, checked_policy = operational.check(tmp_path)
    assert checked_policy.model == "offline-test"
