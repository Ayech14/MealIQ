package io.github.ayech14.mealiq.config;

import java.util.List;

/** Thrown when configuration values are missing or out of range; lists every problem at once. */
public class ConfigException extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    @SuppressWarnings("serial") // List.copyOf returns a serializable list
    private final List<String> problems;

    public ConfigException(List<String> problems) {
        super("Invalid configuration: " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
