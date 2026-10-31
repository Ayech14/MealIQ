package io.github.ayech14.mealiq.contracts;

import io.github.ayech14.mealiq.domain.PlannedMeal;
import io.github.ayech14.mealiq.domain.PlanningMode;
import java.util.List;
import java.util.Objects;

/** Meals proposed by the agent, with its reasoning and any conflicts it could not resolve. */
public record PlanningProposal(List<PlannedMeal> meals, PlanningMode mode, String rationale, List<String> conflicts) {

    public PlanningProposal {
        Objects.requireNonNull(mode, "mode");
        meals = meals == null ? List.of() : List.copyOf(meals);
        rationale = rationale == null ? "" : rationale;
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
    }
}
