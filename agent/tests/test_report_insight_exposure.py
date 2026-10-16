from copy import deepcopy

import pytest
from test_report_insight_split import case, holdout

from app.eval.report_insight_exposure import record_development_exposure
from app.eval.report_insight_run import EvaluationStopped
from app.eval.report_insight_split import validate_report_splits


@pytest.mark.parametrize("dimension", ["report", "article", "source", "event_family"])
def test_historical_development_cannot_be_promoted_in_a_later_run(dimension):
    old = case("old-development")
    registry = record_development_exposure([old], reference="development-tuning-1")
    new = holdout()
    if dimension == "report":
        new["request"]["report"]["id"] = old["request"]["report"]["id"]
    elif dimension == "article":
        new["request"]["findings"][0]["articleId"] = 11
    elif dimension == "source":
        new["request"]["findings"][0]["sentences"][0]["text"] = " 첫\n사건 원문 "
    else:
        new["event_families"] = deepcopy(old["event_families"])
    with pytest.raises(EvaluationStopped, match=f"HOLDOUT_SPLIT_LEAKAGE:{dimension}"):
        validate_report_splits([new], exposure_registry=registry)
    assert "첫 사건 원문" not in str(registry)


def test_inspected_holdout_is_excluded_without_scheduling_old_cases():
    old = case("dev")
    registry = record_development_exposure([old], reference="frozen-development")
    held = holdout()
    assert validate_report_splits([held], exposure_registry=registry)["caseCountsBySplit"] == {
        "development": 0,
        "holdout": 1,
    }
    expanded = record_development_exposure([held], reference="used-for-tuning", previous=registry)
    assert len(registry["entries"]) == 1
    assert len(expanded["entries"]) == 2
    with pytest.raises(EvaluationStopped, match="HOLDOUT_SPLIT_LEAKAGE"):
        validate_report_splits([held], exposure_registry=expanded)
    held["split"] = "development"
    validate_report_splits([held], exposure_registry=expanded)


def test_missing_family_or_modified_registry_cannot_claim_independence():
    value = case("dev")
    value["event_families"] = {}
    with pytest.raises(EvaluationStopped, match="HOLDOUT_EVENT_FAMILIES_REQUIRED"):
        record_development_exposure([value], reference="not-invented")
    registry = record_development_exposure([case("dev")], reference="observed")
    registry["entries"].clear()
    with pytest.raises(EvaluationStopped, match="EXPOSURE_REGISTRY_CHANGED"):
        validate_report_splits([holdout()], exposure_registry=registry)
