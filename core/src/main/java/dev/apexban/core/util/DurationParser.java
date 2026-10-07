package dev.apexban.core.util;

import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses compact durations such as {@code 30m}, {@code 2h}, {@code 7d}, {@code 1w}, {@code 1mo},
 * {@code 1y} and combinations such as {@code 1d12h30m}.
 *
 * <p>Units: s, m (minutes), h, d, w, mo (30 days), y (365 days).
 */
public final class DurationParser {

    private static final Pattern FULL =
            Pattern.compile("^(?:\\d{1,9}(?:mo|y|w|d|h|m|s))+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PART =
            Pattern.compile("(\\d{1,9})(mo|y|w|d|h|m|s)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOOKS_LIKE =
            Pattern.compile("^\\d+(?:mo|y|w|d|h|m|s)[a-z0-9]*$", Pattern.CASE_INSENSITIVE);

    /** Hard ceiling so arithmetic can never overflow (100 years). */
    public static final long HARD_MAX_MILLIS = 100L * 365L * 86_400_000L;

    private DurationParser() {
    }

    /** True if the token is a fully valid duration expression. */
    public static boolean isDuration(String token) {
        return token != null && FULL.matcher(token).matches();
    }

    /**
     * True if the token was clearly <em>meant</em> as a duration (digits followed by a unit-like
     * suffix) but is malformed, e.g. {@code 7days} or {@code 30min}.
     */
    public static boolean looksLikeDuration(String token) {
        return token != null && !isDuration(token) && LOOKS_LIKE.matcher(token).matches();
    }

    public static boolean isPermanentKeyword(String token) {
        if (token == null) {
            return false;
        }
        String t = token.toLowerCase(Locale.ROOT);
        return t.equals("perm") || t.equals("permanent") || t.equals("permanently");
    }

    /**
     * @return the duration in milliseconds, or empty when the token is invalid, zero, or exceeds
     *         {@link #HARD_MAX_MILLIS}
     */
    public static OptionalLong parse(String token) {
        if (!isDuration(token)) {
            return OptionalLong.empty();
        }
        Matcher m = PART.matcher(token);
        long total = 0L;
        while (m.find()) {
            long amount = Long.parseLong(m.group(1));
            long unit = unitMillis(m.group(2).toLowerCase(Locale.ROOT));
            try {
                total = Math.addExact(total, Math.multiplyExact(amount, unit));
            } catch (ArithmeticException ex) {
                return OptionalLong.empty();
            }
            if (total > HARD_MAX_MILLIS) {
                return OptionalLong.empty();
            }
        }
        return total <= 0L ? OptionalLong.empty() : OptionalLong.of(total);
    }

    private static long unitMillis(String unit) {
        return switch (unit) {
            case "s" -> 1_000L;
            case "m" -> 60_000L;
            case "h" -> 3_600_000L;
            case "d" -> 86_400_000L;
            case "w" -> 7L * 86_400_000L;
            case "mo" -> 30L * 86_400_000L;
            case "y" -> 365L * 86_400_000L;
            default -> throw new IllegalArgumentException("Unknown unit: " + unit);
        };
    }
}
