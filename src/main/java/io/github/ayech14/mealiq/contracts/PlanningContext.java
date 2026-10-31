package io.github.ayech14.mealiq.contracts;

import io.github.ayech14.mealiq.domain.BudgetStatus;
import io.github.ayech14.mealiq.domain.MealPlan;
import io.github.ayech14.mealiq.domain.Pantry;
import io.github.ayech14.mealiq.domain.UserProfile;
import java.util.List;
import java.util.Objects;

/**
 * Everything the agent needs for one planning task. It refers to the live domain objects;
 * {@code activePlan} and {@code budgetStatus} may be null.
 */
public record PlanningContext(PlanRequest request, UserProfile profile, Pantry pantry, MealPlan activePlan,
                              BudgetStatus budgetStatus, List<String> priorDecisions, List<String> violations) {

    public PlanningContext {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(pantry, "pantry");
        priorDecisions = priorDecisions == null ? List.of() : List.copyOf(priorDecisions);
        violations = violations == null ? List.of() : List.copyOf(violations);
    }

    public static PlanningContext of(PlanRequest request, UserProfile profile, Pantry pantry) {
        return new PlanningContext(request, profile, pantry, null, null, List.of(), List.of());
    }
}
