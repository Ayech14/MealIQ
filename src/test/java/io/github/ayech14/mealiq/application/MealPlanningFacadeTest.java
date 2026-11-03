package io.github.ayech14.mealiq.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ayech14.mealiq.contracts.AgentResponse;
import io.github.ayech14.mealiq.contracts.RecipeQuery;
import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.domain.MealType;
import io.github.ayech14.mealiq.domain.PantryItem;
import io.github.ayech14.mealiq.domain.PlanningMode;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.ResponseStatus;
import io.github.ayech14.mealiq.domain.UserProfile;
import io.github.ayech14.mealiq.services.InMemoryRepositories;
import io.github.ayech14.mealiq.services.InvalidInputException;
import io.github.ayech14.mealiq.services.NotFoundException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MealPlanningFacadeTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 2);

    private InMemoryRepositories.RecordingObserver observer;
    private MealPlanningFacade facade;
    private UUID profileId;
    private TestFacades.Fixture fixture;

    @BeforeEach
    void setUp() {
        fixture = TestFacades.inMemory(TODAY);
        facade = fixture.facade();
        observer = fixture.pantryEvents();
        profileId = MealPlanningFacade.localProfileId("default");
    }

    @Test
    void localProfileIdsAreStableAndIgnoreCase() {
        assertEquals(MealPlanningFacade.localProfileId(" Alex "), MealPlanningFacade.localProfileId("alex"));
        assertNotEquals(MealPlanningFacade.localProfileId("alex"), MealPlanningFacade.localProfileId("sam"));
    }

    @Test
    void profileIsSavedThenFound() {
        assertThrows(NotFoundException.class, () -> facade.getProfile(profileId));
        facade.updateProfile(new UserProfile(profileId).weeklyBudget(new BigDecimal("80")));
        assertEquals(new BigDecimal("80"), facade.getProfile(profileId).weeklyBudget());
    }

    @Test
    void invalidProfileIsNotSaved() {
        assertThrows(InvalidInputException.class, () -> facade.updateProfile(new UserProfile(profileId).mealsPerDay(9)));
        assertThrows(NotFoundException.class, () -> facade.getProfile(profileId));
    }

    @Test
    void profileWarningsComeFromTheService() {
        UserProfile profile = new UserProfile(profileId).dietaryRestrictions(Set.of("vegan"))
                .preferredFoods(Set.of("cheddar cheese"));
        assertEquals(1, facade.profileWarnings(profile).size());
    }

    @Test
    void updatePantryReportsSuccessAndPublishes() {
        AgentResponse response = facade.updatePantry(profileId, facade.pantryItem("Egg", 6, "unit", null));
        assertEquals(ResponseStatus.SUCCESS, response.status());
        assertEquals("Pantry updated: egg 6 unit.", response.rationale());
        assertEquals(1, facade.getPantry(profileId).items().size());
        assertEquals(1, observer.events.size());
    }

    @Test
    void badPantryInputReportsEveryField() {
        InvalidInputException error = assertThrows(InvalidInputException.class,
                () -> facade.pantryItem("unobtainium", -1, "bushel", null));
        assertEquals(Set.of("ingredient", "quantity", "unit"), error.errors().keySet());
    }

    @Test
    void removeByNameAndUnknownNameIsNotFound() {
        facade.updatePantry(profileId, facade.pantryItem("milk", 1, "l", null));
        assertThrows(NotFoundException.class, () -> facade.removePantryItem(profileId, "egg"));
        assertTrue(facade.removePantryItem(profileId, " MILK ").items().isEmpty());
    }

    @Test
    void expiredItemsUseTheServiceClock() {
        facade.updatePantry(profileId, facade.pantryItem("milk", 1, "l", TODAY.minusDays(1)));
        facade.updatePantry(profileId, facade.pantryItem("egg", 6, "unit", TODAY));
        List<PantryItem> expired = facade.expiredPantryItems(profileId);
        assertEquals(List.of("milk"), expired.stream().map(i -> i.ingredient().name()).toList());
    }

    @Test
    void searchIngredientsMatchesPartOfTheName() {
        List<String> names = facade.searchIngredients(" Milk").stream().map(Ingredient::name).toList();
        assertTrue(names.containsAll(List.of("almond milk", "milk", "skim milk", "soy milk")), names.toString());
        assertTrue(names.stream().allMatch(n -> n.contains("milk")));
        assertEquals(facade.searchIngredients(null).size(), facade.searchIngredients("").size());
        assertTrue(facade.searchIngredients("unobtainium").isEmpty());
    }

    @Test
    void browseExcludesRecipesThatBreakTheProfileAndSaysWhy() {
        facade.updateProfile(new UserProfile(profileId).allergies(Set.of("peanut")));
        AgentResponse response = facade.browseRecipes(profileId, RecipeQuery.any().withMealType(MealType.SNACK),
                PlanningMode.NUTRITION_GOAL);
        List<String> names = response.recipes().stream().map(Recipe::name).toList();
        assertFalse(names.contains("Apple with peanut butter"));
        assertTrue(response.conflicts().contains("Apple with peanut butter contains peanut butter (allergy: peanut)."),
                response.conflicts().toString());
        assertEquals(ResponseStatus.SUCCESS, response.status());
        assertEquals("", response.rationale());
    }

    @Test
    void browseWithoutProfileAppliesNoRestrictions() {
        AgentResponse response = facade.browseRecipes(profileId, RecipeQuery.any(), PlanningMode.NUTRITION_GOAL);
        assertEquals(30, response.recipes().size());
        assertTrue(response.conflicts().isEmpty());
        assertTrue(response.rationale().startsWith("No saved profile"));
    }

    @Test
    void browseRanksByTheChosenMode() {
        AgentResponse byTime = facade.browseRecipes(profileId, RecipeQuery.any().withMealType(MealType.DINNER),
                PlanningMode.TIME_AWARE);
        List<Integer> minutes = byTime.recipes().stream().map(Recipe::totalMinutes).toList();
        assertEquals(minutes.stream().sorted().toList(), minutes, "no limit set, so quickest first");
    }

    @Test
    void nothingLeftIsAConflict() {
        facade.updateProfile(new UserProfile(profileId).dietaryRestrictions(Set.of("vegan")));
        AgentResponse response = facade.browseRecipes(profileId, RecipeQuery.any().including("chicken"),
                PlanningMode.NUTRITION_GOAL);
        assertEquals(ResponseStatus.CONFLICT, response.status());
        assertFalse(response.conflicts().isEmpty());
    }

    @Test
    void findRecipeIgnoresCaseAndSuggestsCloseNames() {
        assertEquals("Lentil soup", facade.findRecipe("  lentil SOUP ").name());
        NotFoundException missing = assertThrows(NotFoundException.class, () -> facade.findRecipe("tacos"));
        assertEquals("No recipe named 'tacos'. Did you mean: Black bean tacos, Shrimp tacos?", missing.getMessage());
    }

    @Test
    void recipeNutritionAndCheck() {
        Recipe toast = facade.findRecipe("Peanut butter banana toast");
        assertTrue(facade.recipeNutrition(toast, 1).complete());
        assertEquals(2 * facade.recipeNutrition(toast, 1).calories(), facade.recipeNutrition(toast, 2).calories(),
                1e-6);
        assertTrue(facade.checkRecipe(profileId, toast).valid(), "no saved profile");
        facade.updateProfile(new UserProfile(profileId).allergies(Set.of("peanut")));
        assertFalse(facade.checkRecipe(profileId, toast).valid());
    }

    /** Saves an active one-day plan of lentil soup, 4 servings, without going through the agent. */
    private void activeSoupPlan() {
        Recipe soup = facade.findRecipe("Lentil soup");
        io.github.ayech14.mealiq.domain.MealPlan plan = new io.github.ayech14.mealiq.domain.MealPlan(profileId, TODAY,
                1, List.of(new io.github.ayech14.mealiq.domain.PlannedMeal(TODAY, MealType.DINNER, soup, 4)));
        plan.status(io.github.ayech14.mealiq.domain.PlanStatus.ACTIVE);
        fixture.plans().save(plan);
    }

    @Test
    void groceryListWithoutABudgetIsJustPriced() {
        activeSoupPlan();
        AgentResponse response = facade.generateOptimizedGroceryList(profileId);
        assertEquals(ResponseStatus.SUCCESS, response.status());
        assertTrue(response.groceryList().estimatedCost().signum() > 0);
        assertEquals(null, response.budgetStatus());
        assertTrue(response.rationale().startsWith("No weekly budget is set"));
    }

    @Test
    void groceryListOverBudgetIsAConflictWithTheShortfall() {
        activeSoupPlan();
        facade.updateProfile(new UserProfile(profileId).weeklyBudget(new BigDecimal("7")));
        AgentResponse response = facade.generateOptimizedGroceryList(profileId);
        assertEquals(ResponseStatus.CONFLICT, response.status());
        assertEquals(new BigDecimal("1.00"), response.budgetStatus().budget(), "a 1-day plan gets 1/7 of $7");
        assertTrue(response.conflicts().getFirst().startsWith("Over budget by $" + response.budgetStatus().shortfall()
                + ". Biggest costs: "), response.conflicts().toString());
        assertTrue(facade.getGroceryList(profileId).isPresent(), "the list is saved either way");
    }

    @Test
    void groceryListWithinBudget() {
        activeSoupPlan();
        facade.updateProfile(new UserProfile(profileId).weeklyBudget(new BigDecimal("700")));
        AgentResponse response = facade.generateOptimizedGroceryList(profileId);
        assertEquals(ResponseStatus.SUCCESS, response.status());
        assertTrue(response.budgetStatus().withinBudget());
    }

    @Test
    void recipeCostCountsThePantry() {
        Recipe soup = facade.findRecipe("Lentil soup");
        BigDecimal before = facade.recipeCost(profileId, soup).known();
        facade.updatePantry(profileId, facade.pantryItem("lentils", 1, "kg", null));
        assertTrue(facade.recipeCost(profileId, soup).known().compareTo(before) < 0);
    }
}
