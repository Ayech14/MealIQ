package io.github.ayech14.mealiq.persistence.jdbc;

import java.util.Objects;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Runs the repository contract on a real PostgreSQL database. Enabled only when
 * MEALIQ_TEST_DATABASE_URL points at a disposable database: every table is dropped first.
 */
@EnabledIfEnvironmentVariable(named = "MEALIQ_TEST_DATABASE_URL", matches = "jdbc:postgresql:.*")
class PostgresRepositoryTest extends RepositoryContractTest {

    private static final String DROP_ALL = "DROP TABLE IF EXISTS grocery_items, grocery_lists, planned_meals, "
            + "meal_plans, pantry_items, pantries, profiles, recipe_ingredients, recipes, ingredient_substitutes, "
            + "ingredients CASCADE";

    @Override
    Database freshDatabase() {
        Database database = new Database(System.getenv("MEALIQ_TEST_DATABASE_URL"),
                Objects.requireNonNullElse(System.getenv("MEALIQ_TEST_DATABASE_USER"), "test"),
                Objects.requireNonNullElse(System.getenv("MEALIQ_TEST_DATABASE_PASSWORD"), "test"));
        database.transaction(connection -> Sql.update(connection, DROP_ALL));
        database.createSchema();
        return database;
    }
}
