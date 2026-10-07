package dev.apexban.core;

import dev.apexban.core.util.DurationFormatter;
import dev.apexban.core.util.DurationParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurationParserTest {

    @Test
    void parsesSingleUnits() {
        assertEquals(30L * 60_000L, DurationParser.parse("30m").getAsLong());
        assertEquals(2L * 3_600_000L, DurationParser.parse("2h").getAsLong());
        assertEquals(7L * 86_400_000L, DurationParser.parse("7d").getAsLong());
        assertEquals(30L * 86_400_000L, DurationParser.parse("1mo").getAsLong());
        assertEquals(365L * 86_400_000L, DurationParser.parse("1Y").getAsLong());
    }

    @Test
    void parsesCombinations() {
        assertEquals(86_400_000L + 12L * 3_600_000L, DurationParser.parse("1d12h").getAsLong());
        assertEquals(5_400_000L, DurationParser.parse("1h30m").getAsLong());
    }

    @Test
    void rejectsInvalidInput() {
        assertFalse(DurationParser.isDuration("griefing"));
        assertFalse(DurationParser.isDuration("7days"));
        assertTrue(DurationParser.parse("0m").isEmpty());
        assertTrue(DurationParser.parse("999999999y").isEmpty());
        assertTrue(DurationParser.parse("").isEmpty());
    }

    @Test
    void detectsMalformedDurations() {
        assertTrue(DurationParser.looksLikeDuration("7days"));
        assertTrue(DurationParser.looksLikeDuration("30min"));
        assertFalse(DurationParser.looksLikeDuration("7d"));
        assertFalse(DurationParser.looksLikeDuration("hacking"));
        assertFalse(DurationParser.looksLikeDuration("2nd"));
    }

    @Test
    void recognisesPermanentKeywords() {
        assertTrue(DurationParser.isPermanentKeyword("perm"));
        assertTrue(DurationParser.isPermanentKeyword("PERMANENT"));
        assertFalse(DurationParser.isPermanentKeyword("permission"));
    }

    @Test
    void formatsDurations() {
        assertEquals("1d 2h 3m 4s", DurationFormatter.format(((26L * 60 + 3) * 60 + 4) * 1000L));
        assertEquals("0s", DurationFormatter.format(0));
    }
}
