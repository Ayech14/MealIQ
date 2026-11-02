package io.github.ayech14.mealiq.application;

import io.github.ayech14.mealiq.config.AppConfig;
import io.github.ayech14.mealiq.contracts.AgentResponse;
import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.domain.Pantry;
import io.github.ayech14.mealiq.domain.PantryItem;
import io.github.ayech14.mealiq.domain.ResponseStatus;
import io.github.ayech14.mealiq.domain.UserProfile;
import io.github.ayech14.mealiq.persistence.IngredientRepository;
import io.github.ayech14.mealiq.persistence.jdbc.Database;
import io.github.ayech14.mealiq.persistence.jdbc.JdbcIngredientRepository;
import io.github.ayech14.mealiq.persistence.jdbc.JdbcPantryRepository;
import io.github.ayech14.mealiq.persistence.jdbc.JdbcProfileRepository;
import io.github.ayech14.mealiq.services.NotFoundException;
import io.github.ayech14.mealiq.services.PantryService;
import io.github.ayech14.mealiq.services.ProfileService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The single application boundary shared by the GUI and the CLI (Facade, D02). Each method
 * delegates to the services; interfaces only collect input and show results, so both
 * reach the same rules and give the same outcome.
 *
 * <p>Operations are added as features are implemented; this version covers F01 (profile)
 * and F02 (pantry).
 */
public class MealPlanningFacade {

    private final ProfileService profileService;
    private final PantryService pantryService;
    private final IngredientRepository ingredientRepository;

    public MealPlanningFacade(ProfileService profileService, PantryService pantryService,
                              IngredientRepository ingredientRepository) {
        this.profileService = profileService;
        this.pantryService = pantryService;
        this.ingredientRepository = ingredientRepository;
    }

    /** Wires the services to the PostgreSQL repositories configured in {@code .env}. Opens no connection yet. */
    public static MealPlanningFacade fromConfig(AppConfig config) {
        Database database = Database.from(config);
        JdbcIngredientRepository ingredients = new JdbcIngredientRepository(database);
        return new MealPlanningFacade(
                new ProfileService(new JdbcProfileRepository(database), ingredients),
                new PantryService(new JdbcPantryRepository(database), ingredients),
                ingredients);
    }

    /**
     * The ID of a local profile, derived from its name, so a person can pick a profile by
     * name ("default", "alex") without storing or typing UUIDs.
     */
    public static UUID localProfileId(String name) {
        String key = "mealiq:profile:" + name.strip().toLowerCase(Locale.ROOT);
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    // ---- F01 user goals and preferences ----

    /** @throws NotFoundException if the profile was never saved */
    public UserProfile getProfile(UUID profileId) {
        return profileService.getProfile(profileId);
    }

    /** Validates and saves the profile; invalid fields throw InvalidInputException and nothing is saved. */
    public UserProfile updateProfile(UserProfile profile) {
        return profileService.updateProfile(profile);
    }

    /** Preferred foods that the profile's own allergies or diet rule out (UC01 3b). */
    public List<String> profileWarnings(UserProfile profile) {
        return profileService.preferenceWarnings(profile);
    }

    // ---- F02 pantry inventory ----

    public Pantry getPantry(UUID profileId) {
        return pantryService.getPantry(profileId);
    }

    /** Builds a pantry item from interface input; every bad field is reported together. */
    public PantryItem pantryItem(String ingredientName, double amount, String unit, LocalDate expiryDate) {
        return pantryService.makeItem(ingredientName, amount, unit, expiryDate);
    }

    /** Adds the item or replaces its quantity (UC02). */
    public AgentResponse updatePantry(UUID profileId, PantryItem item) {
        pantryService.updateItem(profileId, item);
        return AgentResponse.builder(ResponseStatus.SUCCESS)
                .rationale("Pantry updated: " + item.ingredient().name() + " " + item.quantity() + ".")
                .build();
    }

    /** @throws NotFoundException if no pantry item has that ingredient name */
    public Pantry removePantryItem(UUID profileId, String ingredientName) {
        String name = ingredientName.strip().toLowerCase(Locale.ROOT);
        PantryItem item = getPantry(profileId).items().values().stream()
                .filter(i -> i.ingredient().name().equals(name))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("'" + ingredientName + "' is not in the pantry"));
        return pantryService.removeItem(profileId, item.ingredient().id());
    }

    /** Items kept in the pantry but not counted as available stock. */
    public List<PantryItem> expiredPantryItems(UUID profileId) {
        return pantryService.expiredItems(profileId);
    }

    /** Catalogue ingredients whose name contains {@code text} (all of them when blank), by name. */
    public List<Ingredient> searchIngredients(String text) {
        String needle = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        return ingredientRepository.findAll().stream().filter(i -> i.name().contains(needle)).toList();
    }
}
