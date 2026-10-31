package io.github.ayech14.mealiq.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

/** A multi-day plan of meals. It is the receiver that plan-change commands act on (D03). */
public final class MealPlan {

    private final UUID id;
    private final UUID profileId;
    private final LocalDate startDate;
    private final int days;
    private PlanStatus status;
    private final List<PlannedMeal> meals;

    public MealPlan(UUID id, UUID profileId, LocalDate startDate, int days, PlanStatus status, List<PlannedMeal> meals) {
        this.id = Objects.requireNonNull(id, "id");
        this.profileId = Objects.requireNonNull(profileId, "profileId");
        this.startDate = Objects.requireNonNull(startDate, "startDate");
        if (days <= 0) {
            throw new IllegalArgumentException("A plan must cover at least one day");
        }
        this.days = days;
        this.status = Objects.requireNonNull(status, "status");
        this.meals = new ArrayList<>(meals == null ? List.of() : meals);
    }

    public MealPlan(UUID profileId, LocalDate startDate, int days, List<PlannedMeal> meals) {
        this(UUID.randomUUID(), profileId, startDate, days, PlanStatus.DRAFT, meals);
    }

    public List<PlannedMeal> mealsOn(LocalDate date) {
        return meals.stream().filter(m -> m.date().equals(date)).toList();
    }

    public PlannedMeal findMeal(UUID mealId) {
        return meals.stream()
                .filter(m -> m.id().equals(mealId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Meal " + mealId + " is not in plan " + id));
    }

    /** Puts a different recipe in a meal slot and returns the recipe it replaced. */
    public Recipe replaceMeal(UUID mealId, Recipe recipe) {
        PlannedMeal meal = findMeal(mealId);
        Recipe previous = meal.recipe();
        meal.recipe(recipe);
        return previous;
    }

    public LocalDate endDate() {
        return startDate.plusDays(days - 1L);
    }

    public UUID id() {
        return id;
    }

    public UUID profileId() {
        return profileId;
    }

    public LocalDate startDate() {
        return startDate;
    }

    public int days() {
        return days;
    }

    public PlanStatus status() {
        return status;
    }

    public void status(PlanStatus value) {
        status = Objects.requireNonNull(value);
    }

    /** Read-only view of the meals. */
    public List<PlannedMeal> meals() {
        return Collections.unmodifiableList(meals);
    }
}
