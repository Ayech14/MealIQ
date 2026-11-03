package io.github.ayech14.mealiq.persistence.catalogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.domain.MealType;
import io.github.ayech14.mealiq.domain.NutritionSummary;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.RecipeSource;
import io.github.ayech14.mealiq.domain.UserProfile;
import io.github.ayech14.mealiq.persistence.PersistenceException;
import io.github.ayech14.mealiq.services.NutritionService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RecipeCatalogueLoaderTest {

    private static final Map<String, Ingredient> CATALOGUE = CatalogueLoader.read().stream()
            .map(CatalogueLoader.Entry::ingredient).collect(Collectors.toMap(Ingredient::name, i -> i));
    private static final Function<String, Optional<Ingredient>> LOOKUP =
            name -> Optional.ofNullable(CATALOGUE.get(name));

    private final List<Recipe> recipes = RecipeCatalogueLoader.read(LOOKUP);

    @Test
    void thirtyCuratedRecipesWithStableIds() {
        assertEquals(30, recipes.size());
        for (Recipe recipe : recipes) {
            assertEquals(RecipeCatalogueLoader.recipeId(recipe.name()), recipe.id());
            assertEquals(RecipeSource.CURATED, recipe.source());
            assertTrue(!recipe.instructions().isEmpty() && !recipe.mealTypes().isEmpty(), recipe.name());
        }
    }

    @Test
    void everyRecipeHasCompleteNutrition() {
        NutritionService nutrition = new NutritionService();
        for (Recipe recipe : recipes) {
            NutritionSummary serving = nutrition.calculateNutrition(recipe, 1);
            assertTrue(serving.complete(), recipe.name() + " has an ingredient whose amount cannot be weighed");
            assertTrue(serving.calories() > 50 && serving.calories() < 1200, recipe.name() + ": " + serving);
        }
    }

    @Test
    void everyMealTypeHasChoices() {
        for (MealType type : MealType.values()) {
            long count = recipes.stream().filter(r -> r.mealTypes().contains(type)).count();
            assertTrue(count >= 5, type + " has only " + count + " recipes");
        }
    }

    /** A diet tag on a recipe must be true by the same rules that enforce the diet. */
    @Test
    void dietTagsAgreeWithDietRules() {
        Map<String, String> tagToRule = Map.of("vegetarian", "vegetarian", "gluten-free", "gluten-free",
                "plant-based", "vegan");
        for (Recipe recipe : recipes) {
            for (Map.Entry<String, String> tag : tagToRule.entrySet()) {
                if (recipe.tags().contains(tag.getKey())) {
                    UserProfile profile = new UserProfile().dietaryRestrictions(Set.of(tag.getValue()));
                    recipe.ingredients().stream().filter(l -> !l.optional()).forEach(l -> assertTrue(
                            profile.isAllowed(l.ingredient()),
                            recipe.name() + " is tagged " + tag.getKey() + " but uses " + l.ingredient().name()));
                }
            }
        }
    }

    @Test
    void unknownIngredientsAndDuplicatesAreAllReported() {
        String json = """
                [{"name": "A", "mealTypes": ["SNACK"], "prepMinutes": 1, "cookMinutes": 0, "servings": 1,
                  "tags": [], "instructions": ["x"],
                  "ingredients": [{"name": "unobtainium", "amount": 1, "unit": "g"}]},
                 {"name": "a", "mealTypes": ["SNACK"], "prepMinutes": 1, "cookMinutes": 0, "servings": 1,
                  "tags": [], "instructions": ["x"], "ingredients": []}]
                """;
        PersistenceException error = assertThrows(PersistenceException.class,
                () -> RecipeCatalogueLoader.parse(json, LOOKUP));
        assertTrue(error.getMessage().contains("'unobtainium' is not in the ingredient catalogue"), error.getMessage());
        assertTrue(error.getMessage().contains("duplicate recipe 'a'"), error.getMessage());
    }

    @Test
    void optionalFlagDefaultsToRequired() {
        Recipe yogurt = recipes.stream().filter(r -> r.name().equals("Greek yogurt berry bowl")).findFirst()
                .orElseThrow();
        assertEquals(List.of(false, false, false, true),
                yogurt.ingredients().stream().map(l -> l.optional()).toList());
    }
}
