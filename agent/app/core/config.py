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


@lru_cache
def get_settings() -> Settings:
    return Settings()
