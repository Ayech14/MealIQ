package io.github.ayech14.mealiq.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.sun.net.httpserver.HttpServer;
import io.github.ayech14.mealiq.config.AppConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Runs the adapter and the real OpenAI SDK against a local stand-in server, so request
 * serialization, response parsing and error mapping are tested without the network.
 */
class ProviderLLMAdapterTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonSchema ANSWER = JsonSchema.object("answer")
            .property("recipeIds", JsonSchema.arrayOf(JsonSchema.string()))
            .property("rationale", JsonSchema.string())
            .build();

    private HttpServer server;
    private final List<JsonNode> requests = new ArrayList<>();
    private volatile int status = 200;
    private volatile String body = "{}";
    private volatile long delayMillis;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            requests.add(JSON.readTree(exchange.getRequestBody()));
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (IOException ignored) {
                // the client gave up (timeout test)
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private ProviderLLMAdapter adapter(Duration timeout) {
        return new ProviderLLMAdapter(OpenAIOkHttpClient.builder()
                .apiKey("sk-test")
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                .timeout(timeout)
                .maxRetries(0)
                .build(), "test-model");
    }

    private ProviderLLMAdapter adapter() {
        return adapter(Duration.ofSeconds(5));
    }

    /** A Responses API reply whose single message holds the given content parts. */
    private void reply(String responseStatus, ObjectNode... parts) {
        ObjectNode response = JSON.createObjectNode()
                .put("id", "resp_1").put("object", "response").put("created_at", 0)
                .put("status", responseStatus).put("model", "test-model");
        ArrayNode content = JSON.createArrayNode().addAll(List.of(parts));
        ObjectNode message = JSON.createObjectNode().put("type", "message").put("id", "msg_1")
                .put("role", "assistant").put("status", "completed");
        message.set("content", content);
        response.set("output", JSON.createArrayNode().add(message));
        if (responseStatus.equals("incomplete")) {
            response.set("incomplete_details", JSON.createObjectNode().put("reason", "max_output_tokens"));
        }
        body = response.toString();
    }

    private static ObjectNode text(String value) {
        ObjectNode part = JSON.createObjectNode().put("type", "output_text").put("text", value);
        part.set("annotations", JSON.createArrayNode());
        return part;
    }

    private static ObjectNode refusal(String value) {
        return JSON.createObjectNode().put("type", "refusal").put("refusal", value);
    }

    private LLMException failure(ProviderLLMAdapter adapter) {
        return assertThrows(LLMException.class, () -> adapter.generateStructured("x", ANSWER));
    }

    @Test
    void returnsTheAnswerAndSendsAStrictSchema() {
        reply("completed", text("{\"recipeIds\":[\"a\",\"b\"],\"rationale\":\"high protein\"}"));

        Map<String, Object> answer = adapter().generateStructured("plan my week", ANSWER, "You are MealIQ.");

        assertEquals(Map.of("recipeIds", List.of("a", "b"), "rationale", "high protein"), answer);
        JsonNode request = requests.getFirst();
        assertEquals("test-model", request.get("model").asText());
        assertEquals("plan my week", request.get("input").asText());
        assertEquals("You are MealIQ.", request.get("instructions").asText());
        assertFalse(request.get("store").asBoolean());
        JsonNode format = request.get("text").get("format");
        assertEquals("json_schema", format.get("type").asText());
        assertEquals("answer", format.get("name").asText());
        assertTrue(format.get("strict").asBoolean());
        assertEquals(JSON.valueToTree(ANSWER.definition()), format.get("schema"));
    }

    @Test
    void instructionsAreOptional() {
        reply("completed", text("{\"recipeIds\":[],\"rationale\":\"\"}"));
        adapter().generateStructured("hi", ANSWER);
        assertFalse(requests.getFirst().has("instructions"));
    }

    @Test
    void refusalIsReportedAndNotRetryable() {
        reply("completed", refusal("cannot help with that"));
        LLMException error = failure(adapter());
        assertEquals(LLMException.Kind.REFUSED, error.kind());
        assertFalse(error.retryable());
    }

    @Test
    void cutOffAnswerIsMalformed() {
        reply("incomplete", text("{\"recipeIds\":[\"a\""));
        LLMException error = failure(adapter());
        assertEquals(LLMException.Kind.MALFORMED_OUTPUT, error.kind());
        assertTrue(error.getMessage().contains("max_output_tokens"), error.getMessage());
    }

    @Test
    void missingNonJsonOrMismatchedAnswersAreMalformedAndRetryable() {
        for (ObjectNode[] parts : List.of(new ObjectNode[0], new ObjectNode[] {text("not json")},
                new ObjectNode[] {text("{\"recipeIds\":\"a\",\"rationale\":\"x\"}")})) {
            reply("completed", parts);
            LLMException error = failure(adapter());
            assertEquals(LLMException.Kind.MALFORMED_OUTPUT, error.kind());
            assertTrue(error.retryable());
        }
    }

    @ParameterizedTest
    @CsvSource({
        "401, AUTHENTICATION, false",
        "403, AUTHENTICATION, false",
        "429, RATE_LIMITED, true",
        "400, BAD_REQUEST, false",
        "404, BAD_REQUEST, false",
        "500, PROVIDER_UNAVAILABLE, true",
        "503, PROVIDER_UNAVAILABLE, true"})
    void httpErrorsAreMapped(int httpStatus, LLMException.Kind kind, boolean retryable) {
        status = httpStatus;
        body = "{\"error\":{\"message\":\"nope\",\"type\":\"test\"}}";
        LLMException error = failure(adapter());
        assertEquals(kind, error.kind());
        assertEquals(retryable, error.retryable());
    }

    @Test
    void slowProviderIsATimeout() {
        delayMillis = 1500;
        reply("completed", text("{\"recipeIds\":[],\"rationale\":\"\"}"));
        LLMException error = failure(adapter(Duration.ofMillis(300)));
        assertEquals(LLMException.Kind.TIMEOUT, error.kind());
    }

    @Test
    void unreachableProviderIsUnavailable() {
        ProviderLLMAdapter adapter = adapter();
        server.stop(0);
        assertEquals(LLMException.Kind.PROVIDER_UNAVAILABLE, failure(adapter).kind());
    }

    @Test
    void fromConfigRequiresKeyAndModel() {
        LLMException error = assertThrows(LLMException.class,
                () -> ProviderLLMAdapter.fromConfig(AppConfig.from(Map.of("LLM_MODEL", "m"))));
        assertEquals(LLMException.Kind.NOT_CONFIGURED, error.kind());
    }

    @Test
    void fromConfigBuildsWithoutCallingTheNetwork() {
        ProviderLLMAdapter adapter = ProviderLLMAdapter.fromConfig(AppConfig.from(
                Map.of("LLM_API_KEY", "sk-test", "LLM_MODEL", "test-model", "LLM_MAX_RETRIES", "1")));
        assertEquals("test-model", adapter.modelName());
        assertTrue(requests.isEmpty());
    }
}
