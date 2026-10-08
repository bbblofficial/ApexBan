package dev.minestormban.bukkit;

import dev.minestormban.core.MineStormCore;
import dev.minestormban.core.model.LoginCheck;
import dev.minestormban.core.model.Punishment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;
import java.util.Optional;

public final class BukkitListener implements Listener {

    private final MineStormCore core;
    private final BukkitPlatform platform;

    BukkitListener(MineStormCore core, BukkitPlatform platform) {
        this.core = core;
        this.platform = platform;
    }

    /**
     * Runs on an asynchronous thread, so the blocking database lookup cannot lag the server.
     * (PlayerLoginEvent is synchronous and would stall the main thread.)
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        String ip = event.getAddress() == null ? "" : event.getAddress().getHostAddress();
        LoginCheck check = core.manager().onLogin(event.getUniqueId(), event.getName(), ip);
        if (check.ban() != null) {
            Punishment ban = check.ban();
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, core.banScreen(ban));
        } else if (check.error() && !core.failOpen()) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, core.text("login.database-error"));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        platform.track(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        platform.untrack(event.getPlayer());
        core.manager().onQuit(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Optional<Punishment> mute = core.manager().activeMute(event.getPlayer().getUniqueId());
        if (mute.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        Punishment p = mute.get();
        event.getPlayer().sendMessage(core.text(
                p.permanent() ? "mute.blocked.permanent" : "mute.blocked.temporary", core.placeholders(p)));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (core.blockedMuteCommands().isEmpty()
                || core.manager().activeMute(event.getPlayer().getUniqueId()).isEmpty()) {
            return;
        }
        String message = event.getMessage();
        if (message.length() < 2) {
            return;
        }
        String label = message.substring(1).split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) {
            label = label.substring(colon + 1);
        }
        if (core.blockedMuteCommands().contains(label)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(core.text("mute.blocked-command"));
        }
    }
}
