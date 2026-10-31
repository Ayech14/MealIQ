package io.github.ayech14.mealiq.persistence.catalogue;

import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.domain.NutritionSummary;
import io.github.ayech14.mealiq.persistence.IngredientRepository;
import io.github.ayech14.mealiq.persistence.PersistenceException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Seeds the ingredient catalogue (USDA FoodData Central SR Legacy, D09) from the packaged
 * {@code catalogue/ingredients.csv}.
 *
 * <p>Ingredient IDs are derived from the name, so loading twice updates the same rows and
 * recipes that reference catalogue IDs stay valid across databases.
 */
public final class CatalogueLoader {

    public static final String RESOURCE = "/catalogue/ingredients.csv";

    /** One catalogue row: the ingredient and the names of its substitutes, in preference order. */
    public record Entry(Ingredient ingredient, List<String> substituteNames) {
    }

    private CatalogueLoader() {
    }

    public static UUID ingredientId(String name) {
        String key = "mealiq:ingredient:" + name.strip().toLowerCase(Locale.ROOT);
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    /** Parses the packaged catalogue. */
    public static List<Entry> read() {
        try (InputStream in = CatalogueLoader.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new PersistenceException(RESOURCE + " is missing from the classpath");
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new PersistenceException("Cannot read " + RESOURCE, e);
        }
    }

    /** Parses catalogue CSV text; every substitute must name another row. */
    public static List<Entry> parse(String csv) {
        List<Entry> entries = new ArrayList<>();
        for (Map<String, String> row : CsvReader.readWithHeader(csv)) {
            entries.add(new Entry(ingredient(row), split(row.get("substitutes"))));
        }
        var names = new HashSet<String>();
        for (Entry entry : entries) {
            if (!names.add(entry.ingredient().name())) {
                throw new IllegalArgumentException("Duplicate catalogue name: " + entry.ingredient().name());
            }
        }
        for (Entry entry : entries) {
            for (String substitute : entry.substituteNames()) {
                if (!names.contains(substitute)) {
                    throw new IllegalArgumentException(entry.ingredient().name()
                            + " lists unknown substitute " + substitute);
                }
            }
        }
        return entries;
    }

    /** Inserts or updates every catalogue ingredient, then links substitutes. Returns the count. */
    public static int load(IngredientRepository repository) {
        return load(repository, read());
    }

    static int load(IngredientRepository repository, List<Entry> entries) {
        Map<String, Ingredient> byName = new LinkedHashMap<>();
        for (Entry entry : entries) {
            repository.save(entry.ingredient());
            byName.put(entry.ingredient().name(), entry.ingredient());
        }
        for (Entry entry : entries) {
            repository.saveSubstitutes(entry.ingredient(),
                    entry.substituteNames().stream().map(byName::get).toList());
        }
        return entries.size();
    }

    private static Ingredient ingredient(Map<String, String> row) {
        String name = row.get("name").strip().toLowerCase(Locale.ROOT);
        String fdcId = row.get("fdc_id").strip();
        return new Ingredient(
                ingredientId(name),
                name,
                row.get("category"),
                new HashSet<>(split(row.get("allergens"))),
                nutrition(row),
                number(row.get("grams_per_unit")),
                number(row.get("grams_per_ml")),
                fdcId.isEmpty() ? null : Integer.valueOf(fdcId));
    }

    /** Null when the row has no values at all; incomplete when only some are present (D09). */
    private static NutritionSummary nutrition(Map<String, String> row) {
        Double[] values = {number(row.get("calories")), number(row.get("protein_g")),
                number(row.get("carbs_g")), number(row.get("fat_g"))};
        if (Arrays.stream(values).allMatch(v -> v == null)) {
            return null;
        }
        boolean complete = Arrays.stream(values).allMatch(v -> v != null);
        return new NutritionSummary(orZero(values[0]), orZero(values[1]), orZero(values[2]), orZero(values[3]),
                complete);
    }

    private static Double number(String value) {
        return value == null || value.isBlank() ? null : Double.valueOf(value.strip());
    }

    private static double orZero(Double value) {
        return value == null ? 0 : value;
    }

    private static List<String> split(String value) {
        if (value == null) {
            return List.of();
        }
        return Arrays.stream(value.split(";")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
