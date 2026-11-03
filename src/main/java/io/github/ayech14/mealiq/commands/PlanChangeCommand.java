package io.github.ayech14.mealiq.commands;

import io.github.ayech14.mealiq.domain.MealPlan;
import io.github.ayech14.mealiq.domain.MealSwap;
import java.util.List;

/**
 * A confirmed change to a meal plan (Command, D03). Only a command changes a plan, and
 * every command can be undone, so the agent's proposals stay separate from their effect.
 */
public interface PlanChangeCommand {

    void execute(MealPlan plan);

    /** Restores what {@link #execute} changed. */
    void undo(MealPlan plan);

    /** One line for the user, e.g. "Replace Tue dinner: Lentil soup -> Shrimp tacos". */
    String describe();

    /** The meals this command swapped; available after {@link #execute}, and stored for undo. */
    List<MealSwap> swaps();
}
