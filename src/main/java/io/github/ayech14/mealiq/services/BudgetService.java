package io.github.ayech14.mealiq.services;

import io.github.ayech14.mealiq.domain.BudgetStatus;
import io.github.ayech14.mealiq.domain.CostEstimate;
import io.github.ayech14.mealiq.domain.GroceryItem;
import io.github.ayech14.mealiq.domain.GroceryList;
import io.github.ayech14.mealiq.domain.IngredientRequirement;
import io.github.ayech14.mealiq.domain.Pantry;
import io.github.ayech14.mealiq.domain.PlannedMeal;
import io.github.ayech14.mealiq.domain.Quantity;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.integration.PriceDataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Deterministic cost estimates from a price source (F12, D01). What the pantry already
 * holds costs nothing. An unknown price is never guessed: estimates add up the known prices
 * and count the items without one, so a partial estimate is a lower bound.
 */
public class BudgetService {

    private final PriceDataSource priceSource;

    public BudgetService(PriceDataSource priceSource) {
        this.priceSource = priceSource;
    }

    /** Most expensive items named as cost drivers when a list is over budget. */
    static final int COST_DRIVERS = 3;

    /**
     * Compares a priced list with a budget (F12). The shortfall is exact, and an estimate with
     * unpriced items is marked partial: then "within budget" only means the known prices fit.
     */
    public BudgetStatus evaluate(GroceryList list, BigDecimal budget) {
        BigDecimal estimate = list.total().setScale(2, RoundingMode.HALF_UP);
        BigDecimal shortfall = estimate.subtract(budget).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        List<GroceryItem> drivers = list.items().stream().filter(i -> i.estimatedPrice() != null)
                .filter(i -> i.estimatedPrice().signum() > 0)
                .sorted(Comparator.comparing(GroceryItem::estimatedPrice).reversed())
                .limit(COST_DRIVERS).toList();
        return new BudgetStatus(estimate, budget.setScale(2, RoundingMode.HALF_UP), shortfall,
                shortfall.signum() == 0, list.isPartial(), drivers);
    }

    /** The weekly budget scaled to a plan of {@code days} days. */
    public static BigDecimal budgetFor(BigDecimal weeklyBudget, int days) {
        return weeklyBudget.multiply(BigDecimal.valueOf(days)).divide(BigDecimal.valueOf(7), 2, RoundingMode.HALF_UP);
    }

    /**
     * Cost of buying what the meals need beyond the pantry. Stock is used up meal by meal in
     * date order, so one bag of rice is not counted as free for every meal that uses it.
     * Ingredients without a price are counted, not guessed.
     */
    public CostEstimate estimateCost(List<PlannedMeal> meals, Pantry pantry) {
        Pantry remaining = pantry.copy();
        CostEstimate total = CostEstimate.ZERO;
        for (PlannedMeal meal : meals.stream().sorted(Comparator.comparing(PlannedMeal::date)).toList()) {
            double scale = (double) meal.servings() / meal.recipe().servings();
            for (IngredientRequirement line : meal.recipe().ingredients()) {
                if (line.optional()) {
                    continue;
                }
                Quantity needed = new Quantity(line.quantity().amount() * scale, line.quantity().unit());
                total = add(total, line,
                        remaining.take(new IngredientRequirement(line.ingredient(), needed, false), meal.date()));
            }
        }
        return total;
    }

    /**
     * Cost of buying what is missing for {@code servings} portions of a recipe on a date.
     * Optional ingredients are left out; ingredients without a price are counted.
     */
    public CostEstimate estimateRecipeCost(Recipe recipe, int servings, Pantry pantry, LocalDate on) {
        if (servings < 0) {
            throw new IllegalArgumentException("servings cannot be negative");
        }
        double scale = (double) servings / recipe.servings();
        CostEstimate total = CostEstimate.ZERO;
        for (IngredientRequirement line : recipe.ingredients()) {
            if (line.optional()) {
                continue;
            }
            Quantity needed = new Quantity(line.quantity().amount() * scale, line.quantity().unit());
            total = add(total, line, pantry.shortfall(new IngredientRequirement(line.ingredient(), needed, false), on));
        }
        return total;
    }

    private CostEstimate add(CostEstimate total, IngredientRequirement line, Quantity toBuy) {
        if (toBuy.isZero()) {
            return total;
        }
        Optional<BigDecimal> price = priceSource.estimatePrice(line.ingredient(), toBuy);
        return price.map(total::plus).orElseGet(total::plusUnpriced);
    }
}
