package io.github.ayech14.mealiq.services;

import io.github.ayech14.mealiq.domain.ChangeEventType;
import io.github.ayech14.mealiq.domain.DietRules;
import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.domain.UserProfile;
import io.github.ayech14.mealiq.events.ObserverList;
import io.github.ayech14.mealiq.events.PlanImpactObserver;
import io.github.ayech14.mealiq.events.PlanRelevantChange;
import io.github.ayech14.mealiq.events.PlanRelevantChangePublisher;
import io.github.ayech14.mealiq.persistence.IngredientRepository;
import io.github.ayech14.mealiq.persistence.ProfileRepository;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Validates and saves the user's goals and constraints (F01, UC01), and publishes the changes. */
public class ProfileService implements PlanRelevantChangePublisher {

    public static final int MAX_MEALS_PER_DAY = 6;
    public static final int MAX_MINUTES_PER_DAY = 600;
    public static final int MIN_CALORIE_TARGET = 800;
    public static final int MAX_CALORIE_TARGET = 6000;
    public static final int MAX_PROTEIN_TARGET_G = 400;

    /** Every compared field, in a fixed order, so events list changed fields predictably. */
    private static final Map<String, Function<UserProfile, Object>> FIELDS = new LinkedHashMap<>();

    static {
        FIELDS.put("goal", UserProfile::goal);
        FIELDS.put("dailyCalorieTarget", UserProfile::dailyCalorieTarget);
        FIELDS.put("dailyProteinTargetG", UserProfile::dailyProteinTargetG);
        FIELDS.put("dietaryRestrictions", UserProfile::dietaryRestrictions);
        FIELDS.put("allergies", UserProfile::allergies);
        FIELDS.put("dislikedFoods", UserProfile::dislikedFoods);
        FIELDS.put("preferredFoods", UserProfile::preferredFoods);
        FIELDS.put("mealsPerDay", UserProfile::mealsPerDay);
        FIELDS.put("weeklyBudget", p -> p.weeklyBudget().stripTrailingZeros());
        FIELDS.put("maxCookMinutes", UserProfile::maxCookMinutes);
    }

    /** Which event a changed field raises; any other field change is PROFILE_CHANGED. */
    private static final Map<String, ChangeEventType> FIELD_EVENTS = Map.of(
            "weeklyBudget", ChangeEventType.BUDGET_CHANGED,
            "maxCookMinutes", ChangeEventType.TIME_LIMIT_CHANGED);

    private final ProfileRepository profileRepository;
    private final IngredientRepository ingredientRepository;
    private final ObserverList observers = new ObserverList();

    public ProfileService(ProfileRepository profileRepository, IngredientRepository ingredientRepository) {
        this.profileRepository = profileRepository;
        this.ingredientRepository = ingredientRepository;
    }

    public UserProfile getProfile(UUID profileId) {
        return profileRepository.findById(profileId)
                .orElseThrow(() -> new NotFoundException("Profile " + profileId + " not found"));
    }

    /**
     * Validates, saves, then publishes one event per kind of change. Invalid values throw
     * {@link InvalidInputException} and nothing is saved or published (UC01 3a).
     */
    public UserProfile updateProfile(UserProfile profile) {
        Map<String, String> errors = validate(profile);
        if (!errors.isEmpty()) {
            throw new InvalidInputException(errors);
        }
        Optional<UserProfile> previous = profileRepository.findById(profile.id());
        profileRepository.save(profile);
        previous.ifPresent(before -> eventsFor(before, profile).forEach(this::publish));
        return profile;
    }

    /** Field-level messages for out-of-range values and contradictions; empty when valid. */
    public Map<String, String> validate(UserProfile profile) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (profile.mealsPerDay() < 1 || profile.mealsPerDay() > MAX_MEALS_PER_DAY) {
            errors.put("mealsPerDay", "must be between 1 and " + MAX_MEALS_PER_DAY);
        }
        if (profile.weeklyBudget().signum() < 0) {
            errors.put("weeklyBudget", "cannot be negative");
        }
        Integer calories = profile.dailyCalorieTarget();
        if (calories != null && (calories < MIN_CALORIE_TARGET || calories > MAX_CALORIE_TARGET)) {
            errors.put("dailyCalorieTarget",
                    "must be between " + MIN_CALORIE_TARGET + " and " + MAX_CALORIE_TARGET + " kcal");
        }
        Integer protein = profile.dailyProteinTargetG();
        if (protein != null && (protein < 0 || protein > MAX_PROTEIN_TARGET_G)) {
            errors.put("dailyProteinTargetG", "must be between 0 and " + MAX_PROTEIN_TARGET_G + " g");
        }
        List<String> badDays = new ArrayList<>();
        new TreeMap<DayOfWeek, Integer>(profile.maxCookMinutes()).forEach((day, minutes) -> {
            if (minutes < 0 || minutes > MAX_MINUTES_PER_DAY) {
                badDays.add(day.name());
            }
        });
        if (!badDays.isEmpty()) {
            errors.put("maxCookMinutes", String.join(", ", badDays)
                    + ": must be between 0 and " + MAX_MINUTES_PER_DAY + " minutes");
        }
        Set<String> unsupported = profile.dietaryRestrictions().stream()
                .filter(r -> !DietRules.isSupported(r)).collect(Collectors.toCollection(TreeSet::new));
        if (!unsupported.isEmpty()) {
            // A hard constraint we cannot enforce must not be silently accepted (D04).
            errors.put("dietaryRestrictions", "unsupported: " + String.join(", ", unsupported)
                    + "; supported: " + String.join(", ", new TreeSet<>(DietRules.supportedRestrictions())));
        }
        Set<String> both = normalized(profile.preferredFoods());
        both.retainAll(normalized(profile.dislikedFoods()));
        if (!both.isEmpty()) {
            errors.put("preferredFoods", "also listed as disliked: " + String.join(", ", both));
        }
        return errors;
    }

    /**
     * Preferred foods that the profile's hard constraints forbid (UC01 3b). They are flagged,
     * not rejected: the allergy or restriction always wins, and the user resolves the conflict.
     */
    public List<String> preferenceWarnings(UserProfile profile) {
        List<String> warnings = new ArrayList<>();
        for (String name : new TreeSet<>(profile.preferredFoods())) {
            Optional<Ingredient> ingredient = ingredientRepository.findByName(name);
            if (ingredient.isPresent() && !profile.isAllowed(ingredient.get())) {
                warnings.add("Preferred food '" + name
                        + "' conflicts with your allergies or diet and will be avoided.");
            }
        }
        return warnings;
    }

    @Override
    public void subscribe(PlanImpactObserver observer) {
        observers.add(observer);
    }

    @Override
    public void unsubscribe(PlanImpactObserver observer) {
        observers.remove(observer);
    }

    @Override
    public void publish(PlanRelevantChange event) {
        observers.notifyAll(event);
    }

    private static List<PlanRelevantChange> eventsFor(UserProfile before, UserProfile after) {
        Map<ChangeEventType, List<String>> changedByType = new LinkedHashMap<>();
        FIELDS.forEach((name, getter) -> {
            if (!Objects.equals(getter.apply(before), getter.apply(after))) {
                changedByType.computeIfAbsent(FIELD_EVENTS.getOrDefault(name, ChangeEventType.PROFILE_CHANGED),
                        type -> new ArrayList<>()).add(name);
            }
        });
        List<PlanRelevantChange> events = new ArrayList<>();
        changedByType.forEach((type, names) ->
                events.add(new PlanRelevantChange(type, after.id(), Map.of("changedFields", List.copyOf(names)))));
        return events;
    }

    private static Set<String> normalized(Set<String> values) {
        return values.stream().map(DietRules::normalizeTag).collect(Collectors.toCollection(TreeSet::new));
    }
}
