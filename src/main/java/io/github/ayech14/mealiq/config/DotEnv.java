package io.github.ayech14.mealiq.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal reader for a local {@code .env} file: {@code KEY=VALUE} lines, with blank lines
 * and {@code #} comments ignored and optional surrounding quotes removed.
 */
public final class DotEnv {

    private DotEnv() {
    }

    /** Values from the file, or an empty map when the file does not exist. */
    public static Map<String, String> read(Path file) {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            return parse(Files.readAllLines(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    public static Map<String, String> parse(List<String> lines) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).strip();
            }
            int equals = line.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = line.substring(0, equals).strip();
            values.put(key, unquote(line.substring(equals + 1).strip()));
        }
        return values;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' || first == '\'') && first == last) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
