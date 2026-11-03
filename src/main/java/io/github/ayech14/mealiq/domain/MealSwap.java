package io.github.ayech14.mealiq.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * One meal slot's recipe before and after a confirmed change. For an ingredient
 * substitution, {@code original} and {@code substitute} name the swapped ingredients.
 */
public record MealSwap(UUID mealId, Recipe before, Recipe after, Ingredient original, Ingredient substitute) {

    public MealSwap {
        Objects.requireNonNull(mealId, "mealId");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
    }
}
