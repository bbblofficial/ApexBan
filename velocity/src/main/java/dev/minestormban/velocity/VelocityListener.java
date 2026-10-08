package dev.minestormban.velocity;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.proxy.Player;
import dev.minestormban.core.MineStormCore;
import dev.minestormban.core.model.LoginCheck;
import dev.minestormban.core.model.Punishment;

import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.Optional;

public final class VelocityListener {

    private final MineStormCore core;

    VelocityListener(MineStormCore core) {
        this.core = core;
    }

    /** The ban lookup runs off the Velocity event thread; the login stays suspended until it finishes. */
    @Subscribe(order = PostOrder.EARLY)
    public EventTask onLogin(LoginEvent event) {
        if (!event.getResult().isAllowed()) {
            return null;
        }
        Player player = event.getPlayer();
        return EventTask.async(() -> {
            try {
                String ip = "";
                InetSocketAddress remote = player.getRemoteAddress();
                if (remote != null && remote.getAddress() != null) {
                    ip = remote.getAddress().getHostAddress();
                }
                LoginCheck check = core.manager().onLogin(player.getUniqueId(), player.getUsername(), ip);
                if (check.ban() != null) {
                    event.setResult(ResultedEvent.ComponentResult.denied(
                            VelocityPlatform.component(core.banScreen(check.ban()))));
                } else if (check.error() && !core.failOpen()) {
                    event.setResult(ResultedEvent.ComponentResult.denied(
                            VelocityPlatform.component(core.text("login.database-error"))));
                }
            } catch (RuntimeException ex) {
                core.platform().logger().error("Login check failed for " + player.getUsername(), ex);
            }
        });
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        core.manager().onQuit(event.getPlayer().getUniqueId());
    }

    @Subscribe(order = PostOrder.FIRST)
    public void onChat(PlayerChatEvent event) {
        if (!event.getResult().isAllowed()) {
            return;
        }
        Player player = event.getPlayer();
        Optional<Punishment> mute = core.manager().activeMute(player.getUniqueId());
        if (mute.isEmpty()) {
            return;
        }
        event.setResult(PlayerChatEvent.ChatResult.denied());
        Punishment p = mute.get();
        player.sendMessage(VelocityPlatform.component(core.text(
                p.permanent() ? "mute.blocked.permanent" : "mute.blocked.temporary", core.placeholders(p))));
    }

    @Subscribe(order = PostOrder.FIRST)
    public void onCommand(CommandExecuteEvent event) {
        if (!event.getResult().isAllowed() || core.blockedMuteCommands().isEmpty()) {
            return;
        }
        if (!(event.getCommandSource() instanceof Player player)) {
            return;
        }
        String label = event.getCommand().trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) {
            label = label.substring(colon + 1);
        }
        if (!core.blockedMuteCommands().contains(label)) {
            return;
        }
        if (core.manager().activeMute(player.getUniqueId()).isEmpty()) {
            return;
        }
        event.setResult(CommandExecuteEvent.CommandResult.denied());
        player.sendMessage(VelocityPlatform.component(core.text("mute.blocked-command")));
    }
}
