package io.github.ayech14.mealiq.events;

import io.github.ayech14.mealiq.domain.ChangeEventType;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A saved change that may affect the active plan or grocery list (D06). Details may hold
 * null values, for example no previous quantity when an item is first added.
 */
public record PlanRelevantChange(ChangeEventType type, UUID profileId, Map<String, Object> details,
                                 Instant occurredAt) {

    public PlanRelevantChange {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(profileId, "profileId");
        details = details == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(details));
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public PlanRelevantChange(ChangeEventType type, UUID profileId, Map<String, Object> details) {
        this(type, profileId, details, Instant.now());
    }
}
