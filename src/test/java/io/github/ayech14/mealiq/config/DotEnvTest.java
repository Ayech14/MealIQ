package io.github.ayech14.mealiq.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DotEnvTest {

    @Test
    void parsesKeysValuesCommentsAndQuotes() {
        Map<String, String> values = DotEnv.parse(List.of(
                "# comment",
                "",
                "LLM_MODEL=gpt-test",
                "  DATABASE_USER = mealiq  ",
                "export MAX_TOOL_STEPS=4",
                "LLM_API_KEY=\"quoted value\"",
                "DATABASE_PASSWORD='single'",
                "URL=jdbc:postgresql://h/db?a=b",
                "not a pair"));
        assertEquals(Map.of(
                "LLM_MODEL", "gpt-test",
                "DATABASE_USER", "mealiq",
                "MAX_TOOL_STEPS", "4",
                "LLM_API_KEY", "quoted value",
                "DATABASE_PASSWORD", "single",
                "URL", "jdbc:postgresql://h/db?a=b"), values);
    }

    @Test
    void missingFileGivesEmptyMap(@TempDir Path dir) {
        assertTrue(DotEnv.read(dir.resolve(".env")).isEmpty());
    }

    @Test
    void readsAFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(".env");
        Files.writeString(file, "LLM_MODEL=m\n");
        assertEquals(Map.of("LLM_MODEL", "m"), DotEnv.read(file));
    }
}
