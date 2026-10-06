"""Bounded review runs receive selection priority, not source-array order."""

import pytest
from test_report_insight_assessment import item, payload, request, response

from app.llm.report_insight_assessment import _role_candidate, select_review, validate_draft
from app.llm.report_insight_retrieval import _ROLE_QUERIES


def test_unknown_relation_precedes_lexical_suspect_and_late_source_position():
    source = request(ids=(101, 102, 103, 104, 105, 106))
    value = payload(source)
    entries = value["assessments"]["CHIP_MAKER"]
    entries["finding102"] = item(source.findings[1], relation="UNRELATED")
    entries["finding106"] = item(source.findings[-1], relation="UNDETERMINED")
    full = validate_draft(response(value, source), source)
    before = full.draft.model_dump_json(), full.mapped.model_dump_json()

    selected = select_review(source, full)

    assert selected[:3] == (101, 106, 103)
    assert selected.index(106) < selected.index(102)
    assert (full.draft.model_dump_json(), full.mapped.model_dump_json()) == before
    assert [entry.finding_id for entry in full.mapped.insights[0].assessments] == [
        101,
        102,
        103,
        104,
        105,
        106,
    ]


@pytest.mark.parametrize(
    "text",
    [
        "기판에 새로운 구조가 적용됐다.",
        "The substrate incorporates the new structure.",
        "An RF-SiP incorporates this structure.",
        "An RF‑SiP incorporates this structure.",
        "The System in Package incorporates this structure.",
    ],
)
def test_chip_packaging_aliases_add_review_opportunity_without_changing_axes(text):
    source = request(text=text)
    before_queries = dict(_ROLE_QUERIES)
    full = validate_draft(response(payload(source, relation="UNRELATED"), source), source)
    before = full.draft.model_dump_json(), full.mapped.model_dump_json()

    assert _role_candidate(source.findings[0], "CHIP_MAKER")
    assert select_review(source, full) == (101,)
    assert (full.draft.model_dump_json(), full.mapped.model_dump_json()) == before
    assert full.mapped.insights[0].assessments[0].axes.directness == 0
    assert _ROLE_QUERIES == before_queries


@pytest.mark.parametrize("text", ["The system works.", "A package arrived.", "substrateable"])
def test_packaging_phrase_aliases_do_not_match_generic_components(text):
    source = request(text=text)
    assert not _role_candidate(source.findings[0], "CHIP_MAKER")


def test_packaging_aliases_ignore_metadata_and_unlinked_sentences():
    source = request(text="협정의 이행 조건이 바뀌었다.")
    finding = source.findings[0]
    finding.article_title = "RF-SiP substrate 기판 System in Package"
    finding.topic_name = "기판"
    finding.sentences.append(finding.sentences[0].model_copy(update={"index": 9, "text": "기판"}))
    assert not _role_candidate(finding, "CHIP_MAKER")
