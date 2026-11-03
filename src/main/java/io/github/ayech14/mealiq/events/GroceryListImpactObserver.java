package io.github.ayech14.mealiq.events;

import io.github.ayech14.mealiq.services.GroceryService;

/** Marks the active plan's grocery list out of date when the pantry changes (Observer, F13, D06). */
public class GroceryListImpactObserver implements PlanImpactObserver {

    private final GroceryService groceryService;

    public GroceryListImpactObserver(GroceryService groceryService) {
        this.groceryService = groceryService;
    }

    @Override
    public void onPlanRelevantChange(PlanRelevantChange event) {
        groceryService.markAffectedListStale(event);
    }
}
