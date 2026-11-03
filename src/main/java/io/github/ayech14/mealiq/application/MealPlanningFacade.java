package io.github.ayech14.mealiq.application;

import io.github.ayech14.mealiq.agent.MealPlanningAgent;
import io.github.ayech14.mealiq.config.AppConfig;
import io.github.ayech14.mealiq.contracts.AgentResponse;
import io.github.ayech14.mealiq.contracts.PlanRequest;
import io.github.ayech14.mealiq.contracts.PlanningContext;
import io.github.ayech14.mealiq.contracts.RecipeQuery;
import io.github.ayech14.mealiq.contracts.ValidationResult;
import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.domain.NutritionSummary;
import io.github.ayech14.mealiq.domain.Pantry;
import io.github.ayech14.mealiq.domain.PantryItem;
import io.github.ayech14.mealiq.domain.PlanningMode;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.ResponseStatus;
import io.github.ayech14.mealiq.domain.UserProfile;
import io.github.ayech14.mealiq.llm.LLMClient;
import io.github.ayech14.mealiq.llm.LLMException;
import io.github.ayech14.mealiq.llm.ProviderLLMAdapter;
import io.github.ayech14.mealiq.persistence.IngredientRepository;
import io.github.ayech14.mealiq.persistence.RecipeRepository;
import io.github.ayech14.mealiq.persistence.jdbc.Database;
import io.github.ayech14.mealiq.persistence.jdbc.JdbcIngredientRepository;
import io.github.ayech14.mealiq.persistence.jdbc.JdbcPantryRepository;
import io.github.ayech14.mealiq.persistence.jdbc.JdbcProfileRepository;
import io.github.ayech14.mealiq.persistence.jdbc.JdbcRecipeRepository;
import io.github.ayech14.mealiq.planning.DefaultPlanningStrategyFactory;
import io.github.ayech14.mealiq.planning.PlanningStrategyFactory;
import io.github.ayech14.mealiq.services.BudgetService;
import io.github.ayech14.mealiq.services.ConstraintValidator;
import io.github.ayech14.mealiq.services.NotFoundException;
import io.github.ayech14.mealiq.services.NutritionService;
import io.github.ayech14.mealiq.services.PantryService;
import io.github.ayech14.mealiq.services.ProfileService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * The single application boundary shared by the GUI and the CLI (Facade, D02). Each method
 * delegates to the services; interfaces only collect input and show results, so both
 * reach the same rules and give the same outcome.
 *
 * <p>Operations are added as features are implemented; this version covers F01 (profile),
 * F02 (pantry), recipe browsing and agent recommendations (F03), and recipe nutrition (F06).
 */
public class MealPlanningFacade {

    /** The repositories and services behind the facade, grouped so wiring stays readable. */
    public record Services(ProfileService profiles, PantryService pantry, IngredientRepository ingredients,
                           RecipeRepository recipes, NutritionService nutrition, ConstraintValidator validator,
                           PlanningStrategyFactory strategies, AgentController agentController, Clock clock) {
    }

    private final ProfileService profileService;
    private final PantryService pantryService;
    private final IngredientRepository ingredientRepository;
    private final RecipeRepository recipeRepository;
    private final NutritionService nutritionService;
    private final ConstraintValidator constraintValidator;
    private final PlanningStrategyFactory strategyFactory;
    private final AgentController agentController;
    private final Clock clock;

    public MealPlanningFacade(Services services) {
        this.profileService = services.profiles();
        this.pantryService = services.pantry();
        this.ingredientRepository = services.ingredients();
        this.recipeRepository = services.recipes();
        this.nutritionService = services.nutrition();
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
        // No grocery price data is connected yet, so every cost is unknown and BUDGET ranking is unavailable.
        BudgetService budget = new BudgetService((ingredient, quantity) -> Optional.empty());
        ConstraintValidator validator = new ConstraintValidator();
        PlanningStrategyFactory strategies = new DefaultPlanningStrategyFactory(nutrition, budget);
        Clock clock = Clock.systemDefaultZone();
        ProfileService profiles = new ProfileService(new JdbcProfileRepository(database), ingredients);
        PantryService pantry = new PantryService(new JdbcPantryRepository(database), ingredients, clock);
        LLMClient llm = config.llmConfigured() ? ProviderLLMAdapter.fromConfig(config)
                : (prompt, schema, instructions) -> {
                    throw new LLMException(LLMException.Kind.NOT_CONFIGURED, "set LLM_API_KEY and LLM_MODEL in .env");
                };
        MealPlanningAgent agent = AgentAssembly.agent(llm, recipes, ingredients, nutrition, validator, strategies,
                config.maxToolSteps());
        return new MealPlanningFacade(new Services(profiles, pantry, ingredients, recipes, nutrition, validator,
                strategies, new AgentController(agent, profiles, pantry, validator), clock));
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
