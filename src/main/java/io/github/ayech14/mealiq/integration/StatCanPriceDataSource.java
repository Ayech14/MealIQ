package io.github.ayech14.mealiq.integration;

import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.domain.Quantity;
import io.github.ayech14.mealiq.persistence.catalogue.PriceCatalogueLoader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Prices from the packaged Statistics Canada average retail prices (Adapter, F12). A price
 * is the package price scaled to the quantity, so 250 g of a $5.00 per kilogram item costs
 * $1.25. Quantities in another dimension than the package are converted by the catalogue's
 * weights; when that is impossible, or the ingredient has no StatCan product, the price is
 * unknown rather than guessed.
 */
public class StatCanPriceDataSource implements PriceDataSource {

    private final Map<String, PriceCatalogueLoader.Entry> byIngredient;

    public StatCanPriceDataSource(List<PriceCatalogueLoader.Entry> entries) {
        this.byIngredient = entries.stream().collect(Collectors.toMap(PriceCatalogueLoader.Entry::ingredient,
                Function.identity()));
    }

    /** Prices from the packaged {@code prices.csv}. */
    public static StatCanPriceDataSource packaged() {
        return new StatCanPriceDataSource(PriceCatalogueLoader.read());
    }

    @Override
    public Optional<BigDecimal> estimatePrice(Ingredient ingredient, Quantity quantity) {
        PriceCatalogueLoader.Entry entry = byIngredient.get(ingredient.name());
        if (entry == null) {
            return Optional.empty();
        }
        OptionalDouble share = packageShare(ingredient, quantity, entry.packageSize());
        if (share.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(entry.price().multiply(BigDecimal.valueOf(share.getAsDouble()))
                .setScale(4, RoundingMode.HALF_UP));
    }

    /** "Statistics Canada average retail prices, 2026-08" for the newest month in the data. */
    @Override
    public String describe() {
        String month = byIngredient.values().stream().map(PriceCatalogueLoader.Entry::referenceMonth)
                .max(Comparator.naturalOrder()).orElse("unknown month");
        return "Statistics Canada average retail prices, " + month;
    }

    /** How many packages the quantity is, or empty when the two cannot be compared. */
    private static OptionalDouble packageShare(Ingredient ingredient, Quantity quantity, Quantity size) {
        if (quantity.isCompatible(size.unit())) {
            return OptionalDouble.of(quantity.convertTo(size.unit()).amount() / size.amount());
        }
        OptionalDouble grams = ingredient.toGrams(quantity);
        OptionalDouble packageGrams = ingredient.toGrams(size);
        if (grams.isEmpty() || packageGrams.isEmpty() || packageGrams.getAsDouble() == 0) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(grams.getAsDouble() / packageGrams.getAsDouble());
    }
}
