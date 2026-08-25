import json
from pathlib import Path

from pydantic import Field, model_validator

from app.schemas.analyze import AnalyzeOutput, ArticleInput, TopicInput
from app.schemas.common import AgentModel


class GoldenCase(AgentModel):
    case_id: str = Field(min_length=1, max_length=100)
    article: ArticleInput
    topic: TopicInput
    replay: AnalyzeOutput


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


def load_dataset(path: Path) -> GoldenDataset:
    return GoldenDataset.model_validate_json(path.read_text(encoding="utf-8"))


def dump_dataset(dataset: GoldenDataset) -> str:
    """테스트와 리뷰에서 안정적인 JSON 표현을 사용할 수 있게 한다."""
    return json.dumps(
        dataset.model_dump(by_alias=True, mode="json"),
        ensure_ascii=False,
        indent=2,
    )
