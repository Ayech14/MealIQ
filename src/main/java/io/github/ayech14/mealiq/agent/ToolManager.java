package io.github.ayech14.mealiq.agent;

import io.github.ayech14.mealiq.contracts.PlanningContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tools the agent may use (D07). Every call is checked before it runs; a rejected call
 * is not executed, and its error goes back to the LLM so it can correct itself.
 */
public class ToolManager {

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public void register(AgentTool tool) {
        if (tools.putIfAbsent(tool.getName(), tool) != null) {
            throw new IllegalArgumentException("A tool named " + tool.getName() + " is already registered");
        }
    }

    public List<String> availableTools() {
        return List.copyOf(tools.keySet());
    }

    /** Name and description of each tool, for the prompt. */
    public List<String> describeTools() {
        return tools.values().stream().map(t -> t.getName() + ": " + t.description()).toList();
    }

    public boolean isValidCall(String toolName, Map<String, Object> args) {
        return callProblems(toolName, args).isEmpty();
    }

    /** Why a call would be rejected: unknown tool or bad arguments. Empty when it is valid. */
    public List<String> callProblems(String toolName, Map<String, Object> args) {
        AgentTool tool = toolName == null ? null : tools.get(toolName);
        if (tool == null) {
            return List.of("unknown tool '" + toolName + "'; available: " + String.join(", ", tools.keySet()));
        }
        return tool.checkArguments(args);
    }

    /** Runs a call, or returns the reasons it was rejected as a failed result. Never throws for a bad call. */
    public ToolResult execute(String toolName, Map<String, Object> args, PlanningContext context) {
        List<String> problems = new ArrayList<>(callProblems(toolName, args));
        if (!problems.isEmpty()) {
            return ToolResult.failed(String.valueOf(toolName), String.join("; ", problems));
        }
        return tools.get(toolName).execute(args, context);
    }
}
