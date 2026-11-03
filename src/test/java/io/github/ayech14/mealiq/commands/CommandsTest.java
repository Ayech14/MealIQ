package io.github.ayech14.mealiq.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.domain.MealPlan;
import io.github.ayech14.mealiq.domain.MealSwap;
import io.github.ayech14.mealiq.domain.MealType;
import io.github.ayech14.mealiq.domain.PlannedMeal;
import io.github.ayech14.mealiq.domain.Quantity;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.RecipeSource;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CommandsTest {

    static final LocalDate DAY = LocalDate.of(2026, 11, 3);

    final Ingredient milk = Ingredient.of("milk", "dairy", "milk");
    final Ingredient soyMilk = Ingredient.of("soy milk", "plant-milk", "soy");
    final Ingredient oats = Ingredient.of("rolled oats", "grain", "gluten");
    final Recipe oatmeal = Recipe.builder("Oatmeal").mealTypes(MealType.BREAKFAST)
            .ingredient(oats, 50, "g").ingredient(milk, 200, "ml").build();
    final Recipe pancakes = Recipe.builder("Pancakes").mealTypes(MealType.BREAKFAST).ingredient(oats, 80, "g")
            .build();
    final PlannedMeal breakfast = new PlannedMeal(DAY, MealType.BREAKFAST, oatmeal, 1);
    final PlannedMeal secondBreakfast = new PlannedMeal(DAY.plusDays(1), MealType.BREAKFAST, oatmeal, 1);
    final MealPlan plan = new MealPlan(UUID.randomUUID(), DAY, 2, List.of(breakfast, secondBreakfast));

    @Test
    void replaceAndUndo() {
        ReplaceMealCommand command = new ReplaceMealCommand(breakfast.id(), pancakes);
        command.execute(plan);
        assertEquals("Pancakes", plan.findMeal(breakfast.id()).recipe().name());
        assertEquals("Replace 2026-11-03 breakfast: Oatmeal -> Pancakes", command.describe());
        assertEquals(List.of(new MealSwap(breakfast.id(), oatmeal, pancakes, null, null)), command.swaps());
        command.undo(plan);
        assertEquals("Oatmeal", plan.findMeal(breakfast.id()).recipe().name());
    }

    @Test
    void undoBeforeExecuteIsRefused() {
        assertThrows(IllegalStateException.class, () -> new ReplaceMealCommand(breakfast.id(), pancakes).undo(plan));
        assertTrue(new ReplaceMealCommand(breakfast.id(), pancakes).swaps().isEmpty());
    }

    @Test
    void variantSwapsTheIngredientAndHasAStableId() {
        Recipe variant = SubstituteIngredientCommand.variant(oatmeal, milk, soyMilk);
        assertEquals("Oatmeal (soy milk for milk)", variant.name());
        assertEquals(RecipeSource.VARIANT, variant.source());
        assertEquals(Quantity.of(200, "ml"), variant.ingredients().get(1).quantity());
        assertEquals("soy milk", variant.ingredients().get(1).ingredient().name());
        assertEquals(variant.id(), SubstituteIngredientCommand.variant(oatmeal, milk, soyMilk).id());
        assertThrows(IllegalArgumentException.class, () -> SubstituteIngredientCommand.variant(pancakes, milk,
                soyMilk));
    }

    @Test
    void substituteAndUndo() {
        SubstituteIngredientCommand command = new SubstituteIngredientCommand(breakfast.id(), milk, soyMilk);
        command.execute(plan);
        assertEquals("Oatmeal (soy milk for milk)", plan.findMeal(breakfast.id()).recipe().name());
        assertEquals("Oatmeal", plan.findMeal(secondBreakfast.id()).recipe().name(), "other meals untouched");
        assertEquals("Substitute 2026-11-03 breakfast: soy milk for milk in Oatmeal", command.describe());
        assertEquals(milk, command.swaps().getFirst().original());
        command.undo(plan);
        assertEquals(oatmeal, plan.findMeal(breakfast.id()).recipe());
    }

    @Test
    void compositeUndoesInReverseOrder() {
        CompositeCommand both = new CompositeCommand(List.of(
                new ReplaceMealCommand(breakfast.id(), pancakes),
                new SubstituteIngredientCommand(secondBreakfast.id(), milk, soyMilk)));
        both.execute(plan);
        assertEquals(2, both.swaps().size());
        assertTrue(both.describe().contains("; "));
        both.undo(plan);
        assertEquals(oatmeal, plan.findMeal(breakfast.id()).recipe());
        assertEquals(oatmeal, plan.findMeal(secondBreakfast.id()).recipe());
        assertThrows(IllegalArgumentException.class, () -> new CompositeCommand(List.of()));
    }

    @Test
    void restoredCommandsUndoLikeTheOriginals() {
        SubstituteIngredientCommand original = new SubstituteIngredientCommand(breakfast.id(), milk, soyMilk);
        original.execute(plan);
        MealSwap stored = original.swaps().getFirst();
        SubstituteIngredientCommand.restored(stored).undo(plan);
        assertEquals(oatmeal, plan.findMeal(breakfast.id()).recipe());

        new ReplaceMealCommand(breakfast.id(), pancakes).execute(plan);
        ReplaceMealCommand.restored(new MealSwap(breakfast.id(), oatmeal, pancakes, null, null)).undo(plan);
        assertEquals(oatmeal, plan.findMeal(breakfast.id()).recipe());
    }

    @Test
    void constraintUpdateReplacesADaysMealsTogether() {
        UpdatePlanConstraintCommand command = new UpdatePlanConstraintCommand(java.time.DayOfWeek.TUESDAY, 15,
                List.of(new ReplaceMealCommand(breakfast.id(), pancakes)));
        command.execute(plan);
        assertEquals("Pancakes", plan.findMeal(breakfast.id()).recipe().name());
        assertEquals("Adapt Tuesday to the 15-minute limit: Replace 2026-11-03 breakfast: Oatmeal -> Pancakes",
                command.describe());
        assertEquals(1, command.swaps().size());
        command.undo(plan);
        assertEquals(oatmeal, plan.findMeal(breakfast.id()).recipe());
        assertThrows(IllegalArgumentException.class,
                () -> new UpdatePlanConstraintCommand(java.time.DayOfWeek.MONDAY, null, List.of()));
    }
}
