package io.github.ayech14.mealiq.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ScriptedLLMClientTest {

    private static final JsonSchema CHOICE = JsonSchema.object("choice")
            .property("tool", JsonSchema.string()).build();

    @Test
    void repliesInOrderAndRecordsCalls() {
        ScriptedLLMClient client = new ScriptedLLMClient()
                .queue(Map.of("tool", "recipeSearch"), Map.of("tool", "finish"));
        LLMClient asInterface = client;

        assertEquals(Map.of("tool", "recipeSearch"), asInterface.generateStructured("step 1", CHOICE, "sys"));
        assertEquals(Map.of("tool", "finish"), asInterface.generateStructured("step 2", CHOICE));

        assertEquals("step 1", client.calls().get(0).prompt());
        assertSame(CHOICE, client.calls().get(0).schema());
        assertEquals("sys", client.calls().get(0).instructions());
        assertNull(client.calls().get(1).instructions());
        assertEquals(0, client.remaining());
    }

    @Test
    void scriptedErrorIsThrown() {
        ScriptedLLMClient client = new ScriptedLLMClient()
                .queue(new LLMException(LLMException.Kind.TIMEOUT, "slow"));
        LLMException error = assertThrows(LLMException.class, () -> client.generateStructured("x", CHOICE));
        assertEquals(LLMException.Kind.TIMEOUT, error.kind());
        assertTrue(error.retryable());
    }

    @Test
    void runningOutOfRepliesFailsLikeAProvider() {
        LLMException error = assertThrows(LLMException.class,
                () -> new ScriptedLLMClient().generateStructured("x", CHOICE));
        assertEquals(LLMException.Kind.PROVIDER_UNAVAILABLE, error.kind());
    }

    @Test
    void replyThatDoesNotMatchTheSchemaIsMalformed() {
        ScriptedLLMClient client = new ScriptedLLMClient().queue(Map.of("tool", 3));
        LLMException error = assertThrows(LLMException.class, () -> client.generateStructured("x", CHOICE));
        assertEquals(LLMException.Kind.MALFORMED_OUTPUT, error.kind());
    }

    @Test
    void onlyMapsAndErrorsCanBeQueued() {
        assertThrows(IllegalArgumentException.class, () -> new ScriptedLLMClient().queue("not a reply"));
    }
}
