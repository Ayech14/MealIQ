package io.github.ayech14.mealiq.persistence.jdbc;

import java.time.DayOfWeek;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Stores a list of short strings in one TEXT column, separated by the ASCII unit separator
 * (U+001F), a control character that never appears in user-entered text. Sets are sorted
 * first so the stored value is stable.
 */
final class TextListCodec {

    private static final String SEPARATOR = "\u001F";

    private TextListCodec() {
    }

    static String encodeList(List<String> values) {
        for (String value : values) {
            if (value.contains(SEPARATOR)) {
                throw new IllegalArgumentException("Value contains a reserved control character");
            }
        }
        return String.join(SEPARATOR, values);
    }

    static String encodeSet(Collection<String> values) {
        return encodeList(List.copyOf(new TreeSet<>(values)));
    }

    static List<String> decodeList(String text) {
        return text == null || text.isEmpty() ? List.of() : List.of(text.split(SEPARATOR, -1));
    }

    static Set<String> decodeSet(String text) {
        return Set.copyOf(decodeList(text));
    }

    static <E extends Enum<E>> String encodeEnums(Collection<E> values) {
        return encodeSet(values.stream().map(Enum::name).toList());
    }

    static <E extends Enum<E>> Set<E> decodeEnums(String text, Class<E> type) {
        return decodeList(text).stream().map(name -> Enum.valueOf(type, name)).collect(Collectors.toUnmodifiableSet());
    }

    /** {MONDAY=30, WEDNESDAY=45} is stored as "MONDAY=30" and "WEDNESDAY=45" entries. */
    static String encodeDayMinutes(Map<DayOfWeek, Integer> minutes) {
        return encodeList(new TreeMap<>(minutes).entrySet().stream()
                .map(e -> e.getKey().name() + "=" + e.getValue())
                .toList());
    }

    static Map<DayOfWeek, Integer> decodeDayMinutes(String text) {
        Map<DayOfWeek, Integer> minutes = new EnumMap<>(DayOfWeek.class);
        for (String entry : decodeList(text)) {
            String[] parts = entry.split("=", 2);
            minutes.put(DayOfWeek.valueOf(parts[0]), Integer.parseInt(parts[1]));
        }
        return minutes;
    }
}
