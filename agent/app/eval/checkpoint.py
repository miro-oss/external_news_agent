import hashlib
import json
import os
import tempfile
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from app.eval.dataset import GoldenDataset
from app.schemas.analyze import AnalyzeResponse, Plan
from app.schemas.report import ReportResponse


class CheckpointError(ValueError):
    """live eval checkpoint가 현재 실행과 호환되지 않을 때 발생한다."""


class LiveCheckpoint(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    format_version: Literal[1] = Field(default=1, alias="formatVersion")
    dataset_version: str = Field(alias="datasetVersion")
    dataset_fingerprint: str = Field(alias="datasetFingerprint")
    baseline_prompt_version: str = Field(alias="baselinePromptVersion")
    analyze_prompt_version: str = Field(alias="analyzePromptVersion")
    report_prompt_version: str = Field(alias="reportPromptVersion")
    plan: Plan
    config: dict[str, object]
    analyses: dict[str, AnalyzeResponse] = Field(default_factory=dict)
    report: ReportResponse | None = None


class LiveCheckpointStore:
    def __init__(
        self,
        path: Path,
        *,
        dataset: GoldenDataset,
        analyze_prompt_version: str,
        report_prompt_version: str,
        plan: Plan,
        config: dict[str, object],
        resume: bool,
    ) -> None:
        self.path = path
        self._case_ids = frozenset(case.case_id for case in dataset.cases)
        expected = LiveCheckpoint(
            datasetVersion=dataset.version,
            datasetFingerprint=_dataset_fingerprint(dataset),
            baselinePromptVersion=dataset.baseline_prompt_version,
            analyzePromptVersion=analyze_prompt_version,
            reportPromptVersion=report_prompt_version,
            plan=plan,
            config=config,
        )
        if resume:
            self.checkpoint = self._load()
            _validate_identity(self.checkpoint, expected)
            unknown = set(self.checkpoint.analyses) - self._case_ids
            if unknown:
                raise CheckpointError(
                    "checkpoint에 현재 dataset에 없는 case가 있습니다: "
                    + ", ".join(sorted(unknown))
                )
        else:
            if path.exists():
                raise CheckpointError(
                    f"checkpoint가 이미 존재합니다: {path}. "
                    "이어서 실행하려면 --resume을 사용하세요."
                )
            self.checkpoint = expected
            self._write()

    def analysis(self, case_id: str) -> AnalyzeResponse | None:
        return self.checkpoint.analyses.get(case_id)

    def record_analysis(self, case_id: str, response: AnalyzeResponse) -> None:
        if case_id not in self._case_ids:
            raise CheckpointError(f"알 수 없는 checkpoint case입니다: {case_id}")
        self.checkpoint.analyses[case_id] = response
        self._write()

    def record_report(self, response: ReportResponse) -> None:
        self.checkpoint.report = response
        self._write()

    def _load(self) -> LiveCheckpoint:
        try:
            return LiveCheckpoint.model_validate_json(self.path.read_text(encoding="utf-8"))
        except OSError as error:
            raise CheckpointError(f"checkpoint를 읽을 수 없습니다: {self.path}") from error
        except ValueError as error:
            raise CheckpointError(f"checkpoint 형식이 올바르지 않습니다: {self.path}") from error

    def _write(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        rendered = self.checkpoint.model_dump_json(by_alias=True, indent=2) + "\n"
        temporary: Path | None = None
        try:
            with tempfile.NamedTemporaryFile(
                mode="w",
                encoding="utf-8",
                dir=self.path.parent,
                prefix=f".{self.path.name}.",
                suffix=".tmp",
                delete=False,
            ) as handle:
                handle.write(rendered)
                handle.flush()
                os.fsync(handle.fileno())
                temporary = Path(handle.name)
            temporary.replace(self.path)
        finally:
            if temporary is not None and temporary.exists():
                temporary.unlink()


def _dataset_fingerprint(dataset: GoldenDataset) -> str:
    canonical = json.dumps(
        dataset.model_dump(by_alias=True, mode="json"),
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _validate_identity(current: LiveCheckpoint, expected: LiveCheckpoint) -> None:
    fields = (
        "dataset_version",
        "dataset_fingerprint",
        "baseline_prompt_version",
        "analyze_prompt_version",
        "report_prompt_version",
        "plan",
        "config",
    )
    mismatches = [field for field in fields if getattr(current, field) != getattr(expected, field)]
    if mismatches:
        raise CheckpointError(
            "checkpoint가 현재 live 실행과 호환되지 않습니다: " + ", ".join(mismatches)
        )
