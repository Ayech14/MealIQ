package io.github.ayech14.mealiq.services;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** Rejected input, with one message per field so an interface can show it in place. */
public class InvalidInputException extends ServiceException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    @SuppressWarnings("serial") // always an unmodifiable LinkedHashMap view
    private final Map<String, String> errors;

    public InvalidInputException(Map<String, String> errors) {
        super(errors.entrySet().stream().map(e -> e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining("; ")));
        this.errors = Collections.unmodifiableMap(new LinkedHashMap<>(errors));
    }

    public static InvalidInputException of(String field, String message) {
        return new InvalidInputException(Map.of(field, message));
    }

    public Map<String, String> errors() {
        return errors;
    }
}
