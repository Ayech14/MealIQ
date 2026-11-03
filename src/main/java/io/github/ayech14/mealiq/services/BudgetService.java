package io.github.ayech14.mealiq.services;

import io.github.ayech14.mealiq.domain.IngredientRequirement;
import io.github.ayech14.mealiq.domain.Pantry;
import io.github.ayech14.mealiq.domain.Quantity;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.integration.PriceDataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Deterministic cost estimates from a price source (F12, D01). What the pantry already
 * holds costs nothing. A price that is unknown makes the estimate unknown; it is never guessed.
 */
public class BudgetService {

    private final PriceDataSource priceSource;

    public BudgetService(PriceDataSource priceSource) {
        this.priceSource = priceSource;
    }

    /**
     * Cost of buying what is missing for {@code servings} portions of a recipe on a date.
     * Optional ingredients are left out. Empty if any needed ingredient has no price.
     */
    public Optional<BigDecimal> estimateRecipeCost(Recipe recipe, int servings, Pantry pantry, LocalDate on) {
        if (servings < 0) {
            throw new IllegalArgumentException("servings cannot be negative");
        }
        double scale = (double) servings / recipe.servings();
        BigDecimal total = BigDecimal.ZERO;
        for (IngredientRequirement line : recipe.ingredients()) {
            if (line.optional()) {
                continue;
            }
            Quantity needed = new Quantity(line.quantity().amount() * scale, line.quantity().unit());
            Quantity toBuy = pantry.shortfall(new IngredientRequirement(line.ingredient(), needed, false), on);
            if (toBuy.isZero()) {
                continue;
            }
            Optional<BigDecimal> price = priceSource.estimatePrice(line.ingredient(), toBuy);
            if (price.isEmpty()) {
                return Optional.empty();
            }
            total = total.add(price.get());
        }
        return Optional.of(total.setScale(2, RoundingMode.HALF_UP));
    }
}
