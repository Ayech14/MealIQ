package io.github.ayech14.mealiq.application;

import io.github.ayech14.mealiq.agent.MealPlanningAgent;
import io.github.ayech14.mealiq.agent.RecipeRecommendation;
import io.github.ayech14.mealiq.agent.ToolCall;
import io.github.ayech14.mealiq.contracts.AgentResponse;
import io.github.ayech14.mealiq.contracts.PlanRequest;
import io.github.ayech14.mealiq.contracts.PlanningContext;
import io.github.ayech14.mealiq.contracts.RecipeQuery;
import io.github.ayech14.mealiq.contracts.ValidationResult;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.ResponseStatus;
import io.github.ayech14.mealiq.domain.UserProfile;
import io.github.ayech14.mealiq.llm.LLMException;
import io.github.ayech14.mealiq.services.ConstraintValidator;
import io.github.ayech14.mealiq.services.NotFoundException;
import io.github.ayech14.mealiq.services.PantryService;
import io.github.ayech14.mealiq.services.ProfileService;
import java.util.ArrayList;
import java.util.List;

/**
 * Coordinates agent use cases: builds the planning context, runs the agent, and checks
 * what it returns with deterministic validators before anything reaches the user (D01, D04).
 *
 * <p>This version covers recipe recommendation (F03); planning and plan changes follow.
 */
public class AgentController {

    private final MealPlanningAgent agent;
    private final ProfileService profileService;
    private final PantryService pantryService;
    private final ConstraintValidator constraintValidator;

    public AgentController(MealPlanningAgent agent, ProfileService profileService, PantryService pantryService,
                           ConstraintValidator constraintValidator) {
        this.agent = agent;
        this.profileService = profileService;
        this.pantryService = pantryService;
        this.constraintValidator = constraintValidator;
    }

    /**
     * Recommends recipes for a request ({@code request.instruction()} holds the user's words).
     * Returned recipes are validated again here, so a tool or agent bug can never
     * surface a recipe that breaks an allergy or diet.
     */
    public AgentResponse recommendRecipe(PlanRequest request, RecipeQuery filters) {
        PlanningContext context;
        try {
            context = buildContext(request);
        } catch (NotFoundException e) {
            return AgentResponse.builder(ResponseStatus.NEEDS_CLARIFICATION)
                    .rationale("Create your profile first (goals, allergies, diet), so recommendations fit you.")
                    .build();
        }
        RecipeRecommendation recommendation;
        try {
            recommendation = agent.recommendRecipes(context, filters);
        } catch (LLMException e) {
            return AgentResponse.builder(ResponseStatus.FAILED).rationale(failureMessage(e)).build();
        }
        List<Recipe> safe = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (Recipe recipe : recommendation.recipes()) {
            ValidationResult check = constraintValidator.validateRestrictions(recipe, context.profile());
            if (check.valid()) {
                safe.add(recipe);
                notes.addAll(check.warnings());
            } else {
                notes.addAll(check.violations());
            }
        }
        List<String> steps = recommendation.toolCalls().stream().map(ToolCall::describe).toList();
        if (safe.isEmpty()) {
            return AgentResponse.builder(ResponseStatus.CONFLICT)
                    .rationale(recommendation.recipes().isEmpty() ? recommendation.rationale()
                            : "None of the recommended recipes fits your restrictions.")
                    .conflicts(notes).agentSteps(steps).build();
        }
        return AgentResponse.builder(ResponseStatus.SUCCESS).recipes(safe)
                .rationale(safe.size() == recommendation.recipes().size() ? recommendation.rationale()
                        : recommendation.rationale() + " (Some recommendations were removed by the restriction check.)")
                .conflicts(notes).agentSteps(steps).build();
    }

    private PlanningContext buildContext(PlanRequest request) {
        UserProfile profile = profileService.getProfile(request.profileId());
        return PlanningContext.of(request, profile, pantryService.getPantry(request.profileId()));
    }

    static String failureMessage(LLMException error) {
        return switch (error.kind()) {
            case NOT_CONFIGURED -> "The AI agent is not set up: add LLM_API_KEY and LLM_MODEL to .env.";
            case AUTHENTICATION -> "The AI provider rejected the API key in .env.";
            case RATE_LIMITED -> "The AI provider's rate limit or quota was reached. Try again later.";
            case TIMEOUT, PROVIDER_UNAVAILABLE -> "The AI provider is unavailable right now. Try again later.";
            case REFUSED -> "The AI declined this request.";
            case BAD_REQUEST, MALFORMED_OUTPUT -> "The AI returned an unusable answer (" + error.getMessage() + ").";
        } + " Nothing was changed.";
    }
}
