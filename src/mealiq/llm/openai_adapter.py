"""ProviderLLMAdapter: implements LLMClient with the OpenAI Responses API (Adapter, D05).

The OpenAI SDK client is the adaptee. Its request shape, parsed output and exception
types stay inside this module; callers see only dicts and LLMError.
"""

from __future__ import annotations

from typing import Any

import openai
from pydantic import BaseModel

from mealiq.config import Settings, get_settings
from mealiq.llm.client import LLMClient, LLMError, LLMErrorKind


class ProviderLLMAdapter(LLMClient):
    def __init__(self, api: Any, model_name: str) -> None:
        """`api` is an openai.OpenAI client (or a stand-in with the same `responses.parse`)."""
        self._api = api
        self.model_name = model_name

    @classmethod
    def from_settings(cls, settings: Settings | None = None) -> ProviderLLMAdapter:
        settings = settings or get_settings()
        if not settings.llm_configured:
            raise LLMError(LLMErrorKind.NOT_CONFIGURED, "set LLM_API_KEY and LLM_MODEL in .env")
        api = openai.OpenAI(
            api_key=settings.llm_api_key.get_secret_value(),
            timeout=settings.llm_timeout_seconds,
            # The SDK retries timeouts, rate limits and 5xx responses with backoff.
            max_retries=settings.llm_max_retries,
        )
        return cls(api, settings.llm_model)

    def generate_structured(
        self, prompt: str, schema: type[BaseModel], instructions: str | None = None
    ) -> dict[str, Any]:
        try:
            response = self._api.responses.parse(**self._to_provider_request(prompt, schema, instructions))
        except openai.OpenAIError as error:
            raise self._map_provider_error(error) from error
        except Exception as error:  # SDK schema/validation failures surface as plain exceptions
            raise LLMError(LLMErrorKind.MALFORMED_OUTPUT, str(error)) from error

        refusal = _find_refusal(response)
        if refusal:
            raise LLMError(LLMErrorKind.REFUSED, refusal)
        parsed = getattr(response, "output_parsed", None)
        if parsed is None:
            raise LLMError(LLMErrorKind.MALFORMED_OUTPUT, "the model returned no structured output")
        return parsed.model_dump(mode="json")

    def _to_provider_request(
        self, prompt: str, schema: type[BaseModel], instructions: str | None
    ) -> dict[str, Any]:
        request: dict[str, Any] = {"model": self.model_name, "input": prompt, "text_format": schema}
        if instructions:
            request["instructions"] = instructions
        return request

    @staticmethod
    def _map_provider_error(error: openai.OpenAIError) -> LLMError:
        # APITimeoutError subclasses APIConnectionError, so it is checked first.
        if isinstance(error, openai.APITimeoutError):
            return LLMError(LLMErrorKind.TIMEOUT, "the LLM provider did not respond in time")
        if isinstance(error, openai.RateLimitError):
            return LLMError(LLMErrorKind.RATE_LIMITED, "the LLM provider rate limit or quota was reached")
        if isinstance(error, (openai.AuthenticationError, openai.PermissionDeniedError)):
            return LLMError(LLMErrorKind.AUTHENTICATION, "the LLM API key was rejected")
        if isinstance(error, (openai.BadRequestError, openai.NotFoundError, openai.UnprocessableEntityError)):
            return LLMError(LLMErrorKind.BAD_REQUEST, str(error))
        if isinstance(error, (openai.APIConnectionError, openai.InternalServerError)):
            return LLMError(LLMErrorKind.PROVIDER_UNAVAILABLE, "the LLM provider is unavailable")
        if isinstance(error, (openai.LengthFinishReasonError, openai.ContentFilterFinishReasonError)):
            return LLMError(LLMErrorKind.MALFORMED_OUTPUT, str(error))
        return LLMError(LLMErrorKind.PROVIDER_UNAVAILABLE, str(error))


def _find_refusal(response: Any) -> str | None:
    for item in getattr(response, "output", None) or []:
        for part in getattr(item, "content", None) or []:
            if getattr(part, "type", None) == "refusal":
                return getattr(part, "refusal", None) or "the model refused the request"
    return None
