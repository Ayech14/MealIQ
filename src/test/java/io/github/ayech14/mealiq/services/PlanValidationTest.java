package io.github.ayech14.mealiq.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ayech14.mealiq.application.TestFacades;
import io.github.ayech14.mealiq.contracts.PlanRequest;
import io.github.ayech14.mealiq.contracts.PlanningContext;
import io.github.ayech14.mealiq.contracts.PlanningProposal;
import io.github.ayech14.mealiq.contracts.RecipeQuery;
import io.github.ayech14.mealiq.contracts.ValidationResult;
import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.domain.MealPlan;
import io.github.ayech14.mealiq.domain.MealType;
import io.github.ayech14.mealiq.domain.Pantry;
import io.github.ayech14.mealiq.domain.PantryItem;
import io.github.ayech14.mealiq.domain.PlanStatus;
import io.github.ayech14.mealiq.domain.PlannedMeal;
import io.github.ayech14.mealiq.domain.PlanningMode;
import io.github.ayech14.mealiq.domain.Quantity;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.UserProfile;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** ProposalValidator, ConstraintValidator.validatePlan, BudgetService.estimateCost and MealPlanService. */
class PlanValidationTest {

    /** A Tuesday. */
    static final LocalDate START = LocalDate.of(2026, 11, 3);

    final InMemoryRepositories.Catalogue catalogue = new InMemoryRepositories.Catalogue();
    final InMemoryRepositories.Recipes recipes = new InMemoryRepositories.Recipes(catalogue);
    final NutritionService nutrition = new NutritionService();
    final UUID profileId = UUID.randomUUID();
    final UserProfile profile = new UserProfile(profileId);
    final Pantry pantry = Pantry.emptyFor(profileId);

    Recipe recipe(String name) {
        return recipes.search(RecipeQuery.any()).stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }

    PlanningContext context(int days) {
        return PlanningContext.of(new PlanRequest(profileId, START, days, PlanningMode.NUTRITION_GOAL, null),
                profile, pantry);
    }

    /** Day by day: breakfast, lunch, dinner. */
    PlanningProposal proposal(String... names) {
        List<PlannedMeal> meals = new ArrayList<>();
        MealType[] slots = {MealType.BREAKFAST, MealType.LUNCH, MealType.DINNER};
        for (int i = 0; i < names.length; i++) {
            meals.add(new PlannedMeal(START.plusDays(i / 3), slots[i % 3], recipe(names[i]), 1));
        }
        return new PlanningProposal(meals, PlanningMode.NUTRITION_GOAL, "", List.of());
    }

    PlanningProposal oneGoodDay() {
        return proposal("Overnight oats", "Lentil soup", "Shrimp tacos");
    }

    @Nested
    class Structure {

        final ProposalValidator validator = new ProposalValidator(recipes);

        @Test
        void completePlanIsValid() {
            assertTrue(validator.validate(oneGoodDay(), context(1)).valid());
        }

        @Test
        void missingAndExtraSlotsAreViolations() {
            ValidationResult result = validator.validate(proposal("Overnight oats", "Lentil soup"), context(1));
            assertEquals(List.of("Day 1 (2026-11-03) dinner: expected 1 meal(s), got 0."), result.violations());

            List<PlannedMeal> meals = new ArrayList<>(oneGoodDay().meals());
            meals.add(new PlannedMeal(START, MealType.SNACK, recipe("Trail mix"), 1));
            result = validator.validate(new PlanningProposal(meals, PlanningMode.BUDGET, "", List.of()), context(1));
            assertEquals(List.of("A snack meal is not part of a 3-meal day."), result.violations());
        }

        @Test
        void wrongMealTypeDatesAndUnknownRecipesAreViolations() {
            Recipe invented = Recipe.builder("Invented stew").mealTypes(MealType.DINNER).build();
            List<PlannedMeal> meals = List.of(
                    new PlannedMeal(START, MealType.BREAKFAST, recipe("Lentil soup"), 1),
                    new PlannedMeal(START, MealType.LUNCH, recipe("Lentil soup"), 1),
                    new PlannedMeal(START, MealType.DINNER, invented, 1),
                    new PlannedMeal(START.plusDays(5), MealType.DINNER, recipe("Shrimp tacos"), 1));
            List<String> violations = validator.validate(new PlanningProposal(meals, PlanningMode.BUDGET, "",
                    List.of()), context(1)).violations();
            assertEquals(List.of(
                    "Day 1 (2026-11-03) breakfast: Lentil soup is not a breakfast recipe.",
                    "Day 1 (2026-11-03) dinner: Invented stew is not a stored recipe.",
                    "Day 6 (2026-11-08) dinner is outside the plan's dates."), violations);
        }

        @Test
        void repeatsGetAVarietyWarning() {
            ValidationResult result = validator.validate(proposal(
                    "Overnight oats", "Lentil soup", "Shrimp tacos",
                    "Overnight oats", "Lentil soup", "Turkey chili",
                    "Overnight oats", "Tofu scramble", "Turkey chili"), context(3));
            assertTrue(result.valid());
            assertEquals(List.of("Overnight oats appears 3 times; consider more variety."), result.warnings());
        }

        @Test
        void sameRecipeTwiceInADayGetsAWarning() {
            ValidationResult result = validator.validate(proposal("Tofu scramble", "Tofu scramble", "Shrimp tacos"),
                    context(1));
            assertTrue(result.valid());
            assertEquals(List.of("Tofu scramble is planned 2 times on day 1."), result.warnings());
        }

        @Test
        void slotsFollowMealsPerDay() {
            profile.mealsPerDay(2);
            List<PlannedMeal> meals = List.of(new PlannedMeal(START, MealType.LUNCH, recipe("Lentil soup"), 1),
                    new PlannedMeal(START, MealType.DINNER, recipe("Shrimp tacos"), 1));
            assertTrue(validator.validate(new PlanningProposal(meals, PlanningMode.BUDGET, "", List.of()), context(1))
                    .valid());
        }
    }

    @Nested
    class Constraints {

        ConstraintValidator validator(BudgetService budget) {
            return new ConstraintValidator(nutrition, budget);
        }

        final ConstraintValidator flatPrices = validator(new BudgetService(TestFacades.FLAT_PRICES));

        @Test
        void mealsOverTheDayLimitAreViolations() {
            profile.maxCookMinutes(Map.of(DayOfWeek.TUESDAY, 20));
            ValidationResult result = flatPrices.validatePlan(oneGoodDay(), context(1));
            assertEquals(List.of("Day 1 (2026-11-03) lunch: Lentil soup takes 40 min, over the 20-minute limit for "
                    + "Tuesday."), result.violations());
            assertTrue(flatPrices.validateTime(oneGoodDay().meals().get(0), profile));
        }

        @Test
        void allergiesAreViolations() {
            profile.allergies(Set.of("shellfish"));
            assertEquals(List.of("Shrimp tacos contains shrimp (allergy: shellfish)."),
                    flatPrices.validatePlan(oneGoodDay(), context(1)).violations());
        }

        @Test
        void overBudgetIsAViolationScaledToThePlanLength() {
            profile.weeklyBudget(new BigDecimal("7"));
            ValidationResult result = flatPrices.validatePlan(oneGoodDay(), context(1));
            assertFalse(result.valid());
            assertTrue(result.violations().getFirst().endsWith("is over the budget of $1.00 for 1 day(s)."),
                    result.violations().toString());
            profile.weeklyBudget(new BigDecimal("1000"));
            assertTrue(flatPrices.validatePlan(oneGoodDay(), context(1)).valid());
        }

        @Test
        void unknownPricesOnlyWarn() {
            profile.weeklyBudget(new BigDecimal("50"));
            ValidationResult result = validator(new BudgetService((i, q) -> Optional.empty()))
                    .validatePlan(oneGoodDay(), context(1));
            assertTrue(result.valid());
            assertEquals(List.of("The cost could not be estimated (some prices are unknown), so the budget of $7.14 "
                    + "was not checked."), result.warnings());
        }

        @Test
        void farFromNutritionTargetsWarns() {
            profile.dailyCalorieTarget(3000).dailyProteinTargetG(200);
            ValidationResult result = flatPrices.validatePlan(oneGoodDay(), context(1));
            assertTrue(result.valid());
            assertEquals(2, result.warnings().size(), result.warnings().toString());
            assertTrue(result.warnings().get(0).startsWith("Day 1 (2026-11-03): ")
                    && result.warnings().get(0).endsWith(" kcal against a target of 3000."));
        }
    }

    @Nested
    class Cost {

        final Ingredient rice = catalogue.findByName("white rice").orElseThrow();

        @Test
        void pantryStockIsUsedOnlyOnce() {
            // Egg fried rice uses 150 g of rice for 2 servings, so 75 g per planned serving.
            Recipe friedRice = recipe("Egg fried rice");
            List<PlannedMeal> twoDinners = List.of(new PlannedMeal(START, MealType.DINNER, friedRice, 1),
                    new PlannedMeal(START.plusDays(1), MealType.DINNER, friedRice, 1));
            BudgetService budget = new BudgetService(TestFacades.FLAT_PRICES);
            BigDecimal withoutRice = budget.estimateCost(twoDinners, pantry).orElseThrow();
            pantry.updateItem(new PantryItem(rice, Quantity.of(100, "g"), null));
            BigDecimal withRice = budget.estimateCost(twoDinners, pantry).orElseThrow();
            assertEquals(new BigDecimal("1.00"), withoutRice.subtract(withRice), "100 g of rice at $1/100 g is saved");
            assertEquals(Quantity.of(100, "g"), pantry.items().values().iterator().next().quantity(),
                    "the real pantry is not changed");
        }
    }

    @Nested
    class Storage {

        final InMemoryRepositories.MealPlans repository = new InMemoryRepositories.MealPlans();
        final MealPlanService service = new MealPlanService(repository);

        @Test
        void newPlanIsActiveAndArchivesThePreviousOne() {
            PlanRequest request = new PlanRequest(profileId, START, 1, PlanningMode.NUTRITION_GOAL, null);
            MealPlan first = service.createFromProposal(request, oneGoodDay());
            MealPlan second = service.createFromProposal(request, oneGoodDay());
            assertEquals(second.id(), service.getActivePlan(profileId).orElseThrow().id());
            assertEquals(PlanStatus.ARCHIVED, service.getPlan(first.id()).status());
            assertEquals(3, service.getPlan(second.id()).meals().size());
        }
    }
}
