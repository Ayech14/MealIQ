package io.github.ayech14.mealiq.domain;

import java.util.Objects;

/** One line of a recipe: an ingredient and how much of it the whole recipe uses. */
public record IngredientRequirement(Ingredient ingredient, Quantity quantity, boolean optional) {

    public IngredientRequirement {
        Objects.requireNonNull(ingredient, "ingredient");
        Objects.requireNonNull(quantity, "quantity");
    }

    public static IngredientRequirement of(Ingredient ingredient, double amount, String unit) {
        return new IngredientRequirement(ingredient, Quantity.of(amount, unit), false);
    }
}
