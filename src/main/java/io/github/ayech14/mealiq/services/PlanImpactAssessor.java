package io.github.ayech14.mealiq.services;

import io.github.ayech14.mealiq.contracts.PlanImpact;
import io.github.ayech14.mealiq.contracts.ValidationResult;
import io.github.ayech14.mealiq.domain.ChangeEventType;
import io.github.ayech14.mealiq.domain.CostEstimate;
import io.github.ayech14.mealiq.domain.ImpactCause;
import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.domain.MealPlan;
import io.github.ayech14.mealiq.domain.Pantry;
import io.github.ayech14.mealiq.domain.PlannedMeal;
import io.github.ayech14.mealiq.domain.UserProfile;
import io.github.ayech14.mealiq.persistence.IngredientRepository;
import io.github.ayech14.mealiq.persistence.PantryRepository;
import io.github.ayech14.mealiq.persistence.ProfileRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * Works out what a change means for a plan, deterministically and without the LLM (D06):
 * meals that now break an allergy, diet or time limit, a plan now over budget, or meals
 * that use an ingredient the pantry no longer has.
 */
public class PlanImpactAssessor {

    private final ProfileRepository profileRepository;
    private final PantryRepository pantryRepository;
    private final IngredientRepository ingredientRepository;
    private final ConstraintValidator constraintValidator;
    private final BudgetService budgetService;
    private final Clock clock;

    public PlanImpactAssessor(ProfileRepository profileRepository, PantryRepository pantryRepository,
                              IngredientRepository ingredientRepository, ConstraintValidator constraintValidator,
                              BudgetService budgetService, Clock clock) {
        this.profileRepository = profileRepository;
        this.pantryRepository = pantryRepository;
        this.ingredientRepository = ingredientRepository;
        this.constraintValidator = constraintValidator;
        this.budgetService = budgetService;
        this.clock = clock;
    }

    /** The current impact of the given causes on the plan; empty when nothing needs attention. */
    public PlanImpact assess(MealPlan plan, List<ImpactCause> causes) {
        Optional<UserProfile> profile = profileRepository.findById(plan.profileId());
        if (profile.isEmpty() || causes.isEmpty()) {
            return PlanImpact.NONE;
        }
        Pantry pantry = pantryRepository.findByProfileId(plan.profileId());
        Map<UUID, PlannedMeal> affected = new LinkedHashMap<>();
        Set<String> conflicts = new LinkedHashSet<>();
        for (ImpactCause cause : causes) {
            if (cause.type() == ChangeEventType.PANTRY_CHANGED) {
                ranOut(plan, pantry, cause.ingredientId(), affected, conflicts);
            } else {
                brokenConstraints(plan, profile.get(), pantry, affected, conflicts);
            }
        }
        return new PlanImpact(new ArrayList<>(affected.values()), List.of(), new ArrayList<>(conflicts), causes);
    }

    /** A profile's cooking-time limit for a weekday; empty without a limit or a profile. */
    public OptionalInt timeLimit(UUID profileId, DayOfWeek day) {
        return profileRepository.findById(profileId).map(p -> p.maxMinutesFor(day)).orElse(OptionalInt.empty());
    }

    /** Meals that use an ingredient the pantry no longer has in stock today. */
    private void ranOut(MealPlan plan, Pantry pantry, UUID ingredientId, Map<UUID, PlannedMeal> affected,
                        Set<String> conflicts) {
        Optional<Ingredient> ingredient = ingredientId == null ? Optional.empty()
                : ingredientRepository.findById(ingredientId);
        if (ingredient.isEmpty() || !pantry.availableQuantity(ingredient.get(), LocalDate.now(clock)).isZero()) {
            return;
        }
        for (PlannedMeal meal : plan.meals()) {
            if (meal.recipe().ingredients().stream().anyMatch(l -> !l.optional()
                    && l.ingredient().id().equals(ingredientId))) {
                affected.putIfAbsent(meal.id(), meal);
                conflicts.add(label(plan, meal) + ": " + meal.recipe().name() + " uses " + ingredient.get().name()
                        + ", which is no longer in your pantry.");
            }
        }
    }

    /** Meals that now break an allergy, diet or time limit, and a budget that known prices exceed. */
    private void brokenConstraints(MealPlan plan, UserProfile profile, Pantry pantry, Map<UUID, PlannedMeal> affected,
                                   Set<String> conflicts) {
        for (PlannedMeal meal : plan.meals()) {
            ValidationResult restrictions = constraintValidator.validateRestrictions(meal.recipe(), profile);
            if (!restrictions.valid()) {
                affected.putIfAbsent(meal.id(), meal);
                restrictions.violations().forEach(v -> conflicts.add(label(plan, meal) + ": " + v));
            }
            if (!constraintValidator.validateTime(meal, profile)) {
                affected.putIfAbsent(meal.id(), meal);
                conflicts.add(label(plan, meal) + ": " + meal.recipe().name() + " takes "
                        + meal.recipe().totalMinutes() + " min, over the new limit of "
                        + profile.maxMinutesFor(meal.date().getDayOfWeek()).getAsInt() + " min.");
            }
        }
        if (profile.weeklyBudget().signum() > 0) {
            BigDecimal budget = BudgetService.budgetFor(profile.weeklyBudget(), plan.days());
            CostEstimate cost = budgetService.estimateCost(plan.meals(), pantry);
            if (cost.known().compareTo(budget) > 0) {
                conflicts.add("The plan's groceries cost " + cost + ", over the budget of $" + budget + ".");
            }
        }
    }

    private static String label(MealPlan plan, PlannedMeal meal) {
        return ProposalValidator.label(meal.date(), meal.mealType(), plan.startDate());
    }
}
