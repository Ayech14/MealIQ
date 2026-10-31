package io.github.ayech14.mealiq.persistence;

import io.github.ayech14.mealiq.domain.MealPlan;
import java.util.Optional;
import java.util.UUID;

public interface MealPlanRepository {

    Optional<MealPlan> findById(UUID planId);

    /** The most recent ACTIVE plan of a profile. */
    Optional<MealPlan> findActiveByProfileId(UUID profileId);

    void save(MealPlan plan);
}
