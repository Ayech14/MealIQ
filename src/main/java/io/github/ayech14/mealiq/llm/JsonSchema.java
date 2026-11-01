package io.github.ayech14.mealiq.llm;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The required shape of a structured LLM answer: a named JSON Schema object.
 *
 * <p>The builder only produces what strict structured output accepts: every property is
 * required and no others are allowed; a value that may be absent is declared nullable.
 * {@link #validate} checks an answer against the same shape, so every LLMClient can
 * guarantee its result fits, whatever the provider promises.
 */
public final class JsonSchema {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final String name;
    private final Map<String, Object> definition;

    private JsonSchema(String name, Map<String, Object> definition) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Schema name must be 1-64 letters, digits, '_' or '-': " + name);
        }
        this.name = name;
        this.definition = definition;
    }

    public static Builder object(String name) {
        return new Builder(name);
    }

    public String name() {
        return name;
    }

    /** The JSON Schema as nested unmodifiable maps and lists. */
    public Map<String, Object> definition() {
        return definition;
    }

    /** Every mismatch between {@code value} and this schema, as readable paths; empty when it fits. */
    public List<String> validate(Object value) {
        List<String> problems = new ArrayList<>();
        check(definition, value, "$", problems);
        return problems;
    }

    @Override
    public String toString() {
        return "JsonSchema[" + name + "]";
    }

    // ---- property types, usable in object properties and as array items ----

    public static Map<String, Object> string() {
        return Map.of("type", "string");
    }

    public static Map<String, Object> integer() {
        return Map.of("type", "integer");
    }

    public static Map<String, Object> number() {
        return Map.of("type", "number");
    }

    public static Map<String, Object> bool() {
        return Map.of("type", "boolean");
    }

    public static Map<String, Object> enumOf(String... values) {
        return Map.of("type", "string", "enum", List.of(values));
    }

    public static Map<String, Object> arrayOf(Map<String, Object> items) {
        return Map.of("type", "array", "items", items);
    }

    /** The same type, but null is also accepted (strict mode has no optional properties). */
    public static Map<String, Object> nullable(Map<String, Object> type) {
        Map<String, Object> copy = new LinkedHashMap<>(type);
        copy.put("type", List.of(type.get("type"), "null"));
        if (type.containsKey("enum")) {
            List<Object> values = new ArrayList<>((List<?>) type.get("enum"));
            values.add(null);
            copy.put("enum", Collections.unmodifiableList(values));
        }
        return Collections.unmodifiableMap(copy);
    }

    public static final class Builder {

        private final String name;
        private final Map<String, Object> properties = new LinkedHashMap<>();
        private String description;

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        public Builder description(String value) {
            description = value;
            return this;
        }

        public Builder property(String property, Map<String, Object> type) {
            if (properties.putIfAbsent(property, type) != null) {
                throw new IllegalArgumentException("Duplicate property " + property);
            }
            return this;
        }

        /** This builder's shape as a nested object type, for use inside another schema. */
        public Map<String, Object> asType() {
            Map<String, Object> object = new LinkedHashMap<>();
            object.put("type", "object");
            if (description != null) {
                object.put("description", description);
            }
            object.put("properties", Collections.unmodifiableMap(new LinkedHashMap<>(properties)));
            object.put("required", List.copyOf(properties.keySet()));
            object.put("additionalProperties", false);
            return Collections.unmodifiableMap(object);
        }

        public JsonSchema build() {
            return new JsonSchema(name, asType());
        }
    }

    // ---- validation ----

    private static void check(Map<?, ?> schema, Object value, String path, List<String> problems) {
        List<?> types = schema.get("type") instanceof List<?> list ? list : List.of(schema.get("type"));
        if (value == null) {
            if (!types.contains("null")) {
                problems.add(path + ": is missing or null");
            }
            return;
        }
        if (schema.get("enum") instanceof List<?> allowed && !allowed.contains(value)) {
            problems.add(path + ": '" + value + "' is not one of " + allowed);
            return;
        }
        if (types.contains("object") && value instanceof Map<?, ?> map) {
            Map<?, ?> properties = (Map<?, ?>) schema.get("properties");
            for (Map.Entry<?, ?> property : properties.entrySet()) {
                check((Map<?, ?>) property.getValue(), map.get(property.getKey()), path + "." + property.getKey(),
                        problems);
            }
            for (Object key : map.keySet()) {
                if (!properties.containsKey(key)) {
                    problems.add(path + ": unexpected property '" + key + "'");
                }
            }
        } else if (types.contains("array") && value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                check((Map<?, ?>) schema.get("items"), list.get(i), path + "[" + i + "]", problems);
            }
        } else if (!matchesScalar(types, value)) {
            problems.add(path + ": expected " + types + " but got " + value.getClass().getSimpleName());
        }
    }

    private static boolean matchesScalar(List<?> types, Object value) {
        return (types.contains("string") && value instanceof String)
                || (types.contains("boolean") && value instanceof Boolean)
                || (types.contains("integer") && (value instanceof Integer || value instanceof Long
                        || value instanceof Short || value instanceof BigInteger))
                || (types.contains("number") && value instanceof Number);
    }
}
