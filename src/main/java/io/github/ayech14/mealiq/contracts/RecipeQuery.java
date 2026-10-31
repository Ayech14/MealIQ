package io.github.ayech14.mealiq.contracts;

import io.github.ayech14.mealiq.domain.MealType;
import java.math.BigDecimal;
import java.util.List;

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
