package io.github.ayech14.mealiq.commands;

import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.domain.IngredientRequirement;
import io.github.ayech14.mealiq.domain.MealPlan;
import io.github.ayech14.mealiq.domain.MealSwap;
import io.github.ayech14.mealiq.domain.PlannedMeal;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.RecipeSource;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Swaps one ingredient for another in a planned meal (F09). The meal gets a variant of its
 * recipe; the original recipe is untouched, and undo puts it back.
 */
public class SubstituteIngredientCommand implements PlanChangeCommand {

    private final UUID mealId;
    private final Ingredient original;
    private final Ingredient substitute;
    private Recipe previousRecipe;
    private Recipe variant;
    private String slot = "";

    public SubstituteIngredientCommand(UUID mealId, Ingredient original, Ingredient substitute) {
        this.mealId = Objects.requireNonNull(mealId, "mealId");
        this.original = Objects.requireNonNull(original, "original");
        this.substitute = Objects.requireNonNull(substitute, "substitute");
    }

    /** A command that was already executed, rebuilt from storage so it can be undone. */
    public static SubstituteIngredientCommand restored(MealSwap swap) {
        SubstituteIngredientCommand command = new SubstituteIngredientCommand(swap.mealId(), swap.original(),
                swap.substitute());
        command.previousRecipe = swap.before();
        command.variant = swap.after();
        return command;
    }

    /**
     * The recipe with every use of {@code original} replaced by the same amount of
     * {@code substitute}. Its ID depends only on the inputs, so the same substitution always
     * gives the same stored variant.
     */
    public static Recipe variant(Recipe recipe, Ingredient original, Ingredient substitute) {
        if (recipe.ingredients().stream().noneMatch(l -> l.ingredient().id().equals(original.id()))) {
            throw new IllegalArgumentException(recipe.name() + " does not use " + original.name());
        }
        List<IngredientRequirement> lines = recipe.ingredients().stream()
                .map(l -> l.ingredient().id().equals(original.id())
                        ? new IngredientRequirement(substitute, l.quantity(), l.optional()) : l)
                .toList();
        String key = "mealiq:variant:" + recipe.id() + ":" + original.id() + ":" + substitute.id();
        return new Recipe(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)),
                recipe.name() + " (" + substitute.name() + " for " + original.name() + ")", recipe.mealTypes(),
                recipe.prepMinutes(), recipe.cookMinutes(), recipe.servings(), lines, recipe.tags(),
                RecipeSource.VARIANT, recipe.instructions());
    }

    @Override
    public void execute(MealPlan plan) {
        PlannedMeal meal = plan.findMeal(mealId);
        slot = ReplaceMealCommand.slot(meal);
        variant = variant(meal.recipe(), original, substitute);
        previousRecipe = plan.replaceMeal(mealId, variant);
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
        return "Substitute " + slot + substitute.name() + " for " + original.name()
                + (previousRecipe == null ? "" : " in " + previousRecipe.name());
    }

    @Override
    public List<MealSwap> swaps() {
        return previousRecipe == null ? List.of()
                : List.of(new MealSwap(mealId, previousRecipe, variant, original, substitute));
    }
}
