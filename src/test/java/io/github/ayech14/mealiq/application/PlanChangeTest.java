package io.github.ayech14.mealiq.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ayech14.mealiq.contracts.AgentResponse;
import io.github.ayech14.mealiq.contracts.PlanChange;
import io.github.ayech14.mealiq.domain.ChangeType;
import io.github.ayech14.mealiq.domain.MealPlan;
import io.github.ayech14.mealiq.domain.MealType;
import io.github.ayech14.mealiq.domain.PlanStatus;
import io.github.ayech14.mealiq.domain.PlannedMeal;
import io.github.ayech14.mealiq.domain.PlanningMode;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.RecipeSource;
import io.github.ayech14.mealiq.domain.ResponseStatus;
import io.github.ayech14.mealiq.domain.UserProfile;
import io.github.ayech14.mealiq.llm.LLMException;
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

/** F08, F09 and F10 through the facade: propose, validate, preview, confirm, undo (SD03, D03). */
class PlanChangeTest {

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
        plan = new MealPlan(UUID.randomUUID(), profileId, START, 2, PlanStatus.ACTIVE, List.of(
                meal(0, MealType.BREAKFAST, "Overnight oats"), meal(0, MealType.LUNCH, "Lentil soup"),
                meal(0, MealType.DINNER, "Chicken stir-fry"), meal(1, MealType.BREAKFAST, "Tofu scramble"),
                meal(1, MealType.LUNCH, "Turkey veggie wrap"), meal(1, MealType.DINNER, "Shrimp tacos")));
        fixture.plans().save(plan);
    }

    PlannedMeal meal(int day, MealType type, String recipe) {
        return new PlannedMeal(START.plusDays(day), type, facade.findRecipe(recipe), 1);
    }

    String recipeOn(int day, MealType type) {
        return facade.getActivePlan(profileId).orElseThrow().mealsOn(START.plusDays(day)).stream()
                .filter(m -> m.mealType() == type).findFirst().orElseThrow().recipe().name();
    }

    static Map<String, Object> search(String args) {
        return Map.of("action", "call_tool", "tool", "recipeSearch", "arguments", args, "reason", "candidates");
    }

    static Map<String, Object> finish() {
        Map<String, Object> answer = new HashMap<>();
        answer.put("action", "finish");
        answer.put("tool", null);
        answer.put("arguments", "{}");
        answer.put("reason", "done");
        return answer;
    }

    static Map<String, Object> replace(String rationale, int day, String mealType, String recipe) {
        Map<String, Object> answer = new HashMap<>();
        answer.put("action", "replace");
        answer.put("replacements", List.of(Map.of("day", day, "mealType", mealType, "recipe", recipe)));
        answer.put("question", null);
        answer.put("rationale", rationale);
        return answer;
    }

    void queueVegetarianDinner(String recipe) {
        llm.queue(search("{\"mealType\": \"dinner\", \"exclude\": [\"chicken\", \"beef\", \"shrimp\"]}"), finish(),
                replace("Tofu keeps the protein up.", 1, "dinner", recipe));
    }

    @Test
    void typedChangeIsPreviewedThenAppliedOnlyAfterConfirmation() {
        queueVegetarianDinner("Vegetable tofu stir-fry");

        AgentResponse preview = facade.modifyPlan(profileId, "make Tuesday's dinner vegetarian",
                PlanningMode.NUTRITION_GOAL);

        assertEquals(ResponseStatus.NEEDS_CONFIRMATION, preview.status(), preview.conflicts().toString());
        assertEquals(ChangeType.REPLACE_MEAL, preview.change().changeType());
        assertEquals("Chicken stir-fry", recipeOn(0, MealType.DINNER), "not applied before confirmation");
        assertTrue(preview.conflicts().stream().anyMatch(n -> n.startsWith("Grocery cost for the plan goes from ")),
                preview.conflicts().toString());
        String changePrompt = llm.calls().get(2).prompt();
        assertTrue(changePrompt.contains("CHANGE REQUEST: make Tuesday's dinner vegetarian"), changePrompt);
        assertTrue(changePrompt.contains("- day 1 (Tuesday) dinner: Chicken stir-fry"), changePrompt);

        AgentResponse applied = facade.executeChange(plan.id(), preview.change());

        assertEquals(ResponseStatus.SUCCESS, applied.status(), applied.rationale());
        assertEquals("Vegetable tofu stir-fry", recipeOn(0, MealType.DINNER));
        assertEquals("Replace 2026-11-03 dinner: Chicken stir-fry -> Vegetable tofu stir-fry", applied.rationale());
        assertTrue(applied.groceryList().items().stream().anyMatch(i -> i.ingredient().name().equals("tofu")),
                "the grocery list was refreshed");
        assertEquals(1, facade.changeHistory(profileId).size());
    }

    @Test
    void aChangeCanBeConfirmedOnlyOnceAndOnlyIfItWasProposed() {
        queueVegetarianDinner("Vegetable tofu stir-fry");
        PlanChange change = facade.modifyPlan(profileId, "vegetarian dinner", PlanningMode.NUTRITION_GOAL).change();
        assertEquals(ResponseStatus.SUCCESS, facade.executeChange(plan.id(), change).status());
        assertEquals(ResponseStatus.FAILED, facade.executeChange(plan.id(), change).status());

        PlanChange forged = PlanChange.proposed(ChangeType.REPLACE_MEAL, List.of(), change.proposal(), "trust me");
        assertEquals(ResponseStatus.FAILED, facade.executeChange(plan.id(), forged).status());
    }

    @Test
    void undoRestoresThePreviousRecipe() {
        queueVegetarianDinner("Vegetable tofu stir-fry");
        facade.executeChange(plan.id(), facade.modifyPlan(profileId, "x", PlanningMode.NUTRITION_GOAL).change());

        AgentResponse undone = facade.undoLastChange(profileId);

        assertEquals(ResponseStatus.SUCCESS, undone.status());
        assertEquals("Undone: Replace 2026-11-03 dinner: Chicken stir-fry -> Vegetable tofu stir-fry",
                undone.rationale());
        assertEquals("Chicken stir-fry", recipeOn(0, MealType.DINNER));
        assertEquals(ResponseStatus.FAILED, facade.undoLastChange(profileId).status());
    }

    @Test
    void anInvalidChangeIsAConflictAndCannotBeConfirmed() {
        facade.updateProfile(facade.getProfile(profileId).maxCookMinutes(Map.of(DayOfWeek.TUESDAY, 20)));
        queueVegetarianDinner("Vegetable tofu stir-fry"); // 25 minutes
        AgentResponse response = facade.modifyPlan(profileId, "vegetarian dinner", PlanningMode.NUTRITION_GOAL);
        assertEquals(ResponseStatus.CONFLICT, response.status());
        assertTrue(response.conflicts().getFirst().contains("over the 20-minute limit for Tuesday"),
                response.conflicts().toString());
        assertEquals(ResponseStatus.FAILED, facade.executeChange(plan.id(), response.change()).status());
        assertEquals("Chicken stir-fry", recipeOn(0, MealType.DINNER));
    }

    @Test
    void sameRecipeTwiceOnADayIsRejected() {
        llm.queue(search("{\"mealType\": \"dinner\"}"), finish(), replace("Same.", 1, "dinner", "Lentil soup"));
        AgentResponse response = facade.modifyPlan(profileId, "soup for dinner", PlanningMode.NUTRITION_GOAL);
        assertEquals(ResponseStatus.CONFLICT, response.status());
        assertTrue(response.conflicts().contains("Lentil soup would be planned 2 times on one day."),
                response.conflicts().toString());
    }

    @Test
    void whenThePlanAlreadyFitsNothingIsProposed() {
        Map<String, Object> none = new HashMap<>();
        none.put("action", "no_change");
        none.put("replacements", List.of());
        none.put("question", null);
        none.put("rationale", "Tuesday's lunch is already vegetarian.");
        llm.queue(finish(), none);
        AgentResponse response = facade.modifyPlan(profileId, "vegetarian lunch on Tuesday",
                PlanningMode.NUTRITION_GOAL);
        assertEquals(ResponseStatus.SUCCESS, response.status());
        assertEquals("Nothing to change: Tuesday's lunch is already vegetarian.", response.rationale());

        llm.queue(search("{\"mealType\": \"lunch\"}"), finish(), replace("Keep it.", 1, "lunch", "Lentil soup"));
        assertEquals(ResponseStatus.SUCCESS,
                facade.modifyPlan(profileId, "soup for lunch", PlanningMode.NUTRITION_GOAL).status(),
                "replacing a meal with itself is no change");
        assertTrue(facade.changeHistory(profileId).isEmpty());
    }

    @Test
    void anAmbiguousRequestGetsAQuestion() {
        Map<String, Object> clarify = new HashMap<>();
        clarify.put("action", "clarify");
        clarify.put("replacements", List.of());
        clarify.put("question", "Which day's dinner do you mean?");
        clarify.put("rationale", "");
        llm.queue(finish(), clarify);
        AgentResponse response = facade.modifyPlan(profileId, "change the dinner", PlanningMode.NUTRITION_GOAL);
        assertEquals(ResponseStatus.NEEDS_CLARIFICATION, response.status());
        assertEquals("Which day's dinner do you mean?", response.rationale());
    }

    @Test
    void namesOutsideTheCandidatesAreReportedAndNothingIsHeld() {
        llm.queue(search("{\"mealType\": \"dinner\"}"), finish(), replace("Fancy.", 1, "dinner", "Lobster"));
        AgentResponse response = facade.modifyPlan(profileId, "something fancy", PlanningMode.NUTRITION_GOAL);
        assertEquals(ResponseStatus.CONFLICT, response.status());
        assertTrue(response.conflicts().contains("Day 1 dinner: 'Lobster' is not one of the candidates."),
                response.conflicts().toString());
    }

    @Test
    void providerFailureChangesNothing() {
        llm.queue(new LLMException(LLMException.Kind.TIMEOUT, "slow"));
        assertEquals(ResponseStatus.FAILED,
                facade.modifyPlan(profileId, "anything", PlanningMode.NUTRITION_GOAL).status());
    }

    @Test
    void substitutionUsesAllowedCandidatesAndCanBeUndone() {
        facade.updateProfile(facade.getProfile(profileId).allergies(Set.of("soy")));
        llm.queue(Map.of("choices", List.of(Map.of("ingredient", "milk", "substitute", "almond milk")),
                "rationale", "Almond milk avoids soy."));

        AgentResponse preview = facade.requestSubstitution(profileId, "Milk");

        assertEquals(ResponseStatus.NEEDS_CONFIRMATION, preview.status(), preview.conflicts().toString());
        String prompt = llm.calls().getFirst().prompt();
        assertTrue(prompt.contains("- milk: almond milk, skim milk"), "soy milk is filtered out: " + prompt);
        Recipe variant = preview.change().proposal().meals().getFirst().recipe();
        assertEquals(RecipeSource.VARIANT, variant.source());

        facade.executeChange(plan.id(), preview.change());
        assertEquals("Overnight oats (almond milk for milk)", recipeOn(0, MealType.BREAKFAST));
        assertTrue(facade.browseRecipes(profileId, io.github.ayech14.mealiq.contracts.RecipeQuery.any(),
                PlanningMode.NUTRITION_GOAL).recipes().stream().noneMatch(r -> r.source() == RecipeSource.VARIANT),
                "variants never show up in searches");

        facade.undoLastChange(profileId);
        assertEquals("Overnight oats", recipeOn(0, MealType.BREAKFAST));
    }

    @Test
    void substitutionOfAnUnusedIngredientAsksBack() {
        AgentResponse response = facade.requestSubstitution(profileId, "salmon");
        assertEquals(ResponseStatus.NEEDS_CLARIFICATION, response.status());
        assertTrue(response.rationale().startsWith("No meal in the plan uses salmon."));
        assertEquals(0, llm.calls().size());
    }

    @Test
    void substitutionWithoutCandidatesIsAConflict() {
        facade.updateProfile(facade.getProfile(profileId).allergies(Set.of("soy", "tree-nut"))
                .dislikedFoods(Set.of("skim milk")));
        AgentResponse response = facade.requestSubstitution(profileId, "milk");
        assertEquals(ResponseStatus.CONFLICT, response.status());
        assertTrue(response.conflicts().contains("No compatible substitute for milk in the catalogue."),
                response.conflicts().toString());
    }

    @Test
    void overBudgetAsksTheAgentForCheaperMeals() {
        facade.updateProfile(facade.getProfile(profileId).weeklyBudget(new BigDecimal("7")));
        llm.queue(search("{\"mealType\": \"dinner\"}"), finish(),
                replace("Lentils cost less.", 2, "dinner", "Black bean tacos"));

        AgentResponse response = facade.suggestCheaperPlan(profileId);

        String toolPrompt = llm.calls().getFirst().prompt();
        assertTrue(toolPrompt.contains("Make the plan cheaper: the groceries are over budget by $"), toolPrompt);
        assertTrue(List.of(ResponseStatus.NEEDS_CONFIRMATION, ResponseStatus.CONFLICT).contains(response.status()));
        assertEquals("Black bean tacos", response.change().proposal().meals().getFirst().recipe().name());
    }

    @Test
    void withinBudgetNeedsNoChange() {
        facade.updateProfile(facade.getProfile(profileId).weeklyBudget(new BigDecimal("1000")));
        AgentResponse response = facade.suggestCheaperPlan(profileId);
        assertEquals(ResponseStatus.SUCCESS, response.status());
        assertEquals("The plan is already within budget.", response.rationale());
        assertEquals(0, llm.calls().size());
        assertFalse(response.groceryList().items().isEmpty());
    }
}
