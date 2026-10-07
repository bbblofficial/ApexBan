package dev.apexban.core;

import dev.apexban.core.command.CommandHandler;
import dev.apexban.core.config.ConfigManager;
import dev.apexban.core.config.YamlFile;
import dev.apexban.core.manager.PunishmentManager;
import dev.apexban.core.model.Punishment;
import dev.apexban.core.model.PunishmentType;
import dev.apexban.core.platform.ApexSender;
import dev.apexban.core.platform.PlatformAdapter;
import dev.apexban.core.storage.Dialect;
import dev.apexban.core.storage.SqlStorage;
import dev.apexban.core.storage.StorageSettings;
import dev.apexban.core.util.DurationFormatter;
import dev.apexban.core.util.DurationParser;
import dev.apexban.core.util.Placeholders;
import dev.apexban.core.util.TextFormatter;

import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Platform-independent entry point shared by the Bukkit, BungeeCord and Velocity modules. */
public final class ApexCore {

    public static final String NOTIFY_PERMISSION = "apexban.notify";

    private final PlatformAdapter platform;
    private final ConfigManager configs;

    private ScheduledExecutorService executor;
    private SqlStorage storage;
    private PunishmentManager manager;
    private CommandHandler commands;

    private volatile DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("UTC"));
    private volatile Set<String> blockedMuteCommands = Set.of();
    private volatile long maxDurationMillis = DurationParser.HARD_MAX_MILLIS;
    private volatile String serverId = "default";
    private volatile boolean failOpen = true;

    public ApexCore(PlatformAdapter platform) {
        this.platform = platform;
        this.configs = new ConfigManager(platform.dataFolder(), platform.logger());
    }

    // ------------------------------------------------------------------ lifecycle

    public void enable() throws Exception {
        configs.load();
        applySettings();

        storage = new SqlStorage(readStorageSettings());
        try {
            storage.init();
        } catch (Exception ex) {
            storage.close();
            throw ex;
        }

        AtomicInteger counter = new AtomicInteger();
        ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(4, runnable -> {
            Thread t = new Thread(runnable, "ApexBan-Worker-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        pool.setRemoveOnCancelPolicy(true);
        executor = pool;

        manager = new PunishmentManager(this, storage, executor);
        manager.startSync(configs.config().getInt("sync.poll-interval-seconds", 5));
        commands = new CommandHandler(this);
        platform.logger().info("ApexBan " + platform.pluginVersion() + " enabled on " + platform.platformName()
                + " (server-id=" + serverId + ", storage=" + readStorageSettings().type() + ")");
    }

    public void disable() {
        if (manager != null) {
            manager.stopSync();
        }
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException ex) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (storage != null) {
            storage.close();
        }
    }

    /** Reloads the YAML files. Database credentials need a full restart. */
    public void reload() throws Exception {
        configs.load();
        applySettings();
        manager.startSync(configs.config().getInt("sync.poll-interval-seconds", 5));
    }

    private void applySettings() {
        YamlFile c = configs.config();
        serverId = c.getString("server-id", "default");
        failOpen = c.getBoolean("database.fail-open", true);

        String zone = c.getString("timezone", "UTC");
        String pattern = c.getString("date-format", "yyyy-MM-dd HH:mm:ss");
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(zone);
        } catch (DateTimeException ex) {
            platform.logger().warn("Invalid timezone '" + zone + "' in config.yml, using UTC");
            zoneId = ZoneId.of("UTC");
        }
        try {
            dateFormat = DateTimeFormatter.ofPattern(pattern).withZone(zoneId);
        } catch (IllegalArgumentException ex) {
            platform.logger().warn("Invalid date-format '" + pattern + "' in config.yml, using default");
            dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zoneId);
        }

        Set<String> blocked = new HashSet<>();
        for (String cmd : c.getStringList("mute.blocked-commands")) {
            String normalized = cmd.trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("/")) {
                normalized = normalized.substring(1);
            }
            if (!normalized.isEmpty()) {
                blocked.add(normalized);
            }
        }
        blockedMuteCommands = Set.copyOf(blocked);

        OptionalLong max = DurationParser.parse(c.getString("limits.max-duration", "5y"));
        maxDurationMillis = max.isPresent() ? max.getAsLong() : DurationParser.HARD_MAX_MILLIS;
    }

    private StorageSettings readStorageSettings() {
        YamlFile c = configs.config();
        String type = c.getString("database.type", "sqlite").trim().toLowerCase(Locale.ROOT);
        Dialect dialect = (type.equals("mysql") || type.equals("mariadb")) ? Dialect.MYSQL : Dialect.SQLITE;
        Path sqlite = platform.dataFolder().resolve(c.getString("database.sqlite.file", "apexban.db"));
        return new StorageSettings(
                dialect,
                c.getString("database.table-prefix", "apexban_"),
                sqlite,
                c.getString("database.mysql.host", "127.0.0.1"),
                c.getInt("database.mysql.port", 3306),
                c.getString("database.mysql.database", "apexban"),
                c.getString("database.mysql.username", "root"),
                c.getString("database.mysql.password", ""),
                c.getBoolean("database.mysql.use-ssl", false),
                c.getInt("database.mysql.pool-size", 8),
                c.getInt("database.mysql.connection-timeout-ms", 10_000));
    }

    // ------------------------------------------------------------------ accessors

    public PlatformAdapter platform() {
        return platform;
    }

    public PunishmentManager manager() {
        return manager;
    }

    public CommandHandler commands() {
        return commands;
    }

    public YamlFile config() {
        return configs.config();
    }

    public String serverId() {
        return serverId;
    }

    public boolean failOpen() {
        return failOpen;
    }

    public long maxDurationMillis() {
        return maxDurationMillis;
    }

    public Set<String> blockedMuteCommands() {
        return blockedMuteCommands;
    }

    public String defaultReason(PunishmentType type) {
        return switch (type) {
            case BAN -> configs.config().getString("defaults.ban-reason", "No reason specified");
            case MUTE -> configs.config().getString("defaults.mute-reason", "No reason specified");
            case KICK -> configs.config().getString("defaults.kick-reason", "No reason specified");
        };
    }

    // ------------------------------------------------------------------ text rendering

    public String text(String path) {
        return text(path, Map.of());
    }

    /** Looks up a messages.yml entry, applies placeholders and colour formatting. */
    public String text(String path, Map<String, String> placeholders) {
        YamlFile messages = configs.messages();
        String raw = messages.getString(path, "&cMissing message: " + path);
        Map<String, String> all = new LinkedHashMap<>();
        all.put("prefix", messages.getString("prefix", ""));
        all.putAll(placeholders);
        return TextFormatter.format(Placeholders.apply(raw, all), platform.supportsHexColors());
    }

    public Map<String, String> placeholders(Punishment p) {
        long now = System.currentTimeMillis();
        YamlFile messages = configs.messages();
        String permanent = messages.getString("time.permanent", "Permanent");
        String never = messages.getString("time.never", "Never");

        Map<String, String> m = new LinkedHashMap<>();
        m.put("player", p.name());
        m.put("reason", p.reason());
        m.put("operator", p.operator());
        m.put("id", String.valueOf(p.id()));
        m.put("server", p.server());
        m.put("type", p.type().name().toLowerCase(Locale.ROOT));
        m.put("date", formatDate(p.createdAt()));
        m.put("duration", p.permanent() ? permanent : DurationFormatter.format(p.totalDuration()));
        m.put("expires", p.permanent() ? never : formatDate(p.expiresAt()));
        m.put("remaining", p.permanent() ? permanent : DurationFormatter.format(p.remaining(now)));
        return m;
    }

    public String formatDate(long epochMillis) {
        return dateFormat.format(Instant.ofEpochMilli(epochMillis));
    }

    /** Renders a multi-line template from layout.yml for the given punishment. */
    public String layout(String path, Punishment p) {
        List<String> lines = configs.layout().getStringList(path);
        String joined = String.join("\n", lines);
        return TextFormatter.format(Placeholders.apply(joined, placeholders(p)), platform.supportsHexColors());
    }

    public String banScreen(Punishment p) {
        return layout(p.permanent() ? "ban.permanent" : "ban.temporary", p);
    }

    public String kickScreen(Punishment p) {
        return layout("kick", p);
    }

    // ------------------------------------------------------------------ announcements

    /**
     * @param key          path below {@code broadcast.} in messages.yml, e.g. {@code ban.permanent}
     * @param announcement toggle name below {@code announcements.} in config.yml
     */
    public void announce(String key, String announcement, Map<String, String> placeholders, boolean silent) {
        String message = text("broadcast." + key, placeholders);
        boolean isPublic = configs.config().getBoolean("announcements." + announcement, true);
        if (silent) {
            platform.broadcast(text("broadcast.silent-prefix") + message, NOTIFY_PERMISSION);
        } else if (isPublic) {
            platform.broadcast(message, null);
        } else {
            platform.broadcast(message, NOTIFY_PERMISSION);
        }
    }

    /** True when the sender will already see the broadcast, so no extra confirmation is needed. */
    public boolean announceReaches(ApexSender sender, String announcement, boolean silent) {
        if (!silent && configs.config().getBoolean("announcements." + announcement, true)) {
            return true;
        }
        return sender.hasPermission(NOTIFY_PERMISSION);
    }
}
