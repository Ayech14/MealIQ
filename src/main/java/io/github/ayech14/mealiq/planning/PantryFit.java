package io.github.ayech14.mealiq.planning;

import io.github.ayech14.mealiq.contracts.PlanningContext;
import io.github.ayech14.mealiq.domain.IngredientRequirement;
import io.github.ayech14.mealiq.domain.Recipe;
import java.util.List;

/** How much of a recipe the user can already make from the pantry; every strategy rewards it a little. */
final class PantryFit {

    private PantryFit() {
    }

    /** Share of the required (non-optional) ingredients fully covered by stock on the plan's start date. */
    static double share(Recipe recipe, PlanningContext context) {
        List<IngredientRequirement> required = recipe.ingredients().stream().filter(l -> !l.optional()).toList();
        if (required.isEmpty()) {
            return 0;
        }
        long covered = required.stream()
                .filter(l -> context.pantry().shortfall(l, context.request().startDate()).isZero())
                .count();
        return (double) covered / required.size();
    }
}
