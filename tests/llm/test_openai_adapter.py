"""ProviderLLMAdapter tests with a stand-in for the OpenAI client; no network calls."""

from types import SimpleNamespace

import httpx
import openai
import pytest
from pydantic import BaseModel

from mealiq.config import Settings
from mealiq.llm import LLMError, LLMErrorKind
from mealiq.llm.openai_adapter import ProviderLLMAdapter

REQUEST = httpx.Request("POST", "https://api.openai.test/v1/responses")


class Answer(BaseModel):
    recipe_ids: list[str]
    rationale: str


class StubResponses:
    def __init__(self, result=None, error=None):
        self.result, self.error, self.requests = result, error, []

    def parse(self, **request):
        self.requests.append(request)
        if self.error:
            raise self.error
        return self.result


def adapter_with(result=None, error=None):
    responses = StubResponses(result, error)
    return ProviderLLMAdapter(SimpleNamespace(responses=responses), "test-model"), responses


def parsed_response(model):
    return SimpleNamespace(output_parsed=model, output=[])


def test_returns_parsed_output_as_dict_and_sends_schema():
    adapter, stub = adapter_with(parsed_response(Answer(recipe_ids=["a", "b"], rationale="high protein")))
    result = adapter.generate_structured("plan my week", Answer, instructions="You are MealIQ.")
    assert result == {"recipe_ids": ["a", "b"], "rationale": "high protein"}
    request = stub.requests[0]
    assert request == {"model": "test-model", "input": "plan my week", "text_format": Answer,
                       "instructions": "You are MealIQ."}


def test_instructions_are_optional():
    adapter, stub = adapter_with(parsed_response(Answer(recipe_ids=[], rationale="")))
    adapter.generate_structured("hi", Answer)
    assert "instructions" not in stub.requests[0]


def test_refusal_is_reported():
    refusal = SimpleNamespace(content=[SimpleNamespace(type="refusal", refusal="cannot help with that")])
    adapter, _ = adapter_with(SimpleNamespace(output_parsed=None, output=[refusal]))
    with pytest.raises(LLMError) as caught:
        adapter.generate_structured("x", Answer)
    assert caught.value.kind is LLMErrorKind.REFUSED
    assert not caught.value.retryable


def test_missing_structured_output_is_malformed():
    adapter, _ = adapter_with(SimpleNamespace(output_parsed=None, output=[]))
    with pytest.raises(LLMError) as caught:
        adapter.generate_structured("x", Answer)
    assert caught.value.kind is LLMErrorKind.MALFORMED_OUTPUT
    assert caught.value.retryable


def status_error(cls, status):
    return cls("error", response=httpx.Response(status, request=REQUEST), body=None)


@pytest.mark.parametrize(
    ("error", "kind", "retryable"),
    [
        (openai.APITimeoutError(request=REQUEST), LLMErrorKind.TIMEOUT, True),
        (openai.APIConnectionError(request=REQUEST), LLMErrorKind.PROVIDER_UNAVAILABLE, True),
        (status_error(openai.RateLimitError, 429), LLMErrorKind.RATE_LIMITED, True),
        (status_error(openai.AuthenticationError, 401), LLMErrorKind.AUTHENTICATION, False),
        (status_error(openai.BadRequestError, 400), LLMErrorKind.BAD_REQUEST, False),
        (status_error(openai.NotFoundError, 404), LLMErrorKind.BAD_REQUEST, False),
        (status_error(openai.InternalServerError, 500), LLMErrorKind.PROVIDER_UNAVAILABLE, True),
    ],
)
def test_provider_errors_are_mapped(error, kind, retryable):
    adapter, _ = adapter_with(error=error)
    with pytest.raises(LLMError) as caught:
        adapter.generate_structured("x", Answer)
    assert caught.value.kind is kind
    assert caught.value.retryable is retryable
    assert not isinstance(caught.value, openai.OpenAIError)


def test_unexpected_parse_failure_is_malformed():
    adapter, _ = adapter_with(error=ValueError("invalid JSON"))
    with pytest.raises(LLMError) as caught:
        adapter.generate_structured("x", Answer)
    assert caught.value.kind is LLMErrorKind.MALFORMED_OUTPUT


def test_from_settings_requires_key_and_model():
    with pytest.raises(LLMError) as caught:
        ProviderLLMAdapter.from_settings(Settings(_env_file=None, llm_api_key=None, llm_model=None))
    assert caught.value.kind is LLMErrorKind.NOT_CONFIGURED


def test_from_settings_builds_client_without_calling_the_network():
    settings = Settings(_env_file=None, llm_api_key="sk-test", llm_model="test-model", llm_max_retries=1)
    adapter = ProviderLLMAdapter.from_settings(settings)
    assert adapter.model_name == "test-model"
