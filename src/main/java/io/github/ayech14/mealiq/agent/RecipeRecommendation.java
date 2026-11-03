package io.github.ayech14.mealiq.agent;

import io.github.ayech14.mealiq.domain.Recipe;
import java.util.List;

/**
 * What the agent recommends: chosen recipes in order, the LLM's explanation, and the tool
 * calls that led there (shown on request, and used to test agent behavior).
 */
public record RecipeRecommendation(List<Recipe> recipes, String rationale, List<ToolCall> toolCalls) {

    public RecipeRecommendation {
        recipes = List.copyOf(recipes);
        toolCalls = List.copyOf(toolCalls);
    }
}
