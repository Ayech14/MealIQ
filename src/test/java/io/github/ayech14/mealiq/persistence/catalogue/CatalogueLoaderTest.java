package io.github.ayech14.mealiq.persistence.catalogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ayech14.mealiq.domain.DietRules;
import io.github.ayech14.mealiq.domain.Ingredient;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CatalogueLoaderTest {

    private static final String HEADER = "name,fdc_id,usda_description,category,allergens,calories,protein_g,"
            + "carbs_g,fat_g,grams_per_unit,unit_portion,grams_per_ml,volume_portion,substitutes\n";

    @Test
    void packagedCatalogueIsCompleteAndConsistent() {
        List<CatalogueLoader.Entry> entries = CatalogueLoader.read();
        assertTrue(entries.size() >= 140, "catalogue size " + entries.size());
        Set<String> names = new HashSet<>();
        for (CatalogueLoader.Entry entry : entries) {
            Ingredient ingredient = entry.ingredient();
            names.add(ingredient.name());
            assertNotNull(ingredient.fdcId(), ingredient.name());
            assertTrue(ingredient.nutrition().map(n -> n.complete()).orElse(false), ingredient.name());
        }
        assertEquals(entries.size(), names.size(), "names are unique");
    }

    @Test
    void everyAllergenTagCanBeChosenAsAnAllergy() {
        for (CatalogueLoader.Entry entry : CatalogueLoader.read()) {
            for (String allergen : entry.ingredient().allergens()) {
                assertTrue(DietRules.isKnownAllergen(allergen), entry.ingredient().name() + ": " + allergen);
            }
        }
    }

    @Test
    void rowValuesAreParsed() {
        Ingredient egg = CatalogueLoader.read().stream().map(CatalogueLoader.Entry::ingredient)
                .filter(i -> i.name().equals("egg")).findFirst().orElseThrow();
        assertEquals(CatalogueLoader.ingredientId("egg"), egg.id());
        assertEquals(171287, egg.fdcId());
        assertEquals(50.0, egg.gramsPerUnit());
        assertNull(egg.gramsPerMl());
        assertEquals(Set.of("egg"), egg.allergens());
        assertEquals(143, egg.nutritionPer100g().calories());
    }

    @Test
    void idsDependOnlyOnTheNormalizedName() {
        assertEquals(CatalogueLoader.ingredientId("Egg "), CatalogueLoader.ingredientId("egg"));
        assertFalse(CatalogueLoader.ingredientId("egg").equals(CatalogueLoader.ingredientId("egg white")));
    }

    @Test
    void partialNutritionIsIncompleteAndEmptyNutritionIsAbsent() {
        List<CatalogueLoader.Entry> entries = CatalogueLoader.parse(HEADER
                + "a,1,A,spice,,10,,,,,,,,\n"
                + "b,2,B,spice,,,,,,,,,,\n");
        assertFalse(entries.get(0).ingredient().nutritionPer100g().complete());
        assertNull(entries.get(1).ingredient().nutritionPer100g());
    }

    @Test
    void unknownSubstituteOrDuplicateNameIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> CatalogueLoader.parse(HEADER + "a,1,A,spice,,1,1,1,1,,,,,ghost\n"));
        assertThrows(IllegalArgumentException.class,
                () -> CatalogueLoader.parse(HEADER + "a,1,A,spice,,1,1,1,1,,,,,\na,2,A,spice,,1,1,1,1,,,,,\n"));
    }

    @Test
    void csvHandlesQuotesEscapesAndLineEndings() {
        List<List<String>> rows = CsvReader.parse("x,\"a, b\",\"say \"\"hi\"\"\"\r\n\"multi\nline\",,z\n\n");
        assertEquals(List.of(List.of("x", "a, b", "say \"hi\""), List.of("multi\nline", "", "z")), rows);
        assertThrows(IllegalArgumentException.class, () -> CsvReader.parse("\"open"));
    }
}
