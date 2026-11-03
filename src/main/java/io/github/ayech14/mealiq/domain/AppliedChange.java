package io.github.ayech14.mealiq.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A confirmed plan change as stored for undo: what it was and every meal it swapped. */
public record AppliedChange(UUID changeId, ChangeType type, String description, List<MealSwap> swaps) {

    public AppliedChange {
        Objects.requireNonNull(changeId, "changeId");
        Objects.requireNonNull(type, "type");
        swaps = List.copyOf(swaps);
    }
}
