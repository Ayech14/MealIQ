package io.github.ayech14.mealiq.domain;

import java.math.BigDecimal;
import java.util.List;

/** Result of comparing a grocery estimate with the weekly budget (F12). */
public record BudgetStatus(BigDecimal estimate, BigDecimal budget, BigDecimal shortfall, boolean withinBudget,
                           boolean partial, List<GroceryItem> costDrivers) {

    public BudgetStatus {
        costDrivers = costDrivers == null ? List.of() : List.copyOf(costDrivers);
    }
}
