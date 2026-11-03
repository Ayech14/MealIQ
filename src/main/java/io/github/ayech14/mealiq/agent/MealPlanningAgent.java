package io.github.ayech14.mealiq.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ayech14.mealiq.commands.SubstituteIngredientCommand;
import io.github.ayech14.mealiq.contracts.PlanChange;
import io.github.ayech14.mealiq.contracts.PlanImpact;
import io.github.ayech14.mealiq.contracts.PlanningContext;
import io.github.ayech14.mealiq.contracts.PlanningProposal;
import io.github.ayech14.mealiq.contracts.RecipeQuery;
import io.github.ayech14.mealiq.domain.ChangeEventType;
import io.github.ayech14.mealiq.domain.ChangeType;
import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.domain.MealPlan;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    /**
     * Candidates shown for a change. The LLM chose the search filters for its request, so
     * everything its searches found is shown, ranked, up to this cap.
     */
    static final int CHANGE_CANDIDATES = 40;

    static final JsonSchema PLAN_PROPOSAL = JsonSchema.object("plan_proposal")
            .property("meals", JsonSchema.arrayOf(JsonSchema.object("planned_meal")
                    .property("day", described(JsonSchema.integer(), "1 for the first day of the plan"))
                    .property("mealType", JsonSchema.enumOf("breakfast", "lunch", "dinner", "snack"))
                    .property("recipe", described(JsonSchema.string(), "exact candidate name"))
                    .asType()))
            .property("rationale", JsonSchema.string())
            .property("conflicts", JsonSchema.arrayOf(JsonSchema.string()))
            .build();

    static final JsonSchema PLAN_CHANGE = JsonSchema.object("plan_change")
            .property("action", JsonSchema.enumOf("replace", "clarify", "no_change"))
            .property("replacements", JsonSchema.arrayOf(JsonSchema.object("replacement")
                    .property("day", described(JsonSchema.integer(), "1 for the first day of the plan"))
                    .property("mealType", JsonSchema.enumOf("breakfast", "lunch", "dinner", "snack"))
                    .property("recipe", described(JsonSchema.string(), "exact candidate name"))
                    .asType()))
            .property("question", JsonSchema.nullable(JsonSchema.string()))
            .property("rationale", JsonSchema.string())
            .build();

    static final JsonSchema SUBSTITUTION = JsonSchema.object("substitution")
            .property("choices", JsonSchema.arrayOf(JsonSchema.object("choice")
                    .property("ingredient", JsonSchema.string())
                    .property("substitute", JsonSchema.string())
                    .asType()))
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

    /**
     * Interprets a typed change request against the active plan in {@code context} (F10, SD03).
     * The LLM searches for candidates with tools, then picks which meals to replace and with
     * which candidate, or asks one clarification question. The plan itself is not touched.
     *
     * @throws io.github.ayech14.mealiq.llm.LLMException if the provider fails
     */
    public ChangeAttempt proposeChange(PlanningContext context, String instruction) {
        MealPlan plan = context.activePlan();
        String goal = "Find candidate recipes for this change to the plan: \"" + instruction + "\". Search with "
                + "filters that fit the request (meal type, time, ingredients), then finish.";
        List<ToolCall> calls = runToolLoop(context, goal, RecipeQuery.any(),
                memory.retrieveRelevantContext(context.request().profileId(), instruction));
        Map<UUID, Recipe> found = new LinkedHashMap<>();
        for (ToolCall call : calls) {
            if (call.result().success() && call.result().data() instanceof List<?> items) {
                items.stream().filter(Recipe.class::isInstance).map(Recipe.class::cast)
                        .forEach(r -> found.putIfAbsent(r.id(), r));
            }
        }
        List<Recipe> candidates = rankRecipes(new ArrayList<>(found.values()), context).stream()
                .limit(CHANGE_CANDIDATES).toList();
        Map<String, Object> answer = llmClient.generateStructured(
                promptBuilder.buildChangePrompt(context, instruction, candidates), PLAN_CHANGE,
                PromptBuilder.INSTRUCTIONS);
        String rationale = (String) answer.get("rationale");
        if ("clarify".equals(answer.get("action"))) {
            Object question = answer.get("question");
            return new ChangeAttempt(PlanChange.clarification(ChangeType.REPLACE_MEAL,
                    question == null || String.valueOf(question).isBlank()
                            ? "Which meal do you want to change?" : String.valueOf(question)), calls);
        }
        List<PlannedMeal> replacements = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        if ("no_change".equals(answer.get("action"))) {
            // The plan already does what was asked: an empty proposal, explained by the rationale.
            return new ChangeAttempt(PlanChange.proposed(ChangeType.REPLACE_MEAL, List.of(),
                    new PlanningProposal(List.of(), context.request().mode(), rationale, List.of()), rationale), calls);
        }
        for (Object item : (List<?>) answer.get("replacements")) {
            Map<?, ?> entry = (Map<?, ?>) item;
            int day = ((Number) entry.get("day")).intValue();
            MealType type = MealType.valueOf(((String) entry.get("mealType")).toUpperCase(Locale.ROOT));
            String name = ((String) entry.get("recipe")).strip();
            LocalDate date = plan.startDate().plusDays(day - 1L);
            Optional<PlannedMeal> slot = plan.mealsOn(date).stream().filter(m -> m.mealType() == type)
                    .filter(m -> replacements.stream().noneMatch(r -> r.id().equals(m.id()))).findFirst();
            Optional<Recipe> recipe = candidates.stream().filter(r -> r.name().equalsIgnoreCase(name)).findFirst();
            String label = "Day " + day + " " + type.name().toLowerCase(Locale.ROOT);
            if (slot.isEmpty()) {
                problems.add(label + " is not a meal of the plan.");
            } else if (recipe.isEmpty()) {
                problems.add(label + ": '" + name + "' is not one of the candidates.");
            } else if (recipe.get().id().equals(slot.get().recipe().id())) {
                continue; // already planned there: nothing to change for this slot
            } else {
                replacements.add(new PlannedMeal(slot.get().id(), date, type, recipe.get(), slot.get().servings()));
            }
        }
        PlanningProposal proposal = new PlanningProposal(replacements, context.request().mode(), rationale, problems);
        return new ChangeAttempt(PlanChange.proposed(ChangeType.REPLACE_MEAL,
                replacements.stream().map(PlannedMeal::id).toList(), proposal, rationale), calls);
    }

    /**
     * Proposes substitutes for ingredients in every planned meal that uses them (F09). The
     * candidates come from {@code substituteIngredient}, already filtered by the profile; the
     * LLM chooses among them and explains. The plan itself is not touched.
     *
     * @throws io.github.ayech14.mealiq.llm.LLMException if the provider fails
     */
    public ChangeAttempt proposeSubstitution(PlanningContext context, List<Ingredient> targets) {
        MealPlan plan = context.activePlan();
        List<ToolCall> calls = new ArrayList<>();
        Map<String, List<Ingredient>> options = new LinkedHashMap<>();
        for (Ingredient target : targets) {
            String args = "{\"ingredient\": \"" + target.name() + "\"}";
            ToolResult result = toolManager.execute(SubstitutionTool.NAME, Map.of("ingredient", target.name()),
                    context);
            calls.add(new ToolCall(SubstitutionTool.NAME, args, "candidates for " + target.name(), result));
            List<Ingredient> found = result.success() && result.data() instanceof List<?> items
                    ? items.stream().filter(Ingredient.class::isInstance).map(Ingredient.class::cast).toList()
                    : List.of();
            options.put(target.name(), found);
        }
        List<PlannedMeal> affected = plan.meals().stream().filter(m -> targets.stream().anyMatch(t -> uses(m, t)))
                .toList();
        if (affected.isEmpty()) {
            return new ChangeAttempt(PlanChange.clarification(ChangeType.SUBSTITUTE_INGREDIENT, "No meal in the plan "
                    + "uses " + String.join(" or ", targets.stream().map(Ingredient::name).toList())
                    + ". Which ingredient do you mean?"), calls);
        }
        List<String> problems = new ArrayList<>();
        options.forEach((name, found) -> {
            if (found.isEmpty()) {
                problems.add("No compatible substitute for " + name + " in the catalogue.");
            }
        });
        Map<String, Ingredient> chosen = new LinkedHashMap<>();
        String rationale = "";
        if (options.values().stream().anyMatch(o -> !o.isEmpty())) {
            Map<String, List<String>> names = new LinkedHashMap<>();
            options.forEach((name, found) -> names.put(name, found.stream().map(Ingredient::name).toList()));
            Map<String, Object> answer = llmClient.generateStructured(promptBuilder.buildSubstitutionPrompt(context,
                    names, affected.stream().map(m -> m.date() + " " + m.mealType().name().toLowerCase(Locale.ROOT)
                            + ": " + m.recipe().name()).toList()), SUBSTITUTION, PromptBuilder.INSTRUCTIONS);
            rationale = (String) answer.get("rationale");
            for (Object item : (List<?>) answer.get("choices")) {
                Map<?, ?> choice = (Map<?, ?>) item;
                String ingredient = String.valueOf(choice.get("ingredient")).strip();
                String substitute = String.valueOf(choice.get("substitute")).strip();
                options.getOrDefault(ingredient, List.of()).stream()
                        .filter(i -> i.name().equalsIgnoreCase(substitute)).findFirst()
                        .ifPresentOrElse(i -> chosen.put(ingredient, i), () -> problems.add("'" + substitute
                                + "' is not an allowed substitute for " + ingredient + "."));
            }
        }
        List<PlannedMeal> changed = new ArrayList<>();
        for (PlannedMeal meal : affected) {
            Recipe recipe = meal.recipe();
            for (Ingredient target : targets) {
                Ingredient substitute = chosen.get(target.name());
                if (substitute != null && recipe.ingredients().stream()
                        .anyMatch(l -> l.ingredient().id().equals(target.id()))) {
                    recipe = SubstituteIngredientCommand.variant(recipe, target, substitute);
                }
            }
            if (!recipe.equals(meal.recipe())) {
                changed.add(new PlannedMeal(meal.id(), meal.date(), meal.mealType(), recipe, meal.servings()));
            }
        }
        PlanningProposal proposal = new PlanningProposal(changed, context.request().mode(), rationale, problems);
        return new ChangeAttempt(PlanChange.proposed(ChangeType.SUBSTITUTE_INGREDIENT,
                changed.stream().map(PlannedMeal::id).toList(), proposal, rationale), calls);
    }

    /**
     * Proposes the smallest change that resolves a pending impact (F13, SD04): only the
     * affected meals are replaced. A time-limit impact becomes an UPDATE_CONSTRAINT change.
     *
     * @throws io.github.ayech14.mealiq.llm.LLMException if the provider fails
     */
    public ChangeAttempt proposeAdaptation(PlanningContext context, PlanImpact impact) {
        String instruction = "Something changed, and the plan no longer fits. Replace only the meals named below "
                + "with candidates that fit the profile now; leave every other meal as it is.\n- "
                + String.join("\n- ", impact.conflicts());
        ChangeAttempt attempt = proposeChange(context, instruction);
        PlanChange change = attempt.change();
        if (change.needsClarification() || impact.affectedMeals().isEmpty()) {
            return attempt;
        }
        Set<UUID> allowed = new HashSet<>();
        impact.affectedMeals().forEach(m -> allowed.add(m.id()));
        List<PlannedMeal> kept = change.proposal().meals().stream().filter(m -> allowed.contains(m.id())).toList();
        List<String> notes = new ArrayList<>(change.proposal().conflicts());
        if (kept.size() < change.proposal().meals().size()) {
            notes.add("Replacements for meals that were not affected were left out.");
        }
        ChangeType type = impact.causes().stream().allMatch(c -> c.type() == ChangeEventType.TIME_LIMIT_CHANGED)
                ? ChangeType.UPDATE_CONSTRAINT : ChangeType.REPLACE_MEAL;
        PlanningProposal proposal = new PlanningProposal(kept, change.proposal().mode(), change.rationale(), notes);
        return new ChangeAttempt(PlanChange.proposed(type, kept.stream().map(PlannedMeal::id).toList(), proposal,
                change.rationale()), attempt.toolCalls());
    }

    /** Remembers a confirmed decision, so later requests can refer to it. */
    public void recordDecision(UUID profileId, String decision) {
        memory.recordDecision(profileId, decision);
    }

    private static boolean uses(PlannedMeal meal, Ingredient ingredient) {
        return meal.recipe().ingredients().stream().anyMatch(l -> l.ingredient().id().equals(ingredient.id()));
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
