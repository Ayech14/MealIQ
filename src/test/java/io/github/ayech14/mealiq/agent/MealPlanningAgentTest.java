package io.github.ayech14.mealiq.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ayech14.mealiq.application.AgentAssembly;
import io.github.ayech14.mealiq.contracts.PlanRequest;
import io.github.ayech14.mealiq.contracts.PlanningContext;
import io.github.ayech14.mealiq.contracts.RecipeQuery;
import io.github.ayech14.mealiq.domain.MealType;
import io.github.ayech14.mealiq.domain.Pantry;
import io.github.ayech14.mealiq.domain.PlanningMode;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.UserProfile;
import io.github.ayech14.mealiq.llm.LLMException;
import io.github.ayech14.mealiq.llm.ScriptedLLMClient;
import io.github.ayech14.mealiq.planning.DefaultPlanningStrategyFactory;
import io.github.ayech14.mealiq.services.BudgetService;
import io.github.ayech14.mealiq.services.ConstraintValidator;
import io.github.ayech14.mealiq.services.InMemoryRepositories;
import io.github.ayech14.mealiq.services.NutritionService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Drives the agent's tool loop with scripted LLM answers and checks what it does with them. */
class MealPlanningAgentTest {

    static final int MAX_STEPS = 3;

    final ScriptedLLMClient llm = new ScriptedLLMClient();
    final UUID profileId = UUID.randomUUID();
    MealPlanningAgent agent;
    UserProfile profile;

    @BeforeEach
    void setUp() {
        InMemoryRepositories.Catalogue catalogue = new InMemoryRepositories.Catalogue();
        NutritionService nutrition = new NutritionService();
        agent = AgentAssembly.agent(llm, new InMemoryRepositories.Recipes(catalogue), catalogue, nutrition,
                new ConstraintValidator(), new DefaultPlanningStrategyFactory(nutrition,
                        new BudgetService((i, q) -> Optional.empty())), MAX_STEPS);
        profile = new UserProfile(profileId);
    }

    PlanningContext context(String request) {
        return PlanningContext.of(new PlanRequest(profileId, LocalDate.of(2026, 11, 3), 1,
                PlanningMode.TIME_AWARE, request), profile, Pantry.emptyFor(profileId));
    }

    static Map<String, Object> call(String tool, String arguments) {
        return Map.of("action", "call_tool", "tool", tool, "arguments", arguments, "reason", "test");
    }

    static Map<String, Object> finish() {
        Map<String, Object> answer = new java.util.HashMap<>();
        answer.put("action", "finish");
        answer.put("tool", null);
        answer.put("arguments", "{}");
        answer.put("reason", "enough");
        return answer;
    }

    static Map<String, Object> choose(String rationale, String... names) {
        return Map.of("recipes", List.of(names), "rationale", rationale);
    }

    List<String> names(List<Recipe> recipes) {
        return recipes.stream().map(Recipe::name).toList();
    }

    @Test
    void searchesThenChoosesAndExplains() {
        llm.queue(call("recipeSearch", "{\"mealType\": \"dinner\", \"maxMinutes\": 20}"), finish(),
                choose("Both are quick and high in protein.", "Shrimp tacos", "Black bean tacos"));

        RecipeRecommendation result = agent.recommendRecipes(context("quick dinner"), RecipeQuery.any());

        assertEquals(List.of("Shrimp tacos", "Black bean tacos"), names(result.recipes()));
        assertEquals("Both are quick and high in protein.", result.rationale());
        assertEquals(1, result.toolCalls().size());
        assertTrue(result.toolCalls().getFirst().result().success());
        String secondPrompt = llm.calls().get(1).prompt();
        assertTrue(secondPrompt.contains("1. recipeSearch"), secondPrompt);
        assertTrue(secondPrompt.contains("Shrimp tacos (18 min"), "the observation lists real tool results");
        String choicePrompt = llm.calls().get(2).prompt();
        assertTrue(choicePrompt.contains("CANDIDATES, best first by the time aware ranking"), choicePrompt);
        assertEquals(0, llm.remaining());
    }

    @Test
    void firstPromptHasTheRequestProfileAndTools() {
        profile.allergies(Set.of("peanut")).dailyCalorieTarget(1800).mealsPerDay(3);
        llm.queue(finish());
        agent.recommendRecipes(context("something light"), RecipeQuery.any().withMaxMinutes(15));
        String prompt = llm.calls().getFirst().prompt();
        assertTrue(prompt.contains("USER REQUEST: something light"), prompt);
        assertTrue(prompt.contains("REQUIRED FILTERS (applied to every result): at most 15 minutes"), prompt);
        assertTrue(prompt.contains("about 600 kcal per meal"), prompt);
        assertTrue(prompt.contains("allergies: peanut"), prompt);
        assertTrue(prompt.contains("- recipeSearch: ") && prompt.contains("- nutritionAnalysis: ")
                && prompt.contains("- substituteIngredient: "), prompt);
        assertEquals(PromptBuilder.INSTRUCTIONS, llm.calls().getFirst().instructions());
    }

    @Test
    void invalidCallsAreNotRunAndTheErrorIsFedBack() {
        llm.queue(call("webSearch", "{}"),
                call("recipeSearch", "{\"maxMinutes\": -5, \"cuisine\": \"thai\"}"),
                call("recipeSearch", "not json"));

        RecipeRecommendation result = agent.recommendRecipes(context("dinner"), RecipeQuery.any());

        List<ToolCall> calls = result.toolCalls();
        assertEquals(3, calls.size());
        assertTrue(calls.stream().noneMatch(c -> c.result().success()));
        assertTrue(calls.get(0).result().error().startsWith("unknown tool 'webSearch'"));
        assertTrue(calls.get(1).result().error().contains("unknown argument 'cuisine'"));
        assertTrue(calls.get(1).result().error().contains("'maxMinutes' must be a positive whole number"));
        assertTrue(calls.get(2).result().error().startsWith("arguments must be a JSON object"));
        assertTrue(llm.calls().get(1).prompt().contains("ERROR: unknown tool 'webSearch'"),
                "the LLM sees why its call failed");
        assertTrue(result.recipes().isEmpty(), "nothing was found, so nothing is recommended");
        assertEquals(MAX_STEPS, llm.calls().size(), "no recommendation call without candidates");
    }

    @Test
    void theLoopStopsAtMaxToolSteps() {
        for (int i = 0; i < MAX_STEPS; i++) {
            llm.queue(call("recipeSearch", "{\"mealType\": \"snack\"}"));
        }
        llm.queue(choose("Snacks.", "Trail mix"));
        RecipeRecommendation result = agent.recommendRecipes(context("snack"), RecipeQuery.any());
        assertEquals(MAX_STEPS, result.toolCalls().size());
        assertEquals(List.of("Trail mix"), names(result.recipes()));
        assertTrue(llm.calls().get(MAX_STEPS - 1).prompt().contains("(" + (MAX_STEPS - 1) + " of at most " + MAX_STEPS));
    }

    @Test
    void explicitFiltersHoldEvenWhenTheLlmSearchesWider() {
        llm.queue(call("recipeSearch", "{}"), finish(), choose("Long one.", "Turkey chili", "Shrimp tacos"));
        RecipeRecommendation result = agent.recommendRecipes(context("dinner"),
                RecipeQuery.any().withMealType(MealType.DINNER).withMaxMinutes(20));
        assertEquals(List.of("Shrimp tacos"), names(result.recipes()), "Turkey chili takes 45 minutes");
        assertFalse(llm.calls().get(2).prompt().contains("Turkey chili"));
    }

    @Test
    void namesOutsideTheCandidatesFallBackToTheRanking() {
        llm.queue(call("recipeSearch", "{\"mealType\": \"breakfast\"}"), finish(),
                choose("Made up.", "Pancake tower"));
        RecipeRecommendation result = agent.recommendRecipes(context("breakfast"), RecipeQuery.any());
        assertEquals(MealPlanningAgent.MAX_CHOICES, result.recipes().size());
        assertTrue(result.rationale().startsWith("These are the top-ranked recipes"));
        assertEquals("Cottage cheese fruit plate", result.recipes().getFirst().name(), "quickest first (5 min)");
    }

    @Test
    void recipeSearchNeverReturnsWhatTheProfileForbids() {
        profile.allergies(Set.of("peanut"));
        llm.queue(call("recipeSearch", "{\"include\": [\"peanut butter\"]}"), finish());
        RecipeRecommendation result = agent.recommendRecipes(context("peanut butter snack"), RecipeQuery.any());
        String observation = result.toolCalls().getFirst().result().observation();
        assertTrue(observation.startsWith("0 recipe(s) found, 2 left out for allergies or diet."), observation);
        assertTrue(result.recipes().isEmpty());
    }

    @Test
    void earlierDecisionsAreRecalled() {
        llm.queue(call("recipeSearch", "{\"mealType\": \"snack\"}"), finish(), choose("Nutty.", "Trail mix"));
        agent.recommendRecipes(context("a snack for hiking"), RecipeQuery.any());
        llm.queue(finish());
        agent.recommendRecipes(context("another hiking snack"), RecipeQuery.any());
        String prompt = llm.calls().get(3).prompt();
        assertTrue(prompt.contains("EARLIER DECISIONS:\n- Recommended Trail mix for \"a snack for hiking\""), prompt);
    }

    @Test
    void providerFailuresPropagate() {
        llm.queue(new LLMException(LLMException.Kind.TIMEOUT, "slow"));
        assertThrows(LLMException.class, () -> agent.recommendRecipes(context("x"), RecipeQuery.any()));
    }
}
