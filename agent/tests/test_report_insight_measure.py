import json
import logging
import warnings
from copy import deepcopy

import pytest

from app.eval import report_insight_measure as measure
from app.eval.report_insight_compare import render_comparison
from app.eval.report_insight_corpus import load_corpus
from app.eval.report_insight_run import EvaluationStopped


@pytest.fixture
def bundle():
    case = load_corpus().cases[0]
    output = {
        "insights": [
            {
                "audience": case.request.audiences[0],
                "headline": "실행 조건을 확인해야 한다.",
                "overview": [],
                "assessments": [
                    {
                        "findingId": finding.id,
                        "reason": "원문 범위에서 생산·공정 조건을 확인해야 한다.",
                        "basisClaimIds": [finding.claims[0].id],
                        "axes": {
                            "directness": 1,
                            "impact": None,
                            "urgency": None,
                            "novelty": None,
                        },
                    }
                    for finding in case.request.findings
                ],
                "implications": [],
                "watchItems": [],
            }
        ]
    }
    return {
        "schemaVersion": 1,
        "provenance": {"datasetPath": str(measure.DEFAULT_DATASET), "policySha256": "frozen"},
        "caseIds": [case.case_id],
        "attempts": [],
        "errors": [],
        "inFlight": None,
        "results": [
            {
                "caseId": case.case_id,
                "variant": variant,
                "request": case.request.model_dump(mode="json", by_alias=True),
                "status": "success",
                "response": deepcopy(output),
                "latencyMs": 1000,
                "attemptIds": [],
            }
            for variant in measure.VARIANTS
        ],
    }


def judgments_for(bundle, *, winner="tie"):
    _, key = render_comparison(bundle)
    return {
        "schemaVersion": 1,
        "manifestHash": key["manifestHash"],
        "ratings": [{"reviewId": key["cases"][0]["reviewId"], "winner": winner, "sides": {}}],
    }


def test_preference_only_is_counted_without_inventing_optional_scores(bundle):
    result = measure.aggregate_judgments(bundle, judgments_for(bundle))
    assert result["judgedCount"] == 1 and result["ties"] == 1
    assert result["eligiblePairs"] == 1 and result["unjudgedEligiblePairs"] == 0
    assert all(
        item["pairedScoreCount"] == 0 and item["stagedMean"] is None
        for item in result["optionalPairedCriteria"].values()
    )
    assert result["qualityImprovementClaimed"] is False and result["synthetic"] is True


def test_win_and_paired_scores_use_private_mapping_and_same_cases(bundle):
    _, key = render_comparison(bundle)
    sides = key["cases"][0]["sides"]
    staged_side = next(side for side, item in sides.items() if item["variant"] == "staged")
    rating = judgments_for(bundle, winner=staged_side)
    rating["ratings"][0]["sides"] = {
        side: {"scores": {"factual_integrity": 0 if item["variant"] == "single_call" else 3}}
        for side, item in sides.items()
    }
    result = measure.aggregate_judgments(bundle, rating)
    assert result["wins"] == {"single_call": 0, "staged": 1}
    assert result["cases"][0]["preference"] == "staged"
    assert result["optionalPairedCriteria"]["factual_integrity"]["stagedMinusSingleMean"] == 3
    assert result["factualIntegrityZeroCounts"]["single_call"] == 1


@pytest.mark.parametrize("mutation", ["hash", "duplicate", "bool_score", "unknown_id"])
def test_mismatched_or_edited_ratings_cannot_contaminate_summary(bundle, mutation):
    rating = judgments_for(bundle)
    if mutation == "hash":
        rating["manifestHash"] = "different-experiment"
    elif mutation == "duplicate":
        rating["ratings"].append(deepcopy(rating["ratings"][0]))
    elif mutation == "bool_score":
        rating["ratings"][0]["sides"] = {"A": {"scores": {"factual_integrity": True}}}
    else:
        rating["ratings"][0]["reviewId"] = "unknown"
    with pytest.raises(EvaluationStopped):
        measure.aggregate_judgments(bundle, rating)


def test_failed_pairs_and_unrated_scores_are_not_successes(bundle):
    bundle["results"][0].update(status="failed", response=None)
    _, key = render_comparison(bundle)
    result = measure.aggregate_judgments(
        bundle, {"schemaVersion": 1, "manifestHash": key["manifestHash"], "ratings": []}
    )
    assert result["excludedPairs"] == 1 and result["judgedCount"] == 0
    assert result["stagedWinShareOfJudged"] is None
    assert result["generation"]["single_call"]["failedResults"] == 1


def test_null_preference_in_partial_browser_export_remains_unjudged(bundle):
    rating = judgments_for(bundle, winner=None)
    result = measure.aggregate_judgments(bundle, rating)
    assert result["judgedCount"] == 0 and result["unjudgedEligiblePairs"] == 1


def test_launcher_uses_hidden_input_and_restores_environment_on_failure(monkeypatch, tmp_path):
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    state = {"results": [{"status": "pending"}], "errors": [], "inFlight": None}
    monkeypatch.setattr(measure, "prepare", lambda *args, **kwargs: None)
    monkeypatch.setattr(measure, "read_state", lambda *_: state)
    monkeypatch.setattr(measure, "export_artifacts", lambda *_: tmp_path / "review.html")
    monkeypatch.setattr(measure.sys.stdin, "isatty", lambda: True)
    monkeypatch.setattr(measure.getpass, "getpass", lambda *_: "test-only-placeholder")

    def failed_run(*args, **kwargs):
        assert measure.os.environ["OPENAI_API_KEY"] == "test-only-placeholder"
        raise EvaluationStopped("PROVIDER_ATTEMPT_FAILED")

    monkeypatch.setattr(measure, "run", failed_run)
    with pytest.raises(EvaluationStopped, match="PROVIDER_ATTEMPT_FAILED"):
        measure.measure(tmp_path)
    assert "OPENAI_API_KEY" not in measure.os.environ
    assert list(tmp_path.iterdir()) == []


def test_noninteractive_missing_key_does_not_prompt_or_call(monkeypatch, tmp_path):
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    monkeypatch.setattr(measure, "prepare", lambda *args, **kwargs: None)
    monkeypatch.setattr(
        measure,
        "read_state",
        lambda *_: {"results": [{"status": "pending"}], "errors": [], "inFlight": None},
    )
    monkeypatch.setattr(measure.sys.stdin, "isatty", lambda: False)
    monkeypatch.setattr(measure, "export_artifacts", lambda *_: tmp_path / "review.html")
    monkeypatch.setattr(measure.getpass, "getpass", lambda *_: pytest.fail("must not prompt"))
    monkeypatch.setattr(measure, "run", lambda *_: pytest.fail("must not call provider"))
    with pytest.raises(EvaluationStopped, match="KEY_INPUT_REQUIRES_TERMINAL"):
        measure.measure(tmp_path)


def test_getpass_echo_fallback_is_rejected_before_reading_key(monkeypatch, tmp_path):
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    monkeypatch.setattr(measure, "prepare", lambda *args, **kwargs: None)
    monkeypatch.setattr(
        measure,
        "read_state",
        lambda *_: {"results": [{"status": "pending"}], "errors": [], "inFlight": None},
    )
    monkeypatch.setattr(measure.sys.stdin, "isatty", lambda: True)
    monkeypatch.setattr(measure, "export_artifacts", lambda *_: tmp_path / "review.html")

    def fallback(*args, **kwargs):
        warnings.warn("would echo input", measure.getpass.GetPassWarning, stacklevel=1)
        pytest.fail("must not read key after fallback warning")

    monkeypatch.setattr(measure.getpass, "getpass", fallback)
    monkeypatch.setattr(measure, "run", lambda *_: pytest.fail("must not call provider"))
    with pytest.raises(EvaluationStopped, match="HIDDEN_KEY_INPUT_UNAVAILABLE"):
        measure.measure(tmp_path)
    assert "OPENAI_API_KEY" not in measure.os.environ


def test_prepare_only_exports_no_paid_calls(monkeypatch, tmp_path):
    monkeypatch.setattr(measure, "prepare", lambda *args, **kwargs: None)
    monkeypatch.setattr(measure, "read_state", lambda *_: {"results": [{"status": "pending"}]})
    monkeypatch.setattr(measure, "export_artifacts", lambda *_: tmp_path / "review.html")
    monkeypatch.setattr(measure, "summary", lambda *_: {"status": "prepared"})
    monkeypatch.setattr(measure, "run", lambda *_: pytest.fail("no paid calls in preparation"))
    result = measure.measure(tmp_path, prepare_only=True)
    assert result["status"] == "prepared" and result["requiresHumanReview"] is True


def test_cli_never_prints_untrusted_exception_content(monkeypatch, capsys):
    previous = logging.root.manager.disable
    monkeypatch.setattr(measure.sys, "argv", ["measure", "--prepare-only"])

    def crash(*args, **kwargs):
        raise RuntimeError("test-sensitive-placeholder")

    monkeypatch.setattr(measure, "measure", crash)
    with pytest.raises(SystemExit, match="2"):
        measure.main()
    output = capsys.readouterr().out
    assert "test-sensitive-placeholder" not in output
    assert json.loads(output)["code"] == "MEASUREMENT_INPUT_ERROR"
    assert logging.root.manager.disable == previous
