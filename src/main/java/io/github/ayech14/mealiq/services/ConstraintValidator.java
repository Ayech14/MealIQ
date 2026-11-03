package io.github.ayech14.mealiq.services;

import io.github.ayech14.mealiq.contracts.ValidationResult;
import io.github.ayech14.mealiq.domain.DietRules;
import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.domain.IngredientRequirement;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.UserProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Deterministic checks of recipes and plans against the profile (D04). Allergies and
 * dietary rules are hard: a breach is a violation. Dislikes are soft: only a warning.
 *
 * <p>This version checks single recipes (F03); plan, change and time validation follow
 * with meal-plan generation.
 */
public class ConstraintValidator {

    /**
     * A required ingredient that breaks an allergy or diet makes the recipe invalid. An
     * optional one only produces a warning to leave it out, because the recipe works without it.
     */
    public ValidationResult validateRestrictions(Recipe recipe, UserProfile profile) {
        List<String> violations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (IngredientRequirement line : recipe.ingredients()) {
            Ingredient ingredient = line.ingredient();
            if (!profile.isAllowed(ingredient)) {
                String reason = ingredient.name() + " (" + reasonNotAllowed(ingredient, profile) + ")";
                if (line.optional()) {
                    warnings.add("Leave out optional " + reason + ".");
                } else {
                    violations.add(recipe.name() + " contains " + reason + ".");
                }
            } else if (profile.dislikedFoods().contains(ingredient.name())) {
                warnings.add(recipe.name() + " uses " + ingredient.name() + ", which you dislike.");
            }
        }
        return new ValidationResult(violations.isEmpty(), violations, warnings);
    }

    /** Names every allergy and diet rule the ingredient breaks, e.g. "allergy: milk; not vegan". */
    static String reasonNotAllowed(Ingredient ingredient, UserProfile profile) {
        List<String> reasons = new ArrayList<>();
        TreeSet<String> allergens = new TreeSet<>(ingredient.allergens());
        allergens.retainAll(profile.allergies());
        if (!allergens.isEmpty()) {
            reasons.add("allergy: " + String.join(", ", allergens));
        }
        for (String restriction : new TreeSet<>(profile.dietaryRestrictions())) {
            DietRules.Rule rule = DietRules.ruleFor(restriction);
            if (rule != null && (rule.excludedCategories().contains(ingredient.category())
                    || ingredient.allergens().stream().anyMatch(rule.excludedAllergens()::contains))) {
                reasons.add("not " + restriction);
            }
        }
        return String.join("; ", reasons);
    }
}
