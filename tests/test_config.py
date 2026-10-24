import pytest
from pydantic import ValidationError

from mealiq.config import Settings


@pytest.fixture(autouse=True)
def _isolate_env(monkeypatch):
    for name in ("DATABASE_URL", "LLM_API_KEY", "LLM_MODEL", "LLM_TIMEOUT_SECONDS", "MAX_TOOL_STEPS"):
        monkeypatch.delenv(name, raising=False)


def make_settings(**overrides) -> Settings:
    # _env_file=None keeps a developer's real .env out of the tests.
    return Settings(_env_file=None, **overrides)


def test_defaults_point_to_local_postgres_and_have_no_llm():
    settings = make_settings()
    assert settings.database_url.startswith("postgresql+psycopg://")
    assert settings.max_tool_steps == 6
    assert settings.llm_configured is False


def test_values_are_read_from_environment(monkeypatch):
    monkeypatch.setenv("LLM_API_KEY", "test-key")
    monkeypatch.setenv("LLM_MODEL", "test-model")
    monkeypatch.setenv("MAX_TOOL_STEPS", "4")
    settings = make_settings()
    assert settings.llm_model == "test-model"
    assert settings.max_tool_steps == 4
    assert settings.llm_configured is True


def test_api_key_is_not_exposed_in_repr():
    settings = make_settings(llm_api_key="secret-value")
    assert "secret-value" not in repr(settings)
    assert settings.llm_api_key.get_secret_value() == "secret-value"


def test_key_without_model_is_not_configured():
    assert make_settings(llm_api_key="k").llm_configured is False


@pytest.mark.parametrize("steps", [0, -1, 21])
def test_tool_step_limit_is_bounded(steps):
    with pytest.raises(ValidationError):
        make_settings(max_tool_steps=steps)


def test_database_url_must_be_a_url():
    with pytest.raises(ValidationError):
        make_settings(database_url="not-a-url")
