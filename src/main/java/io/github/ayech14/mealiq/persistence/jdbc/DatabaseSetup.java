package io.github.ayech14.mealiq.persistence.jdbc;

import io.github.ayech14.mealiq.persistence.catalogue.CatalogueLoader;
import io.github.ayech14.mealiq.persistence.catalogue.RecipeCatalogueLoader;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prepares a database for MealIQ: creates missing tables, then loads the ingredient catalogue
 * and the seed recipes. Run it with {@code ./mealiq setup}.
 *
 * <p>Safe to run more than once: existing tables are kept, and catalogue rows and recipes are
 * updated in place, because their IDs are derived from their names.
 */
public final class DatabaseSetup {

    private static final Pattern CREATE_TABLE = Pattern.compile("CREATE TABLE IF NOT EXISTS (\\w+)");

    public record Result(List<String> tablesCreated, int ingredientsLoaded, int recipesLoaded) {
    }

    private DatabaseSetup() {
    }

    public static Result run(Database database) {
        List<String> missing = new ArrayList<>();
        for (String table : schemaTables()) {
            if (!exists(database, table)) {
                missing.add(table);
            }
        }
        database.createSchema();
        JdbcIngredientRepository ingredients = new JdbcIngredientRepository(database);
        int ingredientsLoaded = CatalogueLoader.load(ingredients);
        int recipesLoaded = RecipeCatalogueLoader.load(ingredients, new JdbcRecipeRepository(database));
        return new Result(List.copyOf(missing), ingredientsLoaded, recipesLoaded);
    }

    /** Table names defined in {@code db/schema.sql}, in creation order. */
    static List<String> schemaTables() {
        List<String> tables = new ArrayList<>();
        Matcher matcher = CREATE_TABLE.matcher(Database.readSchemaScript());
        while (matcher.find()) {
            tables.add(matcher.group(1));
        }
        return tables;
    }

    private static boolean exists(Database database, String table) {
        return database.read(connection -> {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet rows = metaData.getTables(null, null, table, null)) {
                return rows.next();
            }
        });
    }
}
