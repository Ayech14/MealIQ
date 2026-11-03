package io.github.ayech14.mealiq.commands;

import io.github.ayech14.mealiq.domain.MealPlan;
import io.github.ayech14.mealiq.domain.MealSwap;
import java.time.DayOfWeek;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Adapts one weekday's meals to a changed cooking-time limit (F11, F13): the meals over the
 * new limit are replaced together, and undo puts them all back. The limit itself is part
 * of the profile and is not changed by undo.
 */
public class UpdatePlanConstraintCommand implements PlanChangeCommand {

    private final DayOfWeek day;
    private final Integer maxMinutes;
    private final List<ReplaceMealCommand> replacements;

    /** {@code maxMinutes} is the day's new limit, or null when the day has none. */
    public UpdatePlanConstraintCommand(DayOfWeek day, Integer maxMinutes, List<ReplaceMealCommand> replacements) {
        this.day = Objects.requireNonNull(day, "day");
        this.maxMinutes = maxMinutes;
        if (replacements.isEmpty()) {
            throw new IllegalArgumentException("An adaptation replaces at least one meal");
        }
        this.replacements = List.copyOf(replacements);
    }

    @Override
    public void execute(MealPlan plan) {
        replacements.forEach(r -> r.execute(plan));
    }

    @Override
    public void undo(MealPlan plan) {
        replacements.reversed().forEach(r -> r.undo(plan));
    }

    @Override
    public String describe() {
        return "Adapt " + day.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                + (maxMinutes == null ? "" : " to the " + maxMinutes + "-minute limit") + ": "
                + String.join("; ", replacements.stream().map(ReplaceMealCommand::describe).toList());
    }

    @Override
    public List<MealSwap> swaps() {
        return replacements.stream().flatMap(r -> r.swaps().stream()).toList();
    }
}
