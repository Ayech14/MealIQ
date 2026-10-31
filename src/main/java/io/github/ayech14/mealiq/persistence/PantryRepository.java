package io.github.ayech14.mealiq.persistence;

import io.github.ayech14.mealiq.domain.Pantry;
import java.util.UUID;

public interface PantryRepository {

    /** The profile's pantry, or a new empty one if none is stored yet. */
    Pantry findByProfileId(UUID profileId);

    void save(Pantry pantry);
}
