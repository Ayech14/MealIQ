package io.github.ayech14.mealiq.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ayech14.mealiq.application.TestFacades;
import io.github.ayech14.mealiq.domain.GroceryItem;
import io.github.ayech14.mealiq.domain.GroceryList;
import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.domain.MealPlan;
import io.github.ayech14.mealiq.domain.MealType;
import io.github.ayech14.mealiq.domain.Pantry;
import io.github.ayech14.mealiq.domain.PantryItem;
import io.github.ayech14.mealiq.domain.PlannedMeal;
import io.github.ayech14.mealiq.domain.Quantity;
import io.github.ayech14.mealiq.domain.Recipe;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GroceryServiceTest {

    static final LocalDate START = LocalDate.of(2026, 11, 3);

    final InMemoryRepositories.Catalogue catalogue = new InMemoryRepositories.Catalogue();
    final Ingredient rice = catalogue.findByName("white rice").orElseThrow();
    final Ingredient egg = catalogue.findByName("egg").orElseThrow();
    final Ingredient milk = catalogue.findByName("milk").orElseThrow();
    final Ingredient salt = catalogue.findByName("salt").orElseThrow();
    final UUID profileId = UUID.randomUUID();
    final Pantry pantry = Pantry.emptyFor(profileId);
    final InMemoryRepositories.Pantries pantries = new InMemoryRepositories.Pantries();
    final InMemoryRepositories.GroceryLists lists = new InMemoryRepositories.GroceryLists();
    final GroceryService service = new GroceryService(lists, new PantryService(pantries, catalogue),
            TestFacades.FLAT_PRICES);

    /** Serves 2: 300 g rice, 2 eggs, optional salt. */
    final Recipe riceBowl = Recipe.builder("Rice bowl").servings(2).mealTypes(MealType.DINNER)
            .ingredient(rice, 300, "g").ingredient(egg, 2, "unit").optionalIngredient(salt, 1, "tsp").build();
    /** Serves 1: 100 g egg, 250 ml milk. */
    final Recipe custard = Recipe.builder("Custard").servings(1).mealTypes(MealType.SNACK)
            .ingredient(egg, 100, "g").ingredient(milk, 250, "ml").build();

    MealPlan plan(PlannedMeal... meals) {
        return new MealPlan(profileId, START, 2, List.of(meals));
    }

    GroceryItem item(GroceryList list, String name) {
        return list.items().stream().filter(i -> i.ingredient().name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void addsUpEveryMealScaledToServingsWithoutOptionalIngredients() {
        GroceryList list = service.generateList(plan(
                new PlannedMeal(START, MealType.DINNER, riceBowl, 2),
                new PlannedMeal(START.plusDays(1), MealType.DINNER, riceBowl, 1)), pantry);
        assertEquals(Quantity.of(450, "g"), item(list, "white rice").required());
        assertEquals(Quantity.of(3, "unit"), item(list, "egg").required());
        assertTrue(list.items().stream().noneMatch(i -> i.ingredient().equals(salt)));
    }

    @Test
    void differentUnitsOfOneIngredientAreCombinedByWeight() {
        GroceryList list = service.generateList(plan(new PlannedMeal(START, MealType.DINNER, riceBowl, 2),
                new PlannedMeal(START, MealType.SNACK, custard, 1)), pantry);
        assertEquals(Quantity.of(200, "g"), item(list, "egg").required(), "2 eggs of 50 g + 100 g");
        assertEquals(1, list.items().stream().filter(i -> i.ingredient().equals(egg)).count());
    }

    @Test
    void amountsThatCannotBeWeighedStaySeparate() {
        Ingredient herb = Ingredient.of("mystery herb", "herb");
        Recipe a = Recipe.builder("A").ingredient(herb, 10, "g").build();
        Recipe b = Recipe.builder("B").ingredient(herb, 1, "unit").build();
        List<String> lines = service.generateList(plan(new PlannedMeal(START, MealType.DINNER, a, 1),
                        new PlannedMeal(START, MealType.LUNCH, b, 1)), pantry)
                .items().stream().map(i -> i.required().toString()).toList();
        assertEquals(List.of("10 g", "1 unit"), lines);
    }

    @Test
    void pantryStockIsSubtractedAndPriced() {
        pantry.updateItem(new PantryItem(rice, Quantity.of(1, "kg"), null));
        pantry.updateItem(new PantryItem(egg, Quantity.of(1, "unit"), null));
        GroceryList list = service.generateList(plan(new PlannedMeal(START, MealType.DINNER, riceBowl, 2)), pantry);

        GroceryItem riceLine = item(list, "white rice");
        assertEquals(Quantity.of(300, "g"), riceLine.inPantry());
        assertTrue(riceLine.toBuy().isZero());
        assertEquals(BigDecimal.ZERO, riceLine.estimatedPrice());

        GroceryItem eggLine = item(list, "egg");
        assertEquals(Quantity.of(1, "unit"), eggLine.toBuy());
        assertEquals(new BigDecimal("0.50"), eggLine.estimatedPrice(), "one 50 g egg at $1 per 100 g");
        assertEquals(new BigDecimal("0.50"), list.estimatedCost());
        assertEquals(Quantity.of(1, "kg"), pantry.items().get(rice.id()).quantity(), "the real pantry is unchanged");
    }

    @Test
    void expiredStockIsNotUsed() {
        pantry.updateItem(new PantryItem(rice, Quantity.of(1, "kg"), START.minusDays(1)));
        GroceryList list = service.generateList(plan(new PlannedMeal(START, MealType.DINNER, riceBowl, 2)), pantry);
        assertEquals(Quantity.of(300, "g"), item(list, "white rice").toBuy());
    }

    @Test
    void largeAmountsUseLargerUnitsForAllThreeColumns() {
        pantry.updateItem(new PantryItem(rice, Quantity.of(500, "g"), null));
        GroceryList list = service.generateList(plan(new PlannedMeal(START, MealType.DINNER, riceBowl, 10)), pantry);
        GroceryItem line = item(list, "white rice");
        assertEquals("1.5 kg", line.required().toString());
        assertEquals("0.5 kg", line.inPantry().toString());
        assertEquals("1 kg", line.toBuy().toString());
    }

    @Test
    void unknownPricesMakeThePartialListHonest() {
        GroceryService noPrices = new GroceryService(lists, new PantryService(pantries, catalogue),
                (i, q) -> Optional.empty());
        GroceryList list = noPrices.generateList(plan(new PlannedMeal(START, MealType.DINNER, riceBowl, 2)), pantry);
        assertNull(item(list, "egg").estimatedPrice());
        assertTrue(list.isPartial());
        assertEquals(BigDecimal.ZERO, list.total());
    }

    @Test
    void itemsAreGroupedByCategory() {
        GroceryList list = service.generateList(plan(new PlannedMeal(START, MealType.DINNER, riceBowl, 2),
                new PlannedMeal(START, MealType.SNACK, custard, 1)), pantry);
        assertEquals(List.of("milk", "egg", "white rice"), list.items().stream().map(i -> i.ingredient().name())
                .toList(), "dairy, egg, grain");
    }

    @Test
    void refreshSavesOneListPerPlan() {
        MealPlan plan = plan(new PlannedMeal(START, MealType.DINNER, riceBowl, 2));
        Pantry stored = pantries.findByProfileId(profileId);
        stored.updateItem(new PantryItem(egg, Quantity.of(12, "unit"), null));
        pantries.save(stored);
        GroceryList first = service.refreshForPlan(plan);
        assertTrue(item(first, "egg").toBuy().isZero(), "uses the saved pantry");
        service.refreshForPlan(plan);
        assertEquals(1, lists.byPlan.size());
        assertFalse(service.getList(plan.id()).orElseThrow().isStale());
    }
}
