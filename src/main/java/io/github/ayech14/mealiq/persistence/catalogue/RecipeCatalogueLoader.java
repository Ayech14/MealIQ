package io.github.ayech14.mealiq.persistence.catalogue;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.domain.MealType;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.RecipeSource;
import io.github.ayech14.mealiq.persistence.IngredientRepository;
import io.github.ayech14.mealiq.persistence.PersistenceException;
import io.github.ayech14.mealiq.persistence.RecipeRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Loads the curated seed recipes from {@code catalogue/recipes.json}. Every ingredient must
 * name a catalogue entry, so each recipe's nutrition can be calculated from USDA data (D09).
 */
public final class RecipeCatalogueLoader {

    public static final String RESOURCE = "/catalogue/recipes.json";

    private static final ObjectMapper JSON = new ObjectMapper();

    record Line(String name, double amount, String unit, boolean optional) {
    }

    record Entry(String name, List<MealType> mealTypes, int prepMinutes, int cookMinutes, int servings,
                 List<String> tags, @JsonProperty("ingredients") List<Line> lines, List<String> instructions) {
    }

    private RecipeCatalogueLoader() {
    }

    /** Recipe IDs depend only on the name, so reloading updates recipes in place. */
    public static UUID recipeId(String name) {
        String key = "mealiq:recipe:" + name.strip().toLowerCase(Locale.ROOT);
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    /** The packaged recipes, with ingredients resolved by {@code lookup}. */
    public static List<Recipe> read(Function<String, Optional<Ingredient>> lookup) {
        try (InputStream in = RecipeCatalogueLoader.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new PersistenceException(RESOURCE + " is missing from the classpath");
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), lookup);
        } catch (IOException e) {
            throw new PersistenceException("Cannot read " + RESOURCE, e);
        }
    }

    /** Rejects duplicate names and ingredients missing from the catalogue, listing every problem. */
    public static List<Recipe> parse(String json, Function<String, Optional<Ingredient>> lookup) {
        List<Entry> entries;
        try {
            entries = JSON.readValue(json, new TypeReference<List<Entry>>() { });
        } catch (IOException e) {
            throw new PersistenceException("Recipe catalogue is not valid JSON: " + e.getMessage(), e);
        }
        List<String> problems = new ArrayList<>();
        Set<String> names = new HashSet<>();
        List<Recipe> recipes = new ArrayList<>();
        for (Entry entry : entries) {
            if (!names.add(entry.name().strip().toLowerCase(Locale.ROOT))) {
                problems.add("duplicate recipe '" + entry.name() + "'");
                continue;
            }
            Recipe.Builder recipe = Recipe.builder(entry.name()).id(recipeId(entry.name()))
                    .mealTypes(entry.mealTypes().toArray(MealType[]::new))
                    .minutes(entry.prepMinutes(), entry.cookMinutes())
                    .servings(entry.servings())
                    .tags(entry.tags().toArray(String[]::new))
                    .source(RecipeSource.CURATED)
                    .instructions(entry.instructions().toArray(String[]::new));
            for (Line line : entry.lines()) {
                Optional<Ingredient> ingredient = lookup.apply(line.name());
                if (ingredient.isEmpty()) {
                    problems.add(entry.name() + ": '" + line.name() + "' is not in the ingredient catalogue");
                } else if (line.optional()) {
                    recipe.optionalIngredient(ingredient.get(), line.amount(), line.unit());
                } else {
                    recipe.ingredient(ingredient.get(), line.amount(), line.unit());
                }
            }
            recipes.add(recipe.build());
        }
        if (!problems.isEmpty()) {
            throw new PersistenceException("Recipe catalogue problems: " + String.join("; ", problems));
        }
        return recipes;
    }

    /** Saves every packaged recipe; the ingredient catalogue must be loaded first. */
    public static int load(IngredientRepository ingredients, RecipeRepository recipes) {
        List<Recipe> all = read(ingredients::findByName);
        all.forEach(recipes::save);
        return all.size();
    }
}
