package io.github.ayech14.mealiq.agent;

import io.github.ayech14.mealiq.contracts.PlanChange;
import java.util.List;

/** A change proposed by the agent and the tool calls that led to it. */
public record ChangeAttempt(PlanChange change, List<ToolCall> toolCalls) {

    public ChangeAttempt {
        toolCalls = List.copyOf(toolCalls);
    }
}
