package io.github.ayech14.mealiq.persistence.jdbc;

import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.domain.IngredientRequirement;
import io.github.ayech14.mealiq.domain.MealType;
import io.github.ayech14.mealiq.domain.NutritionSummary;
import io.github.ayech14.mealiq.domain.Quantity;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.RecipeSource;
import io.github.ayech14.mealiq.domain.Unit;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds ingredients and recipes from rows. One loader is used per repository call, so an
 * ingredient or recipe referenced many times in one plan becomes one shared object.
 */
final class JdbcLoader {

    static final String INGREDIENT_COLUMNS =
            "id, name, category, allergens, calories, protein_g, carbs_g, fat_g, grams_per_unit, grams_per_ml, fdc_id";
    static final String RECIPE_COLUMNS =
            "id, name, meal_types, prep_minutes, cook_minutes, servings, tags, source, instructions";

    private final Connection connection;
    private final Map<UUID, Ingredient> ingredients = new HashMap<>();
    private final Map<UUID, Recipe> recipes = new HashMap<>();

    JdbcLoader(Connection connection) {
        this.connection = connection;
    }

    Ingredient ingredient(UUID id) throws SQLException {
        Ingredient cached = ingredients.get(id);
        if (cached != null) {
            return cached;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + INGREDIENT_COLUMNS + " FROM ingredients WHERE id = ?")) {
            statement.setObject(1, id);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SQLException("Ingredient " + id + " not found");
                }
                return ingredient(row);
            }
        }
    }

    /** Maps the current row, which must contain {@link #INGREDIENT_COLUMNS}. */
    Ingredient ingredient(ResultSet row) throws SQLException {
        UUID id = row.getObject("id", UUID.class);
        Ingredient cached = ingredients.get(id);
        if (cached != null) {
            return cached;
        }
        Ingredient ingredient = new Ingredient(
                id,
                row.getString("name"),
                row.getString("category"),
                TextListCodec.decodeSet(row.getString("allergens")),
                nutrition(row),
                nullableDouble(row, "grams_per_unit"),
                nullableDouble(row, "grams_per_ml"),
                nullableInt(row, "fdc_id"));
        ingredients.put(id, ingredient);
        return ingredient;
    }

    Recipe recipe(UUID id) throws SQLException {
        Recipe cached = recipes.get(id);
        if (cached != null) {
            return cached;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + RECIPE_COLUMNS + " FROM recipes WHERE id = ?")) {
            statement.setObject(1, id);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SQLException("Recipe " + id + " not found");
                }
                return recipe(row);
            }
        }
    }

    /** Maps the current row, which must contain {@link #RECIPE_COLUMNS}, plus its ingredient lines. */
    Recipe recipe(ResultSet row) throws SQLException {
        UUID id = row.getObject("id", UUID.class);
        Recipe cached = recipes.get(id);
        if (cached != null) {
            return cached;
        }
        Recipe recipe = new Recipe(
                id,
                row.getString("name"),
                TextListCodec.decodeEnums(row.getString("meal_types"), MealType.class),
                row.getInt("prep_minutes"),
                row.getInt("cook_minutes"),
                row.getInt("servings"),
                requirements(id),
                TextListCodec.decodeSet(row.getString("tags")),
                RecipeSource.valueOf(row.getString("source")),
                TextListCodec.decodeList(row.getString("instructions")));
        recipes.put(id, recipe);
        return recipe;
    }

    private List<IngredientRequirement> requirements(UUID recipeId) throws SQLException {
        record Line(UUID ingredientId, double amount, String unit, boolean optional) {
        }
        List<Line> lines = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT ingredient_id, amount, unit, optional FROM recipe_ingredients "
                        + "WHERE recipe_id = ? ORDER BY position")) {
            statement.setObject(1, recipeId);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    lines.add(new Line(row.getObject("ingredient_id", UUID.class), row.getDouble("amount"),
                            row.getString("unit"), row.getBoolean("optional")));
                }
            }
        }
        List<IngredientRequirement> requirements = new ArrayList<>();
        for (Line line : lines) {
            requirements.add(new IngredientRequirement(ingredient(line.ingredientId()),
                    new Quantity(line.amount(), Unit.parse(line.unit())), line.optional()));
        }
        return requirements;
    }

    private static NutritionSummary nutrition(ResultSet row) throws SQLException {
        Double calories = nullableDouble(row, "calories");
        Double protein = nullableDouble(row, "protein_g");
        Double carbs = nullableDouble(row, "carbs_g");
        Double fat = nullableDouble(row, "fat_g");
        if (calories == null && protein == null && carbs == null && fat == null) {
            return null;
        }
        boolean complete = calories != null && protein != null && carbs != null && fat != null;
        return new NutritionSummary(orZero(calories), orZero(protein), orZero(carbs), orZero(fat), complete);
    }

    static Double nullableDouble(ResultSet row, String column) throws SQLException {
        double value = row.getDouble(column);
        return row.wasNull() ? null : value;
    }

    static Integer nullableInt(ResultSet row, String column) throws SQLException {
        int value = row.getInt(column);
        return row.wasNull() ? null : value;
    }

    private static double orZero(Double value) {
        return value == null ? 0 : value;
    }
}
