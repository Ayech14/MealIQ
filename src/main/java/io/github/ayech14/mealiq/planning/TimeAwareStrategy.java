package io.github.ayech14.mealiq.planning;

import io.github.ayech14.mealiq.contracts.PlanningContext;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.UserProfile;
import java.util.OptionalInt;

/**
 * Ranks by total cooking time against the profile's limit for the plan's start day (F11).
 * Recipes over the limit get a negative score, so they stay visible as near matches but
 * always rank below every recipe that fits.
 */
public class TimeAwareStrategy implements PlanningStrategy {

    static final double TIME_WEIGHT = 0.8;
    static final double PANTRY_WEIGHT = 0.2;
    /** Without a limit, recipes this long or longer get no time credit. */
    static final int LONG_RECIPE_MINUTES = 120;

    private UserProfile profile;

    @Override
    public void configure(UserProfile value) {
        profile = value;
    }

    @Override
    public double score(Recipe recipe, PlanningContext context) {
        int minutes = recipe.totalMinutes();
        OptionalInt limit = profile == null ? OptionalInt.empty()
                : profile.maxMinutesFor(context.request().startDate().getDayOfWeek());
        double pantry = PANTRY_WEIGHT * PantryFit.share(recipe, context);
        if (limit.isEmpty()) {
            return TIME_WEIGHT * (1 - (double) Math.min(minutes, LONG_RECIPE_MINUTES) / LONG_RECIPE_MINUTES) + pantry;
        }
        int max = limit.getAsInt();
        if (minutes > max) {
            return -(double) (minutes - max) / (max + 1); // further over the limit ranks lower
        }
        return TIME_WEIGHT * (1 - (double) minutes / Math.max(max, 1)) + pantry;
    }
}
