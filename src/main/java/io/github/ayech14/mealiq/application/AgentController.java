package io.github.ayech14.mealiq.application;

import io.github.ayech14.mealiq.agent.ChangeAttempt;
import io.github.ayech14.mealiq.agent.MealPlanningAgent;
import io.github.ayech14.mealiq.agent.PlanAttempt;
import io.github.ayech14.mealiq.agent.RecipeDraft;
import io.github.ayech14.mealiq.agent.RecipeRecommendation;
import io.github.ayech14.mealiq.agent.ToolCall;
import io.github.ayech14.mealiq.contracts.AgentResponse;
import io.github.ayech14.mealiq.contracts.PlanChange;
import io.github.ayech14.mealiq.contracts.PlanImpact;
import io.github.ayech14.mealiq.contracts.PlanRequest;
import io.github.ayech14.mealiq.contracts.PlanningContext;
import io.github.ayech14.mealiq.contracts.RecipeQuery;
import io.github.ayech14.mealiq.contracts.ValidationResult;
import io.github.ayech14.mealiq.domain.BudgetStatus;
import io.github.ayech14.mealiq.domain.ChangeEventType;
import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.domain.MealPlan;
import io.github.ayech14.mealiq.domain.PlanningMode;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.ResponseStatus;
import io.github.ayech14.mealiq.domain.UserProfile;
import io.github.ayech14.mealiq.llm.LLMException;
import io.github.ayech14.mealiq.services.ConstraintValidator;
import io.github.ayech14.mealiq.services.GroceryService;
import io.github.ayech14.mealiq.services.MealPlanService;
import io.github.ayech14.mealiq.services.NotFoundException;
import io.github.ayech14.mealiq.services.PantryService;
import io.github.ayech14.mealiq.services.ProfileService;
import io.github.ayech14.mealiq.services.ProposalValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Coordinates agent use cases: builds the planning context, runs the agent, and checks
 * what it returns with deterministic validators before anything reaches the user (D01, D04).
 *
 * <p>Covers recipe recommendation (F03) and generation (F04), weekly plans (F05) and plan changes (F08 budget
 * alternatives, F09 substitution, F10 typed changes, F11 and F13 adaptation). A proposed change is validated and
 * held until the user confirms it; only {@link #confirmChange} applies it (D03).
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
    private final GroceryService groceryService;
    private final Map<UUID, PlanChange> pendingChanges = new ConcurrentHashMap<>();
    private final Map<UUID, Recipe> pendingRecipes = new ConcurrentHashMap<>();

    public AgentController(MealPlanningAgent agent, ProfileService profileService, PantryService pantryService,
                           MealPlanService mealPlanService, ProposalValidator proposalValidator,
                           ConstraintValidator constraintValidator, GroceryService groceryService) {
        this.agent = agent;
        this.profileService = profileService;
        this.pantryService = pantryService;
        this.mealPlanService = mealPlanService;
        this.proposalValidator = proposalValidator;
        this.constraintValidator = constraintValidator;
        this.groceryService = groceryService;
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

    /**
     * A new recipe from the LLM for a request the collection cannot serve (F04, SD05). It is
     * checked against the profile's allergies and diet again here, and a recipe that breaks
     * them is never shown as suitable. A valid recipe is held until the user saves it.
     */
    public AgentResponse generateRecipe(PlanRequest request, RecipeQuery query) {
        PlanningContext context;
        try {
            context = buildContext(request);
        } catch (NotFoundException e) {
            return AgentResponse.builder(ResponseStatus.NEEDS_CLARIFICATION)
                    .rationale("Create your profile first (allergies, diet), so the recipe is safe for you.").build();
        }
        RecipeDraft draft;
        try {
            draft = agent.generateRecipe(context, query);
        } catch (LLMException e) {
            return AgentResponse.builder(ResponseStatus.FAILED).rationale(failureMessage(e)).build();
        }
        if (!draft.usable()) {
            return AgentResponse.builder(ResponseStatus.CONFLICT)
                    .rationale("No usable recipe after " + draft.attempts() + " attempt(s); nothing was saved.")
                    .conflicts(draft.problems()).build();
        }
        ValidationResult check = constraintValidator.validateRestrictions(draft.recipe(), context.profile());
        if (!check.valid()) {
            return AgentResponse.builder(ResponseStatus.CONFLICT)
                    .rationale("The generated recipe was rejected because it breaks your restrictions.")
                    .conflicts(check.violations()).build();
        }
        pendingRecipes.put(draft.recipe().id(), draft.recipe());
        return AgentResponse.builder(ResponseStatus.NEEDS_CONFIRMATION).recipes(List.of(draft.recipe()))
                .rationale("Generated by the AI from catalogue ingredients; check it before saving.")
                .conflicts(check.warnings()).build();
    }

    /** The generated recipe with this ID, if this controller generated and validated it; it can be taken once. */
    public Optional<Recipe> takeGeneratedRecipe(UUID recipeId) {
        return Optional.ofNullable(pendingRecipes.remove(recipeId));
    }

    /** A typed change such as "make Wednesday's dinner vegetarian" (F10, SD03). */
    public AgentResponse interpretModification(MealPlan plan, String instruction, PlanningMode mode) {
        return propose(plan, mode, instruction, context -> agent.proposeChange(context, instruction));
    }

    /** Substitutes for an ingredient in every planned meal that uses it (F09, UC07). */
    public AgentResponse requestSubstitution(MealPlan plan, Ingredient ingredient) {
        return propose(plan, PlanningMode.NUTRITION_GOAL, "replace " + ingredient.name(),
                context -> agent.proposeSubstitution(context, List.of(ingredient)));
    }

    /** Cheaper replacements when the grocery list is over budget (F08, UC07 from UC06). */
    public AgentResponse resolveBudgetConflict(MealPlan plan, BudgetStatus status) {
        String instruction = "Make the plan cheaper: the groceries are over budget by $" + status.shortfall()
                + ". The biggest costs are " + String.join(", ", status.costDrivers().stream()
                        .map(i -> i.ingredient().name()).toList())
                + ". Replace as few meals as needed with cheaper candidates.";
        return propose(plan, PlanningMode.BUDGET, instruction, context -> agent.proposeChange(context, instruction));
    }

    /** The smallest change that resolves a pending impact (F13, SD04); like every change, it waits for confirmation. */
    public AgentResponse adaptPlan(MealPlan plan, PlanImpact impact) {
        PlanningMode mode = impact.causes().stream().anyMatch(c -> c.type() == ChangeEventType.TIME_LIMIT_CHANGED)
                ? PlanningMode.TIME_AWARE
                : impact.causes().stream().anyMatch(c -> c.type() == ChangeEventType.BUDGET_CHANGED)
                        ? PlanningMode.BUDGET : PlanningMode.NUTRITION_GOAL;
        return propose(plan, mode, "adapt the plan", context -> agent.proposeAdaptation(context, impact));
    }

    /**
     * Applies a change this controller proposed and the user confirmed, then records the
     * decision and refreshes the grocery list (SD03). Anything else is refused.
     */
    public AgentResponse confirmChange(UUID planId, PlanChange change) {
        PlanChange pending = pendingChanges.remove(change.id());
        if (pending == null) {
            return AgentResponse.builder(ResponseStatus.FAILED)
                    .rationale("That change was not proposed here or was already handled. Ask for it again.").build();
        }
        MealPlan plan = mealPlanService.executeChange(planId, pending);
        String applied = mealPlanService.changeHistory(planId).getLast().description();
        agent.recordDecision(plan.profileId(), "Applied: " + applied);
        return AgentResponse.builder(ResponseStatus.SUCCESS).plan(plan).change(pending)
                .groceryList(groceryService.refreshForPlan(plan)).rationale(applied).build();
    }

    /** Validates what the agent proposes and holds a valid change for confirmation. */
    private AgentResponse propose(MealPlan plan, PlanningMode mode, String instruction,
                                  Function<PlanningContext, ChangeAttempt> proposer) {
        PlanningContext context;
        try {
            context = new PlanningContext(new PlanRequest(plan.profileId(), plan.startDate(), plan.days(), mode,
                    instruction), profileService.getProfile(plan.profileId()),
                    pantryService.getPantry(plan.profileId()), plan, null, List.of(), List.of());
        } catch (NotFoundException e) {
            return AgentResponse.builder(ResponseStatus.NEEDS_CLARIFICATION)
                    .rationale("Create your profile first, so changes fit you.").build();
        }
        ChangeAttempt attempt;
        try {
            attempt = proposer.apply(context);
        } catch (LLMException e) {
            return AgentResponse.builder(ResponseStatus.FAILED).rationale(failureMessage(e)).build();
        }
        List<String> steps = attempt.toolCalls().stream().map(ToolCall::describe).toList();
        PlanChange change = attempt.change();
        if (change.needsClarification()) {
            return AgentResponse.builder(ResponseStatus.NEEDS_CLARIFICATION).change(change)
                    .rationale(change.clarificationQuestion()).agentSteps(steps).build();
        }
        if (change.proposal().meals().isEmpty() && change.proposal().conflicts().isEmpty()) {
            return AgentResponse.builder(ResponseStatus.SUCCESS).plan(plan)
                    .rationale("Nothing to change: " + change.rationale()).agentSteps(steps).build();
        }
        ValidationResult structure = proposalValidator.validate(change, context);
        ValidationResult result = structure.valid()
                ? structure.merge(constraintValidator.validateChange(change, context)) : structure;
        List<String> notes = new ArrayList<>(result.valid() ? result.warnings() : result.violations());
        notes.addAll(change.proposal().conflicts());
        if (!result.valid()) {
            return AgentResponse.builder(ResponseStatus.CONFLICT).change(change)
                    .rationale("The change was not applied: it breaks these rules. Nothing was changed.")
                    .conflicts(notes).agentSteps(steps).build();
        }
        pendingChanges.put(change.id(), change);
        return AgentResponse.builder(ResponseStatus.NEEDS_CONFIRMATION).plan(plan).change(change)
                .rationale(change.rationale()).conflicts(notes).agentSteps(steps).build();
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
