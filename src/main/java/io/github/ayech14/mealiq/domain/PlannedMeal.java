package io.github.ayech14.mealiq.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** One meal slot in a plan. The recipe can be swapped by a plan-change command. */
public final class PlannedMeal {

    private final UUID id;
    private final LocalDate date;
    private final MealType mealType;
    private Recipe recipe;
    private final int servings;

    public PlannedMeal(UUID id, LocalDate date, MealType mealType, Recipe recipe, int servings) {
        this.id = Objects.requireNonNull(id, "id");
        this.date = Objects.requireNonNull(date, "date");
        this.mealType = Objects.requireNonNull(mealType, "mealType");
        this.recipe = Objects.requireNonNull(recipe, "recipe");
        if (servings <= 0) {
            throw new IllegalArgumentException("A planned meal needs at least one serving");
        }
        this.servings = servings;
    }

    public PlannedMeal(LocalDate date, MealType mealType, Recipe recipe, int servings) {
        this(UUID.randomUUID(), date, mealType, recipe, servings);
    }

    public UUID id() {
        return id;
    }

    public LocalDate date() {
        return date;
    }

    public MealType mealType() {
        return mealType;
    }

    public Recipe recipe() {
        return recipe;
    }

    void recipe(Recipe value) {
        recipe = Objects.requireNonNull(value);
    }

    public int servings() {
        return servings;
    }

    @Override
    public String toString() {
        return date + " " + mealType + ": " + recipe.name() + " x" + servings;
    }
}
