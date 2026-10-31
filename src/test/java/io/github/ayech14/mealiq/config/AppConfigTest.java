package io.github.ayech14.mealiq.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AppConfigTest {

    @Test
    void defaultsPointToLocalPostgresAndHaveNoLlm() {
        AppConfig config = AppConfig.from(Map.of());
        assertEquals(AppConfig.DEFAULT_DATABASE_URL, config.databaseUrl());
        assertEquals("mealiq", config.databaseUser());
        assertEquals(6, config.maxToolSteps());
        assertEquals(2, config.llmMaxRetries());
        assertEquals(Duration.ofSeconds(60), config.llmTimeout());
        assertFalse(config.llmConfigured());
    }

    @Test
    void valuesAreReadFromTheMap() {
        AppConfig config = AppConfig.from(Map.of(
                "LLM_API_KEY", "test-key",
                "LLM_MODEL", "test-model",
                "MAX_TOOL_STEPS", "4",
                "LLM_TIMEOUT_SECONDS", "30"));
        assertEquals("test-model", config.llmModel().orElseThrow());
        assertEquals(4, config.maxToolSteps());
        assertEquals(Duration.ofSeconds(30), config.llmTimeout());
        assertTrue(config.llmConfigured());
    }

    @Test
    void secretsAreMaskedInToString() {
        AppConfig config = AppConfig.from(Map.of("LLM_API_KEY", "secret-value", "DATABASE_PASSWORD", "db-secret"));
        assertFalse(config.toString().contains("secret-value"));
        assertFalse(config.toString().contains("db-secret"));
        assertEquals("secret-value", config.llmApiKey().orElseThrow());
    }

    @Test
    void keyWithoutModelIsNotConfigured() {
        assertFalse(AppConfig.from(Map.of("LLM_API_KEY", "k")).llmConfigured());
    }

    @Test
    void blankValuesCountAsUnset() {
        assertFalse(AppConfig.from(Map.of("LLM_API_KEY", "  ", "LLM_MODEL", "m")).llmConfigured());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "21", "abc"})
    void toolStepLimitIsBounded(String steps) {
        assertThrows(ConfigException.class, () -> AppConfig.from(Map.of("MAX_TOOL_STEPS", steps)));
    }

    @Test
    void databaseUrlMustBeJdbc() {
        assertThrows(ConfigException.class, () -> AppConfig.from(Map.of("DATABASE_URL", "postgres://x")));
    }

    @Test
    void allProblemsAreReportedTogether() {
        ConfigException error = assertThrows(ConfigException.class, () -> AppConfig.from(Map.of(
                "DATABASE_URL", "nope", "MAX_TOOL_STEPS", "0", "LLM_MAX_RETRIES", "9")));
        assertEquals(3, error.problems().size());
    }
}
