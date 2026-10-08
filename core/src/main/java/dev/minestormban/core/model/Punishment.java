package dev.minestormban.core.model;

import java.util.UUID;

/**
 * Immutable punishment record.
 *
 * @param id        database id (0 before insertion)
 * @param expiresAt epoch millis, or {@code -1} when permanent
 * @param server    server-id that issued the punishment
 * @param scope     whether it is enforced only on {@code server} or on every server
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
        PunishmentScope scope,
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

    /** True when this punishment is enforced on the server with the given id. */
    public boolean appliesTo(String serverId) {
        return scope == PunishmentScope.GLOBAL || (server != null && server.equals(serverId));
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
                server, scope, active, removedBy, removedAt, updatedAt);
    }
}
