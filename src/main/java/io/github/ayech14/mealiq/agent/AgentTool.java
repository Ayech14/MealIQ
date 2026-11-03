package io.github.ayech14.mealiq.agent;

import io.github.ayech14.mealiq.contracts.PlanningContext;
import java.util.List;
import java.util.Map;

/**
 * A deterministic capability the LLM may call by name (D07). Tools compute facts from data;
 * the LLM only decides which tool to call and with which arguments.
 */
public interface AgentTool {

    String getName();

    /** One line for the LLM: what the tool does and the arguments it takes. */
    String description();

    /** Every problem with the arguments, as readable messages; empty when the call may run. */
    List<String> checkArguments(Map<String, Object> args);

    /** Runs a call whose arguments passed {@link #checkArguments}. */
    ToolResult execute(Map<String, Object> args, PlanningContext context);
}
