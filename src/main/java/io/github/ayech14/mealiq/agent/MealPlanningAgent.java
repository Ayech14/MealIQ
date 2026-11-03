package io.github.ayech14.mealiq.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ayech14.mealiq.contracts.PlanningContext;
import io.github.ayech14.mealiq.contracts.PlanningProposal;
import io.github.ayech14.mealiq.contracts.RecipeQuery;
import io.github.ayech14.mealiq.domain.MealType;
import io.github.ayech14.mealiq.domain.PlannedMeal;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.llm.JsonSchema;
import io.github.ayech14.mealiq.llm.LLMClient;
import io.github.ayech14.mealiq.planning.MealSlots;
import io.github.ayech14.mealiq.planning.PlanningStrategy;
import io.github.ayech14.mealiq.planning.PlanningStrategyFactory;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
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

    /** Ranked candidates per meal type offered for the plan. */
    static final int CANDIDATES_PER_TYPE = 8;

    static final JsonSchema PLAN_PROPOSAL = JsonSchema.object("plan_proposal")
            .property("meals", JsonSchema.arrayOf(JsonSchema.object("planned_meal")
                    .property("day", described(JsonSchema.integer(), "1 for the first day of the plan"))
                    .property("mealType", JsonSchema.enumOf("breakfast", "lunch", "dinner", "snack"))
                    .property("recipe", described(JsonSchema.string(), "exact candidate name"))
                    .asType()))
            .property("rationale", JsonSchema.string())
            .property("conflicts", JsonSchema.arrayOf(JsonSchema.string()))
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

    /**
     * Proposes a multi-day plan (F05, SD01). The LLM gathers candidates with tools, the strategy
     * ranks them per meal type, and the LLM assigns them to slots. Nothing is validated or
     * saved here: the controller does both.
     *
     * @throws io.github.ayech14.mealiq.llm.LLMException if the provider fails
     */
    public PlanAttempt createPlanProposal(PlanningContext context) {
        List<MealType> slots = MealSlots.forDay(context.profile().mealsPerDay());
        List<MealType> types = slots.stream().distinct().toList();
        String goal = "Collect candidate recipes for a " + context.request().days() + "-day plan whose days each "
                + "need: " + String.join(", ", slots.stream().map(t -> t.name().toLowerCase(Locale.ROOT)).toList())
                + ". Call recipeSearch once per meal type (set mealType), within the cooking-time limits. "
                + "Finish when every meal type has candidates.";
        List<ToolCall> calls = new ArrayList<>(runToolLoop(context, goal, RecipeQuery.any(),
                memory.retrieveRelevantContext(context.request().profileId(), context.request().instruction())));

        Map<MealType, List<Recipe>> ranked = new EnumMap<>(MealType.class);
        for (MealType type : types) {
            List<Recipe> found = candidatesOf(calls, type);
            if (found.isEmpty()) {
                // The LLM skipped this meal type; search it so the plan can still be complete.
                String args = "{\"mealType\": \"" + type.name().toLowerCase(Locale.ROOT) + "\"}";
                calls.add(new ToolCall(RecipeTool.NAME, args, "automatic: no candidates yet for this meal type",
                        toolManager.execute(RecipeTool.NAME, Map.of("mealType", type.name()), context)));
                found = candidatesOf(calls, type);
            }
            ranked.put(type, rankRecipes(found, context).stream().limit(CANDIDATES_PER_TYPE).toList());
        }

        Map<String, Object> answer = llmClient.generateStructured(
                promptBuilder.buildPlanningPrompt(context, ranked, slots), PLAN_PROPOSAL, PromptBuilder.INSTRUCTIONS);
        List<PlannedMeal> meals = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        for (Object item : (List<?>) answer.get("meals")) {
            Map<?, ?> entry = (Map<?, ?>) item;
            int day = ((Number) entry.get("day")).intValue();
            MealType type = MealType.valueOf(((String) entry.get("mealType")).toUpperCase(Locale.ROOT));
            String name = ((String) entry.get("recipe")).strip();
            LocalDate date = context.request().startDate().plusDays(day - 1L);
            ranked.values().stream().flatMap(List::stream).filter(r -> r.name().equalsIgnoreCase(name))
                    .findFirst()
                    .ifPresentOrElse(recipe -> meals.add(new PlannedMeal(date, type, recipe, 1)),
                            () -> conflicts.add("Day " + day + " " + type.name().toLowerCase(Locale.ROOT) + ": '"
                                    + name + "' is not one of the candidates, so the slot was left empty."));
        }
        ((List<?>) answer.get("conflicts")).forEach(c -> conflicts.add(String.valueOf(c)));
        PlanningProposal proposal = new PlanningProposal(meals, context.request().mode(),
                (String) answer.get("rationale"), conflicts);
        return new PlanAttempt(proposal, calls);
    }

    /** Distinct recipes of a meal type returned by successful tool calls, in the order found. */
    private static List<Recipe> candidatesOf(List<ToolCall> calls, MealType type) {
        Map<UUID, Recipe> found = new LinkedHashMap<>();
        for (ToolCall call : calls) {
            if (call.result().success() && call.result().data() instanceof List<?> items) {
                items.stream().filter(Recipe.class::isInstance).map(Recipe.class::cast)
                        .filter(r -> r.mealTypes().contains(type)).forEach(r -> found.putIfAbsent(r.id(), r));
            }
        }
        return new ArrayList<>(found.values());
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
