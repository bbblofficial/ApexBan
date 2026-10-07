package dev.apexban.bukkit;

import dev.apexban.core.platform.ApexLogger;
import dev.apexban.core.platform.ApexPlayer;
import dev.apexban.core.platform.PlatformAdapter;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Bukkit implementation of the platform adapter. Online players are tracked in thread-safe maps
 * so worker threads never have to touch Bukkit's player collections.
 */
final class BukkitPlatform implements PlatformAdapter {

    private final JavaPlugin plugin;
    private final boolean hexSupport;
    private final Map<UUID, Player> online = new ConcurrentHashMap<>();
    private final Map<String, UUID> byName = new ConcurrentHashMap<>();

    private final ApexLogger logger = new ApexLogger() {
        @Override
        public void info(String message) {
            plugin.getLogger().info(message);
        }

        @Override
        public void warn(String message) {
            plugin.getLogger().warning(message);
        }

        @Override
        public void error(String message, Throwable error) {
            plugin.getLogger().log(Level.SEVERE, message, error);
        }
    };

    BukkitPlatform(JavaPlugin plugin) {
        this.plugin = plugin;
        this.hexSupport = detectHexSupport();
    }

    private static boolean detectHexSupport() {
        try {
            Class.forName("net.md_5.bungee.api.ChatColor").getMethod("of", String.class);
            return true;
        } catch (ReflectiveOperationException | LinkageError ex) {
            return false;
        }
    }

    void track(Player player) {
        online.put(player.getUniqueId(), player);
        byName.put(player.getName().toLowerCase(Locale.ROOT), player.getUniqueId());
    }

    void untrack(Player player) {
        online.remove(player.getUniqueId());
        byName.remove(player.getName().toLowerCase(Locale.ROOT), player.getUniqueId());
    }

    @Override
    public String platformName() {
        return "Bukkit/" + Bukkit.getName();
    }

    @Override
    public String pluginVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean supportsHexColors() {
        return hexSupport;
    }

    @Override
    public boolean isOnlineMode() {
        return Bukkit.getOnlineMode();
    }

    @Override
    public ApexLogger logger() {
        return logger;
    }

    @Override
    public Path dataFolder() {
        return plugin.getDataFolder().toPath();
    }

    @Override
    public Optional<ApexPlayer> findOnline(UUID uuid) {
        Player player = online.get(uuid);
        return player == null ? Optional.empty() : Optional.of(new BukkitPlayerAdapter(player));
    }

    @Override
    public Optional<ApexPlayer> findOnline(String name) {
        UUID uuid = byName.get(name.toLowerCase(Locale.ROOT));
        return uuid == null ? Optional.empty() : findOnline(uuid);
    }

    @Override
    public Collection<String> onlinePlayerNames() {
        Collection<String> names = new ArrayList<>(online.size());
        for (Player player : online.values()) {
            names.add(player.getName());
        }
        return names;
    }

    @Override
    public void kick(UUID uuid, String legacyMessage) {
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = online.get(uuid);
            if (player != null && player.isOnline()) {
                player.kickPlayer(legacyMessage);
            }
        });
    }

    @Override
    public void broadcast(String legacyMessage, String permission) {
        Bukkit.getConsoleSender().sendMessage(legacyMessage);
        for (Player player : online.values()) {
            if (permission == null || player.hasPermission(permission)) {
                player.sendMessage(legacyMessage);
            }
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public Optional<UUID> resolveOfflineUuid(String name) {
        // Runs on a worker thread. Bukkit may contact Mojang here, which is acceptable off-thread.
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        if (offline != null && (offline.hasPlayedBefore() || !Bukkit.getOnlineMode())) {
            return Optional.ofNullable(offline.getUniqueId());
        }
        return Optional.empty();
    }
}
