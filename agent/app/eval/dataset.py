from pathlib import Path
from typing import Literal

from pydantic import Field, field_validator, model_validator

from app.schemas.analyze import ArticleInput, Audience, Groundedness, TopicInput
from app.schemas.common import AgentModel

ExpectedFailure = Literal["schema", "grounding", "korean-summary"]


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
    cases: list[GoldenCase] = Field(min_length=20, max_length=30)

    @model_validator(mode="after")
    def validate_unique_cases(self) -> "GoldenDataset":
        case_ids = [case.case_id for case in self.cases]
        if len(case_ids) != len(set(case_ids)):
            raise ValueError("Golden caseId는 데이터셋 안에서 유일해야 합니다.")

        article_ids = [case.article.id for case in self.cases]
        if len(article_ids) != len(set(article_ids)):
            raise ValueError("Golden article id는 데이터셋 안에서 유일해야 합니다.")
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
