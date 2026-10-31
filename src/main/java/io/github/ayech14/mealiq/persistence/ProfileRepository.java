package io.github.ayech14.mealiq.persistence;

import io.github.ayech14.mealiq.domain.UserProfile;
import java.util.Optional;
import java.util.UUID;

public interface ProfileRepository {

    Optional<UserProfile> findById(UUID profileId);

    void save(UserProfile profile);
}
