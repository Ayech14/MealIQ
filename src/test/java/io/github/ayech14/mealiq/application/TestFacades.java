package io.github.ayech14.mealiq.application;

import io.github.ayech14.mealiq.agent.MealPlanningAgent;
import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.domain.Quantity;
import io.github.ayech14.mealiq.integration.PriceDataSource;
import io.github.ayech14.mealiq.llm.ScriptedLLMClient;
import io.github.ayech14.mealiq.planning.DefaultPlanningStrategyFactory;
import io.github.ayech14.mealiq.services.BudgetService;
import io.github.ayech14.mealiq.services.ConstraintValidator;
import io.github.ayech14.mealiq.services.InMemoryRepositories;
import io.github.ayech14.mealiq.services.NutritionService;
import io.github.ayech14.mealiq.services.PantryService;
import io.github.ayech14.mealiq.services.ProfileService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

/** A facade wired to in-memory repositories, a scripted LLM and a fixed clock, for facade and CLI tests. */
public final class TestFacades {

    public record Fixture(MealPlanningFacade facade, InMemoryRepositories.RecordingObserver pantryEvents,
                          ScriptedLLMClient llm) {
    }

    public static final int MAX_TOOL_STEPS = 4;

    /** Every ingredient costs $1.00 per 100 g, or per unit when it has no weight. */
    public static final PriceDataSource FLAT_PRICES = (Ingredient ingredient, Quantity quantity) -> Optional.of(
            BigDecimal.valueOf(ingredient.toGrams(quantity).orElse(quantity.amount() * 100) / 100));

    private TestFacades() {
    }

    public static Fixture inMemory(LocalDate today) {
        Clock clock = Clock.fixed(today.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        InMemoryRepositories.Catalogue catalogue = new InMemoryRepositories.Catalogue();
        PantryService pantry = new PantryService(new InMemoryRepositories.Pantries(), catalogue, clock);
        InMemoryRepositories.RecordingObserver events = new InMemoryRepositories.RecordingObserver();
        pantry.subscribe(events);
        NutritionService nutrition = new NutritionService();
        ProfileService profiles = new ProfileService(new InMemoryRepositories.Profiles(), catalogue);
        InMemoryRepositories.Recipes recipes = new InMemoryRepositories.Recipes(catalogue);
        ConstraintValidator validator = new ConstraintValidator();
        DefaultPlanningStrategyFactory strategies =
                new DefaultPlanningStrategyFactory(nutrition, new BudgetService(FLAT_PRICES));
        ScriptedLLMClient llm = new ScriptedLLMClient();
        MealPlanningAgent agent = AgentAssembly.agent(llm, recipes, catalogue, nutrition, validator, strategies,
                MAX_TOOL_STEPS);
        MealPlanningFacade facade = new MealPlanningFacade(new MealPlanningFacade.Services(
                profiles, pantry, catalogue, recipes, nutrition, validator, strategies,
                new AgentController(agent, profiles, pantry, validator), clock));
        return new Fixture(facade, events, llm);
    }
}
