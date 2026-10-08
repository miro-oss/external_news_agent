"""Historical learning and native source selection survive the combined pipeline."""

import json

from test_feedback_learning import example
from test_report_insight_assessment import framed, request
from test_report_insight_source_quote_repair_scope import quote_scenario
from test_report_insight_v4_pipeline import V4Provider, generate, stages

from app.llm.feedback_learning import FEEDBACK_LEARNING_INSTRUCTION
from app.llm.report_insight_fact_rendering import (
    FACT_TEMPLATE_INSTRUCTIONS,
    SOURCE_QUOTE_INSTRUCTIONS,
)


def _scoped_feedback(source):
    payload = source.model_dump(by_alias=True, mode="json")
    for finding, topic_id in zip(payload["findings"], (7, 8), strict=True):
        finding["topicIds"] = [topic_id]
    payload["feedbackExamples"] = [example(topic_id=7), example(topic_id=8)]
    return type(source).model_validate(payload)


def test_native_pipeline_keeps_learning_and_source_selection_in_every_stage():
    source = _scoped_feedback(request(ids=(101, 102)))
    provider = V4Provider(source)

    result = generate(provider, source)

    assert stages(provider) == ["MAP-001", "REVIEW-001", "REDUCE-001"]
    assert {item.finding_id for item in result.insights[0].assessments} == {101, 102}
    for call in provider.calls:
        prompt = call["prompt"]
        payload = framed(prompt)
        stage = call["response_schema"]["description"].split(":", 1)[1]
        instructions = (
            FACT_TEMPLATE_INSTRUCTIONS if stage.startswith("REDUCE") else SOURCE_QUOTE_INSTRUCTIONS
        )
        assert instructions in prompt
        assert FEEDBACK_LEARNING_INSTRUCTION in prompt
        assert [item["topicId"] for item in payload.pop("feedbackExamples")] == [7, 8]
        # Historical article text never becomes a selectable current source.
        assert source.feedback_examples[0].evidence[0].quote not in json.dumps(
            payload, ensure_ascii=False
        )
        assert "9001" not in json.dumps(payload, ensure_ascii=False)


def test_native_quote_repair_keeps_only_failed_topic_feedback_and_frozen_source_scope():
    original, slots, _, provider = quote_scenario("reason")
    source = _scoped_feedback(original)
    provider.source = source
    snapshot = source.model_dump_json()

    result = generate(provider, source)

    assert stages(provider) == ["MAP-001", "MAP-001", "REVIEW-001", "REDUCE-001"]
    initial = framed(provider.calls[0]["prompt"])
    repair = framed(provider.calls[1]["prompt"])
    assert [item["topicId"] for item in initial["feedbackExamples"]] == [7, 8]
    assert [item["topicId"] for item in repair["feedbackExamples"]] == [7]
    assert [item["id"] for item in repair["findings"]] == [101]
    assert SOURCE_QUOTE_INSTRUCTIONS in provider.calls[1]["prompt"]
    assert FEEDBACK_LEARNING_INSTRUCTION in provider.calls[1]["prompt"]
    records = provider.calls[1]["response_schema"]["properties"]["assessments"]["properties"][
        "CHIP_MAKER"
    ]
    assert set(records["properties"]) == {"finding101"}
    selection = records["properties"]["finding101"]["properties"]["sourceQuotes"]["properties"][
        "reason"
    ]
    assert selection == {"anyOf": [{"type": "string", "enum": [slots["101:1"]]}, {"type": "null"}]}
    assert {item.finding_id for item in result.insights[0].assessments} == {101, 102}
    assert source.model_dump_json() == snapshot
