package io.github.ayech14.mealiq.application;

import io.github.ayech14.mealiq.agent.MealPlanningAgent;
import io.github.ayech14.mealiq.config.AppConfig;
import io.github.ayech14.mealiq.contracts.AgentResponse;
import io.github.ayech14.mealiq.contracts.PlanChange;
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
import io.github.ayech14.mealiq.persistence.jdbc.JdbcProfileRepository;
import io.github.ayech14.mealiq.persistence.jdbc.JdbcRecipeRepository;
import io.github.ayech14.mealiq.planning.DefaultPlanningStrategyFactory;
import io.github.ayech14.mealiq.planning.PlanningStrategyFactory;
import io.github.ayech14.mealiq.services.BudgetService;
import io.github.ayech14.mealiq.services.ConstraintValidator;
import io.github.ayech14.mealiq.services.GroceryService;
import io.github.ayech14.mealiq.services.MealPlanService;
import io.github.ayech14.mealiq.services.NotFoundException;
import io.github.ayech14.mealiq.services.NutritionService;
import io.github.ayech14.mealiq.services.PantryService;
import io.github.ayech14.mealiq.services.ProfileService;
import io.github.ayech14.mealiq.services.ProposalValidator;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * The single application boundary shared by the GUI and the CLI (Facade, D02). Each method
 * delegates to the services; interfaces only collect input and show results, so both
 * reach the same rules and give the same outcome.
 *
 * <p>Operations are added as features are implemented; this version covers F01 (profile),
 * F02 (pantry), recipe browsing and agent recommendations (F03), weekly plans (F05),
 * nutrition analysis (F06), grocery lists with prices and budget checks (F07, F08, F12), and
 * confirmed plan changes with undo (F09, F10).
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
        ProfileService profiles = new ProfileService(new JdbcProfileRepository(database), ingredients);
        PantryService pantry = new PantryService(new JdbcPantryRepository(database), ingredients, clock);
        LLMClient llm = config.llmConfigured() ? ProviderLLMAdapter.fromConfig(config)
                : (prompt, schema, instructions) -> {
                    throw new LLMException(LLMException.Kind.NOT_CONFIGURED, "set LLM_API_KEY and LLM_MODEL in .env");
                };
        MealPlanService mealPlans = new MealPlanService(new JdbcMealPlanRepository(database), recipes,
                new JdbcPlanChangeRepository(database));
        GroceryService groceries = new GroceryService(new JdbcGroceryListRepository(database), pantry, prices);
        MealPlanningAgent agent = AgentAssembly.agent(llm, recipes, ingredients, nutrition, budget, groceries, validator,
                strategies, config.maxToolSteps());
        AgentController controller = new AgentController(agent, profiles, pantry, mealPlans,
                new ProposalValidator(recipes), validator, groceries);
        return new MealPlanningFacade(new Services(profiles, pantry, mealPlans, groceries, ingredients, recipes,
                nutrition, budget, validator, strategies, controller, clock));
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

    /** Adds the item or replaces its quantity (UC02). */
    public AgentResponse updatePantry(UUID profileId, PantryItem item) {
        pantryService.updateItem(profileId, item);
        return AgentResponse.builder(ResponseStatus.SUCCESS)
                .rationale("Pantry updated: " + item.ingredient().name() + " " + item.quantity() + ".")
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

    private Optional<UserProfile> findProfile(UUID profileId) {
        try {
            return Optional.of(profileService.getProfile(profileId));
        } catch (NotFoundException e) {
            return Optional.empty();
        }
    }
}
