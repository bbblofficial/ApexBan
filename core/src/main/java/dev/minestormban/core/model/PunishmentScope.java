package dev.minestormban.core.model;

import java.util.Locale;

/**
 * Where a punishment is enforced.
 *
 * <ul>
 *   <li>{@link #SERVER} - only on the server (server-id) that issued it.</li>
 *   <li>{@link #GLOBAL} - on every server that shares the database.</li>
 * </ul>
 */
public enum PunishmentScope {
    SERVER,
    GLOBAL;

    /** Lenient parser for config values and database rows; unknown input returns the fallback. */
    public static PunishmentScope parse(String value, PunishmentScope fallback) {
        if (value == null) {
            return fallback;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "server", "local", "this", "single" -> SERVER;
            case "global", "network", "all" -> GLOBAL;
            default -> fallback;
        };
    }
}
