"""Provider-neutral LLM access (Adapter target, D05).

The agent depends only on LLMClient. Structured output is requested with a Pydantic
model that describes the JSON shape; the result is returned as a plain dict so the
caller decides how to validate it and what to do when it does not fit (for example,
retry once, F04).
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from enum import Enum
from typing import Any

from pydantic import BaseModel


class LLMErrorKind(str, Enum):
    TIMEOUT = "TIMEOUT"
    RATE_LIMITED = "RATE_LIMITED"
    AUTHENTICATION = "AUTHENTICATION"
    BAD_REQUEST = "BAD_REQUEST"
    PROVIDER_UNAVAILABLE = "PROVIDER_UNAVAILABLE"
    REFUSED = "REFUSED"
    MALFORMED_OUTPUT = "MALFORMED_OUTPUT"
    NOT_CONFIGURED = "NOT_CONFIGURED"


class LLMError(Exception):
    """A provider failure translated into MealIQ terms; no SDK types escape this layer."""

    def __init__(self, kind: LLMErrorKind, message: str) -> None:
        self.kind = kind
        super().__init__(f"{kind.value}: {message}")

    @property
    def retryable(self) -> bool:
        """True when repeating the same request may succeed."""
        return self.kind in {
            LLMErrorKind.TIMEOUT,
            LLMErrorKind.RATE_LIMITED,
            LLMErrorKind.PROVIDER_UNAVAILABLE,
            LLMErrorKind.MALFORMED_OUTPUT,
        }


class LLMClient(ABC):
    @abstractmethod
    def generate_structured(
        self, prompt: str, schema: type[BaseModel], instructions: str | None = None
    ) -> dict[str, Any]:
        """Ask the model for JSON matching `schema` and return it as a dict.

        `instructions` is the fixed system-level guidance; `prompt` carries the request
        and its context. Raises LLMError on any provider or output failure.
        """
