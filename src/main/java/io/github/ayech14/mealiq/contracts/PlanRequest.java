package io.github.ayech14.mealiq.contracts;

import io.github.ayech14.mealiq.domain.PlanningMode;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** A request to generate a meal plan. {@code instruction} is optional free text from the user. */
public record PlanRequest(UUID profileId, LocalDate startDate, int days, PlanningMode mode, String instruction) {

    public static final int MAX_DAYS = 14;

    public PlanRequest {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(startDate, "startDate");
        Objects.requireNonNull(mode, "mode");
        if (days < 1 || days > MAX_DAYS) {
            throw new IllegalArgumentException("days must be between 1 and " + MAX_DAYS);
        }
    }

    public static PlanRequest week(UUID profileId, LocalDate startDate, PlanningMode mode) {
        return new PlanRequest(profileId, startDate, 7, mode, null);
    }
}
