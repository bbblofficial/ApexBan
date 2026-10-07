package dev.apexban.bungee;

import dev.apexban.core.ApexCore;
import dev.apexban.core.model.LoginCheck;
import dev.apexban.core.model.Punishment;
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.event.LoginEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.Optional;

public final class BungeeListener implements Listener {

    private final ApexCore core;
    private final Plugin plugin;

    BungeeListener(ApexCore core, Plugin plugin) {
        this.core = core;
        this.plugin = plugin;
    }

    /** The lookup runs on BungeeCord's async scheduler while the login is held with an intent. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onLogin(LoginEvent event) {
        if (event.isCancelled()) {
            return;
        }
        PendingConnection connection = event.getConnection();
        event.registerIntent(plugin);
        plugin.getProxy().getScheduler().runAsync(plugin, () -> {
            try {
                String ip = "";
                if (connection.getSocketAddress() instanceof InetSocketAddress address
                        && address.getAddress() != null) {
                    ip = address.getAddress().getHostAddress();
                }
                LoginCheck check = core.manager().onLogin(connection.getUniqueId(), connection.getName(), ip);
                if (check.ban() != null) {
                    event.setCancelled(true);
                    event.setCancelReason(BungeePlatform.component(core.banScreen(check.ban())));
                } else if (check.error() && !core.failOpen()) {
                    event.setCancelled(true);
                    event.setCancelReason(BungeePlatform.component(core.text("login.database-error")));
                }
            } catch (RuntimeException ex) {
                core.platform().logger().error("Login check failed for " + connection.getName(), ex);
            } finally {
                event.completeIntent(plugin);
            }
        });
    }

    @EventHandler
    public void onDisconnect(PlayerDisconnectEvent event) {
        core.manager().onQuit(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(ChatEvent event) {
        if (event.isCancelled() || !(event.getSender() instanceof ProxiedPlayer player)) {
            return;
        }
        Optional<Punishment> mute = core.manager().activeMute(player.getUniqueId());
        if (mute.isEmpty()) {
            return;
        }
        String message = event.getMessage();
        if (event.isCommand() || message.startsWith("/")) {
            if (message.length() < 2 || core.blockedMuteCommands().isEmpty()) {
                return;
            }
            String label = message.substring(1).split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
            int colon = label.indexOf(':');
            if (colon >= 0) {
                label = label.substring(colon + 1);
            }
            if (core.blockedMuteCommands().contains(label)) {
                event.setCancelled(true);
                player.sendMessage(BungeePlatform.component(core.text("mute.blocked-command")));
            }
            return;
        }
        event.setCancelled(true);
        Punishment p = mute.get();
        player.sendMessage(BungeePlatform.component(core.text(
                p.permanent() ? "mute.blocked.permanent" : "mute.blocked.temporary", core.placeholders(p))));
    }
}
