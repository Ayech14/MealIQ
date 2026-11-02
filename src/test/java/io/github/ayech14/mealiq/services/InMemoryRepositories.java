package io.github.ayech14.mealiq.services;

import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.domain.Pantry;
import io.github.ayech14.mealiq.domain.UserProfile;
import io.github.ayech14.mealiq.events.PlanImpactObserver;
import io.github.ayech14.mealiq.events.PlanRelevantChange;
import io.github.ayech14.mealiq.persistence.IngredientRepository;
import io.github.ayech14.mealiq.persistence.PantryRepository;
import io.github.ayech14.mealiq.persistence.ProfileRepository;
import io.github.ayech14.mealiq.persistence.catalogue.CatalogueLoader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory repository fakes for service, facade and CLI tests (D08). Saved objects are copied, like a
 * database round trip, so a test cannot pass by mutating shared objects.
 */
public final class InMemoryRepositories {

    private InMemoryRepositories() {
    }

    public static final class Profiles implements ProfileRepository {
        final Map<UUID, UserProfile> rows = new HashMap<>();

        @Override
        public Optional<UserProfile> findById(UUID profileId) {
            return Optional.ofNullable(rows.get(profileId)).map(UserProfile::copy);
        }

        @Override
        public void save(UserProfile profile) {
            rows.put(profile.id(), profile.copy());
        }
    }

    public static final class Pantries implements PantryRepository {
        final Map<UUID, Pantry> rows = new HashMap<>();

        @Override
        public Pantry findByProfileId(UUID profileId) {
            Pantry stored = rows.get(profileId);
            return stored == null ? Pantry.emptyFor(profileId) : copy(stored);
        }

        @Override
        public void save(Pantry pantry) {
            rows.put(pantry.profileId(), copy(pantry));
        }

        private static Pantry copy(Pantry pantry) {
            Pantry copy = new Pantry(pantry.id(), pantry.profileId());
            pantry.items().values().forEach(copy::updateItem); // items are immutable records
            return copy;
        }
    }

    /** Read-only view of the packaged USDA catalogue. */
    public static final class Catalogue implements IngredientRepository {
        private final Map<String, Ingredient> byName = new HashMap<>();
        private final Map<UUID, Ingredient> byId = new HashMap<>();

        public Catalogue() {
            for (CatalogueLoader.Entry entry : CatalogueLoader.read()) {
                byName.put(entry.ingredient().name(), entry.ingredient());
                byId.put(entry.ingredient().id(), entry.ingredient());
            }
        }

        @Override
        public Optional<Ingredient> findById(UUID ingredientId) {
            return Optional.ofNullable(byId.get(ingredientId));
        }

        @Override
        public Optional<Ingredient> findByName(String name) {
            return Optional.ofNullable(byName.get(name.strip().toLowerCase(Locale.ROOT)));
        }

        @Override
        public List<Ingredient> findSubstitutes(Ingredient ingredient) {
            return List.of();
        }

        @Override
        public List<Ingredient> findAll() {
            return byName.values().stream().sorted(Comparator.comparing(Ingredient::name)).toList();
        }

        @Override
        public void save(Ingredient ingredient) {
            throw new UnsupportedOperationException("read-only catalogue");
        }

        @Override
        public void saveSubstitutes(Ingredient ingredient, List<Ingredient> substitutes) {
            throw new UnsupportedOperationException("read-only catalogue");
        }
    }

    public static final class RecordingObserver implements PlanImpactObserver {
        public final List<PlanRelevantChange> events = new ArrayList<>();

        @Override
        public void onPlanRelevantChange(PlanRelevantChange event) {
            events.add(event);
        }
    }
}
