package io.github.ayech14.mealiq.agent;

import io.github.ayech14.mealiq.contracts.PlanningContext;
import io.github.ayech14.mealiq.contracts.RecipeQuery;
import io.github.ayech14.mealiq.domain.NutritionSummary;
import io.github.ayech14.mealiq.domain.PlannedMeal;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.persistence.RecipeRepository;
import io.github.ayech14.mealiq.services.NutritionService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code nutritionAnalysis}: calories and macros of named recipes from catalogue data (F06).
 * The LLM must use these values instead of estimating its own (D01, D09).
 */
public class NutritionTool implements AgentTool {

    public static final String NAME = "nutritionAnalysis";
    private static final Set<String> ARGS = Set.of("recipes", "servings");

    private final NutritionService nutritionService;
    private final RecipeRepository recipeRepository;

    public NutritionTool(NutritionService nutritionService, RecipeRepository recipeRepository) {
        this.nutritionService = nutritionService;
        this.recipeRepository = recipeRepository;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String description() {
        return "exact calories, protein, carbs and fat of recipes. Arguments: recipes (list of recipe names, "
                + "required), servings (whole number per recipe, default 1)";
    }

    @Override
    public List<String> checkArguments(Map<String, Object> args) {
        ToolArguments read = new ToolArguments(args, ARGS);
        read.positiveInt("servings");
        for (String name : read.texts("recipes", true)) {
            if (find(name).isEmpty()) {
                read.problems().add("no recipe named '" + name + "'");
            }
        }
        return read.problems();
    }

    /** Total for planned meals, each at its own serving count. */
    public NutritionSummary analyze(List<PlannedMeal> meals) {
        return nutritionService.calculateNutrition(meals);
    }

    @Override
    public ToolResult execute(Map<String, Object> args, PlanningContext context) {
        ToolArguments read = new ToolArguments(args, ARGS);
        Integer servingsArg = read.positiveInt("servings");
        int servings = servingsArg == null ? 1 : servingsArg;
        Map<String, NutritionSummary> results = new LinkedHashMap<>();
        StringBuilder summary = new StringBuilder("For " + servings + " serving(s):");
        for (String name : read.texts("recipes", true)) {
            Recipe recipe = find(name).orElseThrow();
            NutritionSummary n = nutritionService.calculateNutrition(recipe, servings);
            results.put(recipe.name(), n);
            summary.append("\n- ").append(recipe.name()).append(": ").append(n.complete()
                    ? String.format(Locale.ROOT, "%d kcal, %.0f g protein, %.0f g carbs, %.0f g fat",
                            Math.round(n.calories()), n.proteinG(), n.carbsG(), n.fatG())
                    : "incomplete (an ingredient has no catalogue data)");
        }
        return ToolResult.ok(NAME, results, summary.toString());
    }

    private Optional<Recipe> find(String name) {
        return recipeRepository.search(RecipeQuery.any().withGenerated(true)).stream()
                .filter(r -> r.name().equalsIgnoreCase(name.strip())).findFirst();
    }
}
