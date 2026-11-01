package io.github.ayech14.mealiq.llm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * A deterministic LLMClient for tests and offline runs (D08). It returns queued replies in
 * order and records every request, so a test can check what the agent asked for and drive
 * it through a fixed sequence of tool choices.
 *
 * <p>Like the real adapter, a reply that does not match the requested schema fails with
 * MALFORMED_OUTPUT, so a script cannot hand the agent data a provider never would.
 */
public final class ScriptedLLMClient implements LLMClient {

    public record Call(String prompt, JsonSchema schema, String instructions) {
    }

    private final Deque<Object> replies = new ArrayDeque<>();
    private final List<Call> calls = new ArrayList<>();

    /** Queues replies: each is a {@code Map<String, Object>} answer or an LLMException to throw. */
    public ScriptedLLMClient queue(Object... replies) {
        for (Object reply : replies) {
            if (!(reply instanceof Map<?, ?>) && !(reply instanceof LLMException)) {
                throw new IllegalArgumentException("A reply must be a Map or an LLMException: " + reply);
            }
            this.replies.add(reply);
        }
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> generateStructured(String prompt, JsonSchema schema, String instructions) {
        calls.add(new Call(prompt, schema, instructions));
        Object reply = replies.poll();
        if (reply == null) {
            throw new LLMException(LLMException.Kind.PROVIDER_UNAVAILABLE, "scripted client has no reply left");
        }
        if (reply instanceof LLMException error) {
            throw error;
        }
        Map<String, Object> answer = (Map<String, Object>) reply;
        List<String> problems = schema.validate(answer);
        if (!problems.isEmpty()) {
            throw new LLMException(LLMException.Kind.MALFORMED_OUTPUT,
                    "reply does not match " + schema.name() + ": " + String.join("; ", problems));
        }
        return answer;
    }

    /** Every request so far, oldest first. */
    public List<Call> calls() {
        return Collections.unmodifiableList(calls);
    }

    public int remaining() {
        return replies.size();
    }
}
