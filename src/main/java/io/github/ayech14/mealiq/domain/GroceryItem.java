package io.github.ayech14.mealiq.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One ingredient on a grocery list: what the plan needs, what the pantry has, and what to
 * buy. {@code estimatedPrice} is null when no price is known, which makes the list partial.
 */
public record GroceryItem(Ingredient ingredient, Quantity required, Quantity inPantry, Quantity toBuy,
                          BigDecimal estimatedPrice) {

    public GroceryItem {
        Objects.requireNonNull(ingredient, "ingredient");
        Objects.requireNonNull(required, "required");
        Objects.requireNonNull(inPantry, "inPantry");
        Objects.requireNonNull(toBuy, "toBuy");
    }
}
