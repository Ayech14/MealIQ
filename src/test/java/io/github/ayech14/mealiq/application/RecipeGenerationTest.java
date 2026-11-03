package io.github.ayech14.mealiq.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ayech14.mealiq.contracts.AgentResponse;
import io.github.ayech14.mealiq.contracts.RecipeQuery;
import io.github.ayech14.mealiq.domain.MealType;
import io.github.ayech14.mealiq.domain.PlanningMode;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.RecipeSource;
import io.github.ayech14.mealiq.domain.ResponseStatus;
import io.github.ayech14.mealiq.domain.UserProfile;
import io.github.ayech14.mealiq.llm.JsonSchema;
import io.github.ayech14.mealiq.llm.LLMException;
import io.github.ayech14.mealiq.llm.ScriptedLLMClient;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** F04 through the facade: schema-limited generation, deterministic checks, one retry, save on request. */
class RecipeGenerationTest {

    TestFacades.Fixture fixture;
    MealPlanningFacade facade;
    ScriptedLLMClient llm;
    final UUID profileId = MealPlanningFacade.localProfileId("default");

    @BeforeEach
    void setUp() {
        fixture = TestFacades.inMemory(LocalDate.of(2026, 11, 3));
        facade = fixture.facade();
        llm = fixture.llm();
        facade.updateProfile(new UserProfile(profileId).allergies(Set.of("peanut")).dislikedFoods(Set.of("kale")));
    }

    static Map<String, Object> recipe(String name, String mealType, int prep, int cook, Object... lines) {
        List<Map<String, Object>> ingredients = new java.util.ArrayList<>();
        for (int i = 0; i < lines.length; i += 3) {
            ingredients.add(Map.of("ingredient", lines[i], "amount", lines[i + 1], "unit", lines[i + 2],
                    "optional", false));
        }
        return Map.of("name", name, "mealTypes", List.of(mealType), "prepMinutes", prep, "cookMinutes", cook,
                "servings", 2, "ingredients", ingredients, "instructions", List.of("Cook.", "Serve."),
                "tags", List.of("quick"));
    }

    @Test
    void theSchemaOnlyOffersAllowedCatalogueIngredients() {
        llm.queue(recipe("Oat porridge", "breakfast", 2, 8, "rolled oats", 80, "g", "milk", 300, "ml"));
        facade.generateRecipe(profileId, "warm breakfast", RecipeQuery.any());
        List<?> names = ingredientEnum(llm);
        assertTrue(names.contains("rolled oats"));
        assertFalse(names.contains("peanut butter"), "allergen");
        assertFalse(names.contains("kale"), "disliked");
        assertTrue(llm.calls().getFirst().prompt().contains("USER REQUEST: warm breakfast"));
    }

    @Test
    void aValidRecipeIsPreviewedThenSavedOnce() {
        llm.queue(recipe("Oat porridge", "breakfast", 2, 8, "rolled oats", 80, "g", "milk", 300, "ml"));

        AgentResponse response = facade.generateRecipe(profileId, "warm breakfast",
                RecipeQuery.any().withMealType(MealType.BREAKFAST).withMaxMinutes(15));

        assertEquals(ResponseStatus.NEEDS_CONFIRMATION, response.status(), response.conflicts().toString());
        Recipe generated = response.recipes().getFirst();
        assertEquals(RecipeSource.GENERATED, generated.source());
        assertTrue(facade.recipeNutrition(generated, 1).complete(), "nutrition comes from the catalogue");
        assertFalse(facade.browseRecipes(profileId, RecipeQuery.any().withGenerated(true),
                PlanningMode.NUTRITION_GOAL).recipes().contains(generated), "not saved before confirmation");

        assertEquals(ResponseStatus.SUCCESS, facade.saveGeneratedRecipe(generated.id()).status());
        assertEquals(generated, facade.findRecipe("oat porridge"));
        assertEquals(ResponseStatus.FAILED, facade.saveGeneratedRecipe(generated.id()).status());
        assertEquals(ResponseStatus.FAILED, facade.saveGeneratedRecipe(UUID.randomUUID()).status());
        assertFalse(facade.browseRecipes(profileId, RecipeQuery.any(), PlanningMode.NUTRITION_GOAL).recipes()
                .contains(generated), "generated recipes are listed only on request");
    }

    @Test
    void aDraftThatMissesTheRequestIsRetriedOnceWithItsProblems() {
        llm.queue(recipe("Slow stew", "dinner", 20, 60, "lentils", 200, "g", "basil", 1, "unit"),
                recipe("Quick lentils", "dinner", 5, 15, "lentils", 200, "g", "onion", 1, "unit"));

        AgentResponse response = facade.generateRecipe(profileId, "lentil dinner",
                RecipeQuery.any().withMealType(MealType.DINNER).withMaxMinutes(20));

        assertEquals(ResponseStatus.NEEDS_CONFIRMATION, response.status(), response.conflicts().toString());
        assertEquals("Quick lentils", response.recipes().getFirst().name());
        String retry = llm.calls().get(1).prompt();
        assertTrue(retry.contains("YOUR PREVIOUS RECIPE WAS REJECTED; FIX THESE PROBLEMS:"), retry);
        assertTrue(retry.contains("- '1 unit' of basil cannot be weighed; use g, kg, ml or l for it."), retry);
        assertTrue(retry.contains("- It takes 80 minutes; the limit is 20."), retry);
    }

    @Test
    void aSecondFailureIsDeclinedWithItsProblems() {
        Map<String, Object> bad = recipe("Toast", "snack", 1, 2, "white bread", 2, "unit");
        llm.queue(bad, bad);
        AgentResponse response = facade.generateRecipe(profileId, "x",
                RecipeQuery.any().withMealType(MealType.BREAKFAST).including("egg"));
        assertEquals(ResponseStatus.CONFLICT, response.status());
        assertEquals("No usable recipe after 2 attempt(s); nothing was saved.", response.rationale());
        assertEquals(List.of("It must be a breakfast recipe.", "It must use egg."), response.conflicts());
    }

    @Test
    void aDietRemovesForbiddenIngredientsFromTheSchema() {
        facade.updateProfile(facade.getProfile(profileId).dietaryRestrictions(Set.of("vegetarian")));
        llm.queue(recipe("Toast", "breakfast", 1, 2, "white bread", 60, "g"));
        assertEquals(ResponseStatus.NEEDS_CONFIRMATION,
                facade.generateRecipe(profileId, "toast", RecipeQuery.any()).status());
        List<?> offered = ingredientEnum(llm);
        assertFalse(offered.contains("chicken breast"), "meat is not even offered to a vegetarian");
        assertTrue(offered.contains("tofu"));
    }

    @Test
    void failuresAndMissingProfilesChangeNothing() {
        llm.queue(new LLMException(LLMException.Kind.RATE_LIMITED, "429"));
        assertEquals(ResponseStatus.FAILED, facade.generateRecipe(profileId, "x", RecipeQuery.any()).status());
        assertEquals(ResponseStatus.NEEDS_CLARIFICATION, facade.generateRecipe(
                MealPlanningFacade.localProfileId("nobody"), "x", RecipeQuery.any()).status());
    }

    /** The ingredient names the last generation call's schema allowed. */
    static List<?> ingredientEnum(ScriptedLLMClient llm) {
        JsonSchema schema = llm.calls().getLast().schema();
        Map<?, ?> line = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) schema.definition().get("properties"))
                .get("ingredients")).get("items");
        return (List<?>) ((Map<?, ?>) ((Map<?, ?>) line.get("properties")).get("ingredient")).get("enum");
    }
}
