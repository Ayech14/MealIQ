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
