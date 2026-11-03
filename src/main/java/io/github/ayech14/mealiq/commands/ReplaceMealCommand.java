package io.github.ayech14.mealiq.commands;

import io.github.ayech14.mealiq.domain.MealPlan;
import io.github.ayech14.mealiq.domain.MealSwap;
import io.github.ayech14.mealiq.domain.PlannedMeal;
import io.github.ayech14.mealiq.domain.Recipe;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Puts a different recipe in one meal slot (F10). Undo puts the previous recipe back. */
public class ReplaceMealCommand implements PlanChangeCommand {

    private final UUID mealId;
    private final Recipe newRecipe;
    private Recipe previousRecipe;
    private String slot = "";

    public ReplaceMealCommand(UUID mealId, Recipe newRecipe) {
        this.mealId = Objects.requireNonNull(mealId, "mealId");
        this.newRecipe = Objects.requireNonNull(newRecipe, "newRecipe");
    }

    /** A command that was already executed, rebuilt from storage so it can be undone. */
    public static ReplaceMealCommand restored(MealSwap swap) {
        ReplaceMealCommand command = new ReplaceMealCommand(swap.mealId(), swap.after());
        command.previousRecipe = swap.before();
        return command;
    }

    @Override
    public void execute(MealPlan plan) {
        PlannedMeal meal = plan.findMeal(mealId);
        slot = slot(meal);
        previousRecipe = plan.replaceMeal(mealId, newRecipe);
    }

    @Override
    public void undo(MealPlan plan) {
        if (previousRecipe == null) {
            throw new IllegalStateException("Cannot undo a command that was not executed");
        }
        plan.replaceMeal(mealId, previousRecipe);
    }

    @Override
    public String describe() {
        return "Replace " + slot + (previousRecipe == null ? "" : previousRecipe.name()) + " -> " + newRecipe.name();
    }

    @Override
    public List<MealSwap> swaps() {
        return previousRecipe == null ? List.of() : List.of(new MealSwap(mealId, previousRecipe, newRecipe, null, null));
    }

    static String slot(PlannedMeal meal) {
        return meal.date() + " " + meal.mealType().name().toLowerCase(Locale.ROOT) + ": ";
    }
}
