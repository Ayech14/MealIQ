package io.github.ayech14.mealiq.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ayech14.mealiq.domain.BudgetStatus;
import io.github.ayech14.mealiq.domain.CostEstimate;
import io.github.ayech14.mealiq.domain.GroceryItem;
import io.github.ayech14.mealiq.domain.GroceryList;
import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.domain.Pantry;
import io.github.ayech14.mealiq.domain.PantryItem;
import io.github.ayech14.mealiq.domain.Quantity;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.integration.PriceDataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BudgetServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 3);

    private final InMemoryRepositories.Catalogue catalogue = new InMemoryRepositories.Catalogue();
    private final Ingredient rice = catalogue.findByName("white rice").orElseThrow();
    private final Ingredient egg = catalogue.findByName("egg").orElseThrow();
    private final Ingredient salt = catalogue.findByName("salt").orElseThrow();

    /** $0.50 per 100 g of anything that can be weighed. */
    private final PriceDataSource prices = (ingredient, quantity) -> ingredient.toGrams(quantity).isPresent()
            ? Optional.of(BigDecimal.valueOf(ingredient.toGrams(quantity).getAsDouble() * 0.005))
            : Optional.empty();
    private final BudgetService service = new BudgetService(prices);

    private final Recipe riceAndEggs = Recipe.builder("Rice and eggs").servings(2)
            .ingredient(rice, 200, "g").ingredient(egg, 4, "unit").optionalIngredient(salt, 1, "tsp").build();

    @Test
    void costScalesWithServingsAndSkipsOptionalIngredients() {
        Pantry empty = Pantry.emptyFor(UUID.randomUUID());
        // 200 g rice + 200 g egg = 400 g at $0.005/g
        assertEquals(new CostEstimate(new BigDecimal("2.00"), 2, 0), service.estimateRecipeCost(riceAndEggs, 2, empty, TODAY));
        assertEquals(new BigDecimal("1.00"), service.estimateRecipeCost(riceAndEggs, 1, empty, TODAY).known());
    }

    @Test
    void pantryStockIsFree() {
        Pantry pantry = Pantry.emptyFor(UUID.randomUUID());
        pantry.updateItem(new PantryItem(egg, Quantity.of(12, "unit"), null));
        pantry.updateItem(new PantryItem(rice, Quantity.of(150, "g"), null));
        // eggs covered; 50 g of rice still to buy
        assertEquals(new CostEstimate(new BigDecimal("0.25"), 1, 0), service.estimateRecipeCost(riceAndEggs, 2, pantry, TODAY));
    }

    @Test
    void expiredStockIsNotFree() {
        Pantry pantry = Pantry.emptyFor(UUID.randomUUID());
        pantry.updateItem(new PantryItem(rice, Quantity.of(1, "kg"), TODAY.minusDays(1)));
        assertEquals(new BigDecimal("2.00"), service.estimateRecipeCost(riceAndEggs, 2, pantry, TODAY).known());
    }

    @Test
    void unknownPricesAreCountedNotGuessed() {
        BudgetService partial = new BudgetService((ingredient, quantity) ->
                ingredient.equals(egg) ? Optional.empty() : prices.estimatePrice(ingredient, quantity));
        CostEstimate cost = partial.estimateRecipeCost(riceAndEggs, 2, Pantry.emptyFor(UUID.randomUUID()), TODAY);
        assertEquals(new CostEstimate(new BigDecimal("1.00"), 1, 1), cost);
        assertEquals("$1.00+", cost.toString());
        assertEquals(0.5, cost.pricedShare());
    }

    @Test
    void negativeServingsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.estimateRecipeCost(riceAndEggs, -1, Pantry.emptyFor(UUID.randomUUID()), TODAY));
    }

    @Test
    void evaluateGivesTheExactShortfallAndCostDrivers() {
        GroceryList list = new GroceryList(UUID.randomUUID(), List.of(
                line(rice, "3.10"), line(egg, "1.25"), line(salt, "0.40"),
                line(catalogue.findByName("milk").orElseThrow(), "2.00")));
        BudgetStatus over = service.evaluate(list, new BigDecimal("5"));
        assertEquals(new BigDecimal("6.75"), over.estimate());
        assertEquals(new BigDecimal("1.75"), over.shortfall());
        assertEquals(false, over.withinBudget());
        assertEquals(List.of("white rice", "milk", "egg"),
                over.costDrivers().stream().map(i -> i.ingredient().name()).toList());

        BudgetStatus within = service.evaluate(list, new BigDecimal("10"));
        assertTrue(within.withinBudget());
        assertEquals(new BigDecimal("0.00"), within.shortfall());
        assertEquals(false, within.partial());
    }

    @Test
    void anUnpricedItemMakesTheStatusPartial() {
        GroceryList list = new GroceryList(UUID.randomUUID(), List.of(line(rice, "1.00"),
                new GroceryItem(egg, Quantity.of(2, "unit"), Quantity.of(0, "unit"), Quantity.of(2, "unit"), null)));
        assertTrue(service.evaluate(list, new BigDecimal("50")).partial());
    }

    @Test
    void weeklyBudgetIsScaledToThePlan() {
        assertEquals(new BigDecimal("30.00"), BudgetService.budgetFor(new BigDecimal("70"), 3));
    }

    private static GroceryItem line(Ingredient ingredient, String price) {
        return new GroceryItem(ingredient, Quantity.of(1, "unit"), Quantity.of(0, "unit"), Quantity.of(1, "unit"),
                new BigDecimal(price));
    }
}
