package io.github.ayech14.mealiq.application;

import io.github.ayech14.mealiq.agent.MealPlanningAgent;
import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.domain.Quantity;
import io.github.ayech14.mealiq.integration.PriceDataSource;
import io.github.ayech14.mealiq.llm.ScriptedLLMClient;
import io.github.ayech14.mealiq.persistence.MealPlanRepository;
import io.github.ayech14.mealiq.persistence.RecipeRepository;
import io.github.ayech14.mealiq.planning.DefaultPlanningStrategyFactory;
import io.github.ayech14.mealiq.services.BudgetService;
import io.github.ayech14.mealiq.services.ConstraintValidator;
import io.github.ayech14.mealiq.services.GroceryService;
import io.github.ayech14.mealiq.services.InMemoryRepositories;
import io.github.ayech14.mealiq.services.MealPlanService;
import io.github.ayech14.mealiq.services.NutritionService;
import io.github.ayech14.mealiq.services.PantryService;
import io.github.ayech14.mealiq.services.PlanImpactAssessor;
import io.github.ayech14.mealiq.services.ProfileService;
import io.github.ayech14.mealiq.services.ProposalValidator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

/** A facade wired to in-memory repositories, a scripted LLM and a fixed clock, for facade and CLI tests. */
public final class TestFacades {

    public record Fixture(MealPlanningFacade facade, InMemoryRepositories.RecordingObserver pantryEvents,
                          ScriptedLLMClient llm, InMemoryRepositories.MealPlans plans,
                          InMemoryRepositories.PlanChanges changes) {
    }

    public static final int MAX_TOOL_STEPS = 4;

    /** Every ingredient costs $1.00 per 100 g, or per unit when it has no weight. */
    public static final PriceDataSource FLAT_PRICES = new PriceDataSource() {
        @Override
        public Optional<BigDecimal> estimatePrice(Ingredient ingredient, Quantity quantity) {
            return Optional.of(BigDecimal.valueOf(ingredient.toGrams(quantity).orElse(quantity.amount() * 100) / 100));
        }

        @Override
        public String describe() {
            return "test prices of $1.00 per 100 g";
        }
    };

    private TestFacades() {
    }

    public static Fixture inMemory(LocalDate today) {
        Clock clock = Clock.fixed(today.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        InMemoryRepositories.Catalogue catalogue = new InMemoryRepositories.Catalogue();
        InMemoryRepositories.Pantries pantryRows = new InMemoryRepositories.Pantries();
        InMemoryRepositories.Profiles profileRows = new InMemoryRepositories.Profiles();
        PantryService pantry = new PantryService(pantryRows, catalogue, clock);
        InMemoryRepositories.RecordingObserver events = new InMemoryRepositories.RecordingObserver();
        pantry.subscribe(events);
        NutritionService nutrition = new NutritionService();
        ProfileService profiles = new ProfileService(profileRows, catalogue);
        InMemoryRepositories.Recipes recipes = new InMemoryRepositories.Recipes(catalogue);
        BudgetService budget = new BudgetService(FLAT_PRICES);
        ConstraintValidator validator = new ConstraintValidator(nutrition, budget);
        DefaultPlanningStrategyFactory strategies = new DefaultPlanningStrategyFactory(nutrition, budget);
        ScriptedLLMClient llm = new ScriptedLLMClient();
        InMemoryRepositories.MealPlans plans = new InMemoryRepositories.MealPlans();
        GroceryService groceries = new GroceryService(new InMemoryRepositories.GroceryLists(), plans, pantry,
                FLAT_PRICES);
        MealPlanningAgent agent = AgentAssembly.agent(llm, recipes, catalogue, nutrition, budget, groceries, validator,
                strategies, MAX_TOOL_STEPS);
        InMemoryRepositories.PlanChanges changes = new InMemoryRepositories.PlanChanges();
        MealPlanService mealPlans = new MealPlanService(plans, recipes, changes, new InMemoryRepositories.PlanImpacts(),
                new PlanImpactAssessor(profileRows, pantryRows, catalogue, validator, budget, clock));
        MealPlanningFacade.subscribeObservers(profiles, pantry, mealPlans, groceries);
        AgentController controller = new AgentController(agent, profiles, pantry, mealPlans,
                new ProposalValidator(recipes), validator, groceries);
        MealPlanningFacade facade = new MealPlanningFacade(new MealPlanningFacade.Services(
                profiles, pantry, mealPlans, groceries, catalogue, recipes, nutrition, budget, validator, strategies,
                controller, clock));
        return new Fixture(facade, events, llm, plans, changes);
    }

    /** A plan service without profiles or pantry behind it, for tests that do not look at impacts. */
    public static MealPlanService planService(MealPlanRepository plans, RecipeRepository recipes) {
        NutritionService nutrition = new NutritionService();
        BudgetService budget = new BudgetService(FLAT_PRICES);
        return new MealPlanService(plans, recipes, new InMemoryRepositories.PlanChanges(),
                new InMemoryRepositories.PlanImpacts(), new PlanImpactAssessor(new InMemoryRepositories.Profiles(),
                        new InMemoryRepositories.Pantries(), new InMemoryRepositories.Catalogue(),
                        new ConstraintValidator(nutrition, budget), budget, Clock.systemDefaultZone()));
    }
}
