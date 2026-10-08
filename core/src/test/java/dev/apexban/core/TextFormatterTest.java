package dev.minestormban.core;

import dev.minestormban.core.util.TextFormatter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextFormatterTest {

    private static final String S = "\u00A7";

    @Test
    void convertsLegacyCodes() {
        assertEquals(S + "aHello", TextFormatter.format("&aHello", true));
    }

    @Test
    void convertsMiniMessageColours() {
        assertEquals(S + "cHi", TextFormatter.format("<red>Hi", true));
    }

    @Test
    void convertsHexWhenSupported() {
        assertEquals(S + "x" + S + "f" + S + "f" + S + "0" + S + "0" + S + "0" + S + "0" + "x",
                TextFormatter.format("<#ff0000>x", true));
    }

    @Test
    void downsamplesHexWhenUnsupported() {
        assertEquals(S + "4" + "x", TextFormatter.format("&#ff0000x", false));
    }

    @Test
    void leavesUnknownTagsAlone() {
        assertEquals("/ban <player> [duration]", TextFormatter.format("/ban <player> [duration]", true));
    }

    @Test
    void handlesNewlines() {
        assertEquals("a\nb", TextFormatter.format("a<newline>b", true));
    }
}
