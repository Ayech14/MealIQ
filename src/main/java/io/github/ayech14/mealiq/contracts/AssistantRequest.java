package io.github.ayech14.mealiq.contracts;

import io.github.ayech14.mealiq.domain.PlanningMode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What a typed message asks for, as the agent understood it: one action and its arguments.
 * It only names an action; the facade runs it with the normal checks and confirmations.
 */
public record AssistantRequest(Action action, String reply, RecipeQuery filters, Integer days, PlanningMode mode,
                               String ingredient, List<PantryLine> pantry, ProfileUpdate profile) {

    /** Every action the assistant can take. */
    public enum Action {
        RECIPES, NEW_RECIPE, PLAN, CHANGE_PLAN, SUBSTITUTE, ADAPT, CHEAPER, GROCERIES, PANTRY, PROFILE, SHOW_PLAN,
        ANSWER
    }

    /** A pantry quantity to set; zero means the user ran out. */
    public record PantryLine(String ingredient, double amount, String unit) {
    }

    /** Changes to the profile; null or empty fields leave that part as it is. */
    public record ProfileUpdate(Set<String> addDiet, Set<String> removeDiet, Set<String> addAllergies,
                                Set<String> removeAllergies, Set<String> addLikes, Set<String> addDislikes,
                                Integer calories, Integer protein, Integer mealsPerDay, BigDecimal weeklyBudget) {

        public static final ProfileUpdate NONE = new ProfileUpdate(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                Set.of(), null, null, null, null);

        public ProfileUpdate {
            addDiet = addDiet == null ? Set.of() : Set.copyOf(addDiet);
            removeDiet = removeDiet == null ? Set.of() : Set.copyOf(removeDiet);
            addAllergies = addAllergies == null ? Set.of() : Set.copyOf(addAllergies);
            removeAllergies = removeAllergies == null ? Set.of() : Set.copyOf(removeAllergies);
            addLikes = addLikes == null ? Set.of() : Set.copyOf(addLikes);
            addDislikes = addDislikes == null ? Set.of() : Set.copyOf(addDislikes);
        }

        public boolean isEmpty() {
            return equals(NONE);
        }
    }

    public AssistantRequest {
        Objects.requireNonNull(action, "action");
        reply = reply == null ? "" : reply;
        filters = filters == null ? RecipeQuery.any() : filters;
        pantry = pantry == null ? List.of() : List.copyOf(pantry);
        profile = profile == null ? ProfileUpdate.NONE : profile;
    }

}
