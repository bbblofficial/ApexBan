package dev.apexban.core.model;

import java.util.UUID;

/**
 * Immutable punishment record.
 *
 * @param id        database id (0 before insertion)
 * @param expiresAt epoch millis, or {@code -1} when permanent
 */
public record Punishment(
        long id,
        PunishmentType type,
        UUID uuid,
        String name,
        String operator,
        String reason,
        long createdAt,
        long expiresAt,
        String server,
        boolean active,
        String removedBy,
        long removedAt,
        long updatedAt) {

    public boolean permanent() {
        return expiresAt < 0;
    }

    public boolean expired(long now) {
        return expiresAt >= 0 && now >= expiresAt;
    }

    /** True when the record is flagged active and has not yet run out. */
    public boolean activeNow(long now) {
        return active && !expired(now);
    }

    /** Original length in millis, or {@code -1} when permanent. */
    public long totalDuration() {
        return permanent() ? -1L : Math.max(0L, expiresAt - createdAt);
    }

    public long remaining(long now) {
        return permanent() ? -1L : Math.max(0L, expiresAt - now);
    }

    public Punishment withId(long newId) {
        return new Punishment(newId, type, uuid, name, operator, reason, createdAt, expiresAt,
                server, active, removedBy, removedAt, updatedAt);
    }
}
