package io.github.ayech14.mealiq.domain;

/**
 * Calories and macronutrients. {@code complete} is false when any contributing value was
 * missing or could not be converted (D09); the totals then cover only the known part.
 */
public record NutritionSummary(double calories, double proteinG, double carbsG, double fatG, boolean complete) {

    public static final NutritionSummary ZERO = new NutritionSummary(0, 0, 0, 0, true);
    public static final NutritionSummary UNKNOWN = new NutritionSummary(0, 0, 0, 0, false);

    public NutritionSummary add(NutritionSummary other) {
        return new NutritionSummary(
                calories + other.calories,
                proteinG + other.proteinG,
                carbsG + other.carbsG,
                fatG + other.fatG,
                complete && other.complete);
    }

    public NutritionSummary scaled(double factor) {
        return new NutritionSummary(calories * factor, proteinG * factor, carbsG * factor, fatG * factor, complete);
    }
}
