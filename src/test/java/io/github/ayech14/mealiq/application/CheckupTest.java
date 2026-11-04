package io.github.ayech14.mealiq.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ayech14.mealiq.contracts.Checkup;
import io.github.ayech14.mealiq.domain.MealPlan;
import io.github.ayech14.mealiq.domain.MealType;
import io.github.ayech14.mealiq.domain.PlanStatus;
import io.github.ayech14.mealiq.domain.PlannedMeal;
import io.github.ayech14.mealiq.domain.UserProfile;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The home screen's check-up: what the agent notices and which request it proposes. */
class CheckupTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 3);

    TestFacades.Fixture fixture;
    MealPlanningFacade facade;
    final UUID profileId = MealPlanningFacade.localProfileId("default");

    @BeforeEach
    void setUp() {
        fixture = TestFacades.inMemory(TODAY);
        facade = fixture.facade();
    }

    private List<String> titles(Checkup checkup) {
        return checkup.suggestions().stream().map(Checkup.Suggestion::title).toList();
    }

    private void savePlan(LocalDate start, int days) {
        fixture.plans().save(new MealPlan(UUID.randomUUID(), profileId, start, days, PlanStatus.ACTIVE, List.of(
                new PlannedMeal(start, MealType.BREAKFAST, facade.findRecipe("Overnight oats"), 1),
                new PlannedMeal(start, MealType.LUNCH, facade.findRecipe("Lentil soup"), 1),
                new PlannedMeal(start, MealType.DINNER, facade.findRecipe("Shrimp tacos"), 1))));
    }

    @Test
    void aNewUserIsAskedForTheProfileAndOfferedAPlan() {
        Checkup checkup = facade.checkup(profileId);

        assertEquals(List.of("Tell MealIQ about yourself", "No meal plan yet"), titles(checkup));
        Checkup.Suggestion profile = checkup.suggestions().getFirst();
        assertEquals(Checkup.Level.WARNING, profile.level());
        assertEquals("profile", profile.page());
        assertEquals("Plan 7 days of meals starting today", checkup.suggestions().get(1).agentRequest());
        assertTrue(checkup.todaysMeals().isEmpty());
    }

    @Test
    void todaysMealsAndNutritionComeFromTheCurrentPlan() {
        facade.updateProfile(new UserProfile(profileId));
        savePlan(TODAY, 1);

        Checkup checkup = facade.checkup(profileId);

        assertEquals(3, checkup.todaysMeals().size());
        assertEquals(MealType.BREAKFAST, checkup.todaysMeals().getFirst().mealType());
        assertTrue(checkup.todaysNutrition().orElseThrow().calories() > 0);
        assertEquals(List.of("No shopping list yet"), titles(checkup));
        assertEquals("What do I need to buy?", checkup.suggestions().getFirst().agentRequest());
    }

    @Test
    void anEndedPlanIsNotCurrent() {
        facade.updateProfile(new UserProfile(profileId));
        savePlan(TODAY.minusDays(3), 2);

        Checkup checkup = facade.checkup(profileId);

        assertTrue(checkup.plan().isEmpty());
        assertEquals(List.of("Your plan ended on 2026-11-01"), titles(checkup));
    }

    @Test
    void expiringPantryItemsAndPlanImpactsAreRaised() {
        facade.updateProfile(new UserProfile(profileId));
        savePlan(TODAY, 1);
        facade.generateOptimizedGroceryList(profileId);
        facade.updatePantry(profileId, facade.pantryItem("milk", 1, "l", TODAY.plusDays(2)));
        facade.updatePantry(profileId, facade.pantryItem("spinach", 100, "g", TODAY.minusDays(1)));
        // Lentil soup takes 40 minutes, so a 20-minute limit today affects the plan.
        facade.updateProfile(facade.getProfile(profileId).maxCookMinutes(Map.of(DayOfWeek.TUESDAY, 20)));

        Checkup checkup = facade.checkup(profileId);

        assertEquals(List.of("Your plan no longer fits", "1 pantry item(s) expired", "Use soon: milk"),
                titles(checkup));
        assertEquals("Adapt my plan to my recent changes", checkup.suggestions().getFirst().agentRequest());
        assertEquals("Suggest a recipe that uses milk", checkup.suggestions().get(2).agentRequest());
        assertEquals(1, checkup.expiringSoon());
        assertEquals(2, checkup.pantryItems());
    }

    @Test
    void overBudgetGroceriesSuggestACheaperPlan() {
        facade.updateProfile(new UserProfile(profileId).weeklyBudget(new BigDecimal("1")));
        savePlan(TODAY, 1);
        facade.generateOptimizedGroceryList(profileId);

        Checkup checkup = facade.checkup(profileId);

        assertTrue(titles(checkup).stream().anyMatch(t -> t.startsWith("Groceries are $")), titles(checkup).toString());
        assertTrue(checkup.suggestions().stream().anyMatch(s -> "Make my plan cheaper".equals(s.agentRequest())));
        assertTrue(checkup.itemsToBuy().orElseThrow() > 0);
    }
}
