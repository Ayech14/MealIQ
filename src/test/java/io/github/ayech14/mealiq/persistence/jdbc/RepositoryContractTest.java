package io.github.ayech14.mealiq.persistence.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ayech14.mealiq.contracts.RecipeQuery;
import io.github.ayech14.mealiq.domain.AppliedChange;
import io.github.ayech14.mealiq.domain.ChangeType;
import io.github.ayech14.mealiq.domain.GroceryItem;
import io.github.ayech14.mealiq.domain.GroceryList;
import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.domain.IngredientRequirement;
import io.github.ayech14.mealiq.domain.MealPlan;
import io.github.ayech14.mealiq.domain.MealSwap;
import io.github.ayech14.mealiq.domain.MealType;
import io.github.ayech14.mealiq.domain.NutritionSummary;
import io.github.ayech14.mealiq.domain.Pantry;
import io.github.ayech14.mealiq.domain.PantryItem;
import io.github.ayech14.mealiq.domain.PlanStatus;
import io.github.ayech14.mealiq.domain.PlannedMeal;
import io.github.ayech14.mealiq.domain.Quantity;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.RecipeSource;
import io.github.ayech14.mealiq.domain.UserProfile;
import io.github.ayech14.mealiq.domain.WeightGoal;
import io.github.ayech14.mealiq.persistence.PersistenceException;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Behaviour every repository implementation must have. Subclasses supply a fresh database:
 * H2 always, PostgreSQL when a test database is configured.
 */
abstract class RepositoryContractTest {

    static final LocalDate START = LocalDate.of(2026, 11, 2);

    JdbcIngredientRepository ingredients;
    JdbcRecipeRepository recipes;
    JdbcProfileRepository profiles;
    JdbcPantryRepository pantries;
    JdbcMealPlanRepository plans;
    JdbcGroceryListRepository groceries;

    Ingredient chicken;
    Ingredient tofu;
    Ingredient rice;
    Ingredient egg;
    UserProfile profile;

    /** A database with an empty, freshly created schema. */
    abstract Database freshDatabase();

    /** The database the repositories of this test use. */
    Database database;

    @BeforeEach
    void setUp() {
        database = freshDatabase();
        ingredients = new JdbcIngredientRepository(database);
        recipes = new JdbcRecipeRepository(database);
        profiles = new JdbcProfileRepository(database);
        pantries = new JdbcPantryRepository(database);
        plans = new JdbcMealPlanRepository(database);
        groceries = new JdbcGroceryListRepository(database);

        chicken = Ingredient.of("Chicken Breast", "poultry").withNutrition(new NutritionSummary(165, 31, 0, 3.6, true));
        tofu = Ingredient.of("tofu", "legume", "soy").withNutrition(new NutritionSummary(76, 8, 1.9, 4.8, true));
        rice = Ingredient.of("rice", "grain").withNutrition(new NutritionSummary(130, 2.7, 28, 0.3, true))
                .withUnitWeights(null, 0.78);
        egg = Ingredient.of("egg", "egg", "egg").withUnitWeights(50.0, null);
        for (Ingredient ingredient : List.of(chicken, tofu, rice, egg)) {
            ingredients.save(ingredient);
        }
        ingredients.saveSubstitutes(chicken, List.of(tofu));

        profile = new UserProfile()
                .goal(WeightGoal.GAIN)
                .dailyProteinTargetG(140)
                .allergies(Set.of("peanut"))
                .dietaryRestrictions(Set.of("gluten-free"))
                .weeklyBudget(new BigDecimal("100.00"))
                .maxCookMinutes(Map.of(DayOfWeek.MONDAY, 20, DayOfWeek.WEDNESDAY, 45));
        profiles.save(profile);
    }

    Recipe recipe(String name, int minutes, MealType type, RecipeSource source, Ingredient... uses) {
        Recipe.Builder builder = Recipe.builder(name).mealTypes(type).minutes(minutes / 3, minutes - minutes / 3)
                .servings(2).source(source).instructions("Cook.", "Serve.");
        for (Ingredient ingredient : uses) {
            builder.ingredient(ingredient, 200, "g");
        }
        return builder.build();
    }

    Recipe dinner(String name, Ingredient... uses) {
        return recipe(name, 30, MealType.DINNER, RecipeSource.CURATED, uses);
    }

    @Nested
    class Ingredients {

        @Test
        void roundTripAndCaseInsensitiveName() {
            Ingredient found = ingredients.findByName("  CHICKEN breast ").orElseThrow();
            assertEquals(chicken.id(), found.id());
            assertEquals(new NutritionSummary(165, 31, 0, 3.6, true), found.nutritionPer100g());
            assertEquals(Set.of("soy"), ingredients.findById(tofu.id()).orElseThrow().allergens());
            assertEquals(0.78, ingredients.findByName("rice").orElseThrow().gramsPerMl());
        }

        @Test
        void missingNutritionStaysMissing() {
            Ingredient found = ingredients.findByName("egg").orElseThrow();
            assertNull(found.nutritionPer100g());
            assertEquals(50.0, found.gramsPerUnit());
        }

        @Test
        void substitutes() {
            assertEquals(List.of("tofu"), ingredients.findSubstitutes(chicken).stream().map(Ingredient::name).toList());
            assertTrue(ingredients.findSubstitutes(rice).isEmpty());
        }

        @Test
        void findAllIsOrderedByName() {
            assertEquals(List.of("chicken breast", "egg", "rice", "tofu"),
                    ingredients.findAll().stream().map(Ingredient::name).toList());
        }

        @Test
        void namesAreUnique() {
            assertThrows(PersistenceException.class, () -> ingredients.save(Ingredient.of("chicken breast", "poultry")));
        }
    }

    @Nested
    class Recipes {

        @Test
        void roundTripKeepsIngredientOrderAndFields() {
            Recipe saved = dinner("Chicken rice", chicken, rice, egg);
            recipes.save(saved);
            Recipe loaded = recipes.findById(saved.id()).orElseThrow();
            assertEquals(List.of("chicken breast", "rice", "egg"),
                    loaded.ingredients().stream().map(l -> l.ingredient().name()).toList());
            assertEquals(Quantity.of(200, "g"), loaded.ingredients().get(0).quantity());
            assertEquals(List.of("Cook.", "Serve."), loaded.instructions());
            assertEquals(Set.of(MealType.DINNER), loaded.mealTypes());
            assertEquals(30, loaded.totalMinutes());
        }

        @Test
        void updateReplacesIngredients() {
            Recipe saved = dinner("Bowl", chicken, rice);
            recipes.save(saved);
            recipes.save(saved.withIngredients(List.of(IngredientRequirement.of(tofu, 1, "unit"))));
            Recipe loaded = recipes.findById(saved.id()).orElseThrow();
            assertEquals(List.of("tofu"), loaded.ingredients().stream().map(l -> l.ingredient().name()).toList());
        }

        @Test
        void rejectsIngredientOutsideCatalogue() {
            Recipe mystery = dinner("Mystery", rice, Ingredient.of("unobtainium", "other"));
            PersistenceException error = assertThrows(PersistenceException.class, () -> recipes.save(mystery));
            assertTrue(error.getMessage().contains("unobtainium"));
            assertTrue(recipes.findById(mystery.id()).isEmpty());
        }

        @Test
        void searchFilters() {
            recipes.save(recipe("Quick chicken", 15, MealType.DINNER, RecipeSource.CURATED, chicken, rice));
            recipes.save(recipe("Slow chicken", 90, MealType.DINNER, RecipeSource.CURATED, chicken));
            recipes.save(recipe("Tofu breakfast", 30, MealType.BREAKFAST, RecipeSource.CURATED, tofu, egg));
            recipes.save(recipe("AI rice", 30, MealType.DINNER, RecipeSource.GENERATED, rice));

            assertEquals(List.of("Quick chicken", "Slow chicken", "Tofu breakfast"), names(RecipeQuery.any()));
            assertEquals(List.of("AI rice", "Quick chicken", "Slow chicken", "Tofu breakfast"),
                    names(RecipeQuery.any().withGenerated(true)));
            assertEquals(List.of("Quick chicken"), names(RecipeQuery.any().withMaxMinutes(20)));
            assertEquals(List.of("Tofu breakfast"), names(RecipeQuery.any().withMealType(MealType.BREAKFAST)));
            assertEquals(List.of("Quick chicken", "Slow chicken"), names(RecipeQuery.any().including("chicken")));
            assertEquals(List.of("Quick chicken", "Tofu breakfast"), names(RecipeQuery.any().including("egg", "rice")));
            assertEquals(List.of("Slow chicken", "Tofu breakfast"), names(RecipeQuery.any().excluding("rice")));
        }

        private List<String> names(RecipeQuery query) {
            return recipes.search(query).stream().map(Recipe::name).toList();
        }
    }

    @Nested
    class Profiles {

        @Test
        void roundTrip() {
            UserProfile loaded = profiles.findById(profile.id()).orElseThrow();
            assertEquals(profile, loaded);
            assertEquals(20, loaded.maxMinutesFor(DayOfWeek.MONDAY).getAsInt());
            assertEquals(0, new BigDecimal("100").compareTo(loaded.weeklyBudget()));
        }

        @Test
        void update() {
            profiles.save(profile.mealsPerDay(4).dislikedFoods(Set.of("mushroom")).dailyCalorieTarget(null));
            UserProfile loaded = profiles.findById(profile.id()).orElseThrow();
            assertEquals(4, loaded.mealsPerDay());
            assertEquals(Set.of("mushroom"), loaded.dislikedFoods());
            assertNull(loaded.dailyCalorieTarget());
        }

        @Test
        void unknownProfileIsEmpty() {
            assertTrue(profiles.findById(UUID.randomUUID()).isEmpty());
        }
    }

    @Nested
    class Pantries {

        @Test
        void newProfileGetsEmptyPantry() {
            assertTrue(pantries.findByProfileId(profile.id()).items().isEmpty());
        }

        @Test
        void saveUpdateAndRemove() {
            Pantry pantry = pantries.findByProfileId(profile.id());
            pantry.updateItem(new PantryItem(egg, Quantity.of(12, "unit"), LocalDate.of(2026, 11, 8)));
            pantry.updateItem(new PantryItem(rice, Quantity.of(1, "kg"), null));
            pantries.save(pantry);

            Pantry loaded = pantries.findByProfileId(profile.id());
            assertEquals(Quantity.of(12, "unit"), loaded.availableQuantity(egg, START));
            assertEquals(LocalDate.of(2026, 11, 8), loaded.items().get(egg.id()).expiryDate());
            assertEquals(pantry.id(), loaded.id());

            loaded.updateItem(new PantryItem(egg, Quantity.of(6, "unit"), null));
            loaded.removeItem(rice.id());
            pantries.save(loaded);
            Pantry again = pantries.findByProfileId(profile.id());
            assertEquals(List.of(egg.id()), List.copyOf(again.items().keySet()));
            assertEquals(Quantity.of(6, "unit"), again.availableQuantity(egg, START));
        }
    }

    @Nested
    class Plans {

        Recipe chickenRice;
        Recipe eggRice;

        MealPlan savePlan(PlanStatus status, LocalDate start) {
            chickenRice = dinner("Chicken rice", chicken, rice);
            eggRice = recipe("Egg rice", 20, MealType.BREAKFAST, RecipeSource.CURATED, egg, rice);
            recipes.save(chickenRice);
            recipes.save(eggRice);
            MealPlan plan = new MealPlan(UUID.randomUUID(), profile.id(), start, 2, status, List.of(
                    new PlannedMeal(start, MealType.DINNER, chickenRice, 2),
                    new PlannedMeal(start, MealType.BREAKFAST, eggRice, 1),
                    new PlannedMeal(start.plusDays(1), MealType.DINNER, chickenRice, 1)));
            plans.save(plan);
            return plan;
        }

        @Test
        void roundTripOrdersMealsAndSharesRecipes() {
            MealPlan plan = savePlan(PlanStatus.ACTIVE, START);
            MealPlan loaded = plans.findById(plan.id()).orElseThrow();
            assertEquals(List.of(MealType.BREAKFAST, MealType.DINNER, MealType.DINNER),
                    loaded.meals().stream().map(PlannedMeal::mealType).toList());
            assertEquals(START.plusDays(1), loaded.meals().get(2).date());
            assertSame(loaded.meals().get(1).recipe(), loaded.meals().get(2).recipe());
            assertEquals(2, loaded.meals().get(1).servings());
        }

        @Test
        void replaceMealPersists() {
            MealPlan plan = savePlan(PlanStatus.ACTIVE, START);
            UUID target = plan.meals().get(2).id();
            plan.replaceMeal(target, eggRice);
            plans.save(plan);
            MealPlan loaded = plans.findById(plan.id()).orElseThrow();
            assertEquals("Egg rice", loaded.findMeal(target).recipe().name());
            assertEquals(3, loaded.meals().size());
        }

        @Test
        void findActive() {
            savePlan(PlanStatus.ARCHIVED, START);
            assertTrue(plans.findActiveByProfileId(profile.id()).isEmpty());
            MealPlan active = savePlan(PlanStatus.ACTIVE, START.plusDays(7));
            assertEquals(active.id(), plans.findActiveByProfileId(profile.id()).orElseThrow().id());
        }
    }


    @Nested
    class PlanChanges {

        @Test
        void changesAreStoredInOrderAndRemovedNewestFirst() {
            JdbcPlanChangeRepository changes = new JdbcPlanChangeRepository(database);
            Recipe chickenRice = dinner("Chicken rice", chicken, rice);
            Recipe tofuRice = dinner("Tofu rice", tofu, rice);
            recipes.save(chickenRice);
            recipes.save(tofuRice);
            PlannedMeal first = new PlannedMeal(START, MealType.DINNER, chickenRice, 1);
            PlannedMeal second = new PlannedMeal(START.plusDays(1), MealType.DINNER, chickenRice, 1);
            MealPlan plan = new MealPlan(profile.id(), START, 2, List.of(first, second));
            plans.save(plan);

            AppliedChange replace = new AppliedChange(UUID.randomUUID(), ChangeType.REPLACE_MEAL, "replace both",
                    List.of(new MealSwap(first.id(), chickenRice, tofuRice, null, null),
                            new MealSwap(second.id(), chickenRice, tofuRice, null, null)));
            AppliedChange substitute = new AppliedChange(UUID.randomUUID(), ChangeType.SUBSTITUTE_INGREDIENT,
                    "tofu for chicken", List.of(new MealSwap(first.id(), tofuRice, chickenRice, chicken, tofu)));
            changes.append(plan.id(), replace);
            changes.append(plan.id(), substitute);

            List<AppliedChange> all = changes.findAll(plan.id());
            assertEquals(List.of("replace both", "tofu for chicken"),
                    all.stream().map(AppliedChange::description).toList());
            assertEquals(2, all.getFirst().swaps().size());
            MealSwap swap = all.getLast().swaps().getFirst();
            assertEquals("Chicken rice", swap.after().name());
            assertEquals(tofu.id(), swap.substitute().id());

            assertEquals(substitute.changeId(), changes.removeLast(plan.id()).orElseThrow().changeId());
            assertEquals(replace.changeId(), changes.removeLast(plan.id()).orElseThrow().changeId());
            assertTrue(changes.removeLast(plan.id()).isEmpty());
        }
    }

    @Nested
    class GroceryLists {

        @Test
        void roundTripAndMarkStale() {
            MealPlan plan = new MealPlan(profile.id(), START, 7, List.of());
            plans.save(plan);
            GroceryList list = new GroceryList(UUID.randomUUID(), plan.id(), List.of(
                    new GroceryItem(rice, Quantity.of(1, "kg"), Quantity.of(200, "g"), Quantity.of(800, "g"),
                            new BigDecimal("3.20")),
                    new GroceryItem(egg, Quantity.of(6, "unit"), Quantity.of(0, "unit"), Quantity.of(6, "unit"),
                            null)),
                    new BigDecimal("12.40"), false);
            groceries.save(list);

            GroceryList loaded = groceries.findByPlanId(plan.id()).orElseThrow();
            assertEquals(List.of("rice", "egg"), loaded.items().stream().map(i -> i.ingredient().name()).toList());
            assertEquals(Quantity.of(800, "g"), loaded.items().get(0).toBuy());
            assertNull(loaded.items().get(1).estimatedPrice());
            assertTrue(loaded.isPartial());
            assertEquals(0, new BigDecimal("12.40").compareTo(loaded.estimatedCost()));

            loaded.markStale();
            groceries.save(loaded);
            assertTrue(groceries.findByPlanId(plan.id()).orElseThrow().isStale());
        }
    }
}
