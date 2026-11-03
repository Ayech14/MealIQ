package io.github.ayech14.mealiq.agent;

import io.github.ayech14.mealiq.contracts.PlanningProposal;
import java.util.List;

/** One proposal from the agent and the tool calls that led to it. */
public record PlanAttempt(PlanningProposal proposal, List<ToolCall> toolCalls) {

    public PlanAttempt {
        toolCalls = List.copyOf(toolCalls);
    }
}
