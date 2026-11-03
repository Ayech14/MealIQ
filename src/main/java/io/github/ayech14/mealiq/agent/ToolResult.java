package io.github.ayech14.mealiq.agent;

/**
 * The outcome of one tool call. {@code data} holds the typed result for the agent;
 * {@code summary} is the text fed back to the LLM as its observation.
 */
public record ToolResult(String toolName, boolean success, Object data, String summary, String error) {

    public static ToolResult ok(String toolName, Object data, String summary) {
        return new ToolResult(toolName, true, data, summary, null);
    }

    public static ToolResult failed(String toolName, String error) {
        return new ToolResult(toolName, false, null, null, error);
    }

    /** What the LLM sees for this call. */
    public String observation() {
        return success ? summary : "ERROR: " + error;
    }
}
