package dev.minestormban.bukkit;

import dev.minestormban.core.platform.MineStormPlayer;
import org.bukkit.entity.Player;

import java.util.UUID;

final class BukkitPlayerAdapter implements MineStormPlayer {

    private final Player player;

    BukkitPlayerAdapter(Player player) {
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
        player.sendMessage(legacyMessage);
    }
}
