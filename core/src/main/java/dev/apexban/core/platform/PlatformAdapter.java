package dev.apexban.core.platform;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything the shared core needs from the host platform (Bukkit, BungeeCord or Velocity).
 * All methods must be callable from any thread.
 */
public interface PlatformAdapter {

    String platformName();

    String pluginVersion();

    /** Whether {@code §x§r§r§g§g§b§b} hex colours can be rendered. */
    boolean supportsHexColors();

    /** True when the host verifies accounts with Mojang (UUIDs cannot be guessed offline). */
    boolean isOnlineMode();

    ApexLogger logger();

    Path dataFolder();

    Optional<ApexPlayer> findOnline(UUID uuid);

    Collection<String> onlinePlayerNames();

    /** Case-insensitive exact name match. */
    Optional<ApexPlayer> findOnline(String name);

    /** Disconnects the player with the given legacy-coloured message, handling thread hand-off. */
    void kick(UUID uuid, String legacyMessage);

    /**
     * Sends a message to the console and to every online player holding {@code permission}
     * (or to everyone when {@code permission} is {@code null}).
     */
    void broadcast(String legacyMessage, String permission);

    /**
     * Resolves a never-seen player's UUID. Called from a worker thread, so it may block.
     * Return empty if the UUID cannot be determined reliably.
     */
    Optional<UUID> resolveOfflineUuid(String name);
}
