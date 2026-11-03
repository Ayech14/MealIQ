package io.github.ayech14.mealiq.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * What triggered a plan impact: the event type and, for a pantry change, the ingredient.
 * Only the cause is stored; the impact itself is recomputed from the current plan, profile
 * and pantry, so it disappears once the problem is gone (F13).
 */
public record ImpactCause(ChangeEventType type, UUID ingredientId) {

    public ImpactCause {
        Objects.requireNonNull(type, "type");
    }
}
