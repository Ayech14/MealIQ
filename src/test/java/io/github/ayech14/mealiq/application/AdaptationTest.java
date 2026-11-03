package io.github.ayech14.mealiq.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ayech14.mealiq.contracts.AgentResponse;
import io.github.ayech14.mealiq.contracts.PlanImpact;
import io.github.ayech14.mealiq.domain.ChangeType;
import io.github.ayech14.mealiq.domain.MealPlan;
import io.github.ayech14.mealiq.domain.MealType;
import io.github.ayech14.mealiq.domain.PlanStatus;
import io.github.ayech14.mealiq.domain.PlannedMeal;
import io.github.ayech14.mealiq.domain.ResponseStatus;
import io.github.ayech14.mealiq.domain.UserProfile;
import io.github.ayech14.mealiq.llm.ScriptedLLMClient;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** F11 and F13 through the facade: observers record impacts, and adaptation fixes only affected meals (SD04). */
class AdaptationTest {

    /** A Tuesday. */
    static final LocalDate START = LocalDate.of(2026, 11, 3);

    TestFacades.Fixture fixture;
    MealPlanningFacade facade;
    ScriptedLLMClient llm;
    final UUID profileId = MealPlanningFacade.localProfileId("default");
    MealPlan plan;

    @BeforeEach
    void setUp() {
        fixture = TestFacades.inMemory(START);
        facade = fixture.facade();
        llm = fixture.llm();
        facade.updateProfile(new UserProfile(profileId));
        facade.updatePantry(profileId, facade.pantryItem("egg", 12, "unit", null));
        plan = new MealPlan(UUID.randomUUID(), profileId, START, 2, PlanStatus.ACTIVE, List.of(
                meal(0, MealType.BREAKFAST, "Spinach scrambled eggs on toast"), meal(0, MealType.LUNCH, "Lentil soup"),
                meal(0, MealType.DINNER, "Sheet-pan salmon with sweet potato"),
                meal(1, MealType.BREAKFAST, "Overnight oats"), meal(1, MealType.LUNCH, "Egg fried rice"),
                meal(1, MealType.DINNER, "Shrimp tacos")));
        fixture.plans().save(plan);
    }

    PlannedMeal meal(int day, MealType type, String recipe) {
        return new PlannedMeal(START.plusDays(day), type, facade.findRecipe(recipe), 1);
    }

    String recipeOn(int day, MealType type) {
        return facade.getActivePlan(profileId).orElseThrow().mealsOn(START.plusDays(day)).stream()
                .filter(m -> m.mealType() == type).findFirst().orElseThrow().recipe().name();
    }

    void queueReplacements(String rationale, Object... dayTypeRecipe) {
        Map<String, Object> finish = new HashMap<>();
        finish.put("action", "finish");
        finish.put("tool", null);
        finish.put("arguments", "{}");
        finish.put("reason", "done");
        List<Map<String, Object>> replacements = new java.util.ArrayList<>();
        for (int i = 0; i < dayTypeRecipe.length; i += 3) {
            replacements.add(Map.of("day", dayTypeRecipe[i], "mealType", dayTypeRecipe[i + 1],
                    "recipe", dayTypeRecipe[i + 2]));
        }
        Map<String, Object> change = new HashMap<>();
        change.put("action", "replace");
        change.put("replacements", replacements);
        change.put("question", null);
        change.put("rationale", rationale);
        llm.queue(Map.of("action", "call_tool", "tool", "recipeSearch", "arguments", "{}", "reason", "all"), finish,
                change);
    }

    @Test
    void runningOutOfAnIngredientFlagsTheMealsThatUseIt() {
        facade.generateOptimizedGroceryList(profileId);

        AgentResponse updated = facade.updatePantry(profileId, facade.pantryItem("egg", 0, "unit", null));

        assertEquals(List.of(
                "Day 1 (2026-11-03) breakfast: Spinach scrambled eggs on toast uses egg, which is no longer in your "
                        + "pantry.",
                "Day 2 (2026-11-04) lunch: Egg fried rice uses egg, which is no longer in your pantry."),
                updated.conflicts());
        assertTrue(facade.getGroceryList(profileId).orElseThrow().isStale(), "the grocery observer marked it");
        assertEquals(2, facade.pendingImpact(profileId).orElseThrow().affectedMeals().size());
        assertEquals(0, llm.calls().size(), "observers never call the LLM (D06)");
    }

    @Test
    void addingStockHasNoImpact() {
        facade.updatePantry(profileId, facade.pantryItem("milk", 1, "l", null));
        assertTrue(facade.pendingImpact(profileId).isEmpty());
    }

    @Test
    void adaptationReplacesOnlyAffectedMealsAndClearsTheImpact() {
        facade.updatePantry(profileId, facade.pantryItem("egg", 0, "unit", null));
        queueReplacements("No eggs needed.", 1, "breakfast", "Greek yogurt berry bowl",
                2, "lunch", "Turkey veggie wrap", 2, "dinner", "Black bean tacos");

        AgentResponse preview = facade.adaptPlan(profileId);

        assertEquals(ResponseStatus.NEEDS_CONFIRMATION, preview.status(), preview.conflicts().toString());
        assertEquals(ChangeType.REPLACE_MEAL, preview.change().changeType());
        assertEquals(2, preview.change().proposal().meals().size(), "the unaffected dinner was dropped");
        assertTrue(preview.conflicts().contains("Replacements for meals that were not affected were left out."));
        assertTrue(llm.calls().get(2).prompt().contains("Egg fried rice uses egg, which is no longer in your pantry."));

        facade.executeChange(plan.id(), preview.change());

        assertEquals("Greek yogurt berry bowl", recipeOn(0, MealType.BREAKFAST));
        assertEquals("Turkey veggie wrap", recipeOn(1, MealType.LUNCH));
        assertEquals("Shrimp tacos", recipeOn(1, MealType.DINNER));
        assertTrue(facade.pendingImpact(profileId).isEmpty(), "no meal uses eggs any more");
    }

    @Test
    void aNewAllergyFlagsMealsAndCanBeAdapted() {
        facade.updateProfile(facade.getProfile(profileId).allergies(Set.of("fish")));
        PlanImpact impact = facade.pendingImpact(profileId).orElseThrow();
        assertEquals(List.of("Day 1 (2026-11-03) dinner: Sheet-pan salmon with sweet potato contains salmon "
                + "(allergy: fish)."), impact.conflicts());

        queueReplacements("Chicken instead of fish.", 1, "dinner", "Chicken stir-fry");
        AgentResponse preview = facade.adaptPlan(profileId);
        assertEquals(ResponseStatus.NEEDS_CONFIRMATION, preview.status(), preview.conflicts().toString());
        facade.executeChange(plan.id(), preview.change());
        assertTrue(facade.pendingImpact(profileId).isEmpty());
    }

    @Test
    void aTighterTimeLimitIsAdaptedAsAConstraintUpdateAndCanBeUndone() {
        facade.updateProfile(facade.getProfile(profileId).maxCookMinutes(Map.of(DayOfWeek.TUESDAY, 25)));
        assertEquals(List.of(
                "Day 1 (2026-11-03) lunch: Lentil soup takes 40 min, over the new limit of 25 min.",
                "Day 1 (2026-11-03) dinner: Sheet-pan salmon with sweet potato takes 35 min, over the new limit of "
                        + "25 min."), facade.pendingImpact(profileId).orElseThrow().conflicts());

        queueReplacements("Both are quick.", 1, "lunch", "Turkey veggie wrap", 1, "dinner", "Black bean tacos");
        AgentResponse preview = facade.adaptPlan(profileId);
        assertEquals(ChangeType.UPDATE_CONSTRAINT, preview.change().changeType());
        assertTrue(llm.calls().getFirst().prompt().contains("cooking-time limit today: 25 min"),
                "time-aware context: " + llm.calls().getFirst().prompt());

        AgentResponse applied = facade.executeChange(plan.id(), preview.change());
        assertTrue(applied.rationale().startsWith("Adapt Tuesday to the 25-minute limit: Replace 2026-11-03 lunch: "
                + "Lentil soup -> Turkey veggie wrap; Replace 2026-11-03 dinner:"), applied.rationale());
        assertTrue(facade.pendingImpact(profileId).isEmpty());

        facade.undoLastChange(profileId);
        assertEquals("Lentil soup", recipeOn(0, MealType.LUNCH));
        assertFalse(facade.pendingImpact(profileId).isEmpty(), "after undo the problem is back, so is the impact");
    }

    @Test
    void aLowerBudgetIsReported() {
        facade.updateProfile(facade.getProfile(profileId).weeklyBudget(new BigDecimal("1")));
        PlanImpact impact = facade.pendingImpact(profileId).orElseThrow();
        assertTrue(impact.conflicts().getFirst().startsWith("The plan's groceries cost $"), impact.conflicts().toString());
        assertTrue(impact.conflicts().getFirst().endsWith("over the budget of $0.29."));
    }

    @Test
    void anImpactFixedAnotherWayDisappears() {
        facade.updateProfile(facade.getProfile(profileId).allergies(Set.of("fish")));
        facade.updateProfile(facade.getProfile(profileId).allergies(Set.of()));
        assertTrue(facade.pendingImpact(profileId).isEmpty());
        AgentResponse response = facade.adaptPlan(profileId);
        assertEquals(ResponseStatus.SUCCESS, response.status());
        assertEquals("Nothing to adapt: the plan still fits your pantry and profile.", response.rationale());
    }

    @Test
    void withoutAPlanObserversDoNothing() {
        fixture.plans().rows.clear();
        facade.updatePantry(profileId, facade.pantryItem("egg", 0, "unit", null));
        assertTrue(facade.pendingImpact(profileId).isEmpty());
    }
}
