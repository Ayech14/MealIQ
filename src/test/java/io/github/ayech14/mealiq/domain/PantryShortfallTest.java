package io.github.ayech14.mealiq.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PantryShortfallTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 3);

    private final Ingredient egg = Ingredient.of("egg", "egg", "egg").withUnitWeights(50.0, null);
    private final Ingredient milk = Ingredient.of("milk", "dairy", "milk").withUnitWeights(null, 1.0);
    private final Ingredient basil = Ingredient.of("basil", "herb");
    private final Pantry pantry = Pantry.emptyFor(UUID.randomUUID());

    private Quantity shortfall(Ingredient ingredient, double amount, String unit) {
        return pantry.shortfall(IngredientRequirement.of(ingredient, amount, unit), TODAY);
    }

    @Test
    void nothingInStockMeansEverythingIsMissing() {
        assertEquals(Quantity.of(3, "unit"), shortfall(egg, 3, "unit"));
    }

    @Test
    void sameDimensionIsConverted() {
        pantry.updateItem(new PantryItem(milk, Quantity.of(1, "l"), null));
        assertTrue(shortfall(milk, 250, "ml").isZero());
        assertEquals(Quantity.of(500, "ml"), shortfall(milk, 1.5, "l").convertTo(Unit.ML));
    }

    @Test
    void otherDimensionsAreComparedByWeight() {
        pantry.updateItem(new PantryItem(egg, Quantity.of(2, "unit"), null)); // 100 g
        assertTrue(shortfall(egg, 100, "g").isZero());
        assertEquals(150, shortfall(egg, 250, "g").amount(), 1e-9);
    }

    @Test
    void stockThatCannotBeWeighedDoesNotCount() {
        pantry.updateItem(new PantryItem(basil, Quantity.of(1, "unit"), null));
        assertEquals(Quantity.of(10, "g"), shortfall(basil, 10, "g"));
    }

    @Test
    void expiredStockDoesNotCount() {
        pantry.updateItem(new PantryItem(egg, Quantity.of(6, "unit"), TODAY.minusDays(1)));
        assertEquals(Quantity.of(2, "unit"), shortfall(egg, 2, "unit"));
    }
}
