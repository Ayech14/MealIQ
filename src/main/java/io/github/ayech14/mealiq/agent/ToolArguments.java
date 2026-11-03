package io.github.ayech14.mealiq.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads and checks the loosely typed argument maps the LLM produces. */
final class ToolArguments {

    private final Map<String, Object> args;
    private final List<String> problems = new ArrayList<>();

    ToolArguments(Map<String, Object> args, Set<String> allowed) {
        this.args = args == null ? Map.of() : args;
        for (String key : this.args.keySet()) {
            if (!allowed.contains(key)) {
                problems.add("unknown argument '" + key + "'; allowed: " + String.join(", ", allowed.stream()
                        .sorted().toList()));
            }
        }
    }

    List<String> problems() {
        return problems;
    }

    String text(String key, boolean required) {
        Object value = args.get(key);
        if (value == null) {
            if (required) {
                problems.add("'" + key + "' is required");
            }
            return null;
        }
        if (!(value instanceof String s) || s.isBlank()) {
            problems.add("'" + key + "' must be a non-empty string");
            return null;
        }
        return s.strip();
    }

    Integer positiveInt(String key) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue()) && n.intValue() > 0) {
            return n.intValue();
        }
        problems.add("'" + key + "' must be a positive whole number");
        return null;
    }

    List<String> texts(String key, boolean required) {
        Object value = args.get(key);
        if (value == null) {
            if (required) {
                problems.add("'" + key + "' is required");
            }
            return List.of();
        }
        if (value instanceof List<?> list && list.stream().allMatch(v -> v instanceof String)) {
            List<String> texts = list.stream().map(v -> ((String) v).strip()).filter(v -> !v.isEmpty()).toList();
            if (required && texts.isEmpty()) {
                problems.add("'" + key + "' must not be empty");
            }
            return texts;
        }
        problems.add("'" + key + "' must be a list of strings");
        return List.of();
    }
}
