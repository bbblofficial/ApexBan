package dev.apexban.bungee;

import dev.apexban.core.platform.ApexPlayer;
import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.util.UUID;

final class BungeePlayerAdapter implements ApexPlayer {

    private final ProxiedPlayer player;

    BungeePlayerAdapter(ProxiedPlayer player) {
        this.player = player;
    }

    @Override
    public UUID uuid() {
        return player.getUniqueId();
    }

    @Override
    public String name() {
        return player.getName();
    }

    @Override
    public boolean hasPermission(String permission) {
        return player.hasPermission(permission);
    }

    @Override
    public void sendMessage(String legacyMessage) {
        player.sendMessage(BungeePlatform.component(legacyMessage));
    }
}
