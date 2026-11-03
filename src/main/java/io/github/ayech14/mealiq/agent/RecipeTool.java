package io.github.ayech14.mealiq.agent;

import io.github.ayech14.mealiq.contracts.PlanningContext;
import io.github.ayech14.mealiq.contracts.RecipeQuery;
import io.github.ayech14.mealiq.domain.MealType;
import io.github.ayech14.mealiq.domain.NutritionSummary;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.persistence.RecipeRepository;
import io.github.ayech14.mealiq.services.ConstraintValidator;
import io.github.ayech14.mealiq.services.NutritionService;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code recipeSearch}: finds stored recipes matching the filters (F03). Recipes that break
 * the profile's allergies or diet are never returned, so the LLM cannot pick them (D04).
 */
public class RecipeTool implements AgentTool {

    public static final String NAME = "recipeSearch";
    static final int MAX_LISTED = 15;
    private static final Set<String> ARGS = Set.of("mealType", "maxMinutes", "include", "exclude");

    private final RecipeRepository recipeRepository;
    private final ConstraintValidator constraintValidator;
    private final NutritionService nutritionService;

    public RecipeTool(RecipeRepository recipeRepository, ConstraintValidator constraintValidator,
                      NutritionService nutritionService) {
        this.recipeRepository = recipeRepository;
        this.constraintValidator = constraintValidator;
        this.nutritionService = nutritionService;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String description() {
        return "find recipes that fit the user's allergies and diet. Arguments (all optional): "
                + "mealType (breakfast, lunch, dinner or snack), maxMinutes (whole number), "
                + "include (list of ingredient names, matches recipes using any), "
                + "exclude (list of ingredient names to avoid)";
    }

    @Override
    public List<String> checkArguments(Map<String, Object> args) {
        ToolArguments read = new ToolArguments(args, ARGS);
        query(read);
        return read.problems();
    }

    public List<Recipe> searchRecipes(RecipeQuery query) {
        return recipeRepository.search(query);
    }

    @Override
    public ToolResult execute(Map<String, Object> args, PlanningContext context) {
        RecipeQuery query = query(new ToolArguments(args, ARGS));
        List<Recipe> found = searchRecipes(query);
        List<Recipe> allowed = found.stream()
                .filter(r -> constraintValidator.validateRestrictions(r, context.profile()).valid())
                .toList();
        StringBuilder summary = new StringBuilder(allowed.size() + " recipe(s) found");
        if (allowed.size() < found.size()) {
            summary.append(", ").append(found.size() - allowed.size()).append(" left out for allergies or diet");
        }
        summary.append(allowed.isEmpty() ? "." : ". Per serving:");
        allowed.stream().limit(MAX_LISTED).forEach(r -> summary.append("\n- ").append(line(r)));
        if (allowed.size() > MAX_LISTED) {
            summary.append("\n- ... and ").append(allowed.size() - MAX_LISTED).append(" more");
        }
        return ToolResult.ok(NAME, allowed, summary.toString());
    }

    private String line(Recipe recipe) {
        NutritionSummary n = nutritionService.calculateNutrition(recipe, 1);
        String nutrition = n.complete()
                ? Math.round(n.calories()) + " kcal, " + Math.round(n.proteinG()) + " g protein"
                : "nutrition incomplete";
        return recipe.name() + " (" + recipe.totalMinutes() + " min, " + nutrition + "; "
                + recipe.mealTypes().stream().map(t -> t.name().toLowerCase(Locale.ROOT)).sorted()
                        .collect(Collectors.joining("/")) + ")";
    }

    private static RecipeQuery query(ToolArguments read) {
        String mealType = read.text("mealType", false);
        MealType type = null;
        if (mealType != null) {
            try {
                type = MealType.valueOf(mealType.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                read.problems().add("'mealType' must be one of " + Arrays.stream(MealType.values())
                        .map(t -> t.name().toLowerCase(Locale.ROOT)).toList());
            }
        }
        return new RecipeQuery(type, read.positiveInt("maxMinutes"), read.texts("include", false),
                read.texts("exclude", false), null, false);
    }
}
