package io.github.ayech14.mealiq.agent;

import io.github.ayech14.mealiq.contracts.PlanningContext;
import io.github.ayech14.mealiq.contracts.RecipeQuery;
import io.github.ayech14.mealiq.domain.MealType;
import io.github.ayech14.mealiq.domain.NutritionSummary;
import io.github.ayech14.mealiq.domain.PantryItem;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.UserProfile;
import io.github.ayech14.mealiq.services.NutritionService;
import java.time.LocalDate;
import java.time.format.TextStyle;
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

    public PromptBuilder(NutritionService nutritionService) {
        this.nutritionService = nutritionService;
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
                Respect the cooking-time limits, aim for the calorie and protein targets across each day, \
                use pantry items early, and vary the meals across days. In the rationale (2-4 sentences), \
                explain the main trade-offs. List anything you could not satisfy in conflicts.""");
        return prompt.toString();
    }

    String facts(Recipe recipe, PlanningContext context) {
        NutritionSummary n = nutritionService.calculateNutrition(recipe, 1);
        List<String> parts = new ArrayList<>();
        parts.add(recipe.totalMinutes() + " min");
        parts.add(n.complete() ? Math.round(n.calories()) + " kcal, " + Math.round(n.proteinG()) + " g protein"
                : "nutrition incomplete");
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
