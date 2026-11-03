package io.github.ayech14.mealiq.planning;

import io.github.ayech14.mealiq.domain.PlanningMode;
import io.github.ayech14.mealiq.services.BudgetService;
import io.github.ayech14.mealiq.services.NutritionService;

/** Maps each planning mode to its strategy; a new instance each time, so strategies never share state. */
public class DefaultPlanningStrategyFactory extends PlanningStrategyFactory {

    private final NutritionService nutritionService;
    private final BudgetService budgetService;

    public DefaultPlanningStrategyFactory(NutritionService nutritionService, BudgetService budgetService) {
        this.nutritionService = nutritionService;
        this.budgetService = budgetService;
    }

    @Override
    protected PlanningStrategy createStrategy(PlanningMode mode) {
        return switch (mode) {
            case NUTRITION_GOAL -> new NutritionGoalStrategy(nutritionService);
            case BUDGET -> new BudgetStrategy(budgetService);
            case TIME_AWARE -> new TimeAwareStrategy();
        };
    }
}
