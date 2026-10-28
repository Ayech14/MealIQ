"""ScriptedLLMClient: a deterministic LLMClient for tests and offline runs (D08).

It returns queued replies in order and records every request, so a test can check what
the agent asked for and drive the agent through a fixed sequence of tool choices.
"""

from __future__ import annotations

from collections import deque
from dataclasses import dataclass
from typing import Any

from pydantic import BaseModel

from mealiq.llm.client import LLMClient, LLMError, LLMErrorKind


@dataclass(frozen=True)
class RecordedCall:
    prompt: str
    schema: type[BaseModel]
    instructions: str | None


class ScriptedLLMClient(LLMClient):
    def __init__(self, replies: list[dict[str, Any] | LLMError] | None = None) -> None:
        self._replies: deque[dict[str, Any] | LLMError] = deque(replies or [])
        self.calls: list[RecordedCall] = []

    def queue(self, *replies: dict[str, Any] | LLMError) -> ScriptedLLMClient:
        self._replies.extend(replies)
        return self

    def generate_structured(
        self, prompt: str, schema: type[BaseModel], instructions: str | None = None
    ) -> dict[str, Any]:
        self.calls.append(RecordedCall(prompt, schema, instructions))
        if not self._replies:
            raise LLMError(LLMErrorKind.PROVIDER_UNAVAILABLE, "scripted client has no reply left")
        reply = self._replies.popleft()
        if isinstance(reply, LLMError):
            raise reply
        return reply

    @property
    def remaining(self) -> int:
        return len(self._replies)
