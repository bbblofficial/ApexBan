package dev.apexban.bungee;

import dev.apexban.core.platform.ApexLogger;
import dev.apexban.core.platform.ApexPlayer;
import dev.apexban.core.platform.PlatformAdapter;
import dev.apexban.core.util.UuidUtil;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Plugin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

final class BungeePlatform implements PlatformAdapter {

    private final Plugin plugin;

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

    BungeePlatform(Plugin plugin) {
        this.plugin = plugin;
    }

    static BaseComponent[] component(String legacy) {
        return TextComponent.fromLegacyText(legacy);
    }

    @Override
    public String platformName() {
        return "BungeeCord";
    }

    @Override
    public String pluginVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean supportsHexColors() {
        return true;
    }

    @Override
    public boolean isOnlineMode() {
        return ProxyServer.getInstance().getConfig().isOnlineMode();
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
        ProxiedPlayer player = ProxyServer.getInstance().getPlayer(uuid);
        return player == null ? Optional.empty() : Optional.of(new BungeePlayerAdapter(player));
    }

    @Override
    public Optional<ApexPlayer> findOnline(String name) {
        ProxiedPlayer player = ProxyServer.getInstance().getPlayer(name);
        return player == null ? Optional.empty() : Optional.of(new BungeePlayerAdapter(player));
    }

    @Override
    public Collection<String> onlinePlayerNames() {
        Collection<String> names = new ArrayList<>();
        for (ProxiedPlayer player : ProxyServer.getInstance().getPlayers()) {
            names.add(player.getName());
        }
        return names;
    }

    @Override
    public void kick(UUID uuid, String legacyMessage) {
        ProxiedPlayer player = ProxyServer.getInstance().getPlayer(uuid);
        if (player != null) {
            player.disconnect(component(legacyMessage));
        }
    }

    @Override
    public void broadcast(String legacyMessage, String permission) {
        BaseComponent[] message = component(legacyMessage);
        ProxyServer proxy = ProxyServer.getInstance();
        proxy.getConsole().sendMessage(message);
        for (ProxiedPlayer player : proxy.getPlayers()) {
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

    static String senderName(CommandSender sender) {
        return sender instanceof ProxiedPlayer ? sender.getName() : "Console";
    }
}
