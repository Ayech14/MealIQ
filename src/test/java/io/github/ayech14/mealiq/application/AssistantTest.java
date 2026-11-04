package io.github.ayech14.mealiq.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ayech14.mealiq.contracts.AgentResponse;
import io.github.ayech14.mealiq.contracts.AssistantRequest;
import io.github.ayech14.mealiq.domain.Quantity;
import io.github.ayech14.mealiq.domain.ResponseStatus;
import io.github.ayech14.mealiq.domain.UserProfile;
import io.github.ayech14.mealiq.llm.LLMException;
import io.github.ayech14.mealiq.llm.ScriptedLLMClient;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The assistant: a typed message becomes one action, run through the facade with its usual checks. */
class AssistantTest {

    TestFacades.Fixture fixture;
    MealPlanningFacade facade;
    ScriptedLLMClient llm;
    final UUID profileId = MealPlanningFacade.localProfileId("default");

    @BeforeEach
    void setUp() {
        fixture = TestFacades.inMemory(LocalDate.of(2026, 11, 3));
        facade = fixture.facade();
        llm = fixture.llm();
    }

    /** A complete assistant_request answer; {@code fields} overrides the defaults. */
    static Map<String, Object> request(String action, String reply, Map<String, Object> fields) {
        Map<String, Object> profile = new HashMap<>(Map.of("addDiet", List.of(), "removeDiet", List.of(),
                "addAllergies", List.of(), "removeAllergies", List.of(), "addLikes", List.of(),
                "addDislikes", List.of()));
        profile.put("calories", null);
        profile.put("protein", null);
        profile.put("mealsPerDay", null);
        profile.put("weeklyBudget", null);
        Map<String, Object> answer = new HashMap<>(Map.of("action", action, "reply", reply, "include", List.of(),
                "exclude", List.of(), "pantry", List.of(), "profile", profile));
        answer.put("mealType", null);
        answer.put("maxMinutes", null);
        answer.put("days", null);
        answer.put("mode", null);
        answer.put("ingredient", null);
        fields.forEach((key, value) -> {
            if (key.startsWith("profile.")) {
                profile.put(key.substring("profile.".length()), value);
            } else {
                answer.put(key, value);
            }
        });
        return answer;
    }

    @Test
    void ranOutBecomesAPantryUpdateThatWaitsForConfirmation() {
        llm.queue(request("pantry", "I'll mark eggs as used up.", Map.of("pantry",
                List.of(Map.of("ingredient", "egg", "amount", 0, "unit", "unit")))));

        AssistantReply reply = facade.ask(profileId, "I ran out of eggs");

        assertEquals(AssistantRequest.Action.PANTRY, reply.action());
        assertEquals("I'll mark eggs as used up.", reply.text());
        assertEquals(List.of("egg: none left"), reply.pending().lines());
        assertTrue(facade.getPantry(profileId).items().isEmpty(), "nothing applied before confirming");

        assertEquals(ResponseStatus.FAILED, facade.confirmUpdate(UUID.randomUUID(), reply.pending().id()).status(),
                "another profile cannot confirm it");
        assertEquals(ResponseStatus.SUCCESS, facade.confirmUpdate(profileId, reply.pending().id()).status());
        assertEquals(Quantity.of(0, "unit"), facade.getPantry(profileId).items().values().iterator().next().quantity());
        assertEquals(ResponseStatus.FAILED, facade.confirmUpdate(profileId, reply.pending().id()).status(),
                "an update applies once");
        String prompt = llm.calls().getFirst().prompt();
        assertTrue(prompt.contains("USER MESSAGE: I ran out of eggs"), prompt);
        assertTrue(prompt.contains("- pantry: set what the user has at home"), prompt);
        assertTrue(prompt.contains("CURRENT PLAN: none yet"), prompt);
        assertTrue(prompt.contains("NOTE: The user has no saved profile yet"), prompt);
    }

    @Test
    void pantryIngredientsMustBeCatalogueNames() {
        llm.queue(request("pantry", "Noted.", Map.of("pantry",
                List.of(Map.of("ingredient", "white rice", "amount", 1, "unit", "kg")))));
        AssistantReply reply = facade.ask(profileId, "I bought 1 kg of rice");
        assertEquals(List.of("white rice: 1 kg"), reply.pending().lines());
        Map<?, ?> line = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) llm.calls().getFirst().schema().definition()
                .get("properties")).get("pantry")).get("items");
        List<?> names = (List<?>) ((Map<?, ?>) ((Map<?, ?>) line.get("properties")).get("ingredient")).get("enum");
        assertTrue(names.contains("white rice") && !names.contains("rice"), "the schema only allows catalogue names");

        llm.queue(request("pantry", "Noted.", Map.of("pantry",
                List.of(Map.of("ingredient", "dragonfruit", "amount", 2, "unit", "unit")))));
        assertTrue(facade.ask(profileId, "I bought dragonfruit").text().startsWith("The AI returned an unusable "
                + "answer"), "an answer outside the schema is rejected, not guessed");
    }

    @Test
    void profileChangesArePreviewedThenSaved() {
        llm.queue(request("profile", "Got it.", Map.of("profile.addDiet", List.of("vegetarian"),
                "profile.addAllergies", List.of("peanut"), "profile.weeklyBudget", 60)));

        AssistantReply reply = facade.ask(profileId, "I'm vegetarian, allergic to peanuts, budget $60 a week");

        assertEquals(List.of("Diet: vegetarian", "Allergies: peanut", "Weekly budget: $60.00"),
                reply.pending().lines());
        facade.confirmUpdate(profileId, reply.pending().id());
        UserProfile saved = facade.getProfile(profileId);
        assertEquals(Set.of("vegetarian"), saved.dietaryRestrictions());
        assertEquals(Set.of("peanut"), saved.allergies());
    }

    @Test
    void noRestrictionsCreatesTheProfile() {
        llm.queue(request("profile", "Great, no restrictions.", Map.of()));
        AssistantReply reply = facade.ask(profileId, "no restrictions");
        assertEquals("Create your profile?", reply.pending().title());
        facade.confirmUpdate(profileId, reply.pending().id());
        assertTrue(facade.getProfile(profileId).allergies().isEmpty());
    }

    @Test
    void invalidProfileValuesAreRefused() {
        llm.queue(request("profile", "Ok.", Map.of("profile.mealsPerDay", 9)));
        AssistantReply reply = facade.ask(profileId, "nine meals a day");
        assertNull(reply.pending());
        assertEquals("I can't save that: must be between 1 and 6", reply.text());
    }

    @Test
    void recipeRequestsRunTheRecipeAgentWithTheFilters() {
        facade.updateProfile(new UserProfile(profileId));
        Map<String, Object> finish = new HashMap<>();
        finish.put("action", "finish");
        finish.put("tool", null);
        finish.put("arguments", "{}");
        finish.put("reason", "done");
        llm.queue(request("recipes", "Here are quick dinners.", Map.of("mealType", "dinner", "maxMinutes", 20)),
                Map.of("action", "call_tool", "tool", "recipeSearch", "arguments", "{\"mealType\": \"dinner\"}",
                        "reason", "dinners"), finish,
                Map.of("recipes", List.of("Shrimp tacos"), "rationale", "Quick."));

        AssistantReply reply = facade.ask(profileId, "quick dinner please");

        AgentResponse result = reply.result();
        assertEquals(ResponseStatus.SUCCESS, result.status());
        assertEquals("Shrimp tacos", result.recipes().getFirst().name());
        assertTrue(llm.calls().get(1).prompt().contains("REQUIRED FILTERS (applied to every result): meal type "
                + "dinner; at most 20 minutes"), llm.calls().get(1).prompt());
    }

    @Test
    void whenNoRecipeFitsGenerationIsOffered() {
        facade.updateProfile(new UserProfile(profileId));
        Map<String, Object> finish = new HashMap<>();
        finish.put("action", "finish");
        finish.put("tool", null);
        finish.put("arguments", "{}");
        finish.put("reason", "done");
        llm.queue(request("recipes", "Looking.", Map.of("include", List.of("beef"), "maxMinutes", 10)),
                Map.of("action", "call_tool", "tool", "recipeSearch", "arguments", "{\"include\": [\"beef\"]}",
                        "reason", "beef"), finish);
        AssistantReply reply = facade.ask(profileId, "beef in 10 minutes");
        assertEquals(ResponseStatus.CONFLICT, reply.result().status());
        assertTrue(reply.text().endsWith("I can write a new recipe for this that you review before saving."),
                reply.text());
    }

    @Test
    void planActionsNeedAPlanFirst() {
        llm.queue(request("change_plan", "Sure.", Map.of()));
        AssistantReply reply = facade.ask(profileId, "make Monday's dinner vegetarian");
        assertNull(reply.result());
        assertTrue(reply.text().startsWith("You don't have a meal plan yet."), reply.text());
    }

    @Test
    void answersAndFailuresAreJustReplies() {
        llm.queue(request("answer", "I can plan meals, suggest recipes and make shopping lists.", Map.of()));
        AssistantReply reply = facade.ask(profileId, "what can you do?");
        assertEquals("I can plan meals, suggest recipes and make shopping lists.", reply.text());
        assertNull(reply.result());

        llm.queue(new LLMException(LLMException.Kind.TIMEOUT, "slow"));
        assertTrue(facade.ask(profileId, "hello").text().startsWith("The AI provider is unavailable"));
    }

    @Test
    void usingThePantryCreatesTheProfile() {
        facade.updatePantry(profileId, facade.pantryItem("ground beef", 400, "g", null));
        assertEquals(3, facade.getProfile(profileId).mealsPerDay(), "a default profile was saved first");
    }
}
