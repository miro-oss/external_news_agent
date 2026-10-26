"""Review saved candidates against independent synthetic report annotations."""

import argparse
import json
from pathlib import Path
from typing import Literal

from pydantic import Field, model_validator

from app.eval.report_insight_review import QUALITY_RUBRIC, review_saved_output
from app.llm.report_insight_service import importance_grade
from app.schemas.common import AgentModel
from app.schemas.report_insight import ReportInsightOutput, ReportInsightRequest

DEFAULT_DATASET = Path(__file__).parent / "golden" / "report-insight.ko.v1.cases.json"


class CaseAnnotations(AgentModel):
    required_synthesis_claim_ids: list[str]
    forbidden_prose: list[str]
    not_urgent_finding_ids: list[int]
    expected_importance_by_finding: dict[int, Literal["high", "medium", "low", "unavailable"]] = (
        Field(default_factory=dict)
    )


class ReportReviewCase(AgentModel):
    case_id: str = Field(min_length=1)
    scenario: str = Field(min_length=1)
    request: ReportInsightRequest
    annotations: CaseAnnotations
    review_note: str = Field(min_length=1)

    @model_validator(mode="after")
    def check_annotations(self) -> "ReportReviewCase":
        claim_ids = {claim.id for finding in self.request.findings for claim in finding.claims}
        finding_ids = {finding.id for finding in self.request.findings}
        if len(self.request.audiences) != 1:
            raise ValueError("평가 사례는 관점 하나의 저장 후보를 검토합니다.")
        if not set(self.annotations.required_synthesis_claim_ids) <= claim_ids:
            raise ValueError("평가 라벨의 근거가 입력에 없습니다.")
        if not set(self.annotations.not_urgent_finding_ids) <= finding_ids:
            raise ValueError("시간 평가 라벨의 finding이 입력에 없습니다.")
        if not set(self.annotations.expected_importance_by_finding) <= finding_ids:
            raise ValueError("중요도 평가 라벨의 finding이 입력에 없습니다.")
        return self


class ReportReviewCorpus(AgentModel):
    version: str
    prompt_version: str
    synthetic: bool
    quality_measured: bool
    cases: list[ReportReviewCase] = Field(min_length=1)

    @model_validator(mode="after")
    def check_unique_cases(self) -> "ReportReviewCorpus":
        ids = [case.case_id for case in self.cases]
        if len(ids) != len(set(ids)):
            raise ValueError("caseId가 중복되었습니다.")
        return self


def load_corpus(path: Path = DEFAULT_DATASET) -> ReportReviewCorpus:
    return ReportReviewCorpus.model_validate_json(path.read_text(encoding="utf-8"))


def request_snapshot(request: ReportInsightRequest) -> dict:
    """Keep explicit learning metadata without inventing it in historical snapshots.

    Existing fields retain their canonical defaults. The additive learning fields
    preserve presence as well as value, including explicitly supplied empty lists;
    provider prompt filtering must never rewrite this persisted evaluation input.
    """
    payload = request.model_dump(mode="json", by_alias=True)
    if "feedback_examples" not in request.model_fields_set:
        payload.pop("feedbackExamples")
    for finding, serialized in zip(request.findings, payload["findings"], strict=True):
        if "topic_ids" not in finding.model_fields_set:
            serialized.pop("topicIds")
    return payload


def review_case(case: ReportReviewCase, candidate: dict) -> dict:
    diagnostics = review_saved_output(case.request, candidate)
    if not diagnostics["contractPassed"]:
        return {"caseId": case.case_id, **diagnostics, "annotationFlags": []}
    output = ReportInsightOutput.model_validate(candidate)
    insight = output.insights[0]
    refs = {
        ref
        for item in [*insight.overview, *insight.implications, *insight.watch_items]
        for ref in item.basis_claim_ids
    }
    missing = sorted(set(case.annotations.required_synthesis_claim_ids) - refs)
    flags = ["missing_synthesis_claim:" + claim_id for claim_id in missing]
    prose = "\n".join(
        [
            insight.headline,
            *(item.reason for item in insight.assessments),
            *(item.text for item in insight.overview),
            *(
                value
                for item in insight.implications
                for value in (item.text, item.mechanism, item.assumption, item.falsified_by)
            ),
            *(
                value
                for item in insight.watch_items
                for value in (item.topic, item.indicator, item.trigger)
            ),
        ]
    )
    for text in case.annotations.forbidden_prose:
        if text in prose:
            flags.append("forbidden_prose:" + text)
    for item in insight.assessments:
        if item.finding_id in case.annotations.not_urgent_finding_ids and item.axes.urgency == 3:
            flags.append("past_deadline_is_imminent:" + str(item.finding_id))
        expected = case.annotations.expected_importance_by_finding.get(item.finding_id)
        actual = importance_grade(item.axes)
        if expected is not None and expected != actual:
            flags.append(
                f"importance_calibration:{item.finding_id}:expected_{expected}:actual_{actual}"
            )
    return {"caseId": case.case_id, **diagnostics, "annotationFlags": flags}


def review_candidates(corpus: ReportReviewCorpus, candidates: dict) -> dict:
    known = {case.case_id for case in corpus.cases}
    unknown = set(candidates) - known
    if unknown:
        raise ValueError("후보에 평가 데이터셋에 없는 caseId가 있습니다.")
    results = [
        review_case(case, candidates[case.case_id])
        for case in corpus.cases
        if case.case_id in candidates
    ]
    return {
        "datasetVersion": corpus.version,
        "synthetic": corpus.synthetic,
        "caseCount": len(corpus.cases),
        "reviewedCount": len(results),
        "missingCaseIds": sorted(known - set(candidates)),
        "qualityMeasured": False,
        "requiresHumanReview": True,
        "results": results,
        "humanRubric": QUALITY_RUBRIC,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--candidates", type=Path)
    args = parser.parse_args()
    corpus = load_corpus(args.dataset)
    candidates = json.loads(args.candidates.read_text(encoding="utf-8")) if args.candidates else {}
    print(json.dumps(review_candidates(corpus, candidates), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
