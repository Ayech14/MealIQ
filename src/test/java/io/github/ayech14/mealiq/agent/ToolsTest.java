package io.github.ayech14.mealiq.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ayech14.mealiq.contracts.PlanRequest;
import io.github.ayech14.mealiq.contracts.PlanningContext;
import io.github.ayech14.mealiq.domain.MealPlan;
import io.github.ayech14.mealiq.domain.MealType;
import io.github.ayech14.mealiq.domain.Pantry;
import io.github.ayech14.mealiq.domain.PlannedMeal;
import io.github.ayech14.mealiq.domain.PlanningMode;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.UserProfile;
import io.github.ayech14.mealiq.services.BudgetService;
import io.github.ayech14.mealiq.services.ConstraintValidator;
import io.github.ayech14.mealiq.services.GroceryService;
import io.github.ayech14.mealiq.services.InMemoryRepositories;
import io.github.ayech14.mealiq.services.NutritionService;
import io.github.ayech14.mealiq.services.PantryService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ToolsTest {

    final InMemoryRepositories.Catalogue catalogue = new InMemoryRepositories.Catalogue();
    final InMemoryRepositories.Recipes recipes = new InMemoryRepositories.Recipes(catalogue);
    final NutritionService nutrition = new NutritionService();
    final UUID profileId = UUID.randomUUID();

    PlanningContext context(UserProfile profile) {
        return PlanningContext.of(new PlanRequest(profileId, LocalDate.of(2026, 11, 3), 1,
                PlanningMode.NUTRITION_GOAL, null), profile, Pantry.emptyFor(profileId));
    }

    @Test
    void managerRejectsDuplicatesAndUnknownTools() {
        ToolManager manager = new ToolManager();
        manager.register(new SubstitutionTool(catalogue));
        assertThrows(IllegalArgumentException.class, () -> manager.register(new SubstitutionTool(catalogue)));
        assertEquals(List.of("substituteIngredient"), manager.availableTools());
        assertFalse(manager.isValidCall("recipeSearch", Map.of()));
        assertFalse(manager.isValidCall(null, Map.of()));
        assertTrue(manager.isValidCall("substituteIngredient", Map.of("ingredient", "milk")));
        ToolResult rejected = manager.execute("substituteIngredient", Map.of(), context(new UserProfile(profileId)));
        assertEquals("ERROR: 'ingredient' is required", rejected.observation());
    }

    @Test
    void recipeSearchChecksArgumentTypes() {
        RecipeTool tool = new RecipeTool(recipes,
                new ConstraintValidator(nutrition, new BudgetService((i, q) -> Optional.empty())), nutrition);
        assertEquals(List.of("'mealType' must be one of [breakfast, lunch, dinner, snack]",
                        "'include' must be a list of strings"),
                tool.checkArguments(Map.of("mealType", "brunch", "include", "egg")));
        assertTrue(tool.checkArguments(Map.of("mealType", "Dinner", "maxMinutes", 20.0)).isEmpty(),
                "JSON numbers may arrive as doubles");
    }

    @Test
    void nutritionAnalysisUsesCatalogueValues() {
        NutritionTool tool = new NutritionTool(nutrition, recipes);
        assertEquals(List.of("no recipe named 'Pancake tower'"),
                tool.checkArguments(Map.of("recipes", List.of("Lentil soup", "Pancake tower"))));
        ToolResult result = tool.execute(Map.of("recipes", List.of("lentil soup"), "servings", 2),
                context(new UserProfile(profileId)));
        assertTrue(result.summary().startsWith("For 2 serving(s):\n- Lentil soup: "), result.summary());
        assertTrue(result.summary().endsWith("g fat"), result.summary());
    }

    @Test
    void substitutesRespectAllergiesAndDislikes() {
        SubstitutionTool tool = new SubstitutionTool(catalogue);
        UserProfile profile = new UserProfile(profileId).allergies(Set.of("tree-nut")).dislikedFoods(Set.of("skim milk"));
        ToolResult result = tool.execute(Map.of("ingredient", "milk"), context(profile));
        assertEquals("Substitutes for milk, best first: soy milk", result.summary());
        assertEquals(List.of("'unobtainium' is not in the ingredient catalogue"),
                tool.checkArguments(Map.of("ingredient", "unobtainium")));
    }

    @Test
    void memoryKeepsTheNewestEntriesPerProfile() {
        ConversationMemory memory = new ConversationMemory(2);
        memory.recordDecision(profileId, "Recommended Lentil soup");
        memory.recordDecision(profileId, "Recommended Trail mix");
        memory.recordDecision(profileId, "Recommended Tofu scramble");
        assertEquals(List.of("Recommended Tofu scramble", "Recommended Trail mix"),
                memory.retrieveRelevantContext(profileId, null));
        assertEquals(List.of("Recommended Trail mix"), memory.retrieveRelevantContext(profileId, "more trail food"));
        assertTrue(memory.retrieveRelevantContext(UUID.randomUUID(), "x").isEmpty());
    }

    @Test
    void groceryListNeedsAnActivePlan() {
        GroceryTool tool = new GroceryTool(new GroceryService(new InMemoryRepositories.GroceryLists(),
                new PantryService(new InMemoryRepositories.Pantries(), catalogue), (i, q) -> Optional.empty()));
        UserProfile profile = new UserProfile(profileId);
        assertEquals("ERROR: there is no active meal plan yet", tool.execute(Map.of(), context(profile)).observation());
        assertEquals(List.of("unknown argument 'planId'; allowed: "), tool.checkArguments(Map.of("planId", "x")));

        Recipe soup = recipes.search(io.github.ayech14.mealiq.contracts.RecipeQuery.any()).stream()
                .filter(r -> r.name().equals("Lentil soup")).findFirst().orElseThrow();
        MealPlan plan = new MealPlan(profileId, LocalDate.of(2026, 11, 3), 1,
                List.of(new PlannedMeal(LocalDate.of(2026, 11, 3), MealType.DINNER, soup, 1)));
        PlanningContext withPlan = new PlanningContext(context(profile).request(), profile, Pantry.emptyFor(profileId),
                plan, null, List.of(), List.of());
        ToolResult result = tool.execute(Map.of(), withPlan);
        assertTrue(result.summary().startsWith("9 item(s) to buy (some prices unknown):\n- "), result.summary());
    }
}
