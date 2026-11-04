package io.github.ayech14.mealiq.services;

import io.github.ayech14.mealiq.contracts.PlanningContext;
import io.github.ayech14.mealiq.contracts.PlanningProposal;
import io.github.ayech14.mealiq.domain.NutritionSummary;
import io.github.ayech14.mealiq.domain.PlannedMeal;
import io.github.ayech14.mealiq.domain.UserProfile;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Sizes portions in a proposed plan (D01): the agent picks the recipes, and this service
 * decides how many servings of each to eat so every day gets closer to the calorie and
 * protein targets. One serving per meal cannot reach a high target such as 3000 kcal, so a
 * meal may go up to {@link #MAX_SERVINGS}. A larger portion is only taken when it brings the
 * day closer to its targets and the plan's known cost stays within the budget.
 */
public class PortionService {

    /** The most servings of one recipe planned for one meal. */
    public static final int MAX_SERVINGS = 2;

    private final NutritionService nutritionService;
    private final BudgetService budgetService;

    public PortionService(NutritionService nutritionService, BudgetService budgetService) {
        this.nutritionService = nutritionService;
        this.budgetService = budgetService;
    }

    /** The proposal with portions sized per day; unchanged when the profile has no targets. */
    public PlanningProposal sizePortions(PlanningProposal proposal, PlanningContext context) {
        UserProfile profile = context.profile();
        if (profile.dailyCalorieTarget() == null && profile.dailyProteinTargetG() == null) {
            return proposal;
        }
        List<PlannedMeal> meals = new ArrayList<>(proposal.meals());
        Set<LocalDate> days = new LinkedHashSet<>();
        meals.forEach(m -> days.add(m.date()));
        for (LocalDate day : days) {
            boolean improved = true;
            while (improved) {
                improved = false;
                double best = distance(meals, day, profile);
                int bestIndex = -1;
                for (int i = 0; i < meals.size(); i++) {
                    PlannedMeal meal = meals.get(i);
                    if (!meal.date().equals(day) || meal.servings() >= MAX_SERVINGS) {
                        continue;
                    }
                    List<PlannedMeal> trial = new ArrayList<>(meals);
                    trial.set(i, withServings(meal, meal.servings() + 1));
                    double score = distance(trial, day, profile);
                    if (score < best - 0.01 && withinBudget(trial, context)) {
                        best = score;
                        bestIndex = i;
                    }
                }
                if (bestIndex >= 0) {
                    PlannedMeal meal = meals.get(bestIndex);
                    meals.set(bestIndex, withServings(meal, meal.servings() + 1));
                    improved = true;
                }
            }
        }
        return new PlanningProposal(meals, proposal.mode(), proposal.rationale(), proposal.conflicts());
    }

    /**
     * How far a day is from its targets, as shares of the targets: the calorie gap either way
     * plus the protein shortfall. Negative infinity when nutrition is incomplete, so no portion
     * change can count as an improvement.
     */
    double distance(List<PlannedMeal> meals, LocalDate day, UserProfile profile) {
        List<PlannedMeal> ofDay = meals.stream().filter(m -> m.date().equals(day)).toList();
        NutritionSummary total = nutritionService.calculateNutrition(ofDay);
        if (!total.complete()) {
            return Double.NEGATIVE_INFINITY;
        }
        double distance = 0;
        Integer calories = profile.dailyCalorieTarget();
        if (calories != null && calories > 0) {
            distance += Math.abs(total.calories() - calories) / calories;
        }
        Integer protein = profile.dailyProteinTargetG();
        if (protein != null && protein > 0) {
            distance += Math.max(0, protein - total.proteinG()) / protein;
        }
        return distance;
    }

    private boolean withinBudget(List<PlannedMeal> meals, PlanningContext context) {
        BigDecimal weekly = context.profile().weeklyBudget();
        if (weekly.signum() == 0) {
            return true;
        }
        BigDecimal budget = BudgetService.budgetFor(weekly, context.request().days());
        return budgetService.estimateCost(meals, context.pantry()).known().compareTo(budget) <= 0;
    }

    private static PlannedMeal withServings(PlannedMeal meal, int servings) {
        return new PlannedMeal(meal.id(), meal.date(), meal.mealType(), meal.recipe(), servings);
    }
}
