package io.github.ayech14.mealiq.application;

import io.github.ayech14.mealiq.agent.MealPlanningAgent;
import io.github.ayech14.mealiq.agent.PlanAttempt;
import io.github.ayech14.mealiq.agent.RecipeRecommendation;
import io.github.ayech14.mealiq.agent.ToolCall;
import io.github.ayech14.mealiq.contracts.AgentResponse;
import io.github.ayech14.mealiq.contracts.PlanRequest;
import io.github.ayech14.mealiq.contracts.PlanningContext;
import io.github.ayech14.mealiq.contracts.RecipeQuery;
import io.github.ayech14.mealiq.contracts.ValidationResult;
import io.github.ayech14.mealiq.domain.MealPlan;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.ResponseStatus;
import io.github.ayech14.mealiq.domain.UserProfile;
import io.github.ayech14.mealiq.llm.LLMException;
import io.github.ayech14.mealiq.services.ConstraintValidator;
import io.github.ayech14.mealiq.services.MealPlanService;
import io.github.ayech14.mealiq.services.NotFoundException;
import io.github.ayech14.mealiq.services.PantryService;
import io.github.ayech14.mealiq.services.ProfileService;
import io.github.ayech14.mealiq.services.ProposalValidator;
import java.util.ArrayList;
import java.util.List;

/**
 * Coordinates agent use cases: builds the planning context, runs the agent, and checks
 * what it returns with deterministic validators before anything reaches the user (D01, D04).
 *
 * <p>This version covers recipe recommendation (F03) and weekly plans (F05); plan changes
 * follow.
 */
public class AgentController {

    /** A rejected proposal is retried once with its violations (SD01). */
    static final int MAX_PLAN_ATTEMPTS = 2;

    private final MealPlanningAgent agent;
    private final ProfileService profileService;
    private final PantryService pantryService;
    private final MealPlanService mealPlanService;
    private final ProposalValidator proposalValidator;
    private final ConstraintValidator constraintValidator;

    public AgentController(MealPlanningAgent agent, ProfileService profileService, PantryService pantryService,
                           MealPlanService mealPlanService, ProposalValidator proposalValidator,
                           ConstraintValidator constraintValidator) {
        this.agent = agent;
        this.profileService = profileService;
        this.pantryService = pantryService;
        this.mealPlanService = mealPlanService;
        this.proposalValidator = proposalValidator;
        this.constraintValidator = constraintValidator;
    }

    /**
     * Generates and saves a plan (F05, SD01). The proposal must pass both validators; if it
     * does not, the agent gets one retry with the violations. A plan that still fails is not
     * saved, and the violations are returned as a CONFLICT.
     */
    public AgentResponse generateWeeklyPlan(PlanRequest request) {
        PlanningContext context;
        try {
            context = buildContext(request);
        } catch (NotFoundException e) {
            return AgentResponse.builder(ResponseStatus.NEEDS_CLARIFICATION)
                    .rationale("Create your profile first (meals per day, goals, allergies), so the plan fits you.")
                    .build();
        }
        List<String> steps = new ArrayList<>();
        PlanAttempt attempt = null;
        ValidationResult result = null;
        for (int number = 1; number <= MAX_PLAN_ATTEMPTS; number++) {
            try {
                attempt = agent.createPlanProposal(context);
            } catch (LLMException e) {
                return AgentResponse.builder(ResponseStatus.FAILED).rationale(failureMessage(e)).agentSteps(steps)
                        .build();
            }
            String prefix = "Attempt " + number + ": ";
            attempt.toolCalls().forEach(call -> steps.add(prefix + call.describe()));
            result = proposalValidator.validate(attempt.proposal(), context)
                    .merge(constraintValidator.validatePlan(attempt.proposal(), context));
            if (result.valid()) {
                break;
            }
            steps.add(prefix + "rejected: " + String.join(" ", result.violations()));
            context = new PlanningContext(request, context.profile(), context.pantry(), context.activePlan(),
                    context.budgetStatus(), context.priorDecisions(), result.violations());
        }
        List<String> notes = new ArrayList<>(result.warnings());
        notes.addAll(attempt.proposal().conflicts());
        if (!result.valid()) {
            List<String> problems = new ArrayList<>(result.violations());
            problems.addAll(notes);
            return AgentResponse.builder(ResponseStatus.CONFLICT)
                    .rationale("No plan was saved: it still broke these constraints after a retry.")
                    .conflicts(problems).agentSteps(steps).build();
        }
        MealPlan plan = mealPlanService.createFromProposal(request, attempt.proposal());
        return AgentResponse.builder(ResponseStatus.SUCCESS).plan(plan).rationale(attempt.proposal().rationale())
                .conflicts(notes).agentSteps(steps).build();
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
        return new PlanningContext(request, profile, pantryService.getPantry(request.profileId()),
                mealPlanService.getActivePlan(request.profileId()).orElse(null), null, List.of(), List.of());
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
