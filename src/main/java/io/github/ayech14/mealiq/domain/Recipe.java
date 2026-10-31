package io.github.ayech14.mealiq.domain;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A recipe that yields {@code servings} portions. Immutable: changes such as an ingredient
 * substitution produce a new recipe, so the original stays available for undo.
 */
public record Recipe(
        UUID id,
        String name,
        Set<MealType> mealTypes,
        int prepMinutes,
        int cookMinutes,
        int servings,
        List<IngredientRequirement> ingredients,
        Set<String> tags,
        RecipeSource source,
        List<String> instructions) {

    public Recipe {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(source, "source");
        if (prepMinutes < 0 || cookMinutes < 0) {
            throw new IllegalArgumentException("Recipe times cannot be negative");
        }
        if (servings <= 0) {
            throw new IllegalArgumentException("A recipe must yield at least one serving");
        }
        mealTypes = mealTypes == null || mealTypes.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(mealTypes));
        ingredients = ingredients == null ? List.of() : List.copyOf(ingredients);
        tags = tags == null ? Set.of() : Set.copyOf(tags);
        instructions = instructions == null ? List.of() : List.copyOf(instructions);
    }

    public int totalMinutes() {
        return prepMinutes + cookMinutes;
    }

    public Recipe withIngredients(List<IngredientRequirement> newIngredients) {
        return new Recipe(id, name, mealTypes, prepMinutes, cookMinutes, servings, newIngredients, tags, source,
                instructions);
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** Readable construction for seed data and tests. */
    public static final class Builder {
        private UUID id = UUID.randomUUID();
        private final String name;
        private final Set<MealType> mealTypes = EnumSet.noneOf(MealType.class);
        private int prepMinutes;
        private int cookMinutes;
        private int servings = 1;
        private final List<IngredientRequirement> ingredients = new ArrayList<>();
        private Set<String> tags = Set.of();
        private RecipeSource source = RecipeSource.CURATED;
        private List<String> instructions = List.of();

        private Builder(String name) {
            this.name = name;
        }

        public Builder id(UUID value) {
            id = value;
            return this;
        }

        public Builder mealTypes(MealType... values) {
            mealTypes.addAll(List.of(values));
            return this;
        }

        public Builder minutes(int prep, int cook) {
            prepMinutes = prep;
            cookMinutes = cook;
            return this;
        }

        public Builder servings(int value) {
            servings = value;
            return this;
        }

        public Builder ingredient(Ingredient ingredient, double amount, String unit) {
            ingredients.add(IngredientRequirement.of(ingredient, amount, unit));
            return this;
        }

        public Builder optionalIngredient(Ingredient ingredient, double amount, String unit) {
            ingredients.add(new IngredientRequirement(ingredient, Quantity.of(amount, unit), true));
            return this;
        }

        public Builder tags(String... values) {
            tags = Set.of(values);
            return this;
        }

        public Builder source(RecipeSource value) {
            source = value;
            return this;
        }

        public Builder instructions(String... steps) {
            instructions = List.of(steps);
            return this;
        }

        public Recipe build() {
            return new Recipe(id, name, mealTypes, prepMinutes, cookMinutes, servings, ingredients, tags, source,
                    instructions);
        }
    }
}
