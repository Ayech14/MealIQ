package io.github.ayech14.mealiq.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Application settings, read from environment variables and a local {@code .env} file.
 * Environment variables win over the file. Real secrets live only in the git-ignored
 * {@code .env}; {@code .env.example} documents every key with placeholders.
 *
 * <p>The LLM model is pinned here, never in code (D05), and {@code maxToolSteps} bounds
 * the agent's tool loop (D07).
 */
public final class AppConfig {

    public static final String DEFAULT_DATABASE_URL = "jdbc:postgresql://localhost:5432/mealiq";
    public static final int MAX_TOOL_STEPS_LIMIT = 20;
    public static final int MAX_RETRIES_LIMIT = 5;

    private final String databaseUrl;
    private final String databaseUser;
    private final String databasePassword;
    private final String llmApiKey;
    private final String llmModel;
    private final Duration llmTimeout;
    private final int llmMaxRetries;
    private final int maxToolSteps;

    private AppConfig(String databaseUrl, String databaseUser, String databasePassword, String llmApiKey,
                      String llmModel, Duration llmTimeout, int llmMaxRetries, int maxToolSteps) {
        this.databaseUrl = databaseUrl;
        this.databaseUser = databaseUser;
        this.databasePassword = databasePassword;
        this.llmApiKey = llmApiKey;
        this.llmModel = llmModel;
        this.llmTimeout = llmTimeout;
        this.llmMaxRetries = llmMaxRetries;
        this.maxToolSteps = maxToolSteps;
    }

    /** Loads {@code .env} from the working directory, then overlays the process environment. */
    public static AppConfig load() {
        Map<String, String> values = new HashMap<>(DotEnv.read(Path.of(".env")));
        values.putAll(System.getenv());
        return from(values);
    }

    /** Builds and validates settings from key/value pairs; reports every problem together. */
    public static AppConfig from(Map<String, String> values) {
        List<String> problems = new ArrayList<>();

        String databaseUrl = text(values, "DATABASE_URL").orElse(DEFAULT_DATABASE_URL);
        if (!databaseUrl.startsWith("jdbc:")) {
            problems.add("DATABASE_URL must be a JDBC URL such as " + DEFAULT_DATABASE_URL);
        }
        int timeoutSeconds = integer(values, "LLM_TIMEOUT_SECONDS", 60, 1, 600, problems);
        int maxRetries = integer(values, "LLM_MAX_RETRIES", 2, 0, MAX_RETRIES_LIMIT, problems);
        int maxToolSteps = integer(values, "MAX_TOOL_STEPS", 6, 1, MAX_TOOL_STEPS_LIMIT, problems);
        if (!problems.isEmpty()) {
            throw new ConfigException(problems);
        }
        return new AppConfig(
                databaseUrl,
                text(values, "DATABASE_USER").orElse("mealiq"),
                text(values, "DATABASE_PASSWORD").orElse(""),
                text(values, "LLM_API_KEY").orElse(null),
                text(values, "LLM_MODEL").orElse(null),
                Duration.ofSeconds(timeoutSeconds),
                maxRetries,
                maxToolSteps);
    }

    public String databaseUrl() {
        return databaseUrl;
    }

    public String databaseUser() {
        return databaseUser;
    }

    public String databasePassword() {
        return databasePassword;
    }

    public Optional<String> llmApiKey() {
        return Optional.ofNullable(llmApiKey);
    }

    public Optional<String> llmModel() {
        return Optional.ofNullable(llmModel);
    }

    public Duration llmTimeout() {
        return llmTimeout;
    }

    public int llmMaxRetries() {
        return llmMaxRetries;
    }

    public int maxToolSteps() {
        return maxToolSteps;
    }

    /** True when both an API key and a pinned model are set. */
    public boolean llmConfigured() {
        return llmApiKey != null && llmModel != null;
    }

    /** Secrets are masked so settings can be logged safely. */
    @Override
    public String toString() {
        return "AppConfig[databaseUrl=" + databaseUrl
                + ", databaseUser=" + databaseUser
                + ", databasePassword=" + mask(databasePassword)
                + ", llmApiKey=" + mask(llmApiKey)
                + ", llmModel=" + llmModel
                + ", llmTimeout=" + llmTimeout
                + ", llmMaxRetries=" + llmMaxRetries
                + ", maxToolSteps=" + maxToolSteps + "]";
    }

    private static String mask(String secret) {
        return secret == null || secret.isEmpty() ? "<unset>" : "****";
    }

    private static Optional<String> text(Map<String, String> values, String key) {
        String value = values.get(key);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.strip());
    }

    private static int integer(Map<String, String> values, String key, int fallback, int min, int max,
                               List<String> problems) {
        Optional<String> raw = text(values, key);
        if (raw.isEmpty()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.get());
            if (value < min || value > max) {
                problems.add(key + " must be between " + min + " and " + max);
            }
            return value;
        } catch (NumberFormatException e) {
            problems.add(key + " must be a whole number");
            return fallback;
        }
    }
}
