package io.github.ayech14.mealiq.contracts;

import io.github.ayech14.mealiq.domain.MealType;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.RecipeSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * Recipe search criteria. Null fields mean "no limit". Ingredient terms match catalogue
 * names that contain them ("chicken" matches "chicken breast").
 */
public record RecipeQuery(MealType mealType, Integer maxMinutes, List<String> includeIngredients,
                          List<String> excludeIngredients, BigDecimal maxCostPerServing, boolean allowGenerated) {

    public RecipeQuery {
        if (maxMinutes != null && maxMinutes <= 0) {
            throw new IllegalArgumentException("maxMinutes must be positive");
        }
        if (maxCostPerServing != null && maxCostPerServing.signum() < 0) {
            throw new IllegalArgumentException("maxCostPerServing cannot be negative");
        }
        includeIngredients = includeIngredients == null ? List.of() : List.copyOf(includeIngredients);
        excludeIngredients = excludeIngredients == null ? List.of() : List.copyOf(excludeIngredients);
    }

    /**
     * Whether a recipe meets every filter except cost (which needs prices): meal type, total
     * time, source, at least one included ingredient, and none of the excluded ones.
     * Substitution variants never match; they belong to the plans that use them.
     */
    public boolean matches(Recipe recipe) {
        List<String> include = terms(includeIngredients);
        List<String> exclude = terms(excludeIngredients);
        return (mealType == null || recipe.mealTypes().contains(mealType))
                && (maxMinutes == null || recipe.totalMinutes() <= maxMinutes)
                && recipe.source() != RecipeSource.VARIANT
                && (allowGenerated || recipe.source() != RecipeSource.GENERATED)
                && (include.isEmpty() || usesAny(recipe, include))
                && !usesAny(recipe, exclude);
    }

    private static List<String> terms(List<String> values) {
        return values.stream().map(v -> v.strip().toLowerCase(Locale.ROOT)).filter(v -> !v.isEmpty()).toList();
    }

    private static boolean usesAny(Recipe recipe, List<String> terms) {
        return recipe.ingredients().stream().map(line -> line.ingredient().name())
                .anyMatch(name -> terms.stream().anyMatch(name::contains));
    }

    public static RecipeQuery any() {
        return new RecipeQuery(null, null, List.of(), List.of(), null, false);
    }

    public RecipeQuery withMealType(MealType value) {
        return new RecipeQuery(value, maxMinutes, includeIngredients, excludeIngredients, maxCostPerServing,
                allowGenerated);
    }

    public RecipeQuery withMaxMinutes(Integer value) {
        return new RecipeQuery(mealType, value, includeIngredients, excludeIngredients, maxCostPerServing,
                allowGenerated);
    }

    public RecipeQuery including(String... ingredients) {
        return new RecipeQuery(mealType, maxMinutes, List.of(ingredients), excludeIngredients, maxCostPerServing,
                allowGenerated);
    }

    public RecipeQuery excluding(String... ingredients) {
        return new RecipeQuery(mealType, maxMinutes, includeIngredients, List.of(ingredients), maxCostPerServing,
                allowGenerated);
    }

    public RecipeQuery withMaxCostPerServing(BigDecimal value) {
        return new RecipeQuery(mealType, maxMinutes, includeIngredients, excludeIngredients, value, allowGenerated);
    }

    public RecipeQuery withGenerated(boolean value) {
        return new RecipeQuery(mealType, maxMinutes, includeIngredients, excludeIngredients, maxCostPerServing,
                value);
    }
}
