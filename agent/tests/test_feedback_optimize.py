import json
from copy import deepcopy
from decimal import Decimal

import pytest

from app.core.config import Settings
from app.eval.feedback_optimize import (
    Budget,
    BudgetExceeded,
    BudgetProvider,
    main,
    optimize,
    preflight,
    validate_dataset,
)
from app.llm.base import ProviderResponse, ProviderUsage


def raw_dataset():
    cases = []
    for split in ("train", "validation", "test"):
        for index, label in enumerate(("RELEVANT", "IRRELEVANT")):
            number = len(cases) + 1
            cases.append(
                {
                    "caseId": f"{split}-{index}",
                    "groupId": f"event-{number}",
                    "split": split,
                    "labelSource": "HUMAN_CONFIRMED",
                    "goldLabel": label,
                    "humanExplanation": "기사의 핵심 사건과 주제 연결을 검토했습니다.",
                    "request": {
                        "idempotencyKey": f"case-{number}",
                        "plan": "FREE",
                        "topic": {"id": 1, "name": "반도체"},
                        "articles": [
                            {
                                "articleId": number,
                                "title": f"{split} 기사 {number}",
                                "summary": None,
                                "bodyText": f"{split} {number}: "
                                + (
                                    "반도체 공장 증설을 발표했다."
                                    if index == 0
                                    else "식품회사가 새로운 과자를 출시했다."
                                ),
                            }
                        ],
                    },
                }
            )
    return {"schemaVersion": 1, "cases": cases, "nextAfterId": None, "hasNext": False}


@pytest.mark.parametrize(
    "change",
    [
        "feedback",
        "missing_label",
        "missing_split",
        "no_explanation",
        "group_leak",
        "document_leak",
        "one_label",
        "pagination",
    ],
)
def test_rejects_unconfirmed_or_leaking_datasets(change):
    raw = raw_dataset()
    first = raw["cases"][0]
    if change == "feedback":
        first["labelSource"] = "USER_FEEDBACK"
    elif change == "missing_label":
        first["goldLabel"] = None
    elif change == "missing_split":
        first["split"] = None
    elif change == "no_explanation":
        first["humanExplanation"] = ""
    elif change == "group_leak":
        raw["cases"][2]["groupId"] = first["groupId"]
    elif change == "document_leak":
        raw["cases"][2]["request"]["articles"] = deepcopy(first["request"]["articles"])
    elif change == "one_label":
        first["goldLabel"] = "IRRELEVANT"
    else:
        raw["hasNext"] = True
    with pytest.raises(ValueError):
        validate_dataset(raw)


def test_preflight_is_deterministic_and_never_calls_a_provider(tmp_path, capsys):
    raw = raw_dataset()
    dataset = validate_dataset(raw)
    assert preflight(dataset) == preflight(dataset)
    path = tmp_path / "dataset.json"
    path.write_text(json.dumps(raw))
    assert main(["preflight", "--dataset", str(path)]) == 0
    assert json.loads(capsys.readouterr().out)["automaticPromotion"] is False
    assert main(["optimize", "--dataset", str(path)]) == 2


class FakeProvider:
    """A deterministic world used only to exercise the optimizer orchestration."""

    def __init__(self):
        self.reflections = []
        self.calls = 0

    def generate(self, **kwargs):
        self.calls += 1
        schema = kwargs["response_schema"]
        if schema["title"] == "Guidance":
            self.reflections.append(kwargs["prompt"])
            value = {"instruction": "기사의 핵심 사건과 주제의 직접적인 연결을 확인한다."}
        else:
            key = next(iter(schema["properties"]["decisions"]["properties"]))
            number = int(key.removeprefix("article_"))
            # The unoptimized simulated model has a false-positive failure.
            relevant = number % 2 == 1 or "추가 판정 지침" not in kwargs["system_instruction"]
            quotes = schema["properties"]["decisions"]["properties"][key]["properties"][
                "sourceAssessment"
            ]["properties"]
            value = {
                "decisions": {
                    key: {
                        "sourceAssessment": dict.fromkeys(quotes, "D" if relevant else "N"),
                        "status": "RELEVANT" if relevant else "IRRELEVANT",
                        "reason": "테스트용 판정",
                        "evidenceQuotes": [next(iter(quotes))],
                    }
                }
            }
        return ProviderResponse(
            text=json.dumps(value, ensure_ascii=False),
            provider="openai",
            model="fake-test",
            usage=ProviderUsage(cost_usd=Decimal("0.001")),
        )


def test_actual_gepa_runs_with_fake_provider_and_never_sees_heldout():
    pytest.importorskip("gepa")
    provider = FakeProvider()
    report = optimize(
        validate_dataset(raw_dataset()),
        settings=Settings(AGENT_MOCK=False),
        provider=provider,
        max_calls=40,
        max_cost_usd=Decimal("1"),
        max_proposals=2,
    )
    assert report["gepaVersion"] == "0.1.4"
    assert report["automaticPromotion"] is False
    assert report["eligibleForReview"] is True
    assert report["comparisons"]["test"]["candidate"]["irrelevantPassed"] == 0
    assert report["comparisons"]["test"]["baseline"]["irrelevantPassed"] == 1
    assert provider.reflections
    assert all("test 기사" not in prompt for prompt in provider.reflections)
    assert report["providerCalls"] == provider.calls <= 40


def test_budget_stops_subsequent_requests_and_unknown_failure():
    provider = FakeProvider()
    budget = Budget(1, Decimal("1"))
    guarded = BudgetProvider(provider, budget)
    guarded.generate(response_schema={"title": "Guidance"}, prompt="test")
    with pytest.raises(BudgetExceeded):
        guarded.generate(response_schema={"title": "Guidance"}, prompt="test")
    assert provider.calls == 1

    class Broken:
        def generate(self, **kwargs):
            raise RuntimeError("do not expose provider credentials")

    with pytest.raises(BudgetExceeded, match="COST_UNKNOWN"):
        BudgetProvider(Broken(), Budget(2, Decimal("1"))).generate()


def test_mock_model_cannot_claim_improvement():
    with pytest.raises(ValueError, match="MOCK"):
        optimize(
            validate_dataset(raw_dataset()),
            settings=Settings(AGENT_MOCK=True),
            provider=FakeProvider(),
            max_calls=40,
            max_cost_usd=Decimal("1"),
        )
