package io.github.ayech14.mealiq.domain;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.UUID;

/** The user's inventory: one entry per ingredient; updating an ingredient replaces its entry. */
public final class Pantry {

    private final UUID id;
    private final UUID profileId;
    private final Map<UUID, PantryItem> items = new LinkedHashMap<>();

    public Pantry(UUID id, UUID profileId) {
        this.id = Objects.requireNonNull(id, "id");
        this.profileId = Objects.requireNonNull(profileId, "profileId");
    }

    /** A new, empty pantry for a profile. */
    public static Pantry emptyFor(UUID profileId) {
        return new Pantry(UUID.randomUUID(), profileId);
    }

    /** Usable stock on a date. Expired items stay listed but count as zero (UC02 3b). */
    public Quantity availableQuantity(Ingredient ingredient, LocalDate on) {
        PantryItem item = items.get(ingredient.id());
        if (item == null) {
            return new Quantity(0, Unit.G);
        }
        return item.isExpired(on) ? new Quantity(0, item.quantity().unit()) : item.quantity();
    }

    /**
     * How much of a requirement the pantry cannot cover on a date, in the requirement's unit.
     * Stock in another dimension (6 eggs against 100 g of egg) is compared by weight; when
     * that is impossible the stock is not counted, so the result never overstates what is at home.
     */
    public Quantity shortfall(IngredientRequirement requirement, LocalDate on) {
        Quantity needed = requirement.quantity();
        Quantity available = availableQuantity(requirement.ingredient(), on);
        if (available.isCompatible(needed.unit())) {
            return needed.subtract(available);
        }
        Ingredient ingredient = requirement.ingredient();
        OptionalDouble neededGrams = ingredient.toGrams(needed);
        OptionalDouble availableGrams = ingredient.toGrams(available);
        if (neededGrams.isEmpty() || availableGrams.isEmpty() || neededGrams.getAsDouble() == 0) {
            return needed;
        }
        double missingShare = Math.max(0, 1 - availableGrams.getAsDouble() / neededGrams.getAsDouble());
        return new Quantity(needed.amount() * missingShare, needed.unit());
    }

    /**
     * Uses stock for a requirement and returns what is still missing (see {@link #shortfall}).
     * The item's quantity goes down by what was used, in the item's own unit.
     */
    public Quantity take(IngredientRequirement requirement, LocalDate on) {
        Quantity missing = shortfall(requirement, on);
        PantryItem item = items.get(requirement.ingredient().id());
        Quantity needed = requirement.quantity();
        if (item == null || missing.amount() >= needed.amount()) {
            return missing;
        }
        Quantity used = new Quantity(needed.amount() - missing.amount(), needed.unit());
        Quantity left;
        if (item.quantity().isCompatible(used.unit())) {
            left = item.quantity().subtract(used);
        } else {
            // Different dimensions were compared by weight in shortfall(), so both weights exist.
            double usedGrams = requirement.ingredient().toGrams(used).orElseThrow();
            double stockGrams = requirement.ingredient().toGrams(item.quantity()).orElseThrow();
            left = new Quantity(item.quantity().amount() * Math.max(0, 1 - usedGrams / stockGrams),
                    item.quantity().unit());
        }
        items.put(item.ingredient().id(), new PantryItem(item.ingredient(), left, item.expiryDate()));
        return missing;
    }

    /** An independent copy, for working out what a plan would use without changing the real pantry. */
    public Pantry copy() {
        Pantry copy = new Pantry(id, profileId);
        copy.items.putAll(items); // items are immutable records
        return copy;
    }

    public void updateItem(PantryItem item) {
        items.put(item.ingredient().id(), item);
    }

    public void removeItem(UUID ingredientId) {
        items.remove(ingredientId);
    }

    /** Read-only view, in insertion order. */
    public Map<UUID, PantryItem> items() {
        return Collections.unmodifiableMap(items);
    }

    public UUID id() {
        return id;
    }

    public UUID profileId() {
        return profileId;
    }
}
