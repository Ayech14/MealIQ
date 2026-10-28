"""Application configuration loaded from environment variables or a local .env file.

Real secrets live only in the git-ignored .env file; .env.example documents every
setting with placeholder values.
"""

from functools import lru_cache

from pydantic import Field, SecretStr, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    database_url: str = Field(
        default="postgresql+psycopg://mealiq:mealiq@localhost:5432/mealiq",
        description="SQLAlchemy URL for the MealIQ database.",
    )

    # LLM provider settings (D05). The model is pinned here, never in code, so it
    # can be changed without touching the agent.
    llm_api_key: SecretStr | None = Field(
        default=None, description="API key for the LLM provider."
    )
    llm_model: str | None = Field(
        default=None, description="Pinned model identifier for structured output."
    )
    llm_timeout_seconds: float = Field(default=60.0, gt=0)
    # Transient-failure retries done by the provider SDK (timeouts, rate limits, 5xx).
    llm_max_retries: int = Field(default=2, ge=0, le=5)

    # Upper bound on tool calls in one agent run (D07).
    max_tool_steps: int = Field(default=6, ge=1, le=20)

    @field_validator("database_url")
    @classmethod
    def _require_sqlalchemy_url(cls, value: str) -> str:
        if "://" not in value:
            raise ValueError("database_url must be a SQLAlchemy URL such as postgresql+psycopg://...")
        return value

    @property
    def llm_configured(self) -> bool:
        """True when both an API key and a pinned model are set."""
        return bool(self.llm_api_key and self.llm_api_key.get_secret_value() and self.llm_model)


@lru_cache
def get_settings() -> Settings:
    """Return the process-wide settings, loaded once."""
    return Settings()
