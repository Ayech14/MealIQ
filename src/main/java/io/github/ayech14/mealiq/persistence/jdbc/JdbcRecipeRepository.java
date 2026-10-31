package io.github.ayech14.mealiq.persistence.jdbc;

import io.github.ayech14.mealiq.contracts.RecipeQuery;
import io.github.ayech14.mealiq.domain.IngredientRequirement;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.RecipeSource;
import io.github.ayech14.mealiq.persistence.PersistenceException;
import io.github.ayech14.mealiq.persistence.RecipeRepository;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class JdbcRecipeRepository implements RecipeRepository {

    private static final String[] COLUMNS = {"name", "meal_types", "prep_minutes", "cook_minutes", "servings",
        "tags", "source", "instructions"};

    private final Database database;

    public JdbcRecipeRepository(Database database) {
        this.database = database;
    }

    @Override
    public Optional<Recipe> findById(UUID recipeId) {
        return database.read(connection -> {
            try (PreparedStatement statement = Sql.prepare(connection,
                    "SELECT " + JdbcLoader.RECIPE_COLUMNS + " FROM recipes WHERE id = ?", recipeId);
                 ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(new JdbcLoader(connection).recipe(row)) : Optional.empty();
            }
        });
    }

    /**
     * Total time and source are filtered in SQL; meal type and ingredient names in Java. A
     * recipe matches {@code includeIngredients} if it uses at least one of them; ranking by
     * how many it uses is left to the planning strategies.
     */
    @Override
    public List<Recipe> search(RecipeQuery query) {
        StringBuilder sql = new StringBuilder("SELECT " + JdbcLoader.RECIPE_COLUMNS + " FROM recipes WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (query.maxMinutes() != null) {
            sql.append(" AND prep_minutes + cook_minutes <= ?");
            params.add(query.maxMinutes());
        }
        if (!query.allowGenerated()) {
            sql.append(" AND source <> ?");
            params.add(RecipeSource.GENERATED.name());
        }
        sql.append(" ORDER BY name, id");
        List<String> include = terms(query.includeIngredients());
        List<String> exclude = terms(query.excludeIngredients());

        return database.read(connection -> {
            JdbcLoader loader = new JdbcLoader(connection);
            List<Recipe> candidates = new ArrayList<>();
            try (PreparedStatement statement = Sql.prepare(connection, sql.toString(), params.toArray());
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    candidates.add(loader.recipe(rows));
                }
            }
            return candidates.stream()
                    .filter(r -> query.mealType() == null || r.mealTypes().contains(query.mealType()))
                    .filter(r -> include.isEmpty() || usesAny(r, include))
                    .filter(r -> !usesAny(r, exclude))
                    .toList();
        });
    }

    @Override
    public void save(Recipe recipe) {
        Object[] values = {
            recipe.name(),
            TextListCodec.encodeEnums(recipe.mealTypes()),
            recipe.prepMinutes(),
            recipe.cookMinutes(),
            recipe.servings(),
            TextListCodec.encodeSet(recipe.tags()),
            recipe.source().name(),
            TextListCodec.encodeList(recipe.instructions()),
        };
        database.transaction(connection -> {
            List<String> missing = new ArrayList<>();
            for (IngredientRequirement line : recipe.ingredients()) {
                try (PreparedStatement statement = Sql.prepare(connection,
                        "SELECT 1 FROM ingredients WHERE id = ?", line.ingredient().id());
                     ResultSet row = statement.executeQuery()) {
                    if (!row.next()) {
                        missing.add(line.ingredient().name());
                    }
                }
            }
            if (!missing.isEmpty()) {
                // Every recipe ingredient must come from the catalogue (D09).
                throw new PersistenceException("Ingredients not in catalogue: " + String.join(", ", missing));
            }
            Sql.upsert(connection, "recipes", COLUMNS, values, "id", recipe.id());
            Sql.update(connection, "DELETE FROM recipe_ingredients WHERE recipe_id = ?", recipe.id());
            int position = 0;
            for (IngredientRequirement line : recipe.ingredients()) {
                Sql.update(connection, "INSERT INTO recipe_ingredients "
                        + "(recipe_id, position, ingredient_id, amount, unit, optional) VALUES (?, ?, ?, ?, ?, ?)",
                        recipe.id(), position++, line.ingredient().id(), line.quantity().amount(),
                        line.quantity().unit().symbol(), line.optional());
            }
            return null;
        });
    }

    private static boolean usesAny(Recipe recipe, List<String> terms) {
        return recipe.ingredients().stream()
                .map(line -> line.ingredient().name())
                .anyMatch(name -> terms.stream().anyMatch(name::contains));
    }

    private static List<String> terms(List<String> values) {
        return values.stream().map(v -> v.strip().toLowerCase(Locale.ROOT)).filter(v -> !v.isEmpty()).toList();
    }
}
