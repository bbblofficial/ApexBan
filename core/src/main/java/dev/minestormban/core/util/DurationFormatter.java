package dev.minestormban.core.util;

/** Formats millisecond spans as {@code 1d 2h 3m 4s}. */
public final class DurationFormatter {

    private DurationFormatter() {
    }

    public static String format(long millis) {
        if (millis <= 0L) {
            return "0s";
        }
        long totalSeconds = (millis + 999L) / 1000L;
        long days = totalSeconds / 86_400L;
        long hours = (totalSeconds % 86_400L) / 3_600L;
        long minutes = (totalSeconds % 3_600L) / 60L;
        long seconds = totalSeconds % 60L;

        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("d ");
        }
        if (hours > 0) {
            sb.append(hours).append("h ");
        }
        if (minutes > 0) {
            sb.append(minutes).append("m ");
        }
        if (seconds > 0 || sb.length() == 0) {
            sb.append(seconds).append("s ");
        }
        return sb.toString().trim();
    }
}
