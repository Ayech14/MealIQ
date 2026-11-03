package io.github.ayech14.mealiq.services;

import io.github.ayech14.mealiq.contracts.PlanningContext;
import io.github.ayech14.mealiq.contracts.PlanningProposal;
import io.github.ayech14.mealiq.contracts.ValidationResult;
import io.github.ayech14.mealiq.domain.CostEstimate;
import io.github.ayech14.mealiq.domain.DietRules;
import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.domain.IngredientRequirement;
import io.github.ayech14.mealiq.domain.NutritionSummary;
import io.github.ayech14.mealiq.domain.PlannedMeal;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.UserProfile;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;

/**
 * Deterministic checks of recipes and plans against the profile (D04). Allergies and
 * dietary rules are hard: a breach is a violation. Dislikes are soft: only a warning.
 *
 * <p>For a plan, cooking time and budget are also checked as limits (F11, F12): a plan over
 * either is invalid, never saved silently. Nutrition targets are goals, so a day far from
 * them is a warning. Plan-change validation follows with plan modification.
 */
public class ConstraintValidator {

    /** A day this far from the calorie target, as a share of it, gets a warning. */
    static final double CALORIE_TOLERANCE = 0.20;
    /** A day below this share of the protein target gets a warning. */
    static final double PROTEIN_MINIMUM_SHARE = 0.8;

    private final NutritionService nutritionService;
    private final BudgetService budgetService;

    public ConstraintValidator(NutritionService nutritionService, BudgetService budgetService) {
        this.nutritionService = nutritionService;
        this.budgetService = budgetService;
    }

    /** Restrictions, time, budget and nutrition of a proposed plan for the context's profile. */
    public ValidationResult validatePlan(PlanningProposal proposal, PlanningContext context) {
        UserProfile profile = context.profile();
        Set<String> violations = new LinkedHashSet<>();
        Set<String> warnings = new LinkedHashSet<>();
        LocalDate start = context.request().startDate();
        for (PlannedMeal meal : proposal.meals()) {
            ValidationResult restrictions = validateRestrictions(meal.recipe(), profile);
            violations.addAll(restrictions.violations());
            warnings.addAll(restrictions.warnings());
            if (!validateTime(meal, profile)) {
                violations.add(ProposalValidator.label(meal.date(), meal.mealType(), start) + ": "
                        + meal.recipe().name() + " takes " + meal.recipe().totalMinutes() + " min, over the "
                        + profile.maxMinutesFor(meal.date().getDayOfWeek()).getAsInt() + "-minute limit for "
                        + meal.date().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + ".");
            }
        }
        checkBudget(proposal, context, violations, warnings);
        checkNutrition(proposal, context, warnings);
        return new ValidationResult(violations.isEmpty(), List.copyOf(violations), List.copyOf(warnings));
    }

    /** Whether the meal fits the cooking-time limit of its day; a day without a limit always fits. */
    public boolean validateTime(PlannedMeal meal, UserProfile profile) {
        OptionalInt limit = profile.maxMinutesFor(meal.date().getDayOfWeek());
        return limit.isEmpty() || meal.recipe().totalMinutes() <= limit.getAsInt();
    }

    private void checkBudget(PlanningProposal proposal, PlanningContext context, Set<String> violations,
                             Set<String> warnings) {
        BigDecimal weekly = context.profile().weeklyBudget();
        if (weekly.signum() == 0) {
            return; // no budget set
        }
        int days = context.request().days();
        BigDecimal budget = BudgetService.budgetFor(weekly, days);
        CostEstimate cost = budgetService.estimateCost(proposal.meals(), context.pantry());
        if (cost.known().compareTo(budget) > 0) {
            // Known prices alone are over budget, so the real cost is too.
            violations.add("Estimated grocery cost " + cost + " is over the budget of $" + budget + " for " + days
                    + " day(s).");
        } else if (!cost.complete()) {
            warnings.add("Known prices add up to " + cost + " against a budget of $" + budget + "; "
                    + cost.unpricedItems() + " ingredient amount(s) in the meals have no price, so the plan may still be "
                    + "over.");
        }
    }

    private void checkNutrition(PlanningProposal proposal, PlanningContext context, Set<String> warnings) {
        UserProfile profile = context.profile();
        LocalDate start = context.request().startDate();
        for (int day = 0; day < context.request().days(); day++) {
            LocalDate date = start.plusDays(day);
            List<PlannedMeal> meals = proposal.meals().stream().filter(m -> m.date().equals(date)).toList();
            if (meals.isEmpty()) {
                continue;
            }
            NutritionSummary total = nutritionService.calculateNutrition(meals);
            String label = "Day " + (day + 1) + " (" + date + ")";
            if (!total.complete()) {
                warnings.add(label + ": nutrition is incomplete, so targets were not checked.");
                continue;
            }
            Integer calories = profile.dailyCalorieTarget();
            if (calories != null && Math.abs(total.calories() - calories) > calories * CALORIE_TOLERANCE) {
                warnings.add(label + ": " + Math.round(total.calories()) + " kcal against a target of " + calories
                        + ".");
            }
            Integer protein = profile.dailyProteinTargetG();
            if (protein != null && total.proteinG() < protein * PROTEIN_MINIMUM_SHARE) {
                warnings.add(label + ": " + Math.round(total.proteinG()) + " g protein against a target of "
                        + protein + " g.");
            }
        }
    }

    /**
     * A required ingredient that breaks an allergy or diet makes the recipe invalid. An
     * optional one only produces a warning to leave it out, because the recipe works without it.
     */
    public ValidationResult validateRestrictions(Recipe recipe, UserProfile profile) {
        List<String> violations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (IngredientRequirement line : recipe.ingredients()) {
            Ingredient ingredient = line.ingredient();
            if (!profile.isAllowed(ingredient)) {
                String reason = ingredient.name() + " (" + reasonNotAllowed(ingredient, profile) + ")";
                if (line.optional()) {
                    warnings.add("Leave out optional " + reason + ".");
                } else {
                    violations.add(recipe.name() + " contains " + reason + ".");
                }
            } else if (profile.dislikedFoods().contains(ingredient.name())) {
                warnings.add(recipe.name() + " uses " + ingredient.name() + ", which you dislike.");
            }
        }
        return new ValidationResult(violations.isEmpty(), violations, warnings);
    }

    /** Names every allergy and diet rule the ingredient breaks, e.g. "allergy: milk; not vegan". */
    static String reasonNotAllowed(Ingredient ingredient, UserProfile profile) {
        List<String> reasons = new ArrayList<>();
        TreeSet<String> allergens = new TreeSet<>(ingredient.allergens());
        allergens.retainAll(profile.allergies());
        if (!allergens.isEmpty()) {
            reasons.add("allergy: " + String.join(", ", allergens));
        }
        for (String restriction : new TreeSet<>(profile.dietaryRestrictions())) {
            DietRules.Rule rule = DietRules.ruleFor(restriction);
            if (rule != null && (rule.excludedCategories().contains(ingredient.category())
                    || ingredient.allergens().stream().anyMatch(rule.excludedAllergens()::contains))) {
                reasons.add("not " + restriction);
            }
        }
        return String.join("; ", reasons);
    }
}
