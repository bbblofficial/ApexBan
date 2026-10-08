package dev.minestormban.core.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.minestormban.core.model.Punishment;
import dev.minestormban.core.model.PunishmentScope;
import dev.minestormban.core.model.PunishmentType;
import dev.minestormban.core.model.Target;
import dev.minestormban.core.platform.MineStormLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Blocking JDBC access for SQLite and MySQL/MariaDB. Every method acquires exactly one pooled
 * connection, so it is safe to call concurrently, but callers must invoke it from a worker
 * thread (see {@code PunishmentManager}) and never from a server main/event-loop thread.
 *
 * <h2>Scope</h2>
 * Every punishment row carries a {@code scope}. A {@code SERVER} row is only enforced on the
 * server whose id is stored in its {@code server} column; a {@code GLOBAL} row is enforced on
 * every server that shares the database.
 *
 * <h2>Auto-missing</h2>
 * With {@code database.auto-repair} enabled the schema repairs itself:
 * <ul>
 *   <li>missing tables are created,</li>
 *   <li>missing columns are added (with safe defaults, so old databases keep working),</li>
 *   <li>missing indexes are created,</li>
 *   <li>if a table/column disappears while the server is running, the failing query triggers a
 *       repair and is retried once.</li>
 * </ul>
 */
public final class SqlStorage implements AutoCloseable {

    private static final Pattern PREFIX = Pattern.compile("[A-Za-z0-9_]{0,32}");
    private static final int MAX_REASON = 512;

    /** Column name + full definition (type, NOT NULL, DEFAULT). Valid on SQLite and MariaDB/MySQL. */
    private record Column(String name, String definition) {
    }

    /** Every column except the primary key, in creation order. */
    private static final List<Column> PUNISHMENT_COLUMNS = List.of(
            new Column("type", "type VARCHAR(8) NOT NULL DEFAULT 'BAN'"),
            new Column("uuid", "uuid VARCHAR(36) NOT NULL DEFAULT ''"),
            new Column("name", "name VARCHAR(32) NOT NULL DEFAULT ''"),
            new Column("operator", "operator VARCHAR(64) NOT NULL DEFAULT ''"),
            new Column("reason", "reason VARCHAR(512) NOT NULL DEFAULT ''"),
            new Column("created_at", "created_at BIGINT NOT NULL DEFAULT 0"),
            new Column("expires_at", "expires_at BIGINT NOT NULL DEFAULT -1"),
            new Column("server", "server VARCHAR(64) NOT NULL DEFAULT 'default'"),
            new Column("scope", "scope VARCHAR(8) NOT NULL DEFAULT 'SERVER'"),
            new Column("active", "active SMALLINT NOT NULL DEFAULT 0"),
            new Column("removed_by", "removed_by VARCHAR(64)"),
            new Column("removed_at", "removed_at BIGINT NOT NULL DEFAULT 0"),
            new Column("updated_at", "updated_at BIGINT NOT NULL DEFAULT 0"));

    private static final List<Column> PLAYER_COLUMNS = List.of(
            new Column("name", "name VARCHAR(32) NOT NULL DEFAULT ''"),
            new Column("name_lower", "name_lower VARCHAR(32) NOT NULL DEFAULT ''"),
            new Column("ip", "ip VARCHAR(64)"),
            new Column("last_seen", "last_seen BIGINT NOT NULL DEFAULT 0"));

    @FunctionalInterface
    private interface Op<T> {
        T run(Connection connection) throws SQLException;
    }

    private final HikariDataSource dataSource;
    private final Dialect dialect;
    private final String punishments;
    private final String players;
    private final boolean autoRepair;
    private final MineStormLogger logger;

    public SqlStorage(StorageSettings settings, MineStormLogger logger) throws IOException {
        if (!PREFIX.matcher(settings.tablePrefix()).matches()) {
            throw new IllegalArgumentException(
                    "database.table-prefix may only contain letters, digits and underscores (max 32)");
        }
        this.logger = logger;
        this.autoRepair = settings.autoRepair();
        this.dialect = settings.type();
        this.punishments = settings.tablePrefix() + "punishments";
        this.players = settings.tablePrefix() + "players";

        HikariConfig hc = new HikariConfig();
        hc.setPoolName("MineStormBan-Pool");
        hc.setConnectionTimeout(Math.max(1_000L, settings.connectionTimeoutMs()));
        if (dialect == Dialect.SQLITE) {
            Path file = settings.sqliteFile().toAbsolutePath();
            Files.createDirectories(file.getParent());
            hc.setDriverClassName("org.sqlite.JDBC");
            // IMPORTANT: no "?key=value" suffix here. Spigot/CraftBukkit 1.8 ships an OLD sqlite-jdbc
            // that wins over any bundled copy and treats the suffix as part of the file name, which
            // fails on Windows with "The filename, directory name, or volume label syntax is incorrect".
            // Forward slashes work on every OS and every driver version.
            hc.setJdbcUrl("jdbc:sqlite:" + file.toString().replace('\\', '/'));
            // Per-connection settings are applied as plain statements instead (works on old drivers too).
            hc.setConnectionInitSql("PRAGMA busy_timeout=5000");
            hc.setConnectionTestQuery("SELECT 1");
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

    // ------------------------------------------------------------------ schema (auto-missing)

    /**
     * Creates every missing table, column and index. Always runs once at start-up so that old or
     * partially created databases are brought up to date.
     */
    public void init() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            if (dialect == Dialect.SQLITE) {
                try (Statement st = c.createStatement()) {
                    st.execute("PRAGMA journal_mode=WAL"); // persistent setting; best effort
                } catch (SQLException ignored) {
                    // falls back to the default journal mode
                }
            }
            repair(c);
        }
    }

    /** Idempotent. Safe to run on a healthy database. */
    private synchronized void repair(Connection c) throws SQLException {
        List<String> changes = new ArrayList<>();
        createTables(c, changes);
        addMissingColumns(c, punishments, PUNISHMENT_COLUMNS, changes);
        addMissingColumns(c, players, PLAYER_COLUMNS, changes);
        ensureIndex(c, punishments, "lookup", "uuid, type, active", changes);
        ensureIndex(c, punishments, "updated", "updated_at", changes);
        ensureIndex(c, players, "name", "name_lower", changes);
        for (String change : changes) {
            logger.info("[auto-missing] " + change);
        }
    }

    private void createTables(Connection c, List<String> changes) throws SQLException {
        boolean punishmentsExisted = tableExists(c, punishments);
        boolean playersExisted = tableExists(c, players);
        try (Statement st = c.createStatement()) {
            if (dialect == Dialect.SQLITE) {
                st.execute("CREATE TABLE IF NOT EXISTS " + punishments + " (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + joinDefinitions(PUNISHMENT_COLUMNS) + ")");
                st.execute("CREATE TABLE IF NOT EXISTS " + players + " (uuid VARCHAR(36) PRIMARY KEY, "
                        + joinDefinitions(PLAYER_COLUMNS) + ")");
            } else {
                st.execute("CREATE TABLE IF NOT EXISTS " + punishments + " (id BIGINT NOT NULL AUTO_INCREMENT, "
                        + joinDefinitions(PUNISHMENT_COLUMNS) + ", PRIMARY KEY (id)) "
                        + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                st.execute("CREATE TABLE IF NOT EXISTS " + players + " (uuid VARCHAR(36) NOT NULL, "
                        + joinDefinitions(PLAYER_COLUMNS) + ", PRIMARY KEY (uuid)) "
                        + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            }
        }
        if (!punishmentsExisted) {
            changes.add("created missing table " + punishments);
        }
        if (!playersExisted) {
            changes.add("created missing table " + players);
        }
    }

    private static String joinDefinitions(List<Column> columns) {
        List<String> defs = new ArrayList<>(columns.size());
        for (Column column : columns) {
            defs.add(column.definition());
        }
        return String.join(", ", defs);
    }

    private boolean tableExists(Connection c, String table) throws SQLException {
        String sql = dialect == Dialect.SQLITE
                ? "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?"
                : "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private Set<String> columnsOf(Connection c, String table) throws SQLException {
        Set<String> found = new HashSet<>();
        if (dialect == Dialect.SQLITE) {
            // PRAGMA cannot be parameterised; the table name is validated by the prefix pattern.
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
                while (rs.next()) {
                    found.add(rs.getString("name").toLowerCase(Locale.ROOT));
                }
            }
        } else {
            String sql = "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, table);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        found.add(rs.getString(1).toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return found;
    }

    private void addMissingColumns(Connection c, String table, List<Column> expected, List<String> changes)
            throws SQLException {
        Set<String> present = columnsOf(c, table);
        for (Column column : expected) {
            if (present.contains(column.name())) {
                continue;
            }
            try (Statement st = c.createStatement()) {
                st.execute("ALTER TABLE " + table + " ADD COLUMN " + column.definition());
            }
            changes.add("added missing column " + table + "." + column.name());
        }
    }

    private String indexName(String table, String suffix) {
        // Names kept identical to the ones earlier versions created, so no duplicate indexes appear.
        return dialect == Dialect.SQLITE ? "idx_" + table + "_" + suffix : "idx_" + suffix;
    }

    private void ensureIndex(Connection c, String table, String suffix, String columns, List<String> changes)
            throws SQLException {
        String name = indexName(table, suffix);
        if (dialect == Dialect.SQLITE) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE INDEX IF NOT EXISTS " + name + " ON " + table + " (" + columns + ")");
            }
            return;
        }
        String check = "SELECT 1 FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ? LIMIT 1";
        try (PreparedStatement ps = c.prepareStatement(check)) {
            ps.setString(1, table);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return;
                }
            }
        }
        try (Statement st = c.createStatement()) {
            st.execute("CREATE INDEX " + name + " ON " + table + " (" + columns + ")");
        }
        changes.add("created missing index " + name + " on " + table);
    }

    private static boolean isMissingSchema(SQLException ex) {
        for (SQLException e = ex; e != null; e = e.getNextException()) {
            String state = e.getSQLState();
            int code = e.getErrorCode();
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
            if ("42S02".equals(state) || "42S22".equals(state) || code == 1146 || code == 1054
                    || msg.contains("no such table") || msg.contains("no such column")
                    || msg.contains("doesn't exist") || msg.contains("unknown column")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Runs {@code op} on one pooled connection. When it fails because a table or column is
     * missing, the schema is repaired and the operation is retried exactly once.
     */
    private <T> T exec(Op<T> op) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            try {
                return op.run(c);
            } catch (SQLException ex) {
                if (!autoRepair || !isMissingSchema(ex)) {
                    throw ex;
                }
                logger.warn("[auto-missing] database schema problem (" + ex.getMessage() + "); repairing and retrying");
                repair(c);
                return op.run(c);
            }
        }
    }

    // ------------------------------------------------------------------ punishments

    /** Inserts a record and returns its generated id. */
    public long insert(Punishment p) throws SQLException {
        String sql = "INSERT INTO " + punishments + " (type, uuid, name, operator, reason, created_at, "
                + "expires_at, server, scope, active, removed_by, removed_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        return exec(c -> {
            try (PreparedStatement ps = dialect == Dialect.SQLITE
                    ? c.prepareStatement(sql)
                    : c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, p.type().name());
                ps.setString(2, p.uuid().toString());
                ps.setString(3, truncate(p.name(), 32));
                ps.setString(4, truncate(p.operator(), 64));
                ps.setString(5, truncate(p.reason(), MAX_REASON));
                ps.setLong(6, p.createdAt());
                ps.setLong(7, p.expiresAt());
                ps.setString(8, truncate(p.server(), 64));
                ps.setString(9, p.scope().name());
                ps.setInt(10, p.active() ? 1 : 0);
                ps.setString(11, p.removedBy());
                ps.setLong(12, p.removedAt());
                ps.setLong(13, p.updatedAt());
                ps.executeUpdate();
                if (dialect != Dialect.SQLITE) {
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (keys.next()) {
                            return keys.getLong(1);
                        }
                    }
                }
            }
            if (dialect == Dialect.SQLITE) {
                // The pool holds a single connection for SQLite, so this is the row we just inserted.
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                    if (rs.next()) {
                        return rs.getLong(1);
                    }
                }
            }
            throw new SQLException("Insert succeeded but no generated key was returned");
        });
    }

    /**
     * Newest still-running punishment of the given type that is enforced on {@code serverId}
     * (a {@code GLOBAL} one, or a {@code SERVER} one issued by that server).
     */
    public Optional<Punishment> findActive(UUID uuid, PunishmentType type, long now, String serverId)
            throws SQLException {
        return exec(c -> findActive(c, uuid, type, now, serverId, false));
    }

    /** Newest still-running {@code GLOBAL} punishment of the given type. */
    public Optional<Punishment> findActiveGlobal(UUID uuid, PunishmentType type, long now) throws SQLException {
        return exec(c -> findActive(c, uuid, type, now, null, true));
    }

    private Optional<Punishment> findActive(Connection c, UUID uuid, PunishmentType type, long now,
                                            String serverId, boolean globalOnly) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(punishments)
                .append(" WHERE uuid = ? AND type = ? AND active = 1 AND (expires_at < 0 OR expires_at > ?)");
        if (globalOnly) {
            sql.append(" AND scope = 'GLOBAL'");
        } else if (serverId != null) {
            sql.append(" AND (scope = 'GLOBAL' OR server = ?)");
        }
        sql.append(" ORDER BY id DESC LIMIT 1");
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            ps.setString(1, uuid.toString());
            ps.setString(2, type.name());
            ps.setLong(3, now);
            if (!globalOnly && serverId != null) {
                ps.setString(4, serverId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    /**
     * Marks active punishments of a type as removed and returns the one that was in force.
     *
     * @param serverId only punishments enforced on this server are removed (a {@code SERVER} one
     *                 from another server is left alone); {@code null} removes them everywhere
     * @return empty when the player had nothing to remove
     */
    public Optional<Punishment> deactivate(UUID uuid, PunishmentType type, String removedBy, long now,
                                           String serverId) throws SQLException {
        return exec(c -> {
            Optional<Punishment> current = findActive(c, uuid, type, now, serverId, false);
            StringBuilder sql = new StringBuilder("UPDATE ").append(punishments)
                    .append(" SET active = 0, removed_by = ?, removed_at = ?, updated_at = ?")
                    .append(" WHERE uuid = ? AND type = ? AND active = 1");
            if (serverId != null) {
                sql.append(" AND (scope = 'GLOBAL' OR server = ?)");
            }
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                ps.setString(1, truncate(removedBy, 64));
                ps.setLong(2, now);
                ps.setLong(3, now);
                ps.setString(4, uuid.toString());
                ps.setString(5, type.name());
                if (serverId != null) {
                    ps.setString(6, serverId);
                }
                int changed = ps.executeUpdate();
                if (changed == 0) {
                    return Optional.<Punishment>empty();
                }
            }
            return current;
        });
    }

    /**
     * Ban/mute rows touched at or after {@code since} that are enforced on {@code serverId};
     * used for cross-server synchronisation.
     */
    public List<Punishment> changedSince(long since, String serverId) throws SQLException {
        String sql = "SELECT * FROM " + punishments + " WHERE updated_at >= ? AND type IN ('BAN', 'MUTE') "
                + "AND (scope = 'GLOBAL' OR server = ?) ORDER BY updated_at ASC LIMIT 500";
        return exec(c -> {
            List<Punishment> result = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, since);
                ps.setString(2, serverId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(map(rs));
                    }
                }
            }
            return result;
        });
    }

    // ------------------------------------------------------------------ players

    public void upsertPlayer(UUID uuid, String name, String ip, long now) throws SQLException {
        String safeName = truncate(name, 32);
        String safeIp = truncate(ip == null ? "" : ip, 64);
        exec(c -> {
            if (dialect == Dialect.SQLITE) {
                // Portable upsert that also works on the old SQLite versions bundled with Spigot 1.8.
                int updated;
                try (PreparedStatement ps = c.prepareStatement("UPDATE " + players
                        + " SET name = ?, name_lower = ?, ip = ?, last_seen = ? WHERE uuid = ?")) {
                    ps.setString(1, safeName);
                    ps.setString(2, safeName.toLowerCase(Locale.ROOT));
                    ps.setString(3, safeIp);
                    ps.setLong(4, now);
                    ps.setString(5, uuid.toString());
                    updated = ps.executeUpdate();
                }
                if (updated == 0) {
                    try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO " + players
                            + " (uuid, name, name_lower, ip, last_seen) VALUES (?, ?, ?, ?, ?)")) {
                        ps.setString(1, uuid.toString());
                        ps.setString(2, safeName);
                        ps.setString(3, safeName.toLowerCase(Locale.ROOT));
                        ps.setString(4, safeIp);
                        ps.setLong(5, now);
                        ps.executeUpdate();
                    }
                }
                return null;
            }
            String sql = "INSERT INTO " + players + " (uuid, name, name_lower, ip, last_seen) VALUES (?, ?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE name = VALUES(name), name_lower = VALUES(name_lower), "
                    + "ip = VALUES(ip), last_seen = VALUES(last_seen)";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, safeName);
                ps.setString(3, safeName.toLowerCase(Locale.ROOT));
                ps.setString(4, safeIp);
                ps.setLong(5, now);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public Optional<Target> findPlayerByName(String name) throws SQLException {
        String sql = "SELECT uuid, name FROM " + players + " WHERE name_lower = ? ORDER BY last_seen DESC LIMIT 1";
        return exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, name.toLowerCase(Locale.ROOT));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(new Target(UUID.fromString(rs.getString("uuid")), rs.getString("name")));
                    }
                }
            }
            return Optional.<Target>empty();
        });
    }

    // ------------------------------------------------------------------ helpers

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
                PunishmentScope.parse(rs.getString("scope"), PunishmentScope.SERVER),
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
