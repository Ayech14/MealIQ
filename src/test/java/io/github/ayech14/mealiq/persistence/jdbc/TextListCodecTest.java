package io.github.ayech14.mealiq.persistence.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ayech14.mealiq.domain.MealType;
import java.time.DayOfWeek;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TextListCodecTest {

    @Test
    void listsKeepOrderAndPunctuation() {
        List<String> steps = List.of("Heat oil, then add onion.", "Simmer; stir often.", "");
        assertEquals(steps, TextListCodec.decodeList(TextListCodec.encodeList(steps)));
    }

    @Test
    void emptyValuesRoundTrip() {
        assertTrue(TextListCodec.decodeList(TextListCodec.encodeList(List.of())).isEmpty());
        assertTrue(TextListCodec.decodeDayMinutes(TextListCodec.encodeDayMinutes(Map.of())).isEmpty());
    }

    @Test
    void setsAreSortedForStableStorage() {
        assertEquals(TextListCodec.encodeSet(Set.of("b", "a")), TextListCodec.encodeSet(Set.of("a", "b")));
    }

    @Test
    void enumsAndDayMinutesRoundTrip() {
        Set<MealType> types = Set.of(MealType.LUNCH, MealType.DINNER);
        assertEquals(types, TextListCodec.decodeEnums(TextListCodec.encodeEnums(types), MealType.class));
        Map<DayOfWeek, Integer> minutes = Map.of(DayOfWeek.MONDAY, 20, DayOfWeek.FRIDAY, 60);
        assertEquals(minutes, TextListCodec.decodeDayMinutes(TextListCodec.encodeDayMinutes(minutes)));
    }

    @Test
    void separatorInsideAValueIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> TextListCodec.encodeList(List.of("a\u001Fb")));
    }

    @Test
    void schemaScriptSplitsIntoStatements() {
        List<String> statements = Database.statements("-- comment\nCREATE TABLE a (x INT);\n\nCREATE TABLE b (y INT); -- end\n");
        assertEquals(List.of("CREATE TABLE a (x INT)", "CREATE TABLE b (y INT)"), statements);
    }
}
