"""Source-scoped falsification polarity checks for a named factory expansion."""

import pytest
from test_report_insight_assessment import request as pipeline_request
from test_report_insight_reduce_repair import diagnostic
from test_report_insight_v4_pipeline import V4Provider, generate, stages

from app.core.errors import OutputValidationError, StructuredOutputExhaustedError
from tests.test_report_insight_synthesis_quality import insight, source_request, validate

SOURCE = "가람전자 다낭이 반도체 기판으로 생산 영역을 넓힌다."
PROPOSITION = "가람전자의 다낭 공장 증설은 반도체 기판 생산 능력 향상에 기여할 수 있다."
MECHANISM = "가람전자가 다낭에서 반도체 기판 생산을 확대하면 생산 능력 향상이 기대된다."


def expansion_insight(falsifier, *, text=PROPOSITION, mechanism=MECHANISM, refs=None):
    return insight(
        implication={
            "text": text,
            "mechanism": mechanism,
            "assumption": "해당 생산 확대 계획이 집행되는 경우",
            "falsifiedBy": falsifier,
        },
        refs=refs,
    )


def expansion_request(source=SOURCE):
    return source_request(claim=source, sentences=[source])


@pytest.mark.parametrize(
    "falsifier",
    [
        "가람전자가 다낭에서 반도체 기판 생산 확대 계획을 철회하거나 연기하는 사건이 "
        "발생하지 않았다.",
        "가람전자의 다낭 공장 증설 계획이 철회되지 않는 경우",
        "가람전자가 다낭 공장 증설 계획을 연기하지 않았다.",
        "가람전자의 다낭 공장 증설 계획에 취소나 지연이 없는 경우",
        "가람전자의 다낭 공장 증설 계획이 철회된 적이 없는 경우",
        "가람전자의 다낭 공장 증설 계획이 연기된 사례가 없는 경우",
    ],
)
def test_absence_of_cancellation_does_not_refute_the_same_expansion(falsifier):
    with pytest.raises(OutputValidationError) as caught:
        validate(expansion_insight(falsifier), expansion_request())

    assert caught.value.error_kinds == ("report_falsification_direction",)
    assert "implications[0].falsifiedBy" in str(caught.value)
    assert "발생하지 않았다는 관측은 증설 효과를 반증하지 않습니다" in str(caught.value)


def test_an_internal_particle_syllable_does_not_truncate_the_company_name():
    source = SOURCE.replace("가람전자", "가람이노텍")
    node = expansion_insight(
        "가람이노텍이 다낭에서 반도체 기판 생산 확대 계획을 철회하거나 연기하는 "
        "사건이 발생하지 않았다.",
        text=PROPOSITION.replace("가람전자", "가람이노텍"),
        mechanism=MECHANISM.replace("가람전자", "가람이노텍"),
    )
    with pytest.raises(OutputValidationError) as caught:
        validate(node, expansion_request(source))
    assert caught.value.error_kinds == ("report_falsification_direction",)


@pytest.mark.parametrize(
    "falsifier",
    [
        "가람전자가 다낭에서 반도체 기판 생산 확대 계획을 철회하거나 연기하는 사건이 발생하는 경우",
        "가람전자의 다낭 공장 증설 계획이 철회되는 경우",
        "가람전자가 다낭 공장 증설 계획을 연기하는 경우",
        "가람전자가 다낭 공장 증설 이후에도 생산 능력이 향상되지 않는 경우",
        "가람전자가 다낭 공장 증설 계획을 철회하지 않았지만 생산 능력이 늘어나지 않는 경우",
        "가람전자의 다낭 공장 증설 계획이 철회되지 않은 것은 아니다.",
        "가람전자의 다낭 공장 증설 계획에 지연이 없었던 것은 아니다.",
        "가람전자가 다낭 공장 증설 계획을 철회하지 않았다는 주장이 철회되는 경우",
        "가람전자의 다낭 공장 증설 계획에 지연이 없지 않은 경우",
        "가람전자의 다낭 공장 증설 계획이 철회되지 않았지만 생산 능력 향상이 나타나지 않는 경우",
        "가람전자의 다낭 공장 증설 계획이 철회되지 않더라도 생산 능력 향상이 없다고 확인되는 경우",
        "가람전자의 다낭 공장 증설 계획은 철회되지 않았으나 같은 공장의 원재료 "
        "공급 계약이 중단되는 경우",
        "가람전자의 다낭 공장 증설 계획이 철회되지 않아 생산 능력 증가가 실현되지 않는 경우",
        "가람전자의 다낭 공장 증설 계획이 철회되지 않은 상태에서 생산 능력 향상이 "
        "나타나지 않는 경우",
        "가람전자의 다낭 공장 증설 계획이 철회되지 않았는데 생산 능력 향상이 나타나지 않는 경우",
        "가람전자의 다낭 공장 증설 계획이 철회되지 않은 반면 생산 능력 향상이 나타나지 않는 경우",
        "가람전자의 다낭 공장 증설 계획이 철회되지 않았음에도 생산 능력 향상이 나타나지 않는 경우",
        "가람전자의 다낭 공장 증설 뒤 생산 능력 향상이 나타나지 않았지만 계획 철회가 없는 경우",
        "가람전자의 다낭 공장 증설 이후 생산 능력 향상이 나타나지 않았으나 "
        "증설 계획이 철회되지 않는 경우",
        "생산 능력 향상이 나타나지 않았으나 가람전자의 다낭 공장 증설 계획이 철회되지 않는 경우",
        "가람전자의 다낭 공장 증설 이후 생산 능력 향상이 없지만 가람전자의 "
        "다낭 공장 증설 계획이 철회되지 않는 경우",
        "가람전자의 다낭 공장 증설 계획이 철회되지 않았다. 실제 생산 능력 향상이 "
        "나타나지 않는 경우 해석이 달라진다.",
    ],
)
def test_observed_setbacks_outcome_failures_and_reversed_negations_stay_valid(falsifier):
    validate(expansion_insight(falsifier), expansion_request())


@pytest.mark.parametrize(
    "falsifier",
    [
        "나래전자가 다낭 공장 증설 계획을 철회하지 않는 경우",
        "가람전자가 후에 공장 증설 계획을 철회하지 않는 경우",
        "다낭 공장 증설 계획이 철회되지 않는 경우",
        "가람전자의 공장 증설 계획이 철회되지 않는 경우",
    ],
)
def test_different_or_ambiguous_actors_and_facilities_are_not_assumed_to_be_the_same(falsifier):
    validate(expansion_insight(falsifier), expansion_request())


def test_denied_cancellation_can_refute_a_hypothesis_based_on_that_cancellation():
    validate(
        expansion_insight(
            "가람전자가 다낭 공장 증설 계획을 철회하지 않는 경우",
            text="가람전자의 다낭 공장 증설 계획 철회는 대체 공급 검토로 이어질 수 있다.",
            mechanism="증설 계획이 철회되면 대체 공급 계획을 검토할 수 있다.",
        ),
        expansion_request(),
    )


@pytest.mark.parametrize(
    "proposition",
    [
        "가람전자의 다낭 공장 증설은 자금 부담을 키울 수 있다.",
        "가람전자의 다낭 공장 증설에도 생산 능력이 향상되지 않을 수 있다.",
    ],
)
def test_mentioning_expansion_alone_does_not_establish_a_positive_capacity_hypothesis(proposition):
    validate(
        expansion_insight(
            "가람전자가 다낭 공장 증설 계획을 철회하지 않는 경우",
            text=proposition,
            mechanism="증설 계획과 실제 생산 운영 조건을 확인할 필요가 있다.",
        ),
        expansion_request(),
    )


@pytest.mark.parametrize(
    "source",
    [
        "가람전자 후에 공장은 반도체 기판으로 생산 영역을 넓힌다.",
        "나래전자 다낭은 반도체 기판으로 생산 영역을 넓힌다.",
        "가람전자 다낭은 반도체 기판 생산 확대 계획을 철회했다.",
    ],
)
def test_uncited_or_opposite_source_context_does_not_establish_expansion_benefits(source):
    # These may fail other factual guards; this narrow direction check must not
    # invent a positive expansion premise from a different or opposite source.
    validate(
        expansion_insight("가람전자가 다낭 공장 증설 계획을 철회하지 않는 경우"),
        expansion_request(source),
    )


def test_another_claims_expansion_cannot_supply_the_falsifier_rule_premise():
    request = expansion_request("가람전자가 다낭 공장의 계약을 검토할 계획이다.")
    other = expansion_request().findings[0].claims[0].model_copy(update={"id": "7750:1"})
    request.findings[0].claims.append(other)

    validate(
        expansion_insight("가람전자가 다낭 공장 증설 계획을 철회하지 않는 경우", refs=["7750:0"]),
        request,
    )


@pytest.mark.parametrize("repair_succeeds", [True, False])
def test_direction_error_reaches_the_single_reduce_repair_and_cannot_bypass_it(repair_succeeds):
    source = pipeline_request(
        ids=(101, 102),
        text=SOURCE + " 제조사는 생산라인 전체의 가동 중단이 현재 계속된다고 밝혔다.",
    )
    snapshot = source.model_dump_json(by_alias=True)
    event = "가람전자가 다낭에서 반도체 기판 생산 확대 계획을 철회하거나 연기하는 사건이 "
    denied = event + "발생하지 않았다."
    observed = event + "발생하는 경우"

    def hook(stage, occurrence, _, value):
        if stage == "REDUCE-001":
            falsifier = observed if occurrence == 2 and repair_succeeds else denied
            value["insights"][0]["implications"] = [
                expansion_insight(falsifier, refs=["101:0"])
                .implications[0]
                .model_dump(by_alias=True, mode="json")
            ]
        return value

    provider = V4Provider(source, hook=hook)
    if repair_succeeds:
        result = generate(provider, source)
        assert result.insights[0].implications[0].falsified_by == observed
        assert result.meta.credits == pytest.approx(0.8)
    else:
        with pytest.raises(StructuredOutputExhaustedError) as caught:
            generate(provider, source)
        failure = caught.value.details["validationFailure"]
        assert failure["stage"] == "REDUCE-001"
        assert "report_falsification_direction" in failure["errorKinds"]
        assert caught.value.details["usage"]["credits"] == pytest.approx(0.8)

    assert stages(provider) == ["MAP-001", "REVIEW-001", "REDUCE-001", "REDUCE-001"]
    details = diagnostic(provider.calls[-1]["prompt"])
    assert "implications[0].falsifiedBy" in details
    assert "report_falsification_direction" in details
    assert provider.calls[-1]["response_schema"] == provider.calls[-2]["response_schema"]
    assert all(provider.schema_validity)
    assert source.model_dump_json(by_alias=True) == snapshot
