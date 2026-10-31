package io.github.ayech14.mealiq.services;

import io.github.ayech14.mealiq.domain.IngredientRequirement;
import io.github.ayech14.mealiq.domain.MealPlan;
import io.github.ayech14.mealiq.domain.NutritionSummary;
import io.github.ayech14.mealiq.domain.PlannedMeal;
import io.github.ayech14.mealiq.domain.Recipe;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Deterministic calories and macros from the ingredient catalogue (F06, D01, D09).
 *
 * <p>Each ingredient's per-100 g values are scaled by its quantity converted to grams. When
 * an ingredient has no nutrition data, or its quantity cannot be converted to grams, it
 * contributes nothing and the result is marked incomplete. Values are never estimated.
 */
public class NutritionService {

    /**
     * Nutrition for {@code servings} portions of a recipe that yields {@code recipe.servings()}.
     * Optional ingredients are left out, because the recipe works without them.
     */
    public NutritionSummary calculateNutrition(Recipe recipe, int servings) {
        if (servings < 0) {
            throw new IllegalArgumentException("servings cannot be negative");
        }
        NutritionSummary total = NutritionSummary.ZERO;
        for (IngredientRequirement requirement : recipe.ingredients()) {
            if (requirement.optional()) {
                continue;
            }
            OptionalDouble grams = requirement.ingredient().toGrams(requirement.quantity());
            NutritionSummary per100g = requirement.ingredient().nutritionPer100g();
            if (per100g == null || grams.isEmpty()) {
                total = total.add(NutritionSummary.UNKNOWN);
            } else {
                total = total.add(per100g.scaled(grams.getAsDouble() / 100));
            }
        }
        return total.scaled((double) servings / recipe.servings());
    }

    /** Total for a set of planned meals, each at its own serving count. */
    public NutritionSummary calculateNutrition(List<PlannedMeal> meals) {
        NutritionSummary total = NutritionSummary.ZERO;
        for (PlannedMeal meal : meals) {
            total = total.add(calculateNutrition(meal.recipe(), meal.servings()));
        }
        return total;
    }

    /** Totals for every day of the plan in date order, including days without meals. */
    public Map<LocalDate, NutritionSummary> dailyTotals(MealPlan plan) {
        Map<LocalDate, NutritionSummary> totals = new LinkedHashMap<>();
        for (int i = 0; i < plan.days(); i++) {
            LocalDate day = plan.startDate().plusDays(i);
            totals.put(day, calculateNutrition(plan.mealsOn(day)));
        }
        return totals;
    }
}
