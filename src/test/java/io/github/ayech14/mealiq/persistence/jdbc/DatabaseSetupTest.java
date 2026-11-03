package io.github.ayech14.mealiq.persistence.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.domain.MealType;
import io.github.ayech14.mealiq.domain.NutritionSummary;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.UserProfile;
import io.github.ayech14.mealiq.persistence.catalogue.CatalogueLoader;
import io.github.ayech14.mealiq.services.NutritionService;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class DatabaseSetupTest {

    private static Database emptyH2() {
        return new Database("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
    }

    private static int count(Database database, String table) {
        return database.read(connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                row.next();
                return row.getInt(1);
            }
        });
    }

    @Test
    void freshDatabaseGetsAllTablesAndTheCatalogue() {
        Database database = emptyH2();
        DatabaseSetup.Result result = DatabaseSetup.run(database);

        assertEquals(DatabaseSetup.schemaTables(), result.tablesCreated());
        assertEquals(11, result.tablesCreated().size());
        assertEquals(CatalogueLoader.read().size(), result.ingredientsLoaded());
        assertEquals(result.ingredientsLoaded(), count(database, "ingredients"));
        assertEquals(30, result.recipesLoaded());
        assertEquals(30, count(database, "recipes"));
    }

    @Test
    void runningTwiceCreatesNothingNewAndDuplicatesNothing() {
        Database database = emptyH2();
        DatabaseSetup.run(database);
        int ingredients = count(database, "ingredients");
        int links = count(database, "ingredient_substitutes");
        int recipeLines = count(database, "recipe_ingredients");

        DatabaseSetup.Result second = DatabaseSetup.run(database);

        assertTrue(second.tablesCreated().isEmpty());
        assertEquals(ingredients, count(database, "ingredients"));
        assertEquals(links, count(database, "ingredient_substitutes"));
        assertEquals(30, count(database, "recipes"));
        assertEquals(recipeLines, count(database, "recipe_ingredients"));
    }

    @Test
    void substitutesAreLinkedInOrder() {
        Database database = emptyH2();
        DatabaseSetup.run(database);
        JdbcIngredientRepository repository = new JdbcIngredientRepository(database);
        Ingredient milk = repository.findByName("milk").orElseThrow();
        assertEquals("soy milk, almond milk, skim milk", repository.findSubstitutes(milk).stream()
                .map(Ingredient::name).collect(Collectors.joining(", ")));
    }

    @Test
    void dietRulesApplyToCatalogueCategories() {
        Database database = emptyH2();
        DatabaseSetup.run(database);
        JdbcIngredientRepository repository = new JdbcIngredientRepository(database);
        UserProfile vegan = new UserProfile().dietaryRestrictions(Set.of("vegan"));
        UserProfile vegetarian = new UserProfile().dietaryRestrictions(Set.of("vegetarian"));
        UserProfile glutenFree = new UserProfile().dietaryRestrictions(Set.of("gluten-free"));

        assertFalse(vegan.isAllowed(repository.findByName("honey").orElseThrow()));
        assertFalse(vegan.isAllowed(repository.findByName("mayonnaise").orElseThrow()));
        assertTrue(vegan.isAllowed(repository.findByName("tofu").orElseThrow()));
        assertFalse(vegetarian.isAllowed(repository.findByName("chicken broth").orElseThrow()));
        assertTrue(vegetarian.isAllowed(repository.findByName("vegetable broth").orElseThrow()));
        assertFalse(glutenFree.isAllowed(repository.findByName("soy sauce").orElseThrow()));
        assertTrue(glutenFree.isAllowed(repository.findByName("white rice").orElseThrow()));
    }

    @Test
    void nutritionFromRealCatalogueValues() {
        // Two large eggs and a cup of dry white rice, using USDA SR Legacy values:
        // egg 2 x 50 g at 143 kcal/100 g; rice 236.6 ml x 0.7819 g/ml = 185 g at 365 kcal/100 g.
        Database database = emptyH2();
        DatabaseSetup.run(database);
        JdbcIngredientRepository repository = new JdbcIngredientRepository(database);
        Recipe recipe = Recipe.builder("egg rice").mealTypes(MealType.LUNCH).minutes(5, 20).servings(1)
                .ingredient(repository.findByName("egg").orElseThrow(), 2, "unit")
                .ingredient(repository.findByName("white rice").orElseThrow(), 1, "cup")
                .build();

        NutritionSummary summary = new NutritionService().calculateNutrition(recipe, 1);

        assertEquals(143 + 1.85 * 365, summary.calories(), 0.001 * summary.calories());
        assertTrue(summary.complete());
    }
}
