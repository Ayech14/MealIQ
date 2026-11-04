package io.github.ayech14.mealiq.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ayech14.mealiq.contracts.AgentResponse;
import io.github.ayech14.mealiq.contracts.PlanRequest;
import io.github.ayech14.mealiq.domain.MealPlan;
import io.github.ayech14.mealiq.domain.PlannedMeal;
import io.github.ayech14.mealiq.domain.PlanningMode;
import io.github.ayech14.mealiq.domain.ResponseStatus;
import io.github.ayech14.mealiq.domain.UserProfile;
import io.github.ayech14.mealiq.llm.LLMException;
import io.github.ayech14.mealiq.llm.ScriptedLLMClient;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** F05 end to end through the facade: agent proposal, validation, retry and saving (SD01). */
class PlanGenerationTest {

    /** A Tuesday. */
    static final LocalDate START = LocalDate.of(2026, 11, 3);

    MealPlanningFacade facade;
    ScriptedLLMClient llm;
    TestFacades.Fixture fixture;
    final UUID profileId = MealPlanningFacade.localProfileId("default");

    @BeforeEach
    void setUp() {
        fixture = TestFacades.inMemory(START);
        facade = fixture.facade();
        llm = fixture.llm();
        facade.updateProfile(new UserProfile(profileId));
    }

    static Map<String, Object> search(String mealType) {
        return Map.of("action", "call_tool", "tool", "recipeSearch",
                "arguments", "{\"mealType\": \"" + mealType + "\"}", "reason", "candidates");
    }

    static Map<String, Object> finish() {
        Map<String, Object> answer = new HashMap<>();
        answer.put("action", "finish");
        answer.put("tool", null);
        answer.put("arguments", "{}");
        answer.put("reason", "all meal types covered");
        return answer;
    }

    /** One entry per name: day by day, breakfast, lunch, dinner. */
    static Map<String, Object> plan(String rationale, String... names) {
        String[] slots = {"breakfast", "lunch", "dinner"};
        List<Map<String, Object>> meals = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            meals.add(Map.of("day", i / 3 + 1, "mealType", slots[i % 3], "recipe", names[i]));
        }
        return Map.of("meals", meals, "rationale", rationale, "conflicts", List.of());
    }

    void queueSearches() {
        llm.queue(search("breakfast"), search("lunch"), search("dinner"), finish());
    }

    PlanRequest request(int days) {
        return new PlanRequest(profileId, START, days, PlanningMode.NUTRITION_GOAL, null);
    }

    @Test
    void validProposalIsSavedAsTheActivePlan() {
        queueSearches();
        llm.queue(plan("Balanced and quick.", "Overnight oats", "Lentil soup", "Shrimp tacos",
                "Tofu scramble", "Turkey veggie wrap", "Chicken stir-fry"));

        AgentResponse response = facade.generateWeeklyPlan(request(2));

        assertEquals(ResponseStatus.SUCCESS, response.status(), response.conflicts().toString());
        assertEquals("Balanced and quick.", response.rationale());
        MealPlan saved = facade.getActivePlan(profileId).orElseThrow();
        assertEquals(response.plan().id(), saved.id());
        assertEquals(List.of("Overnight oats", "Lentil soup", "Shrimp tacos"),
                saved.mealsOn(START).stream().map(m -> m.recipe().name()).toList());
        assertEquals(2, facade.dailyNutrition(saved).size());
        assertTrue(facade.analyzeNutrition(saved.id()).complete());
        assertEquals(3, response.agentSteps().size());
        assertTrue(response.agentSteps().getFirst().startsWith("Attempt 1: recipeSearch"));
        String planningPrompt = llm.calls().get(4).prompt();
        assertTrue(planningPrompt.contains("PLAN: 2 day(s) from 2026-11-03 (day 1 is Tuesday). Each day needs "
                + "exactly: breakfast, lunch, dinner."), planningPrompt);
        assertTrue(planningPrompt.contains("BREAKFAST CANDIDATES, best first by the nutrition goal ranking"));
        assertTrue(planningPrompt.matches("(?s).*Overnight oats \\(5 min; \\d+ kcal, \\d+ g protein; \\$\\d+\\.\\d{2} to buy\\).*"),
                "candidate facts include the deterministic cost: " + planningPrompt);
    }

    @Test
    void aRejectedProposalIsRetriedOnceWithItsViolations() {
        facade.updateProfile(facade.getProfile(profileId).maxCookMinutes(Map.of(DayOfWeek.TUESDAY, 25)));
        queueSearches();
        llm.queue(plan("Too slow.", "Overnight oats", "Lentil soup", "Shrimp tacos"));
        queueSearches();
        llm.queue(plan("Fixed.", "Overnight oats", "Turkey veggie wrap", "Shrimp tacos"));

        AgentResponse response = facade.generateWeeklyPlan(request(1));

        assertEquals(ResponseStatus.SUCCESS, response.status());
        String retryPrompt = llm.calls().get(9).prompt();
        assertTrue(retryPrompt.contains("YOUR PREVIOUS PLAN WAS REJECTED FOR THESE REASONS; FIX THEM:\n"
                + "- Day 1 (2026-11-03) lunch: Lentil soup takes 40 min, over the 25-minute limit for Tuesday."),
                retryPrompt);
        assertTrue(response.agentSteps().stream().anyMatch(s -> s.startsWith("Attempt 1: rejected: ")));
        assertEquals("Turkey veggie wrap", facade.getActivePlan(profileId).orElseThrow().mealsOn(START).get(1)
                .recipe().name());
    }

    @Test
    void stillInvalidAfterTheRetryIsAConflictAndNothingIsSaved() {
        for (int attempt = 0; attempt < 2; attempt++) {
            queueSearches();
            llm.queue(plan("Missing dinner.", "Overnight oats", "Lentil soup"));
        }
        AgentResponse response = facade.generateWeeklyPlan(request(1));
        assertEquals(ResponseStatus.CONFLICT, response.status());
        assertTrue(response.conflicts().contains("Day 1 (2026-11-03) dinner: expected 1 meal(s), got 0."));
        assertTrue(facade.getActivePlan(profileId).isEmpty());
        assertTrue(fixture.plans().rows.isEmpty());
    }

    @Test
    void namesOutsideTheCandidatesLeaveTheSlotEmptyAndAreReported() {
        for (int attempt = 0; attempt < 2; attempt++) {
            queueSearches();
            llm.queue(plan("Creative.", "Overnight oats", "Lentil soup", "Grandma's lasagna"));
        }
        AgentResponse response = facade.generateWeeklyPlan(request(1));
        assertEquals(ResponseStatus.CONFLICT, response.status());
        assertTrue(response.conflicts().contains("Day 1 dinner: 'Grandma's lasagna' is not one of the dinner "
                + "candidates, so the slot was left empty."), response.conflicts().toString());
    }

    @Test
    void skippedMealTypesAreSearchedAutomatically() {
        llm.queue(search("dinner"), finish(), plan("Ok.", "Overnight oats", "Lentil soup", "Shrimp tacos"));
        AgentResponse response = facade.generateWeeklyPlan(request(1));
        assertEquals(ResponseStatus.SUCCESS, response.status(), response.conflicts().toString());
        assertTrue(response.agentSteps().stream()
                .anyMatch(s -> s.startsWith("Attempt 1: recipeSearch {\"mealType\": \"breakfast\"} -> ")));
    }

    @Test
    void providerFailureSavesNothing() {
        llm.queue(new LLMException(LLMException.Kind.TIMEOUT, "slow"));
        AgentResponse response = facade.generateWeeklyPlan(request(7));
        assertEquals(ResponseStatus.FAILED, response.status());
        assertTrue(facade.getActivePlan(profileId).isEmpty());
    }

    @Test
    void savedMealsKeepTheirSlotsAndDates() {
        queueSearches();
        llm.queue(plan("Ok.", "Overnight oats", "Lentil soup", "Shrimp tacos"));
        MealPlan plan = facade.generateWeeklyPlan(request(1)).plan();
        assertFalse(plan.meals().isEmpty());
        for (PlannedMeal meal : plan.meals()) {
            assertEquals(START, meal.date());
            assertEquals(1, meal.servings());
        }
    }

    @Test
    void portionsGrowToReachAHighCalorieTarget() {
        facade.updateProfile(facade.getProfile(profileId).dailyCalorieTarget(3000).dailyProteinTargetG(160));
        queueSearches();
        llm.queue(plan("Filling.", "Overnight oats", "Lentil soup", "Shrimp tacos"));

        AgentResponse response = facade.generateWeeklyPlan(request(1));

        assertEquals(ResponseStatus.SUCCESS, response.status(), response.conflicts().toString());
        MealPlan plan = facade.getActivePlan(profileId).orElseThrow();
        assertTrue(plan.meals().stream().allMatch(m -> m.servings() >= 1 && m.servings() <= 2));
        assertTrue(plan.meals().stream().anyMatch(m -> m.servings() == 2),
                "one serving per meal is far below 3000 kcal, so some portions grow");
        double oneServing = plan.meals().stream()
                .mapToDouble(m -> facade.recipeNutrition(m.recipe(), 1).calories()).sum();
        double planned = facade.dailyNutrition(plan).get(START).calories();
        assertTrue(planned > oneServing, planned + " > " + oneServing);
    }

    @Test
    void portionsStayAtOneWithoutTargets() {
        queueSearches();
        llm.queue(plan("Ok.", "Overnight oats", "Lentil soup", "Shrimp tacos"));
        MealPlan plan = facade.generateWeeklyPlan(request(1)).plan();
        assertTrue(plan.meals().stream().allMatch(m -> m.servings() == 1));
    }

    @Test
    void timeLimitedDaysListOnlyTheCandidatesThatFit() {
        facade.updateProfile(facade.getProfile(profileId).maxCookMinutes(Map.of(DayOfWeek.TUESDAY, 25)));
        queueSearches();
        llm.queue(plan("Quick.", "Overnight oats", "Turkey veggie wrap", "Shrimp tacos"));

        AgentResponse response = facade.generateWeeklyPlan(request(1));

        assertEquals(ResponseStatus.SUCCESS, response.status(), response.conflicts().toString());
        String prompt = llm.calls().get(4).prompt();
        String line = prompt.lines().filter(l -> l.startsWith("- day 1 (max 25 min):")).findFirst().orElseThrow();
        assertTrue(line.contains("Turkey veggie wrap"), line);
        assertFalse(line.contains("Lentil soup"), "Lentil soup takes 40 min: " + line);
    }

    @Test
    void pantryIncludeFiltersAreWidenedWithABroadSearch() {
        llm.queue(Map.of("action", "call_tool", "tool", "recipeSearch",
                        "arguments", "{\"mealType\": \"dinner\", \"include\": [\"shrimp\"]}", "reason", "pantry"),
                search("breakfast"), search("lunch"), finish(),
                plan("Ok.", "Overnight oats", "Lentil soup", "Shrimp tacos"));

        AgentResponse response = facade.generateWeeklyPlan(request(1));

        assertEquals(ResponseStatus.SUCCESS, response.status(), response.conflicts().toString());
        assertTrue(response.agentSteps().stream().anyMatch(s -> s.contains("recipeSearch {\"mealType\": \"dinner\"} ->")),
                response.agentSteps().toString());
    }
}
