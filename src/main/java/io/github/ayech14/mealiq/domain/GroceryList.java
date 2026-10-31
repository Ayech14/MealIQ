package io.github.ayech14.mealiq.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** The shopping list for a plan. It is marked stale when the plan or pantry changes (D06). */
public final class GroceryList {

    private final UUID id;
    private final UUID planId;
    private final List<GroceryItem> items;
    private BigDecimal estimatedCost;
    private boolean stale;

    public GroceryList(UUID id, UUID planId, List<GroceryItem> items, BigDecimal estimatedCost, boolean stale) {
        this.id = Objects.requireNonNull(id, "id");
        this.planId = Objects.requireNonNull(planId, "planId");
        this.items = new ArrayList<>(items == null ? List.of() : items);
        this.estimatedCost = Objects.requireNonNull(estimatedCost, "estimatedCost");
        this.stale = stale;
    }

    public GroceryList(UUID planId, List<GroceryItem> items) {
        this(UUID.randomUUID(), planId, items, BigDecimal.ZERO, false);
    }

    public void markStale() {
        stale = true;
    }

    /** Sum of known item prices; items without a price are not counted. */
    public BigDecimal total() {
        return items.stream()
                .map(GroceryItem::estimatedPrice)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** True when some item that must be bought has no price estimate. */
    public boolean isPartial() {
        return items.stream().anyMatch(i -> i.estimatedPrice() == null && !i.toBuy().isZero());
    }

    public UUID id() {
        return id;
    }

    public UUID planId() {
        return planId;
    }

    public List<GroceryItem> items() {
        return Collections.unmodifiableList(items);
    }

    public BigDecimal estimatedCost() {
        return estimatedCost;
    }

    public void estimatedCost(BigDecimal value) {
        estimatedCost = Objects.requireNonNull(value);
    }

    public boolean isStale() {
        return stale;
    }
}
