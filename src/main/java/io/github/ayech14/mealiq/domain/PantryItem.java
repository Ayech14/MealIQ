package io.github.ayech14.mealiq.domain;

import java.time.LocalDate;
import java.util.Objects;

/** A pantry entry; {@code expiryDate} is null when unknown. */
public record PantryItem(Ingredient ingredient, Quantity quantity, LocalDate expiryDate) {

    public PantryItem {
        Objects.requireNonNull(ingredient, "ingredient");
        Objects.requireNonNull(quantity, "quantity");
    }

    public boolean isExpired(LocalDate on) {
        return expiryDate != null && expiryDate.isBefore(on);
    }
}
