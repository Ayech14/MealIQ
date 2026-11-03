package io.github.ayech14.mealiq.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ayech14.mealiq.contracts.ValidationResult;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.UserProfile;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConstraintValidatorTest {

    private final InMemoryRepositories.Catalogue catalogue = new InMemoryRepositories.Catalogue();
    private final InMemoryRepositories.Recipes recipes = new InMemoryRepositories.Recipes(catalogue);
    private final ConstraintValidator validator = new ConstraintValidator();

    private Recipe recipe(String name) {
        return recipes.search(io.github.ayech14.mealiq.contracts.RecipeQuery.any()).stream()
                .filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void allowedRecipeIsValid() {
        ValidationResult result = validator.validateRestrictions(recipe("Lentil soup"),
                new UserProfile().dietaryRestrictions(Set.of("vegan")).allergies(Set.of("peanut")));
        assertTrue(result.valid());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void allergyAndDietBreachesNameTheIngredientAndRule() {
        ValidationResult result = validator.validateRestrictions(recipe("Peanut butter banana toast"),
                new UserProfile().allergies(Set.of("peanut")).dietaryRestrictions(Set.of("gluten-free")));
        assertFalse(result.valid());
        assertEquals(List.of(
                "Peanut butter banana toast contains whole wheat bread (not gluten-free).",
                "Peanut butter banana toast contains peanut butter (allergy: peanut)."), result.violations());
    }

    @Test
    void oneIngredientCanBreakSeveralRules() {
        ValidationResult result = validator.validateRestrictions(recipe("Tuna salad sandwich"),
                new UserProfile().allergies(Set.of("fish")).dietaryRestrictions(Set.of("vegetarian")));
        assertTrue(result.violations().contains(
                "Tuna salad sandwich contains canned tuna (allergy: fish; not vegetarian)."), result.toString());
    }

    @Test
    void optionalIngredientOnlyWarns() {
        ValidationResult result = validator.validateRestrictions(recipe("Cottage cheese fruit plate"),
                new UserProfile().allergies(Set.of("tree-nut")));
        assertTrue(result.valid());
        assertEquals(List.of("Leave out optional walnuts (allergy: tree-nut)."), result.warnings());
    }

    @Test
    void dislikeIsASoftWarning() {
        ValidationResult result = validator.validateRestrictions(recipe("Lentil soup"),
                new UserProfile().dislikedFoods(Set.of("Celery")));
        assertTrue(result.valid());
        assertEquals(List.of("Lentil soup uses celery, which you dislike."), result.warnings());
    }
}
