package io.github.ayech14.mealiq.agent;

/** One step of the tool loop: what the LLM asked for and what came back. */
public record ToolCall(String toolName, String arguments, String reason, ToolResult result) {

    /** A readable line for traces and prompts. */
    public String describe() {
        return toolName + " " + arguments + " -> " + result.observation();
    }
}
