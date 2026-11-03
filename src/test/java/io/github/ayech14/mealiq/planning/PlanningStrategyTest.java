package io.github.ayech14.mealiq.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ayech14.mealiq.contracts.PlanRequest;
import io.github.ayech14.mealiq.contracts.PlanningContext;
import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.domain.Pantry;
import io.github.ayech14.mealiq.domain.PantryItem;
import io.github.ayech14.mealiq.domain.PlanningMode;
import io.github.ayech14.mealiq.domain.Quantity;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.UserProfile;
import io.github.ayech14.mealiq.domain.WeightGoal;
import io.github.ayech14.mealiq.services.BudgetService;
import io.github.ayech14.mealiq.services.InMemoryRepositories;
import io.github.ayech14.mealiq.services.NutritionService;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PlanningStrategyTest {

    /** A Tuesday. */
    static final LocalDate START = LocalDate.of(2026, 11, 3);

    final InMemoryRepositories.Catalogue catalogue = new InMemoryRepositories.Catalogue();
    final Ingredient rice = catalogue.findByName("white rice").orElseThrow();
    final Ingredient chicken = catalogue.findByName("chicken breast").orElseThrow();
    final Ingredient oil = catalogue.findByName("olive oil").orElseThrow();
    final NutritionService nutrition = new NutritionService();
    final UUID profileId = UUID.randomUUID();
    final Pantry pantry = Pantry.emptyFor(profileId);

    /** One serving each: 100 g rice is about 365 kcal; 200 g chicken is 330 kcal with 62 g protein. */
    final Recipe plainRice = Recipe.builder("Plain rice").minutes(5, 15).ingredient(rice, 100, "g").build();
    final Recipe chickenOnly = Recipe.builder("Chicken").minutes(5, 25).ingredient(chicken, 200, "g").build();
    final Recipe oilyRice = Recipe.builder("Oily rice").minutes(5, 10).ingredient(rice, 150, "g")
            .ingredient(oil, 40, "g").build();
    final Recipe mystery = Recipe.builder("Mystery").minutes(1, 0)
            .ingredient(Ingredient.of("unweighable", "other"), 1, "unit").build();

    PlanningContext context(PlanningMode mode, UserProfile profile) {
        return PlanningContext.of(new PlanRequest(profileId, START, 1, mode, null), profile, pantry);
    }

    List<String> names(List<Recipe> ranked) {
        return ranked.stream().map(Recipe::name).toList();
    }

    @Nested
    class Factory {

        final DefaultPlanningStrategyFactory factory = new DefaultPlanningStrategyFactory(nutrition,
                new BudgetService((i, q) -> Optional.empty()));

        @Test
        void eachModeGetsItsStrategy() {
            UserProfile profile = new UserProfile(profileId);
            assertInstanceOf(NutritionGoalStrategy.class,
                    factory.strategyFor(context(PlanningMode.NUTRITION_GOAL, profile)));
            assertInstanceOf(BudgetStrategy.class, factory.strategyFor(context(PlanningMode.BUDGET, profile)));
            assertInstanceOf(TimeAwareStrategy.class,
                    factory.strategyFor(context(PlanningMode.TIME_AWARE, profile)));
        }

        @Test
        void strategiesAreFreshAndConfigured() {
            UserProfile limited = new UserProfile(profileId).maxCookMinutes(Map.of(DayOfWeek.TUESDAY, 20));
            PlanningContext ctx = context(PlanningMode.TIME_AWARE, limited);
            PlanningStrategy first = factory.strategyFor(ctx);
            assertTrue(first != factory.strategyFor(ctx));
            // configured from the profile: the 30-minute recipe is over Tuesday's 20-minute limit
            assertTrue(first.score(chickenOnly, ctx) < 0);
        }
    }

    @Nested
    class NutritionGoal {

        final NutritionGoalStrategy strategy = new NutritionGoalStrategy(nutrition);

        @Test
        void closestToThePerMealTargetRanksFirst() {
            UserProfile profile = new UserProfile(profileId).dailyCalorieTarget(1050).mealsPerDay(3); // 350 per meal
            strategy.configure(profile);
            List<Recipe> ranked = strategy.rank(List.of(oilyRice, plainRice, chickenOnly),
                    context(PlanningMode.NUTRITION_GOAL, profile));
            assertEquals(List.of("Chicken", "Plain rice", "Oily rice"), names(ranked));
        }

        @Test
        void losingWeightPenalizesGoingOverMoreThanUnder() {
            strategy.configure(new UserProfile(profileId).goal(WeightGoal.LOSE).dailyCalorieTarget(1500)
                    .mealsPerDay(3)); // 500 per meal
            assertEquals(1 - 0.2 * 1.5, strategy.calorieFit(600), 1e-9);
            assertEquals(1 - 0.2, strategy.calorieFit(400), 1e-9);
        }

        @Test
        void withoutTargetsTheGoalDecides() {
            strategy.configure(new UserProfile(profileId).goal(WeightGoal.GAIN));
            assertTrue(strategy.calorieFit(800) > strategy.calorieFit(300));
            strategy.configure(new UserProfile(profileId).goal(WeightGoal.LOSE));
            assertTrue(strategy.calorieFit(300) > strategy.calorieFit(800));
        }

        @Test
        void incompleteNutritionRanksLast() {
            UserProfile profile = new UserProfile(profileId);
            strategy.configure(profile);
            PlanningContext ctx = context(PlanningMode.NUTRITION_GOAL, profile);
            assertEquals(PlanningStrategy.UNSCORED, strategy.score(mystery, ctx));
            assertEquals("Mystery", names(strategy.rank(List.of(mystery, plainRice), ctx)).get(1));
        }

        @Test
        void pantryStockBreaksTies() {
            UserProfile profile = new UserProfile(profileId);
            strategy.configure(profile);
            Recipe twin = Recipe.builder("Twin rice").minutes(5, 15).ingredient(rice, 100, "g").build();
            pantry.updateItem(new PantryItem(rice, Quantity.of(1, "kg"), null));
            Recipe other = Recipe.builder("Another rice").minutes(5, 15)
                    .ingredient(catalogue.findByName("brown rice").orElseThrow(), 100, "g").build();
            List<Recipe> ranked = strategy.rank(List.of(other, twin), context(PlanningMode.NUTRITION_GOAL, profile));
            assertEquals("Twin rice", ranked.get(0).name());
        }
    }

    @Nested
    class TimeAware {

        final TimeAwareStrategy strategy = new TimeAwareStrategy();

        @Test
        void quickerFirstAndOverTheDayLimitLast() {
            UserProfile profile = new UserProfile(profileId).maxCookMinutes(Map.of(DayOfWeek.TUESDAY, 20));
            strategy.configure(profile);
            List<Recipe> ranked = strategy.rank(List.of(chickenOnly, plainRice, oilyRice),
                    context(PlanningMode.TIME_AWARE, profile));
            assertEquals(List.of("Oily rice", "Plain rice", "Chicken"), names(ranked));
            assertTrue(strategy.score(chickenOnly, context(PlanningMode.TIME_AWARE, profile)) < 0);
        }

        @Test
        void otherDaysLimitsDoNotApply() {
            UserProfile profile = new UserProfile(profileId).maxCookMinutes(Map.of(DayOfWeek.MONDAY, 10));
            strategy.configure(profile);
            assertTrue(strategy.score(chickenOnly, context(PlanningMode.TIME_AWARE, profile)) > 0);
        }
    }

    @Nested
    class Budget {

        @Test
        void cheaperFirstAndUnknownPriceLast() {
            BudgetStrategy strategy = new BudgetStrategy(new BudgetService((ingredient, quantity) ->
                    ingredient.equals(chicken) ? Optional.empty()
                            : Optional.of(BigDecimal.valueOf(ingredient.toGrams(quantity).getAsDouble() / 100))));
            UserProfile profile = new UserProfile(profileId);
            strategy.configure(profile);
            List<Recipe> ranked = strategy.rank(List.of(chickenOnly, oilyRice, plainRice),
                    context(PlanningMode.BUDGET, profile));
            assertEquals(List.of("Plain rice", "Oily rice", "Chicken"), names(ranked));
        }
    }
}
