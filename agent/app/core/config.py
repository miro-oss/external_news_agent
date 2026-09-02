from functools import lru_cache

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="",
        case_sensitive=True,
        extra="ignore",
        frozen=True,
    )

    bind_host: str = Field(default="127.0.0.1", validation_alias="AGENT_BIND_HOST")
    port: int = Field(default=8088, ge=1, le=65535, validation_alias="AGENT_PORT")
    shared_secret: str = Field(
        default="",
        validation_alias="AGENT_SHARED_SECRET",
    )
    mock: bool = Field(default=True, validation_alias="AGENT_MOCK")
    max_body_chars: int = Field(
        default=20_000,
        ge=1,
        validation_alias="AGENT_MAX_BODY_CHARS",
    )
    max_sentences: int = Field(
        default=200,
        ge=1,
        validation_alias="AGENT_MAX_SENTENCES",
    )
    max_output_tokens: int = Field(
        default=4_096,
        ge=1,
        validation_alias="AGENT_MAX_OUTPUT_TOKENS",
    )
    provider_timeout_seconds: float = Field(
        default=30.0,
        gt=0,
        validation_alias="AGENT_PROVIDER_TIMEOUT_SECONDS",
    )
    report_max_output_tokens: int = Field(
        default=8_192,
        ge=1,
        validation_alias="AGENT_REPORT_MAX_OUTPUT_TOKENS",
    )
    insight_max_output_tokens: int = Field(
        default=8_192,
        ge=1,
        validation_alias="AGENT_INSIGHT_MAX_OUTPUT_TOKENS",
    )
    insight_provider_timeout_seconds: float = Field(
        default=60.0,
        gt=0,
        validation_alias="AGENT_INSIGHT_PROVIDER_TIMEOUT_SECONDS",
    )
    report_provider_timeout_seconds: float = Field(
        default=120.0,
        gt=0,
        validation_alias="AGENT_REPORT_PROVIDER_TIMEOUT_SECONDS",
    )
    provider_retry_attempts: int = Field(
        default=1,
        ge=0,
        le=3,
        validation_alias="AGENT_PROVIDER_RETRY_ATTEMPTS",
    )
    gemini_request_interval_seconds: float = Field(
        default=2.0,
        ge=0,
        validation_alias="GEMINI_REQUEST_INTERVAL_SECONDS",
    )
    rate_limit_retry_attempts: int = Field(
        default=2,
        ge=0,
        le=3,
        validation_alias="AGENT_RATE_LIMIT_RETRY_ATTEMPTS",
    )
    rate_limit_backoff_seconds: float = Field(
        default=4.0,
        gt=0,
        validation_alias="AGENT_RATE_LIMIT_BACKOFF_SECONDS",
    )
    rate_limit_max_backoff_seconds: float = Field(
        default=10.0,
        gt=0,
        validation_alias="AGENT_RATE_LIMIT_MAX_BACKOFF_SECONDS",
    )
    # provider가 retryDelay로 알려준 대기 시간에만 쓰는 상한. 추측 backoff보다 크게 둔다.
    rate_limit_max_wait_seconds: float = Field(
        default=60.0,
        gt=0,
        validation_alias="AGENT_RATE_LIMIT_MAX_WAIT_SECONDS",
    )
    provider_concurrency: int = Field(
        default=4,
        ge=1,
        validation_alias="AGENT_PROVIDER_CONCURRENCY",
    )
    provider_acquire_timeout_seconds: float = Field(
        default=1.0,
        gt=0,
        validation_alias="AGENT_PROVIDER_ACQUIRE_TIMEOUT_SECONDS",
    )
    circuit_failure_threshold: int = Field(
        default=3,
        ge=1,
        validation_alias="AGENT_CIRCUIT_FAILURE_THRESHOLD",
    )
    circuit_cooldown_seconds: float = Field(
        default=30.0,
        gt=0,
        validation_alias="AGENT_CIRCUIT_COOLDOWN_SECONDS",
    )
    hard_cap_credits_per_request: float = Field(
        default=5.0,
        gt=0,
        validation_alias="AGENT_HARD_CAP_CREDITS_PER_REQUEST",
    )
    mindlogic_credits_per_request: float = Field(
        default=1.0,
        gt=0,
        validation_alias="MINDLOGIC_CREDITS_PER_REQUEST",
    )
    schema_repair_attempts: int = Field(
        default=1,
        ge=0,
        le=1,
        validation_alias="AGENT_SCHEMA_REPAIR_ATTEMPTS",
    )
    evidence_grounded_overlap: float = Field(
        default=0.6,
        ge=0,
        le=1,
        validation_alias="AGENT_EVIDENCE_GROUNDED_OVERLAP",
    )
    evidence_weak_overlap: float = Field(
        default=0.2,
        ge=0,
        le=1,
        validation_alias="AGENT_EVIDENCE_WEAK_OVERLAP",
    )
    evidence_max_claim_chars: int = Field(
        default=2_000,
        ge=1,
        validation_alias="AGENT_EVIDENCE_MAX_CLAIM_CHARS",
    )
    evidence_max_sentences: int = Field(
        default=50,
        ge=1,
        validation_alias="AGENT_EVIDENCE_MAX_SENTENCES",
    )
    evidence_max_total_chars: int = Field(
        default=40_000,
        ge=1,
        validation_alias="AGENT_EVIDENCE_MAX_TOTAL_CHARS",
    )
    gemini_api_key: str = Field(default="", validation_alias="GEMINI_API_KEY")
    gemini_model: str = Field(default="", validation_alias="GEMINI_MODEL")
    mindlogic_api_key: str = Field(default="", validation_alias="MINDLOGIC_API_KEY")
    mindlogic_base_url: str = Field(
        default="https://factchat-cloud.mindlogic.ai/v1/gateway",
        validation_alias="MINDLOGIC_BASE_URL",
    )
    mindlogic_claude_model: str = Field(
        default="",
        validation_alias="MINDLOGIC_CLAUDE_MODEL",
    )

    @model_validator(mode="after")
    def validate_evidence_thresholds(self) -> "Settings":
        if self.evidence_weak_overlap > self.evidence_grounded_overlap:
            raise ValueError(
                "AGENT_EVIDENCE_WEAK_OVERLAP은 GROUNDED_OVERLAP보다 클 수 없습니다."
            )
        if self.rate_limit_max_backoff_seconds < self.rate_limit_backoff_seconds:
            raise ValueError(
                "AGENT_RATE_LIMIT_MAX_BACKOFF_SECONDS는 기본 backoff보다 작을 수 없습니다."
            )
        if self.rate_limit_max_wait_seconds < self.rate_limit_max_backoff_seconds:
            raise ValueError(
                "AGENT_RATE_LIMIT_MAX_WAIT_SECONDS는 최대 backoff보다 작을 수 없습니다."
            )
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()
