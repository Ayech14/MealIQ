package io.github.ayech14.mealiq.planning;

import io.github.ayech14.mealiq.contracts.PlanningContext;
import io.github.ayech14.mealiq.domain.NutritionSummary;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.UserProfile;
import io.github.ayech14.mealiq.domain.WeightGoal;
import io.github.ayech14.mealiq.services.NutritionService;

/**
 * Ranks by how close one serving is to the profile's per-meal calorie and protein targets
 * (F05, F06). Without targets it follows the weight goal: lighter meals for LOSE, more
 * energy-dense ones for GAIN, and protein-rich meals in every case.
 */
public class NutritionGoalStrategy implements PlanningStrategy {

    static final double CALORIE_WEIGHT = 0.55;
    static final double PROTEIN_WEIGHT = 0.35;
    static final double PANTRY_WEIGHT = 0.10;
    /** Over target when losing, or under it when gaining, counts this much more. */
    static final double WRONG_DIRECTION_PENALTY = 1.5;
    /** Share of calories from protein that earns the full protein score without a target. */
    static final double FULL_PROTEIN_SHARE = 0.35;
    static final double REFERENCE_MEAL_KCAL = 1000;

    private final NutritionService nutritionService;
    private WeightGoal goal = WeightGoal.MAINTAIN;
    private Double caloriesPerMeal;
    private Double proteinPerMeal;

    public NutritionGoalStrategy(NutritionService nutritionService) {
        this.nutritionService = nutritionService;
    }

    @Override
    public void configure(UserProfile profile) {
        goal = profile.goal();
        int meals = Math.max(1, profile.mealsPerDay());
        caloriesPerMeal = profile.dailyCalorieTarget() == null ? null : (double) profile.dailyCalorieTarget() / meals;
        proteinPerMeal = profile.dailyProteinTargetG() == null ? null : (double) profile.dailyProteinTargetG() / meals;
    }

    @Override
    public double score(Recipe recipe, PlanningContext context) {
        NutritionSummary serving = nutritionService.calculateNutrition(recipe, 1);
        if (!serving.complete()) {
            return UNSCORED; // never rank on partial data (D09)
        }
        return CALORIE_WEIGHT * calorieFit(serving.calories())
                + PROTEIN_WEIGHT * proteinFit(serving)
                + PANTRY_WEIGHT * PantryFit.share(recipe, context);
    }

    double calorieFit(double kcal) {
        if (caloriesPerMeal == null) {
            double size = Math.min(kcal / REFERENCE_MEAL_KCAL, 1);
            return switch (goal) {
                case LOSE -> 1 - size;
                case GAIN -> size;
                case MAINTAIN -> 0.5;
            };
        }
        double deviation = (kcal - caloriesPerMeal) / caloriesPerMeal;
        boolean wrongWay = (goal == WeightGoal.LOSE && deviation > 0) || (goal == WeightGoal.GAIN && deviation < 0);
        return Math.max(0, 1 - Math.abs(deviation) * (wrongWay ? WRONG_DIRECTION_PENALTY : 1));
    }

    double proteinFit(NutritionSummary serving) {
        if (proteinPerMeal != null) {
            return proteinPerMeal == 0 ? 1 : Math.min(1, serving.proteinG() / proteinPerMeal);
        }
        if (serving.calories() <= 0) {
            return 0;
        }
        double proteinShare = serving.proteinG() * 4 / serving.calories();
        return Math.min(1, proteinShare / FULL_PROTEIN_SHARE);
    }
}
