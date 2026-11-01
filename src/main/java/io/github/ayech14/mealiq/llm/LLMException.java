package io.github.ayech14.mealiq.llm;

import java.util.Objects;

/** A provider failure translated into MealIQ terms; no SDK exception escapes the LLM layer. */
public class LLMException extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public enum Kind {
        TIMEOUT, RATE_LIMITED, AUTHENTICATION, BAD_REQUEST, PROVIDER_UNAVAILABLE, REFUSED, MALFORMED_OUTPUT,
        NOT_CONFIGURED
    }

    private final Kind kind;

    public LLMException(Kind kind, String message) {
        this(kind, message, null);
    }

    public LLMException(Kind kind, String message, Throwable cause) {
        super(kind + ": " + message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public Kind kind() {
        return kind;
    }

    /** True when repeating the same request may succeed. */
    public boolean retryable() {
        return switch (kind) {
            case TIMEOUT, RATE_LIMITED, PROVIDER_UNAVAILABLE, MALFORMED_OUTPUT -> true;
            case AUTHENTICATION, BAD_REQUEST, REFUSED, NOT_CONFIGURED -> false;
        };
    }
}
