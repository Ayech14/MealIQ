package io.github.ayech14.mealiq.contracts;

import io.github.ayech14.mealiq.domain.BudgetStatus;
import io.github.ayech14.mealiq.domain.MealPlan;
import io.github.ayech14.mealiq.domain.NutritionSummary;
import io.github.ayech14.mealiq.domain.PlannedMeal;
import io.github.ayech14.mealiq.domain.UserProfile;
import java.util.List;
import java.util.Optional;

/**
 * The agent's look at the user's situation, for the home screen: today's meals, the pantry,
 * the shopping list, and what needs attention next. Every number comes from deterministic
 * services; each suggestion carries the request the agent runs if the user accepts it.
 */
public record Checkup(Optional<UserProfile> profile, Optional<MealPlan> plan, List<PlannedMeal> todaysMeals,
                      Optional<NutritionSummary> todaysNutrition, int pantryItems, int expiringSoon,
                      Optional<Integer> itemsToBuy, Optional<BudgetStatus> budget, List<Suggestion> suggestions) {

    public Checkup {
        todaysMeals = List.copyOf(todaysMeals);
        suggestions = List.copyOf(suggestions);
    }

    /** How urgent a suggestion is. */
    public enum Level { WARNING, TIP }

    /**
     * One thing the agent noticed. {@code agentRequest} is what the agent is asked to do when
     * the user accepts (it still previews any change first); {@code page} names a screen to
     * open instead, when the user has to fill something in.
     */
    public record Suggestion(Level level, String title, String detail, String actionLabel, String agentRequest,
                             String page) {
    }
}
