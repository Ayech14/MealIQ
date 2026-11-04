package io.github.ayech14.mealiq.presentation.gui;

import io.github.ayech14.mealiq.application.MealPlanningFacade;
import io.github.ayech14.mealiq.contracts.ValidationResult;
import io.github.ayech14.mealiq.domain.IngredientRequirement;
import io.github.ayech14.mealiq.domain.NutritionSummary;
import io.github.ayech14.mealiq.domain.Recipe;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.TableColumn;
import javafx.scene.layout.HBox;

/** Small helpers shared by the screens: table columns and recipe descriptions. */
final class Ui {

    static final String DISCLAIMER = "Values come from USDA FoodData Central. A planning aid, not dietary advice.";

    private Ui() {
    }

    /** A horizontal row whose controls and labels line up on their centre. */
    static HBox row(double spacing, Node... nodes) {
        HBox row = new HBox(spacing, nodes);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    static <R> TableColumn<R, String> column(String title, Function<R, String> value, double width) {
        TableColumn<R, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        return column;
    }

    static String kcal(NutritionSummary n) {
        return n.complete() ? String.valueOf(Math.round(n.calories())) : "?";
    }

    static String protein(NutritionSummary n) {
        return n.complete() ? Math.round(n.proteinG()) + " g" : "?";
    }

    /** Everything about a recipe for one serving: ingredients, steps, nutrition, cost and profile notes. */
    static String describe(MealPlanningFacade facade, UUID profileId, Recipe recipe) {
        StringBuilder text = new StringBuilder(recipe.name()).append('\n');
        text.append(String.join(", ", recipe.mealTypes().stream().sorted()
                        .map(t -> t.name().toLowerCase(Locale.ROOT)).toList()))
                .append(" | ").append(recipe.prepMinutes()).append(" min prep + ").append(recipe.cookMinutes())
                .append(" min cooking | makes ").append(recipe.servings()).append(" serving(s)\n");
        if (!recipe.tags().isEmpty()) {
            text.append("Tags: ").append(String.join(", ", recipe.tags().stream().sorted().toList())).append('\n');
        }
        text.append("\nIngredients:\n");
        for (IngredientRequirement line : recipe.ingredients()) {
            text.append("  ").append(line.quantity()).append(' ').append(line.ingredient().name())
                    .append(line.optional() ? " (optional)" : "").append('\n');
        }
        text.append("\nSteps:\n");
        for (int i = 0; i < recipe.instructions().size(); i++) {
            text.append("  ").append(i + 1).append(". ").append(recipe.instructions().get(i)).append('\n');
        }
        NutritionSummary n = facade.recipeNutrition(recipe, 1);
        text.append("\nPer serving: ").append(n.complete() ? String.format(Locale.ROOT,
                "%d kcal, %.0f g protein, %.0f g carbs, %.0f g fat", Math.round(n.calories()), n.proteinG(),
                n.carbsG(), n.fatG()) : "nutrition incomplete").append('\n');
        text.append("Cost per serving beyond your pantry: ").append(facade.recipeCost(profileId, recipe)).append('\n');
        ValidationResult check = facade.checkRecipe(profileId, recipe);
        check.violations().forEach(v -> text.append("Not suitable: ").append(v).append('\n'));
        check.warnings().forEach(w -> text.append("Note: ").append(w).append('\n'));
        return text.append('\n').append(DISCLAIMER).toString();
    }
}
