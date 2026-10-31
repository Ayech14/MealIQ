package io.github.ayech14.mealiq.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.domain.MealPlan;
import io.github.ayech14.mealiq.domain.MealType;
import io.github.ayech14.mealiq.domain.NutritionSummary;
import io.github.ayech14.mealiq.domain.PlannedMeal;
import io.github.ayech14.mealiq.domain.Recipe;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NutritionServiceTest {

    private static final double EPS = 1e-6;

    // Round numbers so expected totals can be checked by hand.
    private static final Ingredient RICE = Ingredient.of("rice", "grain")
            .withNutrition(new NutritionSummary(360, 7, 80, 1, true)).withUnitWeights(null, 0.8);
    private static final Ingredient EGG = Ingredient.of("egg", "egg", "egg")
            .withNutrition(new NutritionSummary(140, 12.5, 1, 10, true)).withUnitWeights(50.0, null);
    private static final Ingredient OIL = Ingredient.of("oil", "oil")
            .withNutrition(new NutritionSummary(900, 0, 0, 100, true)).withUnitWeights(null, 0.9);
    private static final Ingredient NO_DATA = Ingredient.of("mystery spice", "spice");
    private static final Ingredient NO_UNIT_WEIGHT = Ingredient.of("lettuce", "vegetable")
            .withNutrition(new NutritionSummary(15, 1, 3, 0, true));

    private final NutritionService service = new NutritionService();

    private static Recipe.Builder recipe(int servings) {
        return Recipe.builder("test").mealTypes(MealType.DINNER).minutes(5, 10).servings(servings);
    }

    private static void assertSummary(NutritionSummary actual, double calories, double protein, double carbs,
                                      double fat, boolean complete) {
        assertEquals(calories, actual.calories(), EPS, "calories");
        assertEquals(protein, actual.proteinG(), EPS, "protein");
        assertEquals(carbs, actual.carbsG(), EPS, "carbs");
        assertEquals(fat, actual.fatG(), EPS, "fat");
        assertEquals(complete, actual.complete(), "complete");
    }

    @Test
    void massAndCountQuantities() {
        // 200 g rice = 720 kcal; 2 eggs = 100 g = 140 kcal; whole recipe = 860 kcal for 2 servings
        Recipe r = recipe(2).ingredient(RICE, 200, "g").ingredient(EGG, 2, "unit").build();
        assertSummary(service.calculateNutrition(r, 2), 860, 26.5, 161, 12, true);
        assertSummary(service.calculateNutrition(r, 1), 430, 13.25, 80.5, 6, true);
    }

    @Test
    void volumeUsesCatalogueDensity() {
        // 1 tbsp oil = 14.787 ml * 0.9 g/ml = 13.31 g
        Recipe r = recipe(1).ingredient(OIL, 1, "tbsp").build();
        double grams = 14.78676478125 * 0.9;
        assertSummary(service.calculateNutrition(r, 1), 9 * grams, 0, 0, grams, true);
    }

    @Test
    void otherMassUnitsAreConvertedFirst() {
        Recipe r = recipe(1).ingredient(RICE, 0.2, "kg").build();
        assertEquals(720, service.calculateNutrition(r, 1).calories(), EPS);
    }

    @Test
    void missingNutritionMarksIncompleteAndKeepsTheRest() {
        Recipe r = recipe(1).ingredient(RICE, 100, "g").ingredient(NO_DATA, 5, "g").build();
        assertSummary(service.calculateNutrition(r, 1), 360, 7, 80, 1, false);
    }

    @Test
    void unconvertibleQuantityMarksIncomplete() {
        Recipe r = recipe(1).ingredient(RICE, 100, "g").ingredient(NO_UNIT_WEIGHT, 1, "unit").build();
        assertSummary(service.calculateNutrition(r, 1), 360, 7, 80, 1, false);
    }

    @Test
    void optionalIngredientsAreLeftOut() {
        Recipe r = recipe(1).ingredient(RICE, 100, "g").optionalIngredient(OIL, 10, "g").build();
        assertEquals(360, service.calculateNutrition(r, 1).calories(), EPS);
    }

    @Test
    void zeroServingsIsZeroAndNegativeIsRejected() {
        Recipe r = recipe(1).ingredient(RICE, 100, "g").build();
        assertSummary(service.calculateNutrition(r, 0), 0, 0, 0, 0, true);
        assertThrows(IllegalArgumentException.class, () -> service.calculateNutrition(r, -1));
    }

    @Test
    void mealAndDailyTotals() {
        LocalDate start = LocalDate.of(2026, 10, 26);
        Recipe riceBowl = recipe(1).ingredient(RICE, 100, "g").build(); // 360 kcal per serving
        Recipe eggs = recipe(1).ingredient(EGG, 2, "unit").build();     // 140 kcal per serving
        MealPlan plan = new MealPlan(UUID.randomUUID(), start, 3, List.of(
                new PlannedMeal(start, MealType.BREAKFAST, eggs, 1),
                new PlannedMeal(start, MealType.DINNER, riceBowl, 2),
                new PlannedMeal(start.plusDays(2), MealType.LUNCH, riceBowl, 1)));

        assertEquals(140 + 720 + 360, service.calculateNutrition(plan.meals()).calories(), EPS);

        Map<LocalDate, NutritionSummary> totals = service.dailyTotals(plan);
        assertEquals(List.of(start, start.plusDays(1), start.plusDays(2)), List.copyOf(totals.keySet()));
        assertEquals(860, totals.get(start).calories(), EPS);
        assertSummary(totals.get(start.plusDays(1)), 0, 0, 0, 0, true);
        assertEquals(360, totals.get(start.plusDays(2)).calories(), EPS);
    }

    @Test
    void incompleteMealMarksItsDayIncomplete() {
        LocalDate start = LocalDate.of(2026, 10, 26);
        MealPlan plan = new MealPlan(UUID.randomUUID(), start, 1, List.of(
                new PlannedMeal(start, MealType.DINNER, recipe(1).ingredient(NO_DATA, 1, "g").build(), 1)));
        assertFalse(service.dailyTotals(plan).get(start).complete());
        assertTrue(service.dailyTotals(new MealPlan(UUID.randomUUID(), start, 1, List.of())).get(start).complete());
    }
}
