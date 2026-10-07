package dev.apexban.core.util;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Converts text written with a MiniMessage subset and/or legacy {@code &} codes into a legacy
 * section-sign ({@code §}) string that every platform can turn into a chat component.
 *
 * <p>Supported MiniMessage tags: the 16 named colours, {@code <#rrggbb>}, {@code <bold>},
 * {@code <italic>}, {@code <underlined>}, {@code <strikethrough>}, {@code <obfuscated>},
 * {@code <reset>}, {@code <newline>}/{@code <br>} and the matching closing tags. Legacy
 * {@code &a}-style codes and {@code &#rrggbb} hex codes are supported too. Unknown tags are
 * left untouched.
 */
public final class TextFormatter {

    private static final char SECTION = '\u00A7';
    private static final Map<String, Character> COLORS = new HashMap<>();
    private static final Map<String, Character> FORMATS = new HashMap<>();
    private static final char[] LEGACY_CODES = "0123456789abcdef".toCharArray();
    private static final int[][] LEGACY_RGB = {
            {0, 0, 0}, {0, 0, 170}, {0, 170, 0}, {0, 170, 170},
            {170, 0, 0}, {170, 0, 170}, {255, 170, 0}, {170, 170, 170},
            {85, 85, 85}, {85, 85, 255}, {85, 255, 85}, {85, 255, 255},
            {255, 85, 85}, {255, 85, 255}, {255, 255, 85}, {255, 255, 255}
    };

    static {
        COLORS.put("black", '0');
        COLORS.put("dark_blue", '1');
        COLORS.put("dark_green", '2');
        COLORS.put("dark_aqua", '3');
        COLORS.put("dark_red", '4');
        COLORS.put("dark_purple", '5');
        COLORS.put("gold", '6');
        COLORS.put("gray", '7');
        COLORS.put("grey", '7');
        COLORS.put("dark_gray", '8');
        COLORS.put("dark_grey", '8');
        COLORS.put("blue", '9');
        COLORS.put("green", 'a');
        COLORS.put("aqua", 'b');
        COLORS.put("red", 'c');
        COLORS.put("light_purple", 'd');
        COLORS.put("yellow", 'e');
        COLORS.put("white", 'f');

        FORMATS.put("bold", 'l');
        FORMATS.put("b", 'l');
        FORMATS.put("italic", 'o');
        FORMATS.put("i", 'o');
        FORMATS.put("em", 'o');
        FORMATS.put("underlined", 'n');
        FORMATS.put("u", 'n');
        FORMATS.put("strikethrough", 'm');
        FORMATS.put("st", 'm');
        FORMATS.put("obfuscated", 'k');
        FORMATS.put("obf", 'k');
    }

    private TextFormatter() {
    }

    private static final class State {
        String color;
        final Set<Character> formats = new LinkedHashSet<>();

        void reapply(StringBuilder out) {
            out.append(SECTION).append('r');
            if (color != null) {
                out.append(color);
            }
            for (char f : formats) {
                out.append(SECTION).append(f);
            }
        }
    }

    /**
     * @param supportsHex whether the target can render {@code §x§r§r§g§g§b§b}; otherwise hex
     *                    colours are approximated with the nearest of the 16 legacy colours
     */
    public static String format(String input, boolean supportsHex) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(input.length() + 16);
        State state = new State();
        int n = input.length();
        int i = 0;
        while (i < n) {
            char ch = input.charAt(i);
            if (ch == '<') {
                int end = input.indexOf('>', i + 1);
                if (end > i + 1 && end - i <= 40
                        && handleTag(input.substring(i + 1, end), out, state, supportsHex)) {
                    i = end + 1;
                    continue;
                }
            } else if (ch == '&' && i + 1 < n) {
                char next = input.charAt(i + 1);
                if (next == '#' && i + 8 <= n && isHex(input, i + 2, 6)) {
                    applyColor(hexSequence(input.substring(i + 2, i + 8), supportsHex), out, state);
                    i += 8;
                    continue;
                }
                char code = Character.toLowerCase(next);
                if (isLegacyCode(code)) {
                    handleLegacy(code, out, state);
                    i += 2;
                    continue;
                }
            }
            out.append(ch);
            i++;
        }
        return out.toString();
    }

    private static void handleLegacy(char code, StringBuilder out, State state) {
        if (code == 'r') {
            state.color = null;
            state.formats.clear();
            out.append(SECTION).append('r');
        } else if (isFormatCode(code)) {
            state.formats.add(code);
            out.append(SECTION).append(code);
        } else {
            state.color = String.valueOf(SECTION) + code;
            state.formats.clear();
            out.append(SECTION).append(code);
        }
    }

    private static boolean handleTag(String rawTag, StringBuilder out, State state, boolean hex) {
        String tag = rawTag.toLowerCase(Locale.ROOT);
        if (tag.equals("newline") || tag.equals("br")) {
            out.append('\n');
            return true;
        }
        boolean closing = tag.startsWith("/");
        String name = closing ? tag.substring(1) : tag;

        if (name.equals("reset") || name.equals("r")) {
            state.color = null;
            state.formats.clear();
            out.append(SECTION).append('r');
            return true;
        }
        if (name.startsWith("color:") || name.startsWith("colour:") || name.startsWith("c:")) {
            name = name.substring(name.indexOf(':') + 1);
        }

        Character legacy = COLORS.get(name);
        if (legacy != null) {
            if (closing) {
                state.color = null;
                state.reapply(out);
            } else {
                applyColor(String.valueOf(SECTION) + legacy, out, state);
            }
            return true;
        }
        if (name.length() == 7 && name.charAt(0) == '#' && isHex(name, 1, 6)) {
            if (closing) {
                state.color = null;
                state.reapply(out);
            } else {
                applyColor(hexSequence(name.substring(1), hex), out, state);
            }
            return true;
        }
        Character format = FORMATS.get(name);
        if (format != null) {
            if (closing) {
                state.formats.remove(format);
                state.reapply(out);
            } else {
                state.formats.add(format);
                out.append(SECTION).append(format.charValue());
            }
            return true;
        }
        return false;
    }

    /** MiniMessage colours keep active formats; legacy codes would drop them, so re-add them. */
    private static void applyColor(String sequence, StringBuilder out, State state) {
        state.color = sequence;
        out.append(sequence);
        for (char f : state.formats) {
            out.append(SECTION).append(f);
        }
    }

    private static String hexSequence(String rrggbb, boolean supportsHex) {
        String lower = rrggbb.toLowerCase(Locale.ROOT);
        if (supportsHex) {
            StringBuilder sb = new StringBuilder(14).append(SECTION).append('x');
            for (int k = 0; k < 6; k++) {
                sb.append(SECTION).append(lower.charAt(k));
            }
            return sb.toString();
        }
        int rgb = Integer.parseInt(lower, 16);
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int best = 0;
        long bestDistance = Long.MAX_VALUE;
        for (int k = 0; k < LEGACY_RGB.length; k++) {
            long dr = r - LEGACY_RGB[k][0];
            long dg = g - LEGACY_RGB[k][1];
            long db = b - LEGACY_RGB[k][2];
            long d = dr * dr + dg * dg + db * db;
            if (d < bestDistance) {
                bestDistance = d;
                best = k;
            }
        }
        return String.valueOf(SECTION) + LEGACY_CODES[best];
    }

    private static boolean isHex(String s, int from, int length) {
        if (from + length > s.length()) {
            return false;
        }
        for (int k = from; k < from + length; k++) {
            if (Character.digit(s.charAt(k), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLegacyCode(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || isFormatCode(c) || c == 'r';
    }

    private static boolean isFormatCode(char c) {
        return c == 'k' || c == 'l' || c == 'm' || c == 'n' || c == 'o';
    }
}
