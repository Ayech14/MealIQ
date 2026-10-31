package io.github.ayech14.mealiq.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class DomainModelTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 31);

    static final Ingredient CHICKEN = Ingredient.of("chicken breast", "poultry");
    static final Ingredient SALMON = Ingredient.of("salmon", "fish", "fish");
    static final Ingredient MILK = Ingredient.of("milk", "dairy", "milk");
    static final Ingredient BREAD = Ingredient.of("bread", "grain", "gluten");
    static final Ingredient PEANUTS = Ingredient.of("peanuts", "legume", "Peanut");
    static final Ingredient RICE = Ingredient.of("rice", "grain");
    static final Ingredient HONEY = Ingredient.of("honey", "bee-product");

    static Recipe recipe(String name, int minutes) {
        return Recipe.builder(name).mealTypes(MealType.DINNER).minutes(minutes / 2, minutes - minutes / 2)
                .servings(2).build();
    }

    @Nested
    class Profiles {

        @Test
        void allergyBlocksIngredient() {
            UserProfile profile = new UserProfile().allergies(Set.of("peanut"));
            assertFalse(profile.isAllowed(PEANUTS));
            assertTrue(profile.isAllowed(RICE));
        }

        static Stream<Arguments> restrictions() {
            return Stream.of(
                    Arguments.of("vegetarian", List.of(CHICKEN, SALMON), List.of(MILK, RICE)),
                    Arguments.of("pescatarian", List.of(CHICKEN), List.of(SALMON, MILK)),
                    Arguments.of("vegan", List.of(CHICKEN, SALMON, MILK, HONEY), List.of(RICE, BREAD)),
                    Arguments.of("Gluten Free", List.of(BREAD), List.of(RICE, CHICKEN)),
                    Arguments.of("dairy_free", List.of(MILK), List.of(RICE)),
                    Arguments.of("nut-free", List.of(PEANUTS), List.of(RICE)));
        }

        @ParameterizedTest
        @MethodSource("restrictions")
        void dietaryRestrictions(String restriction, List<Ingredient> blocked, List<Ingredient> allowed) {
            UserProfile profile = new UserProfile().dietaryRestrictions(Set.of(restriction));
            blocked.forEach(i -> assertFalse(profile.isAllowed(i), i.name()));
            allowed.forEach(i -> assertTrue(profile.isAllowed(i), i.name()));
        }

        @Test
        void dislikesDoNotBlock() {
            assertTrue(new UserProfile().dislikedFoods(Set.of("rice")).isAllowed(RICE));
        }

        @Test
        void maxMinutesPerDay() {
            UserProfile profile = new UserProfile().maxCookMinutes(Map.of(DayOfWeek.MONDAY, 30));
            assertEquals(OptionalInt.of(30), profile.maxMinutesFor(DayOfWeek.MONDAY));
            assertEquals(OptionalInt.empty(), profile.maxMinutesFor(DayOfWeek.TUESDAY));
        }

        @Test
        void copyIsEqualButIndependent() {
            UserProfile profile = new UserProfile().weeklyBudget(new BigDecimal("100")).allergies(Set.of("egg"));
            UserProfile copy = profile.copy();
            assertEquals(profile, copy);
            copy.mealsPerDay(4);
            assertFalse(profile.equals(copy));
        }
    }

    @Nested
    class Pantries {

        @Test
        void availableQuantityExcludesExpiredItems() {
            Pantry pantry = Pantry.emptyFor(UUID.randomUUID());
            pantry.updateItem(new PantryItem(MILK, Quantity.of(1, "l"), TODAY.minusDays(1)));
            pantry.updateItem(new PantryItem(RICE, Quantity.of(500, "g"), TODAY));
            assertTrue(pantry.availableQuantity(MILK, TODAY).isZero());
            assertEquals(Quantity.of(500, "g"), pantry.availableQuantity(RICE, TODAY));
            assertEquals(2, pantry.items().size());
        }

        @Test
        void missingItemIsZero() {
            assertTrue(Pantry.emptyFor(UUID.randomUUID()).availableQuantity(RICE, TODAY).isZero());
        }

        @Test
        void updateReplacesAndRemoveDeletes() {
            Pantry pantry = Pantry.emptyFor(UUID.randomUUID());
            pantry.updateItem(new PantryItem(RICE, Quantity.of(500, "g"), null));
            pantry.updateItem(new PantryItem(RICE, Quantity.of(1, "kg"), null));
            assertEquals(Quantity.of(1, "kg"), pantry.availableQuantity(RICE, TODAY));
            pantry.removeItem(RICE.id());
            assertTrue(pantry.items().isEmpty());
        }
    }

    @Nested
    class Plans {

        @Test
        void mealsOnFindAndReplace() {
            Recipe quick = recipe("stir fry", 20);
            Recipe slow = recipe("roast", 90);
            PlannedMeal meal = new PlannedMeal(TODAY, MealType.DINNER, slow, 1);
            MealPlan plan = new MealPlan(UUID.randomUUID(), TODAY, 7, List.of(meal));
            assertEquals(List.of(meal), plan.mealsOn(TODAY));
            assertTrue(plan.mealsOn(TODAY.plusDays(1)).isEmpty());
            assertSame(slow, plan.replaceMeal(meal.id(), quick));
            assertSame(quick, plan.findMeal(meal.id()).recipe());
            assertEquals(TODAY.plusDays(6), plan.endDate());
        }

        @Test
        void findUnknownMealThrows() {
            MealPlan plan = new MealPlan(UUID.randomUUID(), TODAY, 7, List.of());
            assertThrows(NoSuchElementException.class, () -> plan.findMeal(UUID.randomUUID()));
        }

        @Test
        void mealsViewIsReadOnly() {
            MealPlan plan = new MealPlan(UUID.randomUUID(), TODAY, 1, List.of());
            assertThrows(UnsupportedOperationException.class,
                    () -> plan.meals().add(new PlannedMeal(TODAY, MealType.LUNCH, recipe("x", 10), 1)));
        }

        @Test
        void recipeTotalMinutesAndValidation() {
            assertEquals(45, recipe("x", 45).totalMinutes());
            assertThrows(IllegalArgumentException.class,
                    () -> Recipe.builder("bad").servings(0).build());
        }
    }

    @Nested
    class IngredientsAndNutrition {

        @Test
        void toGramsUsesCatalogueWeights() {
            Ingredient egg = Ingredient.of("egg", "egg").withUnitWeights(50.0, null);
            Ingredient oil = Ingredient.of("oil", "oil").withUnitWeights(null, 0.9);
            assertEquals(100, egg.toGrams(Quantity.of(2, "unit")).getAsDouble(), 1e-9);
            assertEquals(600, egg.toGrams(Quantity.of(1, "dozen")).getAsDouble(), 1e-9);
            assertEquals(100, egg.toGrams(Quantity.of(0.1, "kg")).getAsDouble(), 1e-9);
            assertTrue(egg.toGrams(Quantity.of(1, "cup")).isEmpty());
            assertEquals(90, oil.toGrams(Quantity.of(100, "ml")).getAsDouble(), 1e-9);
            assertTrue(oil.toGrams(Quantity.of(1, "unit")).isEmpty());
            assertEquals(0, oil.toGrams(Quantity.of(0, "unit")).getAsDouble());
        }

        @Test
        void tagsAreNormalized() {
            Ingredient nut = Ingredient.of("almonds", "Nut", "Tree Nut");
            assertEquals("nut", nut.category());
            assertEquals(Set.of("tree-nut"), nut.allergens());
        }

        @Test
        void nutritionAddPropagatesIncomplete() {
            NutritionSummary a = new NutritionSummary(100, 10, 5, 2, true);
            NutritionSummary total = a.add(new NutritionSummary(50, 1, 1, 1, false));
            assertEquals(150, total.calories());
            assertEquals(11, total.proteinG());
            assertFalse(total.complete());
            assertEquals(200, a.scaled(2).calories());
        }

        @Test
        void groceryListTotalAndPartial() {
            GroceryItem priced = new GroceryItem(RICE, Quantity.of(1, "kg"), Quantity.of(0, "g"),
                    Quantity.of(1, "kg"), new BigDecimal("3.50"));
            GroceryItem unpriced = new GroceryItem(MILK, Quantity.of(1, "l"), Quantity.of(0, "l"),
                    Quantity.of(1, "l"), null);
            GroceryList list = new GroceryList(UUID.randomUUID(), List.of(priced, unpriced));
            assertEquals(new BigDecimal("3.50"), list.total());
            assertTrue(list.isPartial());
            list.markStale();
            assertTrue(list.isStale());
        }
    }
}
