package io.github.ayech14.mealiq.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A cost from known prices only. With unpriced items it is a lower bound: the real cost is
 * at least {@code known}, never estimated beyond what the prices say (F12).
 */
public record CostEstimate(BigDecimal known, int pricedItems, int unpricedItems) {

    public static final CostEstimate ZERO = new CostEstimate(BigDecimal.ZERO, 0, 0);

    public CostEstimate {
        Objects.requireNonNull(known, "known");
        known = known.setScale(2, RoundingMode.HALF_UP);
        if (known.signum() < 0 || pricedItems < 0 || unpricedItems < 0) {
            throw new IllegalArgumentException("A cost estimate cannot be negative");
        }
    }

    public boolean complete() {
        return unpricedItems == 0;
    }

    /** Share of the items to buy that have a price; 1 when there is nothing to buy. */
    public double pricedShare() {
        int items = pricedItems + unpricedItems;
        return items == 0 ? 1 : (double) pricedItems / items;
    }

    public CostEstimate plus(BigDecimal price) {
        return new CostEstimate(known.add(price), pricedItems + 1, unpricedItems);
    }

    public CostEstimate plusUnpriced() {
        return new CostEstimate(known, pricedItems, unpricedItems + 1);
    }

    /** "$4.20", or "$4.20+" when some prices are unknown. */
    @Override
    public String toString() {
        return "$" + known + (complete() ? "" : "+");
    }
}
