package io.github.ayech14.mealiq.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.domain.Quantity;
import io.github.ayech14.mealiq.persistence.PersistenceException;
import io.github.ayech14.mealiq.persistence.catalogue.PriceCatalogueLoader;
import io.github.ayech14.mealiq.services.InMemoryRepositories;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatCanPriceDataSourceTest {

    final InMemoryRepositories.Catalogue catalogue = new InMemoryRepositories.Catalogue();
    final StatCanPriceDataSource prices = StatCanPriceDataSource.packaged();

    Ingredient ingredient(String name) {
        return catalogue.findByName(name).orElseThrow();
    }

    @Test
    void everyPackagedPriceBelongsToACatalogueIngredientAndCanBeUsed() {
        List<PriceCatalogueLoader.Entry> entries = PriceCatalogueLoader.read();
        assertTrue(entries.size() >= 60, "prices: " + entries.size());
        for (PriceCatalogueLoader.Entry entry : entries) {
            Ingredient ingredient = ingredient(entry.ingredient());
            assertTrue(prices.estimatePrice(ingredient, entry.packageSize()).isPresent(), entry.ingredient());
            assertTrue(entry.statcanProduct().contains(","), "keeps the StatCan product name");
            assertTrue(entry.referenceMonth().matches("\\d{4}-\\d{2}"), entry.referenceMonth());
        }
        assertTrue(prices.describe().startsWith("Statistics Canada average retail prices, 20"), prices.describe());
    }

    @Test
    void priceScalesWithTheQuantity() {
        StatCanPriceDataSource source = new StatCanPriceDataSource(List.of(new PriceCatalogueLoader.Entry(
                "white rice", new BigDecimal("9.80"), Quantity.of(2, "kg"), "White rice, 2 kilograms", "2026-08", "")));
        assertEquals(new BigDecimal("1.2250"), source.estimatePrice(ingredient("white rice"), Quantity.of(250, "g"))
                .orElseThrow(), "250 g is an eighth of the 2 kg bag");
    }

    @Test
    void otherDimensionsAreConvertedByWeight() {
        StatCanPriceDataSource source = new StatCanPriceDataSource(List.of(new PriceCatalogueLoader.Entry("egg",
                new BigDecimal("6.00"), Quantity.of(12, "unit"), "Eggs, 1 dozen", "2026-08", "")));
        assertEquals(new BigDecimal("1.0000"), source.estimatePrice(ingredient("egg"), Quantity.of(2, "unit"))
                .orElseThrow());
        assertEquals(new BigDecimal("1.0000"), source.estimatePrice(ingredient("egg"), Quantity.of(100, "g"))
                .orElseThrow(), "100 g is two 50 g eggs");
    }

    @Test
    void unpricedOrIncomparableIsUnknown() {
        assertTrue(prices.estimatePrice(ingredient("tahini"), Quantity.of(1, "tbsp"))
                .isEmpty(), "tahini has no StatCan product");
        StatCanPriceDataSource basil = new StatCanPriceDataSource(List.of(new PriceCatalogueLoader.Entry("basil",
                new BigDecimal("3.00"), Quantity.of(1, "unit"), "Basil, unit", "2026-08", "")));
        assertTrue(basil.estimatePrice(ingredient("basil"), Quantity.of(10, "g")).isEmpty(),
                "basil has no unit weight, so grams cannot be compared with a unit price");
    }

    @Test
    void badRowsAreRejected() {
        String header = "ingredient,price,package_amount,package_unit,statcan_product,reference_month,note\n";
        assertThrows(PersistenceException.class, () -> PriceCatalogueLoader.parse(header
                + "egg,4.83,12,unit,\"Eggs, 1 dozen\",2026-08,\negg,4.83,12,unit,\"Eggs, 1 dozen\",2026-08,\n"));
        assertThrows(PersistenceException.class, () -> PriceCatalogueLoader.parse(header
                + "egg,0,12,unit,\"Eggs, 1 dozen\",2026-08,\n"));
    }
}
