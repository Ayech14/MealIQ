package io.github.ayech14.mealiq.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseTextConfig;
import io.github.ayech14.mealiq.config.AppConfig;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.Map;

/**
 * Implements LLMClient with the OpenAI Responses API and strict JSON-schema output
 * (Adapter, D05). The SDK client is the adaptee: its request types, responses and
 * exceptions stay inside this class; callers see only maps and {@link LLMException}.
 */
public final class ProviderLLMAdapter implements LLMClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final OpenAIClient api;
    private final String modelName;

    /** {@code api} carries the timeout and retry settings; see {@link #fromConfig}. */
    public ProviderLLMAdapter(OpenAIClient api, String modelName) {
        this.api = api;
        this.modelName = modelName;
    }

    /** Builds the adapter from {@code .env} settings without contacting the provider. */
    public static ProviderLLMAdapter fromConfig(AppConfig config) {
        if (!config.llmConfigured()) {
            throw new LLMException(LLMException.Kind.NOT_CONFIGURED, "set LLM_API_KEY and LLM_MODEL in .env");
        }
        OpenAIClient api = OpenAIOkHttpClient.builder()
                .apiKey(config.llmApiKey().orElseThrow())
                .timeout(config.llmTimeout())
                // The SDK retries timeouts, rate limits and 5xx responses with backoff.
                .maxRetries(config.llmMaxRetries())
                .build();
        return new ProviderLLMAdapter(api, config.llmModel().orElseThrow());
    }

    public String modelName() {
        return modelName;
    }

    @Override
    public Map<String, Object> generateStructured(String prompt, JsonSchema schema, String instructions) {
        Response response;
        try {
            response = api.responses().create(toProviderRequest(prompt, schema, instructions));
        } catch (OpenAIException error) {
            throw mapProviderError(error);
        }
        return readAnswer(response, schema);
    }

    ResponseCreateParams toProviderRequest(String prompt, JsonSchema schema, String instructions) {
        ResponseFormatTextJsonSchemaConfig.Schema.Builder definition =
                ResponseFormatTextJsonSchemaConfig.Schema.builder();
        schema.definition().forEach((key, value) -> definition.putAdditionalProperty(key, JsonValue.from(value)));
        ResponseCreateParams.Builder request = ResponseCreateParams.builder()
                .model(modelName)
                .input(prompt)
                .store(false)
                .text(ResponseTextConfig.builder()
                        .format(ResponseFormatTextJsonSchemaConfig.builder()
                                .name(schema.name())
                                .schema(definition.build())
                                .strict(true)
                                .build())
                        .build());
        if (instructions != null && !instructions.isBlank()) {
            request.instructions(instructions);
        }
        return request.build();
    }

    private static Map<String, Object> readAnswer(Response response, JsonSchema schema) {
        StringBuilder text = new StringBuilder();
        for (ResponseOutputItem item : response.output()) {
            if (item.message().isEmpty()) {
                continue; // reasoning and other non-message items carry no answer
            }
            for (ResponseOutputMessage.Content part : item.message().get().content()) {
                if (part.isRefusal()) {
                    throw new LLMException(LLMException.Kind.REFUSED, part.asRefusal().refusal());
                }
                if (part.isOutputText()) {
                    text.append(part.asOutputText().text());
                }
            }
        }
        if (response.status().filter(ResponseStatus.INCOMPLETE::equals).isPresent()) {
            String reason = response.incompleteDetails().flatMap(d -> d.reason()).map(Object::toString)
                    .orElse("unknown reason");
            throw new LLMException(LLMException.Kind.MALFORMED_OUTPUT, "the answer was cut off (" + reason + ")");
        }
        if (text.isEmpty()) {
            throw new LLMException(LLMException.Kind.MALFORMED_OUTPUT, "the model returned no structured output");
        }
        Map<String, Object> answer;
        try {
            answer = JSON.readValue(text.toString(), new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException e) {
            throw new LLMException(LLMException.Kind.MALFORMED_OUTPUT, "the answer is not a JSON object", e);
        }
        List<String> problems = schema.validate(answer);
        if (!problems.isEmpty()) {
            throw new LLMException(LLMException.Kind.MALFORMED_OUTPUT,
                    "the answer does not match " + schema.name() + ": " + String.join("; ", problems));
        }
        return answer;
    }

    static LLMException mapProviderError(OpenAIException error) {
        if (error instanceof OpenAIServiceException service) {
            int status = service.statusCode();
            return switch (status) {
                case 401, 403 -> new LLMException(LLMException.Kind.AUTHENTICATION,
                        "the LLM API key was rejected", error);
                case 429 -> new LLMException(LLMException.Kind.RATE_LIMITED,
                        "the LLM provider rate limit or quota was reached", error);
                case 400, 404, 422 -> new LLMException(LLMException.Kind.BAD_REQUEST,
                        "the LLM provider rejected the request (HTTP " + status + ")", error);
                default -> new LLMException(LLMException.Kind.PROVIDER_UNAVAILABLE,
                        "the LLM provider is unavailable (HTTP " + status + ")", error);
            };
        }
        if (error instanceof OpenAIIoException && causedByTimeout(error)) {
            return new LLMException(LLMException.Kind.TIMEOUT, "the LLM provider did not respond in time", error);
        }
        if (error instanceof OpenAIInvalidDataException) {
            return new LLMException(LLMException.Kind.MALFORMED_OUTPUT, "the provider response could not be read",
                    error);
        }
        return new LLMException(LLMException.Kind.PROVIDER_UNAVAILABLE, "the LLM provider is unavailable", error);
    }

    private static boolean causedByTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedIOException) { // includes SocketTimeoutException
                return true;
            }
        }
        return false;
    }
}
