package io.github.ayech14.mealiq.presentation.cli;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Scripted assistant answers for CLI tests. */
final class AssistantTestRequests {

    private AssistantTestRequests() {
    }

    static Map<String, Object> pantry(String ingredient, double amount) {
        Map<String, Object> profile = new HashMap<>(Map.of("addDiet", List.of(), "removeDiet", List.of(),
                "addAllergies", List.of(), "removeAllergies", List.of(), "addLikes", List.of(),
                "addDislikes", List.of()));
        for (String key : List.of("calories", "protein", "mealsPerDay", "weeklyBudget")) {
            profile.put(key, null);
        }
        Map<String, Object> answer = new HashMap<>(Map.of("action", "pantry", "reply", "Adding it.",
                "include", List.of(), "exclude", List.of(), "profile", profile,
                "pantry", List.of(Map.of("ingredient", ingredient, "amount", amount, "unit", "unit"))));
        for (String key : List.of("mealType", "maxMinutes", "days", "mode", "ingredient")) {
            answer.put(key, null);
        }
        return answer;
    }
}
