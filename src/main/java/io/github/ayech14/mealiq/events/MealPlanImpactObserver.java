package io.github.ayech14.mealiq.events;

import io.github.ayech14.mealiq.contracts.PlanImpact;
import io.github.ayech14.mealiq.services.MealPlanService;

/**
 * Records what a pantry or profile change means for the active plan (Observer, F13). It
 * only does fast, deterministic work; the LLM is asked only when the user adapts (D06).
 */
public class MealPlanImpactObserver implements PlanImpactObserver {

    private final MealPlanService mealPlanService;

    public MealPlanImpactObserver(MealPlanService mealPlanService) {
        this.mealPlanService = mealPlanService;
    }

    @Override
    public void onPlanRelevantChange(PlanRelevantChange event) {
        mealPlanService.getActivePlan(event.profileId()).ifPresent(plan -> {
            PlanImpact impact = mealPlanService.assessImpact(plan, event);
            mealPlanService.recordImpact(plan, impact);
        });
    }
}
