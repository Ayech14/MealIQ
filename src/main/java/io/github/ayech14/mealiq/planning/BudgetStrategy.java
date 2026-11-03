package io.github.ayech14.mealiq.planning;

import io.github.ayech14.mealiq.contracts.PlanningContext;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.UserProfile;
import io.github.ayech14.mealiq.services.BudgetService;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * Ranks by the cost of buying what one serving needs beyond the pantry (F12). A recipe
 * whose cost cannot be estimated ranks last rather than being treated as cheap.
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
        Optional<BigDecimal> cost = budgetService.estimateRecipeCost(recipe, 1, context.pantry(),
                context.request().startDate());
        return cost.map(c -> 1 / (1 + c.doubleValue())).orElse(UNSCORED);
    }
}
