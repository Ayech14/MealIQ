package io.github.ayech14.mealiq.planning;

import io.github.ayech14.mealiq.contracts.PlanningContext;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.UserProfile;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One way of ranking candidate recipes (Strategy). Scores are deterministic, so the LLM
 * chooses among recipes that are already ordered by fit and never computes the ranking (D01).
 */
public interface PlanningStrategy {

    /** Score for a recipe whose fit cannot be measured (missing nutrition or price data); ranked last. */
    double UNSCORED = -1;

    /** Reads the targets and limits this strategy uses from the profile; called before ranking. */
    void configure(UserProfile profile);

    /** Higher is better: 0 to 1 for a measurable fit, below 0 for a recipe that breaks a limit or cannot be scored. */
    double score(Recipe recipe, PlanningContext context);

    /** Candidates ordered by score; ties go to the quicker recipe, then by name. Nothing is removed. */
    default List<Recipe> rank(List<Recipe> candidates, PlanningContext context) {
        Map<UUID, Double> scores = new HashMap<>();
        candidates.forEach(r -> scores.put(r.id(), score(r, context)));
        return candidates.stream()
                .sorted(Comparator.comparingDouble((Recipe r) -> -scores.get(r.id()))
                        .thenComparingInt(Recipe::totalMinutes)
                        .thenComparing(Recipe::name))
                .toList();
    }
}
