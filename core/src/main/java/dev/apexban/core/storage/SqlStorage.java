package dev.apexban.core.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.apexban.core.model.Punishment;
import dev.apexban.core.model.PunishmentType;
import dev.apexban.core.model.Target;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Blocking JDBC access for SQLite and MySQL/MariaDB. Every method acquires exactly one pooled
 * connection, so it is safe to call concurrently, but callers must invoke it from a worker
 * thread (see {@code PunishmentManager}) and never from a server main/event-loop thread.
 */
public final class SqlStorage implements AutoCloseable {

    private static final Pattern PREFIX = Pattern.compile("[A-Za-z0-9_]{0,32}");
    private static final int MAX_REASON = 512;

    private final HikariDataSource dataSource;
    private final Dialect dialect;
    private final String punishments;
    private final String players;

    public SqlStorage(StorageSettings settings) throws IOException {
        if (!PREFIX.matcher(settings.tablePrefix()).matches()) {
            throw new IllegalArgumentException(
                    "database.table-prefix may only contain letters, digits and underscores (max 32)");
        }
        this.dialect = settings.type();
        this.punishments = settings.tablePrefix() + "punishments";
        this.players = settings.tablePrefix() + "players";

        HikariConfig hc = new HikariConfig();
        hc.setPoolName("ApexBan-Pool");
        hc.setConnectionTimeout(Math.max(1_000L, settings.connectionTimeoutMs()));
        if (dialect == Dialect.SQLITE) {
            Path file = settings.sqliteFile().toAbsolutePath();
            Files.createDirectories(file.getParent());
            hc.setDriverClassName("org.sqlite.JDBC");
            hc.setJdbcUrl("jdbc:sqlite:" + file + "?journal_mode=WAL&busy_timeout=5000");
            hc.setMaximumPoolSize(1);
            hc.setMinimumIdle(1);
        } else {
            hc.setDriverClassName("org.mariadb.jdbc.Driver");
            hc.setJdbcUrl("jdbc:mariadb://" + settings.host() + ":" + settings.port() + "/"
                    + settings.database() + "?sslMode=" + (settings.useSsl() ? "trust" : "disable"));
            hc.setUsername(settings.username());
            hc.setPassword(settings.password());
            int size = Math.max(2, settings.poolSize());
            hc.setMaximumPoolSize(size);
            hc.setMinimumIdle(Math.min(2, size));
            hc.setMaxLifetime(1_500_000L);
            hc.setKeepaliveTime(300_000L);
        }
        this.dataSource = new HikariDataSource(hc);
    }

    /** Creates the schema if it does not exist yet. */
    public void init() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            for (String sql : schema()) {
                st.execute(sql);
            }
        }
    }

    private List<String> schema() {
        List<String> sql = new ArrayList<>();
        if (dialect == Dialect.SQLITE) {
            sql.add("CREATE TABLE IF NOT EXISTS " + punishments + " ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "type VARCHAR(8) NOT NULL, uuid VARCHAR(36) NOT NULL, name VARCHAR(32) NOT NULL, "
                    + "operator VARCHAR(64) NOT NULL, reason VARCHAR(512) NOT NULL, "
                    + "created_at BIGINT NOT NULL, expires_at BIGINT NOT NULL, server VARCHAR(64) NOT NULL, "
                    + "active SMALLINT NOT NULL, removed_by VARCHAR(64), "
                    + "removed_at BIGINT NOT NULL DEFAULT 0, updated_at BIGINT NOT NULL)");
            sql.add("CREATE INDEX IF NOT EXISTS idx_" + punishments + "_lookup ON " + punishments
                    + " (uuid, type, active)");
            sql.add("CREATE INDEX IF NOT EXISTS idx_" + punishments + "_updated ON " + punishments
                    + " (updated_at)");
            sql.add("CREATE TABLE IF NOT EXISTS " + players + " ("
                    + "uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(32) NOT NULL, "
                    + "name_lower VARCHAR(32) NOT NULL, ip VARCHAR(64), last_seen BIGINT NOT NULL)");
            sql.add("CREATE INDEX IF NOT EXISTS idx_" + players + "_name ON " + players + " (name_lower)");
        } else {
            sql.add("CREATE TABLE IF NOT EXISTS " + punishments + " ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT, "
                    + "type VARCHAR(8) NOT NULL, uuid VARCHAR(36) NOT NULL, name VARCHAR(32) NOT NULL, "
                    + "operator VARCHAR(64) NOT NULL, reason VARCHAR(512) NOT NULL, "
                    + "created_at BIGINT NOT NULL, expires_at BIGINT NOT NULL, server VARCHAR(64) NOT NULL, "
                    + "active SMALLINT NOT NULL, removed_by VARCHAR(64) NULL, "
                    + "removed_at BIGINT NOT NULL DEFAULT 0, updated_at BIGINT NOT NULL, "
                    + "PRIMARY KEY (id), INDEX idx_lookup (uuid, type, active), INDEX idx_updated (updated_at)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            sql.add("CREATE TABLE IF NOT EXISTS " + players + " ("
                    + "uuid VARCHAR(36) NOT NULL, name VARCHAR(32) NOT NULL, "
                    + "name_lower VARCHAR(32) NOT NULL, ip VARCHAR(64) NULL, last_seen BIGINT NOT NULL, "
                    + "PRIMARY KEY (uuid), INDEX idx_name (name_lower)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }
        return sql;
    }

    /** Inserts a record and returns its generated id. */
    public long insert(Punishment p) throws SQLException {
        String sql = "INSERT INTO " + punishments + " (type, uuid, name, operator, reason, created_at, "
                + "expires_at, server, active, removed_by, removed_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, p.type().name());
            ps.setString(2, p.uuid().toString());
            ps.setString(3, truncate(p.name(), 32));
            ps.setString(4, truncate(p.operator(), 64));
            ps.setString(5, truncate(p.reason(), MAX_REASON));
            ps.setLong(6, p.createdAt());
            ps.setLong(7, p.expiresAt());
            ps.setString(8, truncate(p.server(), 64));
            ps.setInt(9, p.active() ? 1 : 0);
            ps.setString(10, p.removedBy());
            ps.setLong(11, p.removedAt());
            ps.setLong(12, p.updatedAt());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
            throw new SQLException("Insert succeeded but no generated key was returned");
        }
    }

    /** Newest still-running punishment of the given type for the player. */
    public Optional<Punishment> findActive(UUID uuid, PunishmentType type, long now) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            return findActive(c, uuid, type, now);
        }
    }

    private Optional<Punishment> findActive(Connection c, UUID uuid, PunishmentType type, long now)
            throws SQLException {
        String sql = "SELECT * FROM " + punishments + " WHERE uuid = ? AND type = ? AND active = 1 "
                + "AND (expires_at < 0 OR expires_at > ?) ORDER BY id DESC LIMIT 1";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, type.name());
            ps.setLong(3, now);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    /**
     * Marks all active punishments of a type as removed and returns the one that was in force.
     * Empty when the player had nothing to remove.
     */
    public Optional<Punishment> deactivate(UUID uuid, PunishmentType type, String removedBy, long now)
            throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            Optional<Punishment> current = findActive(c, uuid, type, now);
            String sql = "UPDATE " + punishments + " SET active = 0, removed_by = ?, removed_at = ?, "
                    + "updated_at = ? WHERE uuid = ? AND type = ? AND active = 1";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, truncate(removedBy, 64));
                ps.setLong(2, now);
                ps.setLong(3, now);
                ps.setString(4, uuid.toString());
                ps.setString(5, type.name());
                int changed = ps.executeUpdate();
                if (changed == 0) {
                    return Optional.empty();
                }
            }
            return current;
        }
    }

    /** Ban/mute rows touched at or after {@code since}; used for cross-server synchronisation. */
    public List<Punishment> changedSince(long since) throws SQLException {
        String sql = "SELECT * FROM " + punishments + " WHERE updated_at >= ? AND type IN ('BAN', 'MUTE') "
                + "ORDER BY updated_at ASC LIMIT 500";
        List<Punishment> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, since);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(map(rs));
                }
            }
        }
        return result;
    }

    public void upsertPlayer(UUID uuid, String name, String ip, long now) throws SQLException {
        String sql;
        if (dialect == Dialect.SQLITE) {
            sql = "INSERT INTO " + players + " (uuid, name, name_lower, ip, last_seen) VALUES (?, ?, ?, ?, ?) "
                    + "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, name_lower = excluded.name_lower, "
                    + "ip = excluded.ip, last_seen = excluded.last_seen";
        } else {
            sql = "INSERT INTO " + players + " (uuid, name, name_lower, ip, last_seen) VALUES (?, ?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE name = VALUES(name), name_lower = VALUES(name_lower), "
                    + "ip = VALUES(ip), last_seen = VALUES(last_seen)";
        }
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, truncate(name, 32));
            ps.setString(3, truncate(name, 32).toLowerCase(Locale.ROOT));
            ps.setString(4, truncate(ip == null ? "" : ip, 64));
            ps.setLong(5, now);
            ps.executeUpdate();
        }
    }

    public Optional<Target> findPlayerByName(String name) throws SQLException {
        String sql = "SELECT uuid, name FROM " + players + " WHERE name_lower = ? ORDER BY last_seen DESC LIMIT 1";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new Target(UUID.fromString(rs.getString("uuid")), rs.getString("name")));
                }
            }
        }
        return Optional.empty();
    }

    private static Punishment map(ResultSet rs) throws SQLException {
        return new Punishment(
                rs.getLong("id"),
                PunishmentType.valueOf(rs.getString("type")),
                UUID.fromString(rs.getString("uuid")),
                rs.getString("name"),
                rs.getString("operator"),
                rs.getString("reason"),
                rs.getLong("created_at"),
                rs.getLong("expires_at"),
                rs.getString("server"),
                rs.getInt("active") == 1,
                rs.getString("removed_by"),
                rs.getLong("removed_at"),
                rs.getLong("updated_at"));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
