package io.github.ayech14.mealiq.persistence.jdbc;

import io.github.ayech14.mealiq.config.AppConfig;
import io.github.ayech14.mealiq.config.ConfigException;
import io.github.ayech14.mealiq.persistence.PersistenceException;
import io.github.ayech14.mealiq.persistence.catalogue.CatalogueLoader;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prepares a database for MealIQ: creates missing tables and loads the ingredient catalogue.
 * Uses DATABASE_URL from {@code .env}:
 *
 * <pre>./mvnw -q compile exec:java -Dexec.mainClass=io.github.ayech14.mealiq.persistence.jdbc.DatabaseSetup</pre>
 *
 * <p>Safe to run more than once: existing tables are kept and catalogue rows are updated in
 * place, because ingredient IDs are derived from their names.
 */
public final class DatabaseSetup {

    private static final Pattern CREATE_TABLE = Pattern.compile("CREATE TABLE IF NOT EXISTS (\\w+)");

    public record Result(List<String> tablesCreated, int ingredientsLoaded) {
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
        int loaded = CatalogueLoader.load(new JdbcIngredientRepository(database));
        return new Result(List.copyOf(missing), loaded);
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

    public static void main(String[] args) {
        try {
            AppConfig config = AppConfig.load();
            Result result = run(Database.from(config));
            System.out.println("Tables created: " + (result.tablesCreated().isEmpty()
                    ? "none (all tables already existed)" : String.join(", ", result.tablesCreated())));
            System.out.println("Ingredients loaded or updated: " + result.ingredientsLoaded());
        } catch (ConfigException | PersistenceException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }
}
