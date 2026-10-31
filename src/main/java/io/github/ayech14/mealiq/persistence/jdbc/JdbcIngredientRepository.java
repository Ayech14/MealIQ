package io.github.ayech14.mealiq.persistence.jdbc;

import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.domain.NutritionSummary;
import io.github.ayech14.mealiq.persistence.IngredientRepository;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class JdbcIngredientRepository implements IngredientRepository {

    private static final String[] COLUMNS = {"name", "category", "allergens", "calories", "protein_g", "carbs_g",
        "fat_g", "grams_per_unit", "grams_per_ml", "fdc_id"};

    private final Database database;

    public JdbcIngredientRepository(Database database) {
        this.database = database;
    }

    @Override
    public Optional<Ingredient> findById(UUID ingredientId) {
        return findOne("id = ?", ingredientId);
    }

    @Override
    public Optional<Ingredient> findByName(String name) {
        return findOne("name = ?", normalizeName(name));
    }

    @Override
    public List<Ingredient> findSubstitutes(Ingredient ingredient) {
        return database.read(connection -> {
            JdbcLoader loader = new JdbcLoader(connection);
            List<Ingredient> substitutes = new ArrayList<>();
            try (PreparedStatement statement = Sql.prepare(connection,
                    "SELECT i.id, i.name, i.category, i.allergens, i.calories, i.protein_g, i.carbs_g, i.fat_g, "
                            + "i.grams_per_unit, i.grams_per_ml, i.fdc_id FROM ingredient_substitutes s "
                            + "JOIN ingredients i ON i.id = s.substitute_id "
                            + "WHERE s.ingredient_id = ? ORDER BY s.position", ingredient.id());
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    substitutes.add(loader.ingredient(rows));
                }
            }
            return substitutes;
        });
    }

    @Override
    public void save(Ingredient ingredient) {
        NutritionSummary n = ingredient.nutritionPer100g();
        Object[] values = {
            normalizeName(ingredient.name()),
            ingredient.category(),
            TextListCodec.encodeSet(ingredient.allergens()),
            n == null ? null : n.calories(),
            n == null ? null : n.proteinG(),
            n == null ? null : n.carbsG(),
            n == null ? null : n.fatG(),
            ingredient.gramsPerUnit(),
            ingredient.gramsPerMl(),
            ingredient.fdcId(),
        };
        database.transaction(connection -> {
            Sql.upsert(connection, "ingredients", COLUMNS, values, "id", ingredient.id());
            return null;
        });
    }

    @Override
    public void saveSubstitutes(Ingredient ingredient, List<Ingredient> substitutes) {
        database.transaction(connection -> {
            Sql.update(connection, "DELETE FROM ingredient_substitutes WHERE ingredient_id = ?", ingredient.id());
            int position = 0;
            for (Ingredient substitute : substitutes) {
                Sql.update(connection, "INSERT INTO ingredient_substitutes (ingredient_id, substitute_id, position) "
                        + "VALUES (?, ?, ?)", ingredient.id(), substitute.id(), position++);
            }
            return null;
        });
    }

    private Optional<Ingredient> findOne(String condition, Object value) {
        return database.read(connection -> {
            try (PreparedStatement statement = Sql.prepare(connection,
                    "SELECT " + JdbcLoader.INGREDIENT_COLUMNS + " FROM ingredients WHERE " + condition, value);
                 ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(new JdbcLoader(connection).ingredient(row)) : Optional.empty();
            }
        });
    }

    static String normalizeName(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }
}
