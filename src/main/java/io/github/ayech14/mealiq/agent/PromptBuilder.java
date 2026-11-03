package io.github.ayech14.mealiq.agent;

import io.github.ayech14.mealiq.contracts.PlanningContext;
import io.github.ayech14.mealiq.contracts.RecipeQuery;
import io.github.ayech14.mealiq.domain.CostEstimate;
import io.github.ayech14.mealiq.domain.MealPlan;
import io.github.ayech14.mealiq.domain.MealType;
import io.github.ayech14.mealiq.domain.NutritionSummary;
import io.github.ayech14.mealiq.domain.PantryItem;
import io.github.ayech14.mealiq.domain.PlannedMeal;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.UserProfile;
import io.github.ayech14.mealiq.services.BudgetService;
import io.github.ayech14.mealiq.services.NutritionService;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds the agent's prompts from the planning context. Every number in a prompt comes
 * from deterministic services; the LLM is asked to choose and explain, never to calculate.
 */
public class PromptBuilder {

    /** Fixed guidance sent with every request. */
    public static final String INSTRUCTIONS = """
            You are the MealIQ meal-planning agent. You decide which deterministic tool to call next and \
            choose among results those tools return. Never invent recipes, ingredients, nutrition values, \
            prices or cooking times: use only facts from tool results and this prompt. Allergies and \
            dietary rules are hard limits. MealIQ is a planning aid, not medical or dietary advice. \
            Answer only with the JSON object requested.""";

    private final NutritionService nutritionService;
    private final BudgetService budgetService;

    public PromptBuilder(NutritionService nutritionService, BudgetService budgetService) {
        this.nutritionService = nutritionService;
        this.budgetService = budgetService;
    }

    /** Asks for the next tool call, or to finish, given the calls made so far. */
    public String buildToolSelectionPrompt(PlanningContext context, String goal, RecipeQuery filters,
                                           List<String> tools, List<ToolCall> calls, int maxSteps,
                                           List<String> memory) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("TASK: ").append(goal).append('\n');
        appendRequest(prompt, context, filters);
        appendProfile(prompt, context);
        if (!memory.isEmpty()) {
            prompt.append("EARLIER DECISIONS:\n");
            memory.forEach(m -> prompt.append("- ").append(m).append('\n'));
        }
        prompt.append("TOOLS:\n");
        tools.forEach(t -> prompt.append("- ").append(t).append('\n'));
        prompt.append("STEPS SO FAR (").append(calls.size()).append(" of at most ").append(maxSteps).append("):\n");
        if (calls.isEmpty()) {
            prompt.append("(none)\n");
        }
        for (int i = 0; i < calls.size(); i++) {
            prompt.append(i + 1).append(". ").append(calls.get(i).describe()).append('\n');
        }
        prompt.append("""
                Decide the next step. To call a tool, set action to "call_tool", tool to its name and \
                arguments to a JSON object string. If a call returned ERROR, correct it. When the results \
                already give enough suitable candidates, set action to "finish" (tool null, arguments "{}").""");
        return prompt.toString();
    }

    /** Asks the LLM to pick from ranked candidates and explain the choice from the listed facts. */
    public String buildRecommendationPrompt(PlanningContext context, RecipeQuery filters, List<Recipe> ranked,
                                            String strategyName, int maxChoices) {
        StringBuilder prompt = new StringBuilder();
        appendRequest(prompt, context, filters);
        appendProfile(prompt, context);
        prompt.append("CANDIDATES, best first by the ").append(strategyName)
                .append(" ranking (facts are exact, per serving):\n");
        for (int i = 0; i < ranked.size(); i++) {
            prompt.append(i + 1).append(". ").append(facts(ranked.get(i), context)).append('\n');
        }
        prompt.append("Choose up to ").append(maxChoices).append("""
                 recipes from this list that best fit the request, best first, using their exact names. \
                Prefer the ranking unless the request says otherwise. In the rationale (2-4 sentences), \
                explain the choice using only the facts above.""");
        return prompt.toString();
    }

    /**
     * Asks for a full plan built only from the ranked candidates of each meal type. Violations
     * from a rejected attempt are listed so the retry can fix them (SD01).
     */
    public String buildPlanningPrompt(PlanningContext context, Map<MealType, List<Recipe>> candidates,
                                      List<MealType> slots) {
        StringBuilder prompt = new StringBuilder();
        appendRequest(prompt, context, RecipeQuery.any());
        appendProfile(prompt, context);
        LocalDate start = context.request().startDate();
        prompt.append("PLAN: ").append(context.request().days()).append(" day(s) from ").append(start)
                .append(" (day 1 is ").append(start.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH))
                .append("). Each day needs exactly: ")
                .append(slots.stream().map(t -> t.name().toLowerCase(Locale.ROOT)).collect(Collectors.joining(", ")))
                .append(".\n");
        List<String> limits = new ArrayList<>();
        for (int day = 1; day <= context.request().days(); day++) {
            OptionalInt limit = context.profile().maxMinutesFor(start.plusDays(day - 1L).getDayOfWeek());
            if (limit.isPresent()) {
                limits.add("day " + day + ": " + limit.getAsInt() + " min");
            }
        }
        if (!limits.isEmpty()) {
            prompt.append("COOKING-TIME LIMIT PER MEAL: ").append(String.join(", ", limits)).append('\n');
        }
        for (MealType type : slots.stream().distinct().toList()) {
            prompt.append(type.name()).append(" CANDIDATES, best first by the ")
                    .append(context.request().mode().name().toLowerCase(Locale.ROOT).replace('_', ' '))
                    .append(" ranking (exact facts per serving):\n");
            List<Recipe> ranked = candidates.getOrDefault(type, List.of());
            if (ranked.isEmpty()) {
                prompt.append("(none)\n");
            }
            ranked.forEach(r -> prompt.append("- ").append(facts(r, context)).append('\n'));
        }
        if (!context.violations().isEmpty()) {
            prompt.append("YOUR PREVIOUS PLAN WAS REJECTED FOR THESE REASONS; FIX THEM:\n");
            context.violations().forEach(v -> prompt.append("- ").append(v).append('\n'));
        }
        prompt.append("""
                Build the plan: one entry per meal slot, using exact candidate names for that meal type. \
                Respect the cooking-time limits and the grocery budget (the costs are per serving), aim for \
                the calorie and protein targets across each day, use pantry items early, never use the same recipe \
                twice on one day, and vary the meals across days. In the rationale (2-4 sentences), \
                explain the main trade-offs. List anything you could not satisfy in conflicts.""");
        return prompt.toString();
    }

    /** Asks how to change the active plan, using only the candidates the tools found (F10, SD03). */
    public String buildChangePrompt(PlanningContext context, String instruction, List<Recipe> candidates) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("CHANGE REQUEST: ").append(instruction).append('\n');
        appendProfile(prompt, context);
        appendPlan(prompt, context);
        prompt.append("CANDIDATE RECIPES (exact facts per serving):\n");
        if (candidates.isEmpty()) {
            prompt.append("(none)\n");
        }
        candidates.forEach(r -> prompt.append("- ").append(facts(r, context)).append(" [")
                .append(r.mealTypes().stream().map(t -> t.name().toLowerCase(Locale.ROOT)).sorted()
                        .collect(Collectors.joining("/"))).append("]\n"));
        prompt.append("""
                Decide which meals of the plan the request means and what replaces them. Set action to \
                "replace" and list each replacement by day number, meal type and an exact candidate name of \
                that meal type; change only what the request asks for, and never put a recipe twice on one \
                day. If it is unclear which meal is meant, set action to "clarify", leave replacements empty \
                and ask one short question. If the plan already does what is asked, set action to "no_change" \
                and say why. Explain the choice in the rationale (1-3 sentences).""");
        return prompt.toString();
    }

    /** Asks the LLM to choose substitutes from deterministic candidates for the affected meals (F09). */
    public String buildSubstitutionPrompt(PlanningContext context, Map<String, List<String>> candidatesByIngredient,
                                          List<String> affectedMeals) {
        StringBuilder prompt = new StringBuilder();
        appendProfile(prompt, context);
        prompt.append("MEALS USING THE INGREDIENTS TO REPLACE:\n");
        affectedMeals.forEach(m -> prompt.append("- ").append(m).append('\n'));
        prompt.append("ALLOWED SUBSTITUTES, best first (from the ingredient catalogue, already checked against "
                + "allergies, diet and dislikes):\n");
        candidatesByIngredient.forEach((ingredient, options) -> prompt.append("- ").append(ingredient).append(": ")
                .append(options.isEmpty() ? "(none)" : String.join(", ", options)).append('\n'));
        prompt.append("""
                For each ingredient with options, choose one substitute by its exact name. Prefer the first \
                option unless another fits these meals or the user's goals better. Explain in the rationale \
                (1-2 sentences).""");
        return prompt.toString();
    }

    /**
     * Asks for one new recipe (F04, SD05). The ingredient list itself is enforced by the
     * schema; problems with the previous attempt are listed so the retry can fix them.
     */
    public String buildRecipePrompt(PlanningContext context, RecipeQuery query, List<String> previousProblems) {
        StringBuilder prompt = new StringBuilder();
        appendRequest(prompt, context, query);
        appendProfile(prompt, context);
        if (!previousProblems.isEmpty()) {
            prompt.append("YOUR PREVIOUS RECIPE WAS REJECTED; FIX THESE PROBLEMS:\n");
            previousProblems.forEach(p -> prompt.append("- ").append(p).append('\n'));
        }
        prompt.append("""
                Write one realistic home recipe for this request, using only ingredients from the allowed list \
                in the schema, with amounts for the whole recipe and its number of servings. Prefer pantry \
                items. Use g, kg, ml or l unless "unit" (one whole item, such as one egg) is clearly meant. \
                Keep within the time limits. List the steps in order, one per entry, without numbering them. Do \
                not state nutrition values; they are calculated from the ingredients.""");
        return prompt.toString();
    }

    private static void appendPlan(StringBuilder prompt, PlanningContext context) {
        MealPlan plan = context.activePlan();
        prompt.append("CURRENT PLAN (day 1 is ").append(plan.startDate()).append("):\n");
        for (PlannedMeal meal : plan.meals()) {
            long day = ChronoUnit.DAYS.between(plan.startDate(), meal.date()) + 1;
            prompt.append("- day ").append(day).append(" (")
                    .append(meal.date().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH)).append(") ")
                    .append(meal.mealType().name().toLowerCase(Locale.ROOT)).append(": ")
                    .append(meal.recipe().name()).append('\n');
        }
    }

    String facts(Recipe recipe, PlanningContext context) {
        NutritionSummary n = nutritionService.calculateNutrition(recipe, 1);
        List<String> parts = new ArrayList<>();
        parts.add(recipe.totalMinutes() + " min");
        parts.add(n.complete() ? Math.round(n.calories()) + " kcal, " + Math.round(n.proteinG()) + " g protein"
                : "nutrition incomplete");
        CostEstimate cost = budgetService.estimateRecipeCost(recipe, 1, context.pantry(),
                context.request().startDate());
        parts.add(cost + " to buy" + (cost.complete() ? "" : " (some prices unknown)"));
        List<String> fromPantry = recipe.ingredients().stream().filter(l -> !l.optional())
                .filter(l -> context.pantry().shortfall(l, context.request().startDate()).isZero())
                .map(l -> l.ingredient().name()).toList();
        if (!fromPantry.isEmpty()) {
            parts.add("in pantry: " + String.join(", ", fromPantry));
        }
        return recipe.name() + " (" + String.join("; ", parts) + ")";
    }

    private static void appendRequest(StringBuilder prompt, PlanningContext context, RecipeQuery filters) {
        String instruction = context.request().instruction();
        prompt.append("USER REQUEST: ").append(instruction == null || instruction.isBlank() ? "(none)" : instruction)
                .append('\n');
        List<String> required = new ArrayList<>();
        if (filters.mealType() != null) {
            required.add("meal type " + filters.mealType().name().toLowerCase(Locale.ROOT));
        }
        if (filters.maxMinutes() != null) {
            required.add("at most " + filters.maxMinutes() + " minutes");
        }
        if (!filters.includeIngredients().isEmpty()) {
            required.add("uses one of " + String.join(", ", filters.includeIngredients()));
        }
        if (!filters.excludeIngredients().isEmpty()) {
            required.add("without " + String.join(", ", filters.excludeIngredients()));
        }
        if (!required.isEmpty()) {
            prompt.append("REQUIRED FILTERS (applied to every result): ").append(String.join("; ", required))
                    .append('\n');
        }
    }

    private static void appendProfile(StringBuilder prompt, PlanningContext context) {
        UserProfile p = context.profile();
        int meals = Math.max(1, p.mealsPerDay());
        List<String> facts = new ArrayList<>();
        facts.add("goal " + p.goal().name().toLowerCase(Locale.ROOT));
        if (p.dailyCalorieTarget() != null) {
            facts.add("about " + p.dailyCalorieTarget() / meals + " kcal per meal");
        }
        if (p.dailyProteinTargetG() != null) {
            facts.add("about " + p.dailyProteinTargetG() / meals + " g protein per meal");
        }
        facts.add("diet: " + listOrNone(p.dietaryRestrictions()));
        facts.add("allergies: " + listOrNone(p.allergies()));
        facts.add("likes: " + listOrNone(p.preferredFoods()));
        facts.add("dislikes: " + listOrNone(p.dislikedFoods()));
        if (p.weeklyBudget().signum() > 0) {
            facts.add("grocery budget $" + BudgetService.budgetFor(p.weeklyBudget(), context.request().days())
                    + " for " + context.request().days() + " day(s)");
        }
        OptionalInt limit = p.maxMinutesFor(context.request().startDate().getDayOfWeek());
        facts.add("cooking-time limit today: " + (limit.isPresent() ? limit.getAsInt() + " min" : "none"));
        prompt.append("PROFILE: ").append(String.join("; ", facts)).append('\n');
        List<String> pantry = context.pantry().items().values().stream()
                .filter(i -> !i.isExpired(context.request().startDate()) && !i.quantity().isZero())
                .map(PantryItem::ingredient).map(i -> i.name()).sorted().toList();
        prompt.append("PANTRY: ").append(pantry.isEmpty() ? "empty" : String.join(", ", pantry)).append('\n');
    }

    private static String listOrNone(Set<String> values) {
        return values.isEmpty() ? "none" : values.stream().sorted().collect(Collectors.joining(", "));
    }
}
