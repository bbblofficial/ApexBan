package dev.apexban.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.apexban.core.platform.ApexLogger;
import dev.apexban.core.platform.ApexPlayer;
import dev.apexban.core.platform.PlatformAdapter;
import dev.apexban.core.util.UuidUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

final class VelocityPlatform implements PlatformAdapter {

    /** Parses the {@code §x§r§r§g§g§b§b} hex format the shared core emits. */
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('\u00A7')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private final ProxyServer proxy;
    private final Logger slf4j;
    private final Path dataFolder;
    private final String version;

    private final ApexLogger logger = new ApexLogger() {
        @Override
        public void info(String message) {
            slf4j.info(message);
        }

        @Override
        public void warn(String message) {
            slf4j.warn(message);
        }

        @Override
        public void error(String message, Throwable error) {
            slf4j.error(message, error);
        }
    };

    VelocityPlatform(ProxyServer proxy, Logger slf4j, Path dataFolder, String version) {
        this.proxy = proxy;
        this.slf4j = slf4j;
        this.dataFolder = dataFolder;
        this.version = version;
    }

    static Component component(String legacy) {
        return LEGACY.deserialize(legacy);
    }

    @Override
    public String platformName() {
        return "Velocity";
    }

    @Override
    public String pluginVersion() {
        return version;
    }

    @Override
    public boolean supportsHexColors() {
        return true;
    }

    @Override
    public boolean isOnlineMode() {
        return proxy.getConfiguration().isOnlineMode();
    }

    @Override
    public ApexLogger logger() {
        return logger;
    }

    @Override
    public Path dataFolder() {
        return dataFolder;
    }

    @Override
    public Optional<ApexPlayer> findOnline(UUID uuid) {
        return proxy.getPlayer(uuid).map(VelocityPlayerAdapter::new);
    }

    @Override
    public Optional<ApexPlayer> findOnline(String name) {
        return proxy.getPlayer(name).map(VelocityPlayerAdapter::new);
    }

    @Override
    public Collection<String> onlinePlayerNames() {
        Collection<String> names = new ArrayList<>();
        for (Player player : proxy.getAllPlayers()) {
            names.add(player.getUsername());
        }
        return names;
    }

    @Override
    public void kick(UUID uuid, String legacyMessage) {
        proxy.getPlayer(uuid).ifPresent(player -> player.disconnect(component(legacyMessage)));
    }

    @Override
    public void broadcast(String legacyMessage, String permission) {
        Component message = component(legacyMessage);
        proxy.getConsoleCommandSource().sendMessage(message);
        for (Player player : proxy.getAllPlayers()) {
            if (permission == null || player.hasPermission(permission)) {
                player.sendMessage(message);
            }
        }
    }

    @Override
    public Optional<UUID> resolveOfflineUuid(String name) {
        // Proxies cannot look up Mojang profiles reliably; only offline-mode UUIDs are deterministic.
        return isOnlineMode() ? Optional.empty() : Optional.of(UuidUtil.offlineUuid(name));
    }
}
