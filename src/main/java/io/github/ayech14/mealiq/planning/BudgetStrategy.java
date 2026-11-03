package io.github.ayech14.mealiq.planning;

import io.github.ayech14.mealiq.contracts.PlanningContext;
import io.github.ayech14.mealiq.domain.CostEstimate;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.UserProfile;
import io.github.ayech14.mealiq.services.BudgetService;

/**
 * Ranks by the cost of buying what one serving needs beyond the pantry (F12). With missing
 * prices only the known part of the cost is used, scaled by the share of items that have a
 * price, so a recipe never ranks as cheap just because its prices are unknown.
 */
public class BudgetStrategy implements PlanningStrategy {

    private final BudgetService budgetService;

    public BudgetStrategy(BudgetService budgetService) {
        this.budgetService = budgetService;
    }

    @Override
    public void configure(UserProfile profile) {
        // The weekly budget is checked when a plan is validated; ranking only needs prices.
    }

    @Override
    public double score(Recipe recipe, PlanningContext context) {
        CostEstimate cost = budgetService.estimateRecipeCost(recipe, 1, context.pantry(),
                context.request().startDate());
        return cost.pricedShare() / (1 + cost.known().doubleValue());
    }
}
