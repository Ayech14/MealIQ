package io.github.ayech14.mealiq.contracts;

import io.github.ayech14.mealiq.domain.GroceryItem;
import io.github.ayech14.mealiq.domain.PlannedMeal;
import java.util.List;

/** Parts of a plan and grocery list affected by a plan-relevant change (F13). */
public record PlanImpact(List<PlannedMeal> affectedMeals, List<GroceryItem> affectedItems, List<String> conflicts) {

    public static final PlanImpact NONE = new PlanImpact(List.of(), List.of(), List.of());

    public PlanImpact {
        affectedMeals = affectedMeals == null ? List.of() : List.copyOf(affectedMeals);
        affectedItems = affectedItems == null ? List.of() : List.copyOf(affectedItems);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
    }

    public boolean isEmpty() {
        return affectedMeals.isEmpty() && affectedItems.isEmpty() && conflicts.isEmpty();
    }
}
