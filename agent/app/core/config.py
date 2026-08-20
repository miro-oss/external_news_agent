from functools import lru_cache

from pydantic import Field
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
    provider_retry_attempts: int = Field(
        default=1,
        ge=0,
        le=3,
        validation_alias="AGENT_PROVIDER_RETRY_ATTEMPTS",
    )
    schema_repair_attempts: int = Field(
        default=1,
        ge=0,
        le=1,
        validation_alias="AGENT_SCHEMA_REPAIR_ATTEMPTS",
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


@lru_cache
def get_settings() -> Settings:
    return Settings()
