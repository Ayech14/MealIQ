package io.github.ayech14.mealiq.application;

import io.github.ayech14.mealiq.agent.MealPlanningAgent;
import io.github.ayech14.mealiq.config.AppConfig;
import io.github.ayech14.mealiq.contracts.AgentResponse;
import io.github.ayech14.mealiq.contracts.AssistantRequest;
import io.github.ayech14.mealiq.contracts.PlanChange;
import io.github.ayech14.mealiq.contracts.PlanImpact;
import io.github.ayech14.mealiq.contracts.PlanRequest;
import io.github.ayech14.mealiq.contracts.PlanningContext;
import io.github.ayech14.mealiq.contracts.RecipeQuery;
import io.github.ayech14.mealiq.contracts.ValidationResult;
import io.github.ayech14.mealiq.domain.AppliedChange;
import io.github.ayech14.mealiq.domain.BudgetStatus;
import io.github.ayech14.mealiq.domain.CostEstimate;
import io.github.ayech14.mealiq.domain.GroceryList;
import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.domain.MealPlan;
import io.github.ayech14.mealiq.domain.NutritionSummary;
import io.github.ayech14.mealiq.domain.Pantry;
import io.github.ayech14.mealiq.domain.PantryItem;
import io.github.ayech14.mealiq.domain.PlanningMode;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.ResponseStatus;
import io.github.ayech14.mealiq.domain.UserProfile;
import io.github.ayech14.mealiq.events.GroceryListImpactObserver;
import io.github.ayech14.mealiq.events.MealPlanImpactObserver;
import io.github.ayech14.mealiq.integration.PriceDataSource;
import io.github.ayech14.mealiq.integration.StatCanPriceDataSource;
import io.github.ayech14.mealiq.llm.LLMClient;
import io.github.ayech14.mealiq.llm.LLMException;
import io.github.ayech14.mealiq.llm.ProviderLLMAdapter;
import io.github.ayech14.mealiq.persistence.IngredientRepository;
import io.github.ayech14.mealiq.persistence.RecipeRepository;
import io.github.ayech14.mealiq.persistence.jdbc.Database;
import io.github.ayech14.mealiq.persistence.jdbc.JdbcGroceryListRepository;
import io.github.ayech14.mealiq.persistence.jdbc.JdbcIngredientRepository;
import io.github.ayech14.mealiq.persistence.jdbc.JdbcMealPlanRepository;
import io.github.ayech14.mealiq.persistence.jdbc.JdbcPantryRepository;
import io.github.ayech14.mealiq.persistence.jdbc.JdbcPlanChangeRepository;
import io.github.ayech14.mealiq.persistence.jdbc.JdbcPlanImpactRepository;
import io.github.ayech14.mealiq.persistence.jdbc.JdbcProfileRepository;
import io.github.ayech14.mealiq.persistence.jdbc.JdbcRecipeRepository;
import io.github.ayech14.mealiq.planning.DefaultPlanningStrategyFactory;
import io.github.ayech14.mealiq.planning.PlanningStrategyFactory;
import io.github.ayech14.mealiq.services.BudgetService;
import io.github.ayech14.mealiq.services.ConstraintValidator;
import io.github.ayech14.mealiq.services.GroceryService;
import io.github.ayech14.mealiq.services.InvalidInputException;
import io.github.ayech14.mealiq.services.MealPlanService;
import io.github.ayech14.mealiq.services.NotFoundException;
import io.github.ayech14.mealiq.services.NutritionService;
import io.github.ayech14.mealiq.services.PantryService;
import io.github.ayech14.mealiq.services.PlanImpactAssessor;
import io.github.ayech14.mealiq.services.ProfileService;
import io.github.ayech14.mealiq.services.PortionService;
import io.github.ayech14.mealiq.services.ProposalValidator;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single application boundary shared by the GUI and the CLI (Facade, D02). Each method
 * delegates to the services; interfaces only collect input and show results, so both
 * reach the same rules and give the same outcome.
 *
 * <p>Operations are added as features are implemented; this version covers F01 (profile),
 * F02 (pantry), recipe browsing, recommendations and generation (F03, F04), weekly plans (F05),
 * nutrition analysis (F06), grocery lists with prices and budget checks (F07, F08, F12), and
 * confirmed plan changes with undo (F09, F10), and adaptation after pantry or profile
 * changes (F11, F13).
 */
public class MealPlanningFacade {

    /** The repositories and services behind the facade, grouped so wiring stays readable. */
    public record Services(ProfileService profiles, PantryService pantry, MealPlanService mealPlans,
                           GroceryService groceries, IngredientRepository ingredients, RecipeRepository recipes,
                           NutritionService nutrition, BudgetService budget, ConstraintValidator validator,
                           PlanningStrategyFactory strategies, AgentController agentController, Clock clock) {
    }

    private final ProfileService profileService;
    private final PantryService pantryService;
    private final MealPlanService mealPlanService;
    private final GroceryService groceryService;
    private final IngredientRepository ingredientRepository;
    private final RecipeRepository recipeRepository;
    private final NutritionService nutritionService;
    private final BudgetService budgetService;
    private final ConstraintValidator constraintValidator;
    private final PlanningStrategyFactory strategyFactory;
    private final AgentController agentController;
    private final Clock clock;
    /** Assistant updates waiting for confirmation, and the profile each belongs to. */
    private final Map<UUID, Runnable> pendingUpdates = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> pendingOwners = new ConcurrentHashMap<>();

    public MealPlanningFacade(Services services) {
        this.profileService = services.profiles();
        this.pantryService = services.pantry();
        this.mealPlanService = services.mealPlans();
        this.groceryService = services.groceries();
        this.ingredientRepository = services.ingredients();
        this.recipeRepository = services.recipes();
        this.nutritionService = services.nutrition();
        this.budgetService = services.budget();
        this.constraintValidator = services.validator();
        this.strategyFactory = services.strategies();
        this.agentController = services.agentController();
        this.clock = services.clock();
    }

    /**
     * Wires the services to the PostgreSQL repositories and the LLM configured in {@code .env}.
     * Opens no connection yet; without an API key, only the agent features report that they
     * are not set up.
     */
    public static MealPlanningFacade fromConfig(AppConfig config) {
        Database database = Database.from(config);
        JdbcIngredientRepository ingredients = new JdbcIngredientRepository(database);
        JdbcRecipeRepository recipes = new JdbcRecipeRepository(database);
        NutritionService nutrition = new NutritionService();
        PriceDataSource prices = StatCanPriceDataSource.packaged();
        BudgetService budget = new BudgetService(prices);
        ConstraintValidator validator = new ConstraintValidator(nutrition, budget);
        PlanningStrategyFactory strategies = new DefaultPlanningStrategyFactory(nutrition, budget);
        Clock clock = Clock.systemDefaultZone();
        JdbcProfileRepository profileRows = new JdbcProfileRepository(database);
        JdbcPantryRepository pantryRows = new JdbcPantryRepository(database);
        JdbcMealPlanRepository planRows = new JdbcMealPlanRepository(database);
        ProfileService profiles = new ProfileService(profileRows, ingredients);
        PantryService pantry = new PantryService(pantryRows, ingredients, clock);
        LLMClient llm = config.llmConfigured() ? ProviderLLMAdapter.fromConfig(config)
                : (prompt, schema, instructions) -> {
                    throw new LLMException(LLMException.Kind.NOT_CONFIGURED, "set LLM_API_KEY and LLM_MODEL in .env");
                };
        MealPlanService mealPlans = new MealPlanService(planRows, recipes, new JdbcPlanChangeRepository(database),
                new JdbcPlanImpactRepository(database),
                new PlanImpactAssessor(profileRows, pantryRows, ingredients, validator, budget, clock));
        GroceryService groceries = new GroceryService(new JdbcGroceryListRepository(database), planRows, pantry,
                prices);
        subscribeObservers(profiles, pantry, mealPlans, groceries);
        MealPlanningAgent agent = AgentAssembly.agent(llm, recipes, ingredients, nutrition, budget, groceries,
                validator, strategies, config.maxToolSteps());
        AgentController controller = new AgentController(agent, profiles, pantry, mealPlans,
                new ProposalValidator(recipes), validator, groceries,
                new PortionService(nutrition, budget));
        return new MealPlanningFacade(new Services(profiles, pantry, mealPlans, groceries, ingredients, recipes,
                nutrition, budget, validator, strategies, controller, clock));
    }

    /**
     * Connects the impact observers (Observer, F13): pantry changes reach both, profile
     * changes only the plan observer, since they do not change what must be bought.
     */
    public static void subscribeObservers(ProfileService profiles, PantryService pantry, MealPlanService mealPlans,
                                          GroceryService groceries) {
        MealPlanImpactObserver planObserver = new MealPlanImpactObserver(mealPlans);
        pantry.subscribe(new GroceryListImpactObserver(groceries));
        pantry.subscribe(planObserver);
        profiles.subscribe(planObserver);
    }

    /**
     * The ID of a local profile, derived from its name, so a person can pick a profile by
     * name ("default", "alex") without storing or typing UUIDs.
     */
    public static UUID localProfileId(String name) {
        String key = "mealiq:profile:" + name.strip().toLowerCase(Locale.ROOT);
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    // ---- F01 user goals and preferences ----

    /** @throws NotFoundException if the profile was never saved */
    public UserProfile getProfile(UUID profileId) {
        return profileService.getProfile(profileId);
    }

    /** Validates and saves the profile; invalid fields throw InvalidInputException and nothing is saved. */
    public UserProfile updateProfile(UserProfile profile) {
        return profileService.updateProfile(profile);
    }

    /** Preferred foods that the profile's own allergies or diet rule out (UC01 3b). */
    public List<String> profileWarnings(UserProfile profile) {
        return profileService.preferenceWarnings(profile);
    }

    // ---- F02 pantry inventory ----

    public Pantry getPantry(UUID profileId) {
        return pantryService.getPantry(profileId);
    }

    /** Builds a pantry item from interface input; every bad field is reported together. */
    public PantryItem pantryItem(String ingredientName, double amount, String unit, LocalDate expiryDate) {
        return pantryService.makeItem(ingredientName, amount, unit, expiryDate);
    }

    /**
     * Adds the item or replaces its quantity (UC02). When this affects the active plan, the
     * affected meals are listed in {@code conflicts} as an impact notice (SD04).
     */
    public AgentResponse updatePantry(UUID profileId, PantryItem item) {
        ensureProfile(profileId);
        pantryService.updateItem(profileId, item);
        return AgentResponse.builder(ResponseStatus.SUCCESS)
                .rationale("Pantry updated: " + item.ingredient().name() + " " + item.quantity() + ".")
                .conflicts(pendingImpact(profileId).map(PlanImpact::conflicts).orElse(List.of()))
                .build();
    }

    /** @throws NotFoundException if no pantry item has that ingredient name */
    public Pantry removePantryItem(UUID profileId, String ingredientName) {
        String name = ingredientName.strip().toLowerCase(Locale.ROOT);
        PantryItem item = getPantry(profileId).items().values().stream()
                .filter(i -> i.ingredient().name().equals(name))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("'" + ingredientName + "' is not in the pantry"));
        return pantryService.removeItem(profileId, item.ingredient().id());
    }

    /** Items kept in the pantry but not counted as available stock. */
    public List<PantryItem> expiredPantryItems(UUID profileId) {
        return pantryService.expiredItems(profileId);
    }

    /**
     * Catalogue ingredients for a name as people say it: one for an exact or plural name
     * ("eggs"), several for a general one ("beef": ground beef, beef sirloin), none if unknown.
     */
    public List<Ingredient> matchIngredients(String text) {
        return pantryService.matchIngredients(text);
    }

    /** Catalogue ingredients whose name contains {@code text} (all of them when blank), by name. */
    public List<Ingredient> searchIngredients(String text) {
        String needle = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        return ingredientRepository.findAll().stream().filter(i -> i.name().contains(needle)).toList();
    }

    // ---- F05 weekly plans and F06 nutrition analysis ----

    /** Asks the agent for a plan; it is saved only if it passes validation (SD01). */
    public AgentResponse generateWeeklyPlan(PlanRequest request) {
        return agentController.generateWeeklyPlan(request);
    }

    public Optional<MealPlan> getActivePlan(UUID profileId) {
        return mealPlanService.getActivePlan(profileId);
    }

    /** Total nutrition of a whole plan, from catalogue data only (D09). */
    public NutritionSummary analyzeNutrition(UUID planId) {
        return nutritionService.calculateNutrition(mealPlanService.getPlan(planId).meals());
    }

    /** Nutrition per day of a plan, in date order, including days without meals. */
    public Map<LocalDate, NutritionSummary> dailyNutrition(MealPlan plan) {
        return nutritionService.dailyTotals(plan);
    }

    // ---- F09 substitution, F10 typed changes, F08 budget alternatives, undo ----

    /** Asks the agent how to apply a typed change to the active plan; a valid change waits for confirmation. */
    public AgentResponse modifyPlan(UUID profileId, String instruction, PlanningMode mode) {
        return agentController.interpretModification(activePlanOrThrow(profileId), instruction, mode);
    }

    /** Proposes a substitute for an ingredient in every meal of the active plan that uses it. */
    public AgentResponse requestSubstitution(UUID profileId, String ingredientName) {
        Ingredient ingredient = ingredientRepository.findByName(ingredientName).orElseThrow(() ->
                new NotFoundException("'" + ingredientName.strip() + "' is not in the ingredient catalogue"));
        return agentController.requestSubstitution(activePlanOrThrow(profileId), ingredient);
    }

    /**
     * Proposes cheaper meals when the active plan's groceries are over budget (UC07 from UC06).
     * A plan within budget, or without one, gets a SUCCESS answer and no change.
     */
    public AgentResponse suggestCheaperPlan(UUID profileId) {
        AgentResponse groceries = generateOptimizedGroceryList(profileId);
        if (groceries.budgetStatus() == null || groceries.budgetStatus().withinBudget()) {
            return AgentResponse.builder(ResponseStatus.SUCCESS).groceryList(groceries.groceryList())
                    .budgetStatus(groceries.budgetStatus())
                    .rationale(groceries.budgetStatus() == null ? "No weekly budget is set."
                            : "The plan is already within budget.").build();
        }
        return agentController.resolveBudgetConflict(activePlanOrThrow(profileId), groceries.budgetStatus());
    }

    /** Applies a change the user confirmed (only one this application proposed). */
    public AgentResponse executeChange(UUID planId, PlanChange change) {
        return agentController.confirmChange(planId, change);
    }

    /** Reverts the newest confirmed change of the active plan and refreshes its grocery list. */
    public AgentResponse undoLastChange(UUID profileId) {
        MealPlan plan = activePlanOrThrow(profileId);
        List<AppliedChange> history = mealPlanService.changeHistory(plan.id());
        if (history.isEmpty()) {
            return AgentResponse.builder(ResponseStatus.FAILED).rationale("There is no change to undo.").build();
        }
        MealPlan restored = mealPlanService.undoLastChange(plan.id());
        return AgentResponse.builder(ResponseStatus.SUCCESS).plan(restored)
                .groceryList(groceryService.refreshForPlan(restored))
                .rationale("Undone: " + history.getLast().description()).build();
    }

    // ---- the assistant: one typed message, any action ----

    /**
     * Answers a typed message: the agent works out what it asks for, and the matching use case
     * runs with all its usual checks. Plan changes, generated recipes, and pantry or profile
     * updates come back unapplied, for the user to confirm.
     */
    public AssistantReply ask(UUID profileId, String message) {
        AssistantRequest request;
        try {
            request = agentController.interpretRequest(profileId, message, LocalDate.now(clock));
        } catch (LLMException e) {
            return new AssistantReply(AssistantRequest.Action.ANSWER, AgentController.failureMessage(e), null, null);
        }
        String reply = request.reply();
        PlanningMode mode = request.mode() == null ? PlanningMode.NUTRITION_GOAL : request.mode();
        return switch (request.action()) {
            case ANSWER -> new AssistantReply(request.action(), reply, null, null);
            case RECIPES -> {
                AgentResponse found = recommendRecipe(profileId, message, request.filters(), mode);
                // UC03 5a: when the collection has nothing, offer to generate a recipe instead.
                yield found.status() == ResponseStatus.CONFLICT && found.recipes().isEmpty()
                        ? new AssistantReply(request.action(), reply + "\nNothing in the recipe collection fits. "
                                + "I can write a new recipe for this that you review before saving.", found, null)
                        : withResult(request, found);
            }
            case NEW_RECIPE -> withResult(request, generateRecipe(profileId, message,
                    request.filters().withGenerated(true)));
            case PLAN -> withResult(request, generateWeeklyPlan(new PlanRequest(profileId, LocalDate.now(clock),
                    request.days() == null ? 7 : request.days(), mode, message)));
            case SHOW_PLAN, CHANGE_PLAN, SUBSTITUTE, ADAPT, CHEAPER, GROCERIES -> planAction(profileId, message,
                    request, mode);
            case PANTRY -> pantryUpdate(profileId, request);
            case PROFILE -> profileUpdate(profileId, request);
        };
    }

    /**
     * Applies a pantry or profile update the assistant proposed, after the user confirmed it.
     * Only updates proposed for this profile are accepted, each once.
     */
    public AgentResponse confirmUpdate(UUID profileId, UUID updateId) {
        Runnable update = profileId.equals(pendingOwners.get(updateId)) ? pendingUpdates.remove(updateId) : null;
        if (update == null) {
            return AgentResponse.builder(ResponseStatus.FAILED)
                    .rationale("That update was not proposed here or was already applied.").build();
        }
        pendingOwners.remove(updateId);
        update.run();
        return AgentResponse.builder(ResponseStatus.SUCCESS).rationale("Done.")
                .conflicts(pendingImpact(profileId).map(PlanImpact::conflicts).orElse(List.of())).build();
    }

    private AssistantReply withResult(AssistantRequest request, AgentResponse result) {
        return new AssistantReply(request.action(), request.reply(), result, null);
    }

    private AssistantReply planAction(UUID profileId, String message, AssistantRequest request, PlanningMode mode) {
        if (mealPlanService.getActivePlan(profileId).isEmpty()) {
            return new AssistantReply(request.action(), "You don't have a meal plan yet. Ask me to make one, "
                    + "for example \"plan 3 days of quick meals\".", null, null);
        }
        AgentResponse result = switch (request.action()) {
            case CHANGE_PLAN -> modifyPlan(profileId, message, mode);
            case SUBSTITUTE -> request.ingredient() == null
                    ? AgentResponse.builder(ResponseStatus.NEEDS_CLARIFICATION)
                            .rationale("Which ingredient should I replace?").build()
                    : requestSubstitution(profileId, request.ingredient());
            case ADAPT -> adaptPlan(profileId);
            case CHEAPER -> suggestCheaperPlan(profileId);
            case GROCERIES -> generateOptimizedGroceryList(profileId);
            default -> AgentResponse.builder(ResponseStatus.SUCCESS).plan(activePlanOrThrow(profileId)).build();
        };
        return withResult(request, result);
    }

    private AssistantReply pantryUpdate(UUID profileId, AssistantRequest request) {
        if (request.pantry().isEmpty()) {
            return new AssistantReply(request.action(), "Which ingredient, and how much do you have?", null, null);
        }
        List<PantryItem> items = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (AssistantRequest.PantryLine line : request.pantry()) {
            try {
                PantryItem item = pantryItem(line.ingredient(), line.amount(), line.unit(), null);
                items.add(item);
                lines.add(item.ingredient().name() + ": " + (item.quantity().isZero() ? "none left"
                        : item.quantity().toString()));
            } catch (InvalidInputException e) {
                problems.add(line.ingredient() + ": " + String.join("; ", e.errors().values()));
            }
        }
        if (items.isEmpty()) {
            return new AssistantReply(request.action(), "I couldn't match that to the ingredient catalogue:\n"
                    + String.join("\n", problems), null, null);
        }
        String text = request.reply() + (problems.isEmpty() ? "" : "\nSkipped: " + String.join("; ", problems));
        return pending(profileId, request, text, "Update your pantry?", lines,
                () -> items.forEach(item -> updatePantry(profileId, item)));
    }

    private AssistantReply profileUpdate(UUID profileId, AssistantRequest request) {
        AssistantRequest.ProfileUpdate change = request.profile();
        Optional<UserProfile> saved = findProfile(profileId);
        if (change.isEmpty() && saved.isEmpty()) {
            // "No restrictions" from a new user: save the default profile so planning can start.
            UserProfile created = new UserProfile(profileId);
            return pending(profileId, request, request.reply(), "Create your profile?",
                    List.of("No diet rules or allergies", "3 meals a day, no budget or time limits"),
                    () -> updateProfile(created));
        }
        if (change.isEmpty()) {
            return new AssistantReply(request.action(), request.reply().isEmpty()
                    ? "What should I change in your profile?" : request.reply(), null, null);
        }
        UserProfile current = saved.orElseGet(() -> new UserProfile(profileId));
        UserProfile updated = current.copy()
                .dietaryRestrictions(changed(current.dietaryRestrictions(), change.addDiet(), change.removeDiet()))
                .allergies(changed(current.allergies(), change.addAllergies(), change.removeAllergies()))
                .preferredFoods(changed(current.preferredFoods(), change.addLikes(), Set.of()))
                .dislikedFoods(changed(current.dislikedFoods(), change.addDislikes(), Set.of()));
        if (change.calories() != null) {
            updated.dailyCalorieTarget(change.calories());
        }
        if (change.protein() != null) {
            updated.dailyProteinTargetG(change.protein());
        }
        if (change.mealsPerDay() != null) {
            updated.mealsPerDay(change.mealsPerDay());
        }
        if (change.weeklyBudget() != null) {
            updated.weeklyBudget(change.weeklyBudget());
        }
        Map<String, String> errors = profileService.validate(updated);
        if (!errors.isEmpty()) {
            return new AssistantReply(request.action(), "I can't save that: " + String.join("; ", errors.values()),
                    null, null);
        }
        List<String> lines = new ArrayList<>();
        describeChange(lines, "Diet", current.dietaryRestrictions(), updated.dietaryRestrictions());
        describeChange(lines, "Allergies", current.allergies(), updated.allergies());
        describeChange(lines, "Likes", current.preferredFoods(), updated.preferredFoods());
        describeChange(lines, "Dislikes", current.dislikedFoods(), updated.dislikedFoods());
        if (change.calories() != null) {
            lines.add("Calories per day: " + change.calories());
        }
        if (change.protein() != null) {
            lines.add("Protein per day: " + change.protein() + " g");
        }
        if (change.mealsPerDay() != null) {
            lines.add("Meals per day: " + change.mealsPerDay());
        }
        if (change.weeklyBudget() != null) {
            lines.add("Weekly budget: $" + change.weeklyBudget().setScale(2, RoundingMode.HALF_UP));
        }
        if (lines.isEmpty() && saved.isPresent()) {
            return new AssistantReply(request.action(), "Your profile already says that.", null, null);
        }
        if (lines.isEmpty()) {
            lines.add("No diet rules or allergies");
        }
        return pending(profileId, request, request.reply(), "Update your profile?", lines,
                () -> updateProfile(updated));
    }

    private AssistantReply pending(UUID profileId, AssistantRequest request, String text, String title,
                                   List<String> lines, Runnable apply) {
        UUID id = UUID.randomUUID();
        pendingUpdates.put(id, apply);
        pendingOwners.put(id, profileId);
        return new AssistantReply(request.action(), text, null, new AssistantReply.PendingUpdate(id, title, lines));
    }

    private static Set<String> changed(Set<String> current, Set<String> add, Set<String> remove) {
        Set<String> result = new TreeSet<>(current);
        result.addAll(add);
        result.removeAll(remove);
        return result;
    }

    private static void describeChange(List<String> lines, String label, Set<String> before, Set<String> after) {
        if (!before.equals(after)) {
            lines.add(label + ": " + (after.isEmpty() ? "none" : String.join(", ", new TreeSet<>(after))));
        }
    }

    // ---- F11 and F13 adaptation ----

    /** What recent pantry or profile changes mean for the active plan; empty when nothing needs attention. */
    public Optional<PlanImpact> pendingImpact(UUID profileId) {
        return mealPlanService.getActivePlan(profileId).flatMap(plan -> mealPlanService.getPendingImpact(plan.id()));
    }

    /** Asks the agent for the smallest change that resolves the pending impact; it waits for confirmation. */
    public AgentResponse adaptPlan(UUID profileId) {
        MealPlan plan = activePlanOrThrow(profileId);
        Optional<PlanImpact> impact = mealPlanService.getPendingImpact(plan.id());
        if (impact.isEmpty()) {
            return AgentResponse.builder(ResponseStatus.SUCCESS).plan(plan)
                    .rationale("Nothing to adapt: the plan still fits your pantry and profile.").build();
        }
        return agentController.adaptPlan(plan, impact.get());
    }

    /** Confirmed changes of the active plan that can still be undone, oldest first. */
    public List<AppliedChange> changeHistory(UUID profileId) {
        return mealPlanService.changeHistory(activePlanOrThrow(profileId).id());
    }

    // ---- F07 grocery list, F08 optimization and F12 budget ----

    /**
     * Builds, prices and saves the shopping list for the active plan, and compares it with the
     * profile's weekly budget scaled to the plan's length (UC06). Over budget is a CONFLICT
     * with the exact shortfall and the items that cost most.
     *
     * @throws NotFoundException if there is no active plan
     */
    public AgentResponse generateOptimizedGroceryList(UUID profileId) {
        MealPlan plan = activePlanOrThrow(profileId);
        GroceryList list = groceryService.refreshForPlan(plan);
        Optional<UserProfile> profile = findProfile(profileId);
        if (profile.isEmpty() || profile.get().weeklyBudget().signum() == 0) {
            return AgentResponse.builder(ResponseStatus.SUCCESS).groceryList(list)
                    .rationale("No weekly budget is set, so the cost was not compared with one.").build();
        }
        BudgetStatus status = budgetService.evaluate(list,
                BudgetService.budgetFor(profile.get().weeklyBudget(), plan.days()));
        List<String> notes = new ArrayList<>();
        if (status.partial()) {
            notes.add("Some items have no price, so the estimate is partial and the real cost is higher.");
        }
        if (status.withinBudget()) {
            return AgentResponse.builder(ResponseStatus.SUCCESS).groceryList(list).budgetStatus(status)
                    .conflicts(notes).build();
        }
        notes.add(0, "Over budget by $" + status.shortfall() + ". Biggest costs: " + String.join(", ",
                status.costDrivers().stream().map(i -> i.ingredient().name() + " $" + i.estimatedPrice()).toList())
                + ".");
        return AgentResponse.builder(ResponseStatus.CONFLICT).groceryList(list).budgetStatus(status)
                .conflicts(notes).build();
    }

    /** Where grocery prices come from, e.g. "Statistics Canada average retail prices, 2026-08". */
    public String priceSource() {
        return groceryService.priceSource();
    }

    /** The saved list of the active plan, if one was generated. */
    public Optional<GroceryList> getGroceryList(UUID profileId) {
        return mealPlanService.getActivePlan(profileId).flatMap(plan -> groceryService.getList(plan.id()));
    }

    private MealPlan activePlanOrThrow(UUID profileId) {
        return mealPlanService.getActivePlan(profileId)
                .orElseThrow(() -> new NotFoundException("No meal plan yet. Create one with: mealiq plan generate"));
    }

    /** The day a plan starting today would begin on; interfaces use it as the default start. */
    public LocalDate today() {
        return LocalDate.now(clock);
    }

    // ---- recipes (deterministic parts of F03) and nutrition (F06) ----

    /**
     * Recipes matching the query, without any that break the profile's allergies or diet,
     * ranked by the strategy for {@code mode}. Each excluded recipe is listed in
     * {@code conflicts} with the rule it breaks. Without a saved profile, nothing is excluded.
     */
    public AgentResponse browseRecipes(UUID profileId, RecipeQuery query, PlanningMode mode) {
        Optional<UserProfile> saved = findProfile(profileId);
        UserProfile profile = saved.orElseGet(() -> new UserProfile(profileId));
        PlanningContext context = PlanningContext.of(new PlanRequest(profileId, LocalDate.now(clock), 1, mode, null),
                profile, getPantry(profileId));
        List<Recipe> allowed = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        for (Recipe recipe : recipeRepository.search(query)) {
            ValidationResult check = constraintValidator.validateRestrictions(recipe, profile);
            if (check.valid()) {
                allowed.add(recipe);
            } else {
                excluded.addAll(check.violations());
            }
        }
        List<Recipe> ranked = strategyFactory.strategyFor(context).rank(allowed, context);
        return AgentResponse.builder(ranked.isEmpty() ? ResponseStatus.CONFLICT : ResponseStatus.SUCCESS)
                .recipes(ranked)
                .conflicts(excluded)
                .rationale(saved.isPresent() ? "" : "No saved profile, so no allergies or diet rules were applied.")
                .build();
    }

    /**
     * Asks the agent for recipes matching a free-text request (F03). {@code filters} are hard
     * limits the user set explicitly; the agent may narrow them but never widen them.
     */
    public AgentResponse recommendRecipe(UUID profileId, String request, RecipeQuery filters, PlanningMode mode) {
        return agentController.recommendRecipe(
                new PlanRequest(profileId, LocalDate.now(clock), 1, mode, request), filters);
    }

    /**
     * Asks the AI for a new recipe matching the request and filters (F04); a valid one waits
     * for {@link #saveGeneratedRecipe}.
     */
    public AgentResponse generateRecipe(UUID profileId, String request, RecipeQuery filters) {
        return agentController.generateRecipe(
                new PlanRequest(profileId, LocalDate.now(clock), 1, PlanningMode.NUTRITION_GOAL, request), filters);
    }

    /**
     * Saves a recipe generated in this session, so it can be browsed and planned. Only recipes
     * that were generated and validated here are accepted, each once.
     */
    public AgentResponse saveGeneratedRecipe(UUID recipeId) {
        Optional<Recipe> recipe = agentController.takeGeneratedRecipe(recipeId);
        if (recipe.isEmpty()) {
            return AgentResponse.builder(ResponseStatus.FAILED)
                    .rationale("That recipe was not generated here or was already saved.").build();
        }
        recipeRepository.save(recipe.get());
        return AgentResponse.builder(ResponseStatus.SUCCESS).recipes(List.of(recipe.get()))
                .rationale("Saved " + recipe.get().name() + ".").build();
    }

    /** Today's cooking-time limit from the saved profile; empty when none is set or no profile is saved. */
    public OptionalInt cookingLimitToday(UUID profileId) {
        return findProfile(profileId).map(p -> p.maxMinutesFor(LocalDate.now(clock).getDayOfWeek()))
                .orElse(OptionalInt.empty());
    }

    /** A recipe by exact name, ignoring case. @throws NotFoundException listing names that contain it */
    public Recipe findRecipe(String name) {
        String wanted = name.strip().toLowerCase(Locale.ROOT);
        List<Recipe> all = recipeRepository.search(RecipeQuery.any().withGenerated(true));
        return all.stream().filter(r -> r.name().toLowerCase(Locale.ROOT).equals(wanted)).findFirst()
                .orElseThrow(() -> {
                    List<String> close = all.stream().map(Recipe::name)
                            .filter(n -> n.toLowerCase(Locale.ROOT).contains(wanted)).toList();
                    return new NotFoundException("No recipe named '" + name.strip() + "'"
                            + (close.isEmpty() ? "" : ". Did you mean: " + String.join(", ", close) + "?"));
                });
    }

    /** Calories and macros for {@code servings} portions, from catalogue data only (D09). */
    public NutritionSummary recipeNutrition(Recipe recipe, int servings) {
        return nutritionService.calculateNutrition(recipe, servings);
    }

    /** Cost of one serving beyond what the pantry holds today, from known prices (F12). */
    public CostEstimate recipeCost(UUID profileId, Recipe recipe) {
        return budgetService.estimateRecipeCost(recipe, 1, getPantry(profileId), LocalDate.now(clock));
    }

    /** Allergy, diet and dislike findings for one recipe against a saved profile; empty result if none is saved. */
    public ValidationResult checkRecipe(UUID profileId, Recipe recipe) {
        return findProfile(profileId).map(p -> constraintValidator.validateRestrictions(recipe, p))
                .orElseGet(ValidationResult::ok);
    }

    /**
     * The saved profile, or a new one with default settings saved first. A pantry belongs to a
     * profile, so using the pantry before filling in the profile must not fail.
     */
    public UserProfile ensureProfile(UUID profileId) {
        return findProfile(profileId).orElseGet(() -> profileService.updateProfile(new UserProfile(profileId)));
    }

    private Optional<UserProfile> findProfile(UUID profileId) {
        try {
            return Optional.of(profileService.getProfile(profileId));
        } catch (NotFoundException e) {
            return Optional.empty();
        }
    }
}
