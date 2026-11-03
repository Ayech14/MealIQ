package io.github.ayech14.mealiq.services;

import io.github.ayech14.mealiq.contracts.RecipeQuery;
import io.github.ayech14.mealiq.domain.GroceryList;
import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.domain.MealPlan;
import io.github.ayech14.mealiq.domain.Pantry;
import io.github.ayech14.mealiq.domain.PlanStatus;
import io.github.ayech14.mealiq.domain.PlannedMeal;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.UserProfile;
import io.github.ayech14.mealiq.events.PlanImpactObserver;
import io.github.ayech14.mealiq.events.PlanRelevantChange;
import io.github.ayech14.mealiq.persistence.GroceryListRepository;
import io.github.ayech14.mealiq.persistence.IngredientRepository;
import io.github.ayech14.mealiq.persistence.MealPlanRepository;
import io.github.ayech14.mealiq.persistence.PantryRepository;
import io.github.ayech14.mealiq.persistence.ProfileRepository;
import io.github.ayech14.mealiq.persistence.RecipeRepository;
import io.github.ayech14.mealiq.persistence.catalogue.CatalogueLoader;
import io.github.ayech14.mealiq.persistence.catalogue.RecipeCatalogueLoader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

        private final Map<UUID, List<String>> substituteNames = new HashMap<>();

        public Catalogue() {
            for (CatalogueLoader.Entry entry : CatalogueLoader.read()) {
                byName.put(entry.ingredient().name(), entry.ingredient());
                byId.put(entry.ingredient().id(), entry.ingredient());
                substituteNames.put(entry.ingredient().id(), entry.substituteNames());
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
            return substituteNames.getOrDefault(ingredient.id(), List.of()).stream().map(byName::get).toList();
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

    /** The packaged seed recipes, searched with the same rules as the JDBC repository. */
    public static final class Recipes implements RecipeRepository {
        private final Map<UUID, Recipe> byId = new LinkedHashMap<>();

        public Recipes(IngredientRepository catalogue) {
            RecipeCatalogueLoader.read(catalogue::findByName).forEach(this::save);
        }

        @Override
        public Optional<Recipe> findById(UUID recipeId) {
            return Optional.ofNullable(byId.get(recipeId));
        }

        @Override
        public List<Recipe> search(RecipeQuery query) {
            return byId.values().stream().filter(query::matches).sorted(Comparator.comparing(Recipe::name)).toList();
        }

        @Override
        public void save(Recipe recipe) {
            byId.put(recipe.id(), recipe); // recipes are immutable records
        }
    }

    /** Meal plans, copied on save and load like a database round trip. */
    public static final class MealPlans implements MealPlanRepository {
        public final Map<UUID, MealPlan> rows = new LinkedHashMap<>();

        @Override
        public Optional<MealPlan> findById(UUID planId) {
            return Optional.ofNullable(rows.get(planId)).map(MealPlans::copy);
        }

        @Override
        public Optional<MealPlan> findActiveByProfileId(UUID profileId) {
            return rows.values().stream()
                    .filter(p -> p.profileId().equals(profileId) && p.status() == PlanStatus.ACTIVE)
                    .max(Comparator.comparing(MealPlan::startDate)).map(MealPlans::copy);
        }

        @Override
        public void save(MealPlan plan) {
            rows.put(plan.id(), copy(plan));
        }

        private static MealPlan copy(MealPlan plan) {
            return new MealPlan(plan.id(), plan.profileId(), plan.startDate(), plan.days(), plan.status(),
                    plan.meals().stream().map(m -> new PlannedMeal(m.id(), m.date(), m.mealType(), m.recipe(),
                            m.servings())).toList());
        }
    }

    /** Grocery lists, one per plan as in the database. */
    public static final class GroceryLists implements GroceryListRepository {
        public final Map<UUID, GroceryList> byPlan = new LinkedHashMap<>();

        @Override
        public Optional<GroceryList> findByPlanId(UUID planId) {
            return Optional.ofNullable(byPlan.get(planId)).map(GroceryLists::copy);
        }

        @Override
        public void save(GroceryList list) {
            byPlan.put(list.planId(), copy(list));
        }

        private static GroceryList copy(GroceryList list) {
            return new GroceryList(list.id(), list.planId(), list.items(), list.estimatedCost(), list.isStale());
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
