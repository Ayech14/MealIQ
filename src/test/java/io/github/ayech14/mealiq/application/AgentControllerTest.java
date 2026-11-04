package io.github.ayech14.mealiq.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ayech14.mealiq.agent.AgentTool;
import io.github.ayech14.mealiq.agent.ConversationMemory;
import io.github.ayech14.mealiq.agent.MealPlanningAgent;
import io.github.ayech14.mealiq.agent.PromptBuilder;
import io.github.ayech14.mealiq.agent.ToolManager;
import io.github.ayech14.mealiq.agent.ToolResult;
import io.github.ayech14.mealiq.contracts.AgentResponse;
import io.github.ayech14.mealiq.contracts.PlanRequest;
import io.github.ayech14.mealiq.contracts.PlanningContext;
import io.github.ayech14.mealiq.contracts.RecipeQuery;
import io.github.ayech14.mealiq.domain.MealType;
import io.github.ayech14.mealiq.domain.PlanningMode;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.ResponseStatus;
import io.github.ayech14.mealiq.domain.UserProfile;
import io.github.ayech14.mealiq.llm.LLMException;
import io.github.ayech14.mealiq.llm.ScriptedLLMClient;
import io.github.ayech14.mealiq.planning.DefaultPlanningStrategyFactory;
import io.github.ayech14.mealiq.services.BudgetService;
import io.github.ayech14.mealiq.services.ConstraintValidator;
import io.github.ayech14.mealiq.services.GroceryService;
import io.github.ayech14.mealiq.services.InMemoryRepositories;
import io.github.ayech14.mealiq.services.MealPlanService;
import io.github.ayech14.mealiq.services.NutritionService;
import io.github.ayech14.mealiq.services.PantryService;
import io.github.ayech14.mealiq.services.ProfileService;
import io.github.ayech14.mealiq.services.PortionService;
import io.github.ayech14.mealiq.services.ProposalValidator;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentControllerTest {

    final ScriptedLLMClient llm = new ScriptedLLMClient();
    final InMemoryRepositories.Catalogue catalogue = new InMemoryRepositories.Catalogue();
    final InMemoryRepositories.Recipes recipes = new InMemoryRepositories.Recipes(catalogue);
    final NutritionService nutrition = new NutritionService();
    final ConstraintValidator validator =
            new ConstraintValidator(nutrition, new BudgetService((i, q) -> Optional.empty()));
    final UUID profileId = UUID.randomUUID();
    final InMemoryRepositories.MealPlans plans = new InMemoryRepositories.MealPlans();
    ProfileService profiles;
    PantryService pantry;

    @BeforeEach
    void setUp() {
        profiles = new ProfileService(new InMemoryRepositories.Profiles(), catalogue);
        pantry = new PantryService(new InMemoryRepositories.Pantries(), catalogue);
    }

    PlanRequest request() {
        return new PlanRequest(profileId, LocalDate.of(2026, 11, 3), 1, PlanningMode.NUTRITION_GOAL, "snack");
    }

    /** A broken search tool that ignores the profile, to check the controller's own safety net. */
    AgentController controllerWithUnsafeSearch() {
        ToolManager tools = new ToolManager();
        tools.register(new AgentTool() {
            public String getName() {
                return "recipeSearch";
            }

            public String description() {
                return "returns every snack, unchecked";
            }

            public List<String> checkArguments(Map<String, Object> args) {
                return List.of();
            }

            public ToolResult execute(Map<String, Object> args, PlanningContext context) {
                return ToolResult.ok("recipeSearch", recipes.search(RecipeQuery.any().withMealType(MealType.SNACK)),
                        "all snacks");
            }
        });
        BudgetService budget = new BudgetService((i, q) -> Optional.empty());
        MealPlanningAgent agent = new MealPlanningAgent(llm, tools, new PromptBuilder(nutrition, budget),
                new ConversationMemory(5), new DefaultPlanningStrategyFactory(nutrition, budget), catalogue, 2);
        return new AgentController(agent, profiles, pantry, planService(), new ProposalValidator(recipes), validator,
                groceries(), new PortionService(nutrition, budget));
    }

    AgentController controller() {
        BudgetService budget = new BudgetService((i, q) -> Optional.empty());
        return new AgentController(AgentAssembly.agent(llm, recipes, catalogue, nutrition, budget, groceries(),
                validator, new DefaultPlanningStrategyFactory(nutrition, budget), 3),
                profiles, pantry, planService(), new ProposalValidator(recipes), validator, groceries(),
                new PortionService(nutrition, budget));
    }

    MealPlanService planService() {
        return TestFacades.planService(plans, recipes);
    }

    GroceryService groceries() {
        return new GroceryService(new InMemoryRepositories.GroceryLists(), plans, pantry, (i, q) -> Optional.empty());
    }

    static Map<String, Object> finish() {
        Map<String, Object> answer = new HashMap<>();
        answer.put("action", "finish");
        answer.put("tool", null);
        answer.put("arguments", "{}");
        answer.put("reason", "done");
        return answer;
    }

    @Test
    void withoutAProfileTheUserIsAskedToCreateOne() {
        AgentResponse response = controller().recommendRecipe(request(), RecipeQuery.any());
        assertEquals(ResponseStatus.NEEDS_CLARIFICATION, response.status());
        assertEquals(0, llm.calls().size(), "no AI call without a profile");
    }

    @Test
    void providerFailureIsReportedAndChangesNothing() {
        profiles.updateProfile(new UserProfile(profileId));
        llm.queue(new LLMException(LLMException.Kind.RATE_LIMITED, "429"));
        AgentResponse response = controller().recommendRecipe(request(), RecipeQuery.any());
        assertEquals(ResponseStatus.FAILED, response.status());
        assertEquals("The AI provider's rate limit or quota was reached. Try again later. Nothing was changed.",
                response.rationale());
    }

    @Test
    void nothingFoundIsAConflictWithTheSteps() {
        profiles.updateProfile(new UserProfile(profileId));
        llm.queue(Map.of("action", "call_tool", "tool", "recipeSearch", "arguments", "{\"include\": [\"durian\"]}",
                "reason", "try"), finish());
        AgentResponse response = controller().recommendRecipe(request(), RecipeQuery.any());
        assertEquals(ResponseStatus.CONFLICT, response.status());
        assertEquals(1, response.agentSteps().size());
        assertTrue(response.agentSteps().getFirst().startsWith("recipeSearch {\"include\": [\"durian\"]} -> 0 recipe"));
    }

    @Test
    void forbiddenRecipesAreRemovedEvenIfAToolLetsThemThrough() {
        profiles.updateProfile(new UserProfile(profileId).allergies(Set.of("peanut")));
        llm.queue(Map.of("action", "call_tool", "tool", "recipeSearch", "arguments", "{}", "reason", "all"), finish(),
                Map.of("recipes", List.of("Apple with peanut butter", "Trail mix"), "rationale", "Tasty."));
        AgentResponse response = controllerWithUnsafeSearch().recommendRecipe(request(), RecipeQuery.any());
        assertEquals(ResponseStatus.SUCCESS, response.status());
        assertEquals(List.of("Trail mix"), response.recipes().stream().map(Recipe::name).toList());
        assertTrue(response.rationale().endsWith("(Some recommendations were removed by the restriction check.)"));
        assertTrue(response.conflicts().contains("Apple with peanut butter contains peanut butter (allergy: peanut)."));
    }
}
