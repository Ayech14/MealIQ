package io.github.ayech14.mealiq.presentation.gui;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Scripted assistant_request answers for rendering the Assistant screen. */
final class AssistantAnswers {

    private AssistantAnswers() {
    }

    static Map<String, Object> pantryRanOut(String ingredient) {
        Map<String, Object> answer = base("pantry", "Sorry to hear that. I'll mark " + ingredient
                + " as used up, so your plan and shopping list account for it.");
        answer.put("pantry", List.of(Map.of("ingredient", ingredient, "amount", 0, "unit", "unit")));
        return answer;
    }

    static Map<String, Object> recipes(String reply, String mealType, int maxMinutes) {
        Map<String, Object> answer = base("recipes", reply);
        answer.put("mealType", mealType);
        answer.put("maxMinutes", maxMinutes);
        return answer;
    }

    private static Map<String, Object> base(String action, String reply) {
        Map<String, Object> profile = new HashMap<>(Map.of("addDiet", List.of(), "removeDiet", List.of(),
                "addAllergies", List.of(), "removeAllergies", List.of(), "addLikes", List.of(),
                "addDislikes", List.of()));
        for (String key : List.of("calories", "protein", "mealsPerDay", "weeklyBudget")) {
            profile.put(key, null);
        }
        Map<String, Object> answer = new HashMap<>(Map.of("action", action, "reply", reply, "include", List.of(),
                "exclude", List.of(), "pantry", List.of(), "profile", profile));
        for (String key : List.of("mealType", "maxMinutes", "days", "mode", "ingredient")) {
            answer.put(key, null);
        }
        return answer;
    }
}
