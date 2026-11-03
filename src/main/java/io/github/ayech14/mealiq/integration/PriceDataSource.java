package io.github.ayech14.mealiq.integration;

import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.domain.Quantity;
import java.math.BigDecimal;
import java.util.Optional;

/** Grocery prices from some source (Adapter target). Empty when the price is unknown; never guessed. */
public interface PriceDataSource {

    Optional<BigDecimal> estimatePrice(Ingredient ingredient, Quantity quantity);

    /** Where the prices come from, shown next to estimates. */
    default String describe() {
        return "price source not specified";
    }
}
