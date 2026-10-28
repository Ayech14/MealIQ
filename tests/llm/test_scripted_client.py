import pytest
from pydantic import BaseModel

from mealiq.llm import LLMClient, LLMError, LLMErrorKind, ScriptedLLMClient


class Choice(BaseModel):
    tool: str


def test_replies_in_order_and_records_calls():
    client = ScriptedLLMClient([{"tool": "recipeSearch"}]).queue({"tool": "finish"})
    assert isinstance(client, LLMClient)
    assert client.generate_structured("step 1", Choice, "sys") == {"tool": "recipeSearch"}
    assert client.generate_structured("step 2", Choice) == {"tool": "finish"}
    assert [c.prompt for c in client.calls] == ["step 1", "step 2"]
    assert client.calls[0].schema is Choice and client.calls[0].instructions == "sys"
    assert client.remaining == 0


def test_scripted_error_is_raised():
    client = ScriptedLLMClient([LLMError(LLMErrorKind.TIMEOUT, "slow")])
    with pytest.raises(LLMError) as caught:
        client.generate_structured("x", Choice)
    assert caught.value.kind is LLMErrorKind.TIMEOUT


def test_running_out_of_replies_fails_like_a_provider():
    with pytest.raises(LLMError) as caught:
        ScriptedLLMClient().generate_structured("x", Choice)
    assert caught.value.kind is LLMErrorKind.PROVIDER_UNAVAILABLE
