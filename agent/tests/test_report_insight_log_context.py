"""Concurrent report stages have safe, attributable repair and terminal logs."""

import logging
import re

import pytest
from test_report_insight_assessment import request
from test_report_insight_reduce_partial_repair import broken_second_overview
from test_report_insight_v4_pipeline import V4Provider, generate
from test_structured_call import response

from app.core.errors import OutputValidationError
from app.llm.report_validation_diagnostics import ReportValidationContext, ReportValidationIssue
from app.llm.structured_call import _log_validation_failure


def test_all_stages_and_repair_share_context_but_independent_runs_do_not(caplog):
    caplog.set_level(logging.INFO)
    source = request(ids=(101, 102))
    for _ in range(2):
        generate(V4Provider(source, hook=broken_second_overview), source)
    traces = re.findall(r"traceId=([0-9a-f]{32})", caplog.text)
    assert len(set(traces)) == 2
    for trace in set(traces):
        messages = [
            record.getMessage() for record in caplog.records if trace in record.getMessage()
        ]
        for stage in ("MAP-001", "REVIEW-001", "REDUCE-001"):
            assert any(f"stage={stage}" in message for message in messages)
        assert any("action=REPAIR" in message for message in messages)
        assert any("outcome=REPAIRED" in message for message in messages)
        assert all(f"reportId={source.report.id}" in message for message in messages)
    assert "overview[1].text" in caplog.text
    assert "unsupported_number" in caplog.text
    assert "999억원" not in caplog.text


def test_terminal_log_keeps_only_bounded_owned_issue_fields(caplog):
    marker = "SYNTHETIC_PRIVATE_OUTPUT"
    error = OutputValidationError(marker, error_kinds=("report_fact_mismatch",))
    error.fact_repair_kinds = ("source_binding", marker)
    error.validation_issues = (
        ReportValidationIssue(
            "CHIP_MAKER", "assessments[101].reason", "report_fact_mismatch", ("101:0",)
        ),
        ReportValidationIssue("CHIP_MAKER", marker, "report_fact_mismatch", ("101:0",)),
    )
    _log_validation_failure(
        logging.getLogger(__name__),
        response(),
        error,
        task_name="report",
        attempt=2,
        stage="MAP-003",
        schema={},
        context=ReportValidationContext.create(42, ["CHIP_MAKER"]),
    )
    assert marker not in caplog.text
    assert "action=EXHAUSTED" in caplog.text
    assert "stage=MAP-003" in caplog.text
    assert "assessments[101].reason" in caplog.text
    assert "101:0" in caplog.text
    assert "validationRules=('source_binding',)" in caplog.text


@pytest.mark.parametrize(
    "trace,report_id,audiences",
    [
        ("untrusted\ntrace", 1, ("CHIP_MAKER",)),
        ("a" * 32, True, ("CHIP_MAKER",)),
        ("a" * 32, 1, ("untrusted",)),
    ],
)
def test_context_never_accepts_unbounded_log_values(trace, report_id, audiences):
    with pytest.raises(ValueError):
        ReportValidationContext(trace, report_id, audiences)
