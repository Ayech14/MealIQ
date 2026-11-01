package io.github.ayech14.mealiq.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonSchemaTest {

    private static final JsonSchema CHOICE = JsonSchema.object("tool_choice")
            .property("tool", JsonSchema.enumOf("recipeSearch", "finish"))
            .property("recipeIds", JsonSchema.arrayOf(JsonSchema.string()))
            .property("servings", JsonSchema.integer())
            .property("budget", JsonSchema.nullable(JsonSchema.number()))
            .property("detail", JsonSchema.object("detail").property("note", JsonSchema.string()).asType())
            .build();

    private static Map<String, Object> validAnswer() {
        Map<String, Object> answer = new HashMap<>();
        answer.put("tool", "recipeSearch");
        answer.put("recipeIds", List.of("a", "b"));
        answer.put("servings", 2);
        answer.put("budget", null);
        answer.put("detail", Map.of("note", "quick"));
        return answer;
    }

    @Test
    void definitionIsStrict() {
        Map<String, Object> definition = CHOICE.definition();
        assertEquals("object", definition.get("type"));
        assertEquals(false, definition.get("additionalProperties"));
        assertEquals(List.of("tool", "recipeIds", "servings", "budget", "detail"), definition.get("required"));
        assertEquals(List.of("number", "null"),
                ((Map<?, ?>) ((Map<?, ?>) definition.get("properties")).get("budget")).get("type"));
    }

    @Test
    void matchingAnswerHasNoProblems() {
        assertEquals(List.of(), CHOICE.validate(validAnswer()));
        Map<String, Object> withBudget = validAnswer();
        withBudget.put("budget", 12.5);
        assertEquals(List.of(), CHOICE.validate(withBudget));
    }

    @Test
    void everyMismatchIsReportedWithItsPath() {
        Map<String, Object> answer = validAnswer();
        answer.put("tool", "deleteEverything");
        answer.put("recipeIds", List.of("a", 7));
        answer.remove("servings");
        answer.put("detail", Map.of("note", "x", "extra", true));
        answer.put("surprise", 1);

        List<String> problems = CHOICE.validate(answer);

        assertEquals(5, problems.size(), problems.toString());
        assertTrue(problems.contains("$.tool: 'deleteEverything' is not one of [recipeSearch, finish]"));
        assertTrue(problems.contains("$.recipeIds[1]: expected [string] but got Integer"));
        assertTrue(problems.contains("$.servings: is missing or null"));
        assertTrue(problems.contains("$.detail: unexpected property 'extra'"));
        assertTrue(problems.contains("$: unexpected property 'surprise'"));
    }

    @Test
    void integerRejectsFractionsButNumberAcceptsIntegers() {
        Map<String, Object> answer = validAnswer();
        answer.put("servings", 2.5);
        answer.put("budget", 10);
        assertEquals(List.of("$.servings: expected [integer] but got Double"), CHOICE.validate(answer));
    }

    @Test
    void namesAndPropertiesAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> JsonSchema.object("has space").build());
        assertThrows(IllegalArgumentException.class,
                () -> JsonSchema.object("x").property("a", JsonSchema.string()).property("a", JsonSchema.bool()));
    }
}
