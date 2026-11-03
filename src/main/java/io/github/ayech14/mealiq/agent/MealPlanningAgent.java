package io.github.ayech14.mealiq.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ayech14.mealiq.contracts.PlanningContext;
import io.github.ayech14.mealiq.contracts.RecipeQuery;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.llm.JsonSchema;
import io.github.ayech14.mealiq.llm.LLMClient;
import io.github.ayech14.mealiq.planning.PlanningStrategy;
import io.github.ayech14.mealiq.planning.PlanningStrategyFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The AI agent (D07). The LLM chooses tools in a loop bounded by {@code maxToolSteps}, then
 * chooses among candidates that a deterministic strategy has already ranked, and explains.
 * It returns recommendations; it never saves or changes anything itself.
 */
public class MealPlanningAgent {

    /** Candidates shown to the LLM for the final choice. */
    static final int CANDIDATES_SHOWN = 8;
    static final int MAX_CHOICES = 3;

    static final JsonSchema NEXT_ACTION = JsonSchema.object("next_action")
            .property("action", JsonSchema.enumOf("call_tool", "finish"))
            .property("tool", JsonSchema.nullable(JsonSchema.string()))
            .property("arguments", described(JsonSchema.string(), "the tool arguments as a JSON object, or {}"))
            .property("reason", described(JsonSchema.string(), "one short sentence: why this step"))
            .build();

    static final JsonSchema RECOMMENDATION = JsonSchema.object("recipe_recommendation")
            .property("recipes", JsonSchema.arrayOf(JsonSchema.string()))
            .property("rationale", JsonSchema.string())
            .build();

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LLMClient llmClient;
    private final ToolManager toolManager;
    private final PromptBuilder promptBuilder;
    private final ConversationMemory memory;
    private final PlanningStrategyFactory strategyFactory;
    private final int maxToolSteps;

    public MealPlanningAgent(LLMClient llmClient, ToolManager toolManager, PromptBuilder promptBuilder,
                             ConversationMemory memory, PlanningStrategyFactory strategyFactory, int maxToolSteps) {
        this.llmClient = llmClient;
        this.toolManager = toolManager;
        this.promptBuilder = promptBuilder;
        this.memory = memory;
        this.strategyFactory = strategyFactory;
        this.maxToolSteps = maxToolSteps;
    }

    /**
     * Recipes for the request in {@code context}, best first, with the LLM's explanation (F03).
     * Every candidate comes from a tool and also meets the user's explicit {@code filters},
     * whatever arguments the LLM chose.
     *
     * @throws io.github.ayech14.mealiq.llm.LLMException if the provider fails
     */
    public RecipeRecommendation recommendRecipes(PlanningContext context, RecipeQuery filters) {
        UUID profileId = context.request().profileId();
        String goal = "Find recipes for the user's request. Use recipeSearch (you may refine it), and "
                + "nutritionAnalysis or substituteIngredient if they help.";
        List<ToolCall> calls = runToolLoop(context, goal, filters,
                memory.retrieveRelevantContext(profileId, context.request().instruction()));

        Map<UUID, Recipe> found = new LinkedHashMap<>();
        for (ToolCall call : calls) {
            if (call.result().success() && call.result().data() instanceof List<?> items) {
                items.stream().filter(Recipe.class::isInstance).map(Recipe.class::cast)
                        .filter(filters::matches).forEach(r -> found.putIfAbsent(r.id(), r));
            }
        }
        if (found.isEmpty()) {
            return new RecipeRecommendation(List.of(),
                    "No recipe from the searches fits the request and your restrictions.", calls);
        }
        List<Recipe> ranked = rankRecipes(new ArrayList<>(found.values()), context);
        List<Recipe> shown = ranked.stream().limit(CANDIDATES_SHOWN).toList();
        Map<String, Object> answer = llmClient.generateStructured(
                promptBuilder.buildRecommendationPrompt(context, filters, shown,
                        context.request().mode().name().toLowerCase(Locale.ROOT).replace('_', ' '), MAX_CHOICES),
                RECOMMENDATION, PromptBuilder.INSTRUCTIONS);

        List<Recipe> chosen = new ArrayList<>();
        for (Object name : (List<?>) answer.get("recipes")) {
            shown.stream().filter(r -> r.name().equalsIgnoreCase(String.valueOf(name).strip())).findFirst()
                    .filter(r -> !chosen.contains(r)).ifPresent(chosen::add);
        }
        String rationale = (String) answer.get("rationale");
        if (chosen.isEmpty()) {
            // The LLM named nothing from the list: fall back to the deterministic ranking.
            chosen.addAll(shown.stream().limit(MAX_CHOICES).toList());
            rationale = "These are the top-ranked recipes; the AI's own choice did not match the candidates.";
        }
        List<Recipe> result = chosen.stream().limit(MAX_CHOICES).toList();
        memory.recordDecision(profileId, "Recommended " + String.join(", ", result.stream().map(Recipe::name).toList())
                + " for \"" + context.request().instruction() + "\"");
        return new RecipeRecommendation(result, rationale, calls);
    }

    /** Orders candidates with the strategy for the context's planning mode (Strategy, Factory Method). */
    public List<Recipe> rankRecipes(List<Recipe> candidates, PlanningContext context) {
        PlanningStrategy strategy = strategyFactory.strategyFor(context);
        return strategy.rank(candidates, context);
    }

    /**
     * Lets the LLM call tools until it finishes or {@code maxToolSteps} calls are made.
     * Invalid calls are not run; their errors become the next observation (D07).
     */
    List<ToolCall> runToolLoop(PlanningContext context, String goal, RecipeQuery filters, List<String> recalled) {
        List<ToolCall> calls = new ArrayList<>();
        while (calls.size() < maxToolSteps) {
            String prompt = promptBuilder.buildToolSelectionPrompt(context, goal, filters,
                    toolManager.describeTools(), calls, maxToolSteps, recalled);
            Map<String, Object> next = llmClient.generateStructured(prompt, NEXT_ACTION, PromptBuilder.INSTRUCTIONS);
            if ("finish".equals(next.get("action"))) {
                break;
            }
            String tool = (String) next.get("tool");
            String arguments = (String) next.get("arguments");
            ToolResult result;
            try {
                Map<String, Object> args = JSON.readValue(arguments.isBlank() ? "{}" : arguments,
                        new TypeReference<Map<String, Object>>() { });
                result = toolManager.execute(tool, args, context);
            } catch (JsonProcessingException e) {
                result = ToolResult.failed(String.valueOf(tool), "arguments must be a JSON object, got: " + arguments);
            }
            calls.add(new ToolCall(String.valueOf(tool), arguments, (String) next.get("reason"), result));
        }
        return calls;
    }

    private static Map<String, Object> described(Map<String, Object> type, String description) {
        Map<String, Object> copy = new LinkedHashMap<>(type);
        copy.put("description", description);
        return copy;
    }
}
