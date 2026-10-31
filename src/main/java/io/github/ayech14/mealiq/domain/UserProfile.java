package io.github.ayech14.mealiq.domain;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The user's goals and constraints (F01). Allergies and dietary restrictions are hard
 * constraints; likes and dislikes are soft (D04). Range validation is done by
 * ProfileService so it can report one message per field.
 */
public final class UserProfile {

    private final UUID id;
    private WeightGoal goal = WeightGoal.MAINTAIN;
    private Integer dailyCalorieTarget;
    private Integer dailyProteinTargetG;
    private Set<String> dietaryRestrictions = Set.of();
    private Set<String> allergies = Set.of();
    private Set<String> dislikedFoods = Set.of();
    private Set<String> preferredFoods = Set.of();
    private int mealsPerDay = 3;
    private BigDecimal weeklyBudget = BigDecimal.ZERO;
    private Map<DayOfWeek, Integer> maxCookMinutes = Map.of();

    public UserProfile(UUID id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    public UserProfile() {
        this(UUID.randomUUID());
    }

    /** Independent copy, used to compare a profile before and after an edit. */
    public UserProfile copy() {
        UserProfile copy = new UserProfile(id);
        copy.goal = goal;
        copy.dailyCalorieTarget = dailyCalorieTarget;
        copy.dailyProteinTargetG = dailyProteinTargetG;
        copy.dietaryRestrictions = dietaryRestrictions;
        copy.allergies = allergies;
        copy.dislikedFoods = dislikedFoods;
        copy.preferredFoods = preferredFoods;
        copy.mealsPerDay = mealsPerDay;
        copy.weeklyBudget = weeklyBudget;
        copy.maxCookMinutes = maxCookMinutes;
        return copy;
    }

    /** Hard-constraint check only: allergies and dietary restrictions. Dislikes are not checked here. */
    public boolean isAllowed(Ingredient ingredient) {
        if (ingredient.allergens().stream().anyMatch(allergies::contains)) {
            return false;
        }
        for (String restriction : dietaryRestrictions) {
            DietRules.Rule rule = DietRules.ruleFor(restriction);
            if (rule == null) {
                continue;
            }
            if (rule.excludedCategories().contains(ingredient.category())
                    || ingredient.allergens().stream().anyMatch(rule.excludedAllergens()::contains)) {
                return false;
            }
        }
        return true;
    }

    public OptionalInt maxMinutesFor(DayOfWeek day) {
        Integer minutes = maxCookMinutes.get(day);
        return minutes == null ? OptionalInt.empty() : OptionalInt.of(minutes);
    }

    public UUID id() {
        return id;
    }

    public WeightGoal goal() {
        return goal;
    }

    public UserProfile goal(WeightGoal value) {
        goal = Objects.requireNonNull(value);
        return this;
    }

    public Integer dailyCalorieTarget() {
        return dailyCalorieTarget;
    }

    public UserProfile dailyCalorieTarget(Integer value) {
        dailyCalorieTarget = value;
        return this;
    }

    public Integer dailyProteinTargetG() {
        return dailyProteinTargetG;
    }

    public UserProfile dailyProteinTargetG(Integer value) {
        dailyProteinTargetG = value;
        return this;
    }

    public Set<String> dietaryRestrictions() {
        return dietaryRestrictions;
    }

    public UserProfile dietaryRestrictions(Set<String> values) {
        dietaryRestrictions = tags(values);
        return this;
    }

    public Set<String> allergies() {
        return allergies;
    }

    public UserProfile allergies(Set<String> values) {
        allergies = tags(values);
        return this;
    }

    public Set<String> dislikedFoods() {
        return dislikedFoods;
    }

    public UserProfile dislikedFoods(Set<String> values) {
        dislikedFoods = names(values);
        return this;
    }

    public Set<String> preferredFoods() {
        return preferredFoods;
    }

    public UserProfile preferredFoods(Set<String> values) {
        preferredFoods = names(values);
        return this;
    }

    public int mealsPerDay() {
        return mealsPerDay;
    }

    public UserProfile mealsPerDay(int value) {
        mealsPerDay = value;
        return this;
    }

    public BigDecimal weeklyBudget() {
        return weeklyBudget;
    }

    public UserProfile weeklyBudget(BigDecimal value) {
        weeklyBudget = Objects.requireNonNull(value);
        return this;
    }

    public Map<DayOfWeek, Integer> maxCookMinutes() {
        return maxCookMinutes;
    }

    public UserProfile maxCookMinutes(Map<DayOfWeek, Integer> values) {
        maxCookMinutes = values == null || values.isEmpty() ? Map.of() : Map.copyOf(new EnumMap<>(values));
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserProfile other)) {
            return false;
        }
        return id.equals(other.id) && goal == other.goal
                && Objects.equals(dailyCalorieTarget, other.dailyCalorieTarget)
                && Objects.equals(dailyProteinTargetG, other.dailyProteinTargetG)
                && dietaryRestrictions.equals(other.dietaryRestrictions) && allergies.equals(other.allergies)
                && dislikedFoods.equals(other.dislikedFoods) && preferredFoods.equals(other.preferredFoods)
                && mealsPerDay == other.mealsPerDay && weeklyBudget.compareTo(other.weeklyBudget) == 0
                && maxCookMinutes.equals(other.maxCookMinutes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, goal, mealsPerDay, allergies, dietaryRestrictions);
    }

    @Override
    public String toString() {
        return "UserProfile[" + id + ", goal=" + goal + ", mealsPerDay=" + mealsPerDay
                + ", weeklyBudget=" + weeklyBudget + ", restrictions=" + dietaryRestrictions
                + ", allergies=" + allergies + "]";
    }

    private static Set<String> tags(Set<String> values) {
        return values == null ? Set.of()
                : values.stream().map(DietRules::normalizeTag).collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> names(Set<String> values) {
        return values == null ? Set.of()
                : values.stream().map(v -> v.strip().toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }
}
