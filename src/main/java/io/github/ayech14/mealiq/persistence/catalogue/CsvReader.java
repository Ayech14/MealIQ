package io.github.ayech14.mealiq.persistence.catalogue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal RFC 4180 reader for the catalogue: comma-separated, fields optionally quoted,
 * {@code ""} inside quotes is a literal quote, and quoted fields may span lines.
 */
final class CsvReader {

    private CsvReader() {
    }

    /** Rows keyed by the header line's column names, in file order. */
    static List<Map<String, String>> readWithHeader(String text) {
        List<List<String>> rows = parse(text);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> header = rows.getFirst();
        List<Map<String, String>> records = new ArrayList<>();
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (row.size() != header.size()) {
                throw new IllegalArgumentException("Line " + (r + 1) + " has " + row.size()
                        + " fields, expected " + header.size());
            }
            Map<String, String> record = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                record.put(header.get(c), row.get(c));
            }
            records.add(record);
        }
        return records;
    }

    static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                addUnlessBlank(rows, row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
            i++;
        }
        if (quoted) {
            throw new IllegalArgumentException("Unterminated quoted field");
        }
        row.add(field.toString());
        addUnlessBlank(rows, row);
        return rows;
    }

    private static void addUnlessBlank(List<List<String>> rows, List<String> row) {
        if (!(row.size() == 1 && row.getFirst().isEmpty())) {
            rows.add(row);
        }
    }
}
