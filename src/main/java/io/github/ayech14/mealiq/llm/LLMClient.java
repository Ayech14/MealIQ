package io.github.ayech14.mealiq.llm;

import java.util.Map;

/**
 * Provider-neutral LLM access (Adapter target, D05). The agent depends only on this
 * interface; provider SDK types never cross it.
 */
public interface LLMClient {

    /**
     * Asks the model for a JSON object matching {@code schema}.
     *
     * @param prompt       the request and its context
     * @param schema       the required shape of the answer
     * @param instructions fixed system-level guidance, or null
     * @return the answer as plain maps, lists, strings, numbers and booleans; it always
     *         matches {@code schema}
     * @throws LLMException on any provider or output failure
     */
    Map<String, Object> generateStructured(String prompt, JsonSchema schema, String instructions);

    default Map<String, Object> generateStructured(String prompt, JsonSchema schema) {
        return generateStructured(prompt, schema, null);
    }
}
