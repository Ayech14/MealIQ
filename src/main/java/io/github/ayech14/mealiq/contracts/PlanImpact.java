package io.github.ayech14.mealiq.contracts;

import io.github.ayech14.mealiq.domain.GroceryItem;
import io.github.ayech14.mealiq.domain.ImpactCause;
import io.github.ayech14.mealiq.domain.PlannedMeal;
import java.util.List;

/**
 * Parts of a plan and grocery list affected by plan-relevant changes (F13), the reasons as
 * readable conflicts, and the changes that caused them.
 */
public record PlanImpact(List<PlannedMeal> affectedMeals, List<GroceryItem> affectedItems, List<String> conflicts,
                         List<ImpactCause> causes) {

    public static final PlanImpact NONE = new PlanImpact(List.of(), List.of(), List.of(), List.of());

    public PlanImpact {
        affectedMeals = affectedMeals == null ? List.of() : List.copyOf(affectedMeals);
        affectedItems = affectedItems == null ? List.of() : List.copyOf(affectedItems);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        causes = causes == null ? List.of() : List.copyOf(causes);
    }

    /** True when nothing in the plan needs attention; the causes alone do not count. */
    public boolean isEmpty() {
        return affectedMeals.isEmpty() && affectedItems.isEmpty() && conflicts.isEmpty();
    }
}
