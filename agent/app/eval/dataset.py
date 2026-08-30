from collections import Counter
from pathlib import Path
from typing import Literal, get_args

from pydantic import Field, field_validator, model_validator

from app.schemas.analyze import ArticleInput, Audience, Groundedness, TopicInput
from app.schemas.common import AgentModel
from app.schemas.evidence import EvidenceSentence

ExpectedFailure = Literal["schema", "grounding", "korean-summary"]
ClaimValidity = Literal["invalid", "valid"]
ClaimFailureType = Literal[
    "number-mismatch",
    "polarity-inversion",
    "company-substitution",
    "modality-overreach",
    "unsupported-claim",
]


class GoldenClaimLabel(AgentModel):
    claim_id: str = Field(min_length=1, max_length=100)
    validity: ClaimValidity
    claim: str = Field(min_length=1)


class GoldenClaimControl(AgentModel):
    control_id: str = Field(min_length=1, max_length=100)
    failure_type: ClaimFailureType
    evidence: list[EvidenceSentence] = Field(min_length=1)
    labels: list[GoldenClaimLabel] = Field(min_length=2, max_length=2)

    @model_validator(mode="after")
    def validate_label_pair(self) -> "GoldenClaimControl":
        claim_ids = [label.claim_id for label in self.labels]
        if len(claim_ids) != len(set(claim_ids)):
            raise ValueError("Claim control의 claimId는 중복될 수 없습니다.")
        validity_counts = Counter(label.validity for label in self.labels)
        if validity_counts != {"invalid": 1, "valid": 1}:
            raise ValueError("Claim control은 invalid/valid 라벨을 하나씩 가져야 합니다.")
        sentence_ids = [sentence.id for sentence in self.evidence]
        if len(sentence_ids) != len(set(sentence_ids)):
            raise ValueError("Claim control의 evidence id는 중복될 수 없습니다.")
        return self


class GoldenCase(AgentModel):
    case_id: str = Field(min_length=1, max_length=100)
    article: ArticleInput
    topic: TopicInput
    replay: dict[str, object] = Field(min_length=1)
    expected_audiences: list[Audience]
    expected_failures: list[ExpectedFailure] = Field(default_factory=list)

    @field_validator("expected_failures")
    @classmethod
    def validate_unique_expected_failures(
        cls, value: list[ExpectedFailure]
    ) -> list[ExpectedFailure]:
        if len(value) != len(set(value)):
            raise ValueError("expectedFailures는 중복될 수 없습니다.")
        return value

    @field_validator("expected_audiences")
    @classmethod
    def validate_unique_expected_audiences(
        cls, value: list[Audience]
    ) -> list[Audience]:
        if len(value) != len(set(value)):
            raise ValueError("expectedAudiences는 중복될 수 없습니다.")
        return value


class GoldenDataset(AgentModel):
    version: str = Field(min_length=1, max_length=100)
    baseline_prompt_version: str = Field(min_length=1, max_length=50)
    claim_labels_version: str = Field(min_length=1, max_length=50)
    claim_controls: list[GoldenClaimControl] = Field(min_length=15, max_length=50)
    cases: list[GoldenCase] = Field(min_length=20, max_length=30)

    @model_validator(mode="after")
    def validate_unique_cases(self) -> "GoldenDataset":
        case_ids = [case.case_id for case in self.cases]
        if len(case_ids) != len(set(case_ids)):
            raise ValueError("Golden caseId는 데이터셋 안에서 유일해야 합니다.")

        article_ids = [case.article.id for case in self.cases]
        if len(article_ids) != len(set(article_ids)):
            raise ValueError("Golden article id는 데이터셋 안에서 유일해야 합니다.")

        control_ids = [control.control_id for control in self.claim_controls]
        if len(control_ids) != len(set(control_ids)):
            raise ValueError("Golden claim controlId는 데이터셋 안에서 유일해야 합니다.")

        claim_ids = [
            label.claim_id
            for control in self.claim_controls
            for label in control.labels
        ]
        if len(claim_ids) != len(set(claim_ids)):
            raise ValueError("Golden claimId는 데이터셋 안에서 유일해야 합니다.")

        type_counts = Counter(control.failure_type for control in self.claim_controls)
        underfilled = {
            failure_type: type_counts[failure_type]
            for failure_type in get_args(ClaimFailureType)
            if type_counts[failure_type] < 3
        }
        if underfilled:
            raise ValueError(
                "Claim control은 실패 유형별 3쌍 이상이어야 합니다: "
                + ", ".join(
                    f"{failure_type}={count}"
                    for failure_type, count in underfilled.items()
                )
            )
        return self


class GoldenReportFixture(AgentModel):
    dataset_version: str = Field(min_length=1, max_length=100)
    prompt_version: str = Field(min_length=1, max_length=50)
    output: dict[str, object] = Field(min_length=1)
    expected_claim_statuses: dict[str, Groundedness] = Field(min_length=1)


def load_dataset(path: Path) -> GoldenDataset:
    return GoldenDataset.model_validate_json(path.read_text(encoding="utf-8"))


def load_report_fixture(path: Path) -> GoldenReportFixture:
    return GoldenReportFixture.model_validate_json(path.read_text(encoding="utf-8"))
